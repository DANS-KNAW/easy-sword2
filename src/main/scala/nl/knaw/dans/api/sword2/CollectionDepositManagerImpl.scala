/*******************************************************************************
  * Copyright 2015 DANS - Data Archiving and Networked Services
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  ******************************************************************************/
package nl.knaw.dans.api.sword2

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Collections

import gov.loc.repository.bagit.BagFactory
import gov.loc.repository.bagit.utilities.SimpleResult
import net.lingala.zip4j.core.ZipFile
import nl.knaw.dans.api.sword2.CollectionDepositManagerImpl._
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.slf4j.LoggerFactory
import org.swordapp.server._
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class CollectionDepositManagerImpl extends CollectionDepositManager {
  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration) = {
    log.info(s"Receiving deposit from ${auth.getUsername}")
    Authentication.checkAuthentication(auth)

    val result = for {
      id <- SwordID.extractOrGenerate(collectionURI)
      _ = log.debug(s"Deposit ID = $id")
      _ <- checkDepositStatus(id)
      payload = Paths.get(SwordProps("temp-dir"), id, deposit.getFilename).toFile
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)
      _ <- handleDepositAsync(id, auth, deposit)
    } yield (id, createDepositReceipt(deposit, id))

    result match {
      case Success((id,depositReceipt)) =>
        log.info(s"Sending deposit receipt for deposit: $id")
        depositReceipt
      case Failure(e) =>
        log.error("Error(s) occurred", e)
        throw e
    }
  }
}

object CollectionDepositManagerImpl {
  val log = LoggerFactory.getLogger(getClass)

  implicit val bagFactory = new BagFactory

  val depositProcessingStream = PublishSubject[(String, Deposit)]()

  depositProcessingStream
    .onBackpressureBuffer
    .observeOn(NewThreadScheduler())
    .doOnEach(_ match { case (id, deposit) => finalizeDeposit(id, deposit.getMimeType).get })
    .subscribe(
      d => log.info(s"Done finalizing deposit ${d._1}"),
      e => log.error(s"Error while finalizing deposit", e),
      () => log.error(s"Deposit processing stream completed, this should never happen!"))

  def checkDepositStatus(id: String): Try[Unit] = Try {
    val tempDir = new File(SwordProps("temp-dir"), id)
    if (tempDir.exists() && Git.open(tempDir).tagList().call().size() > 0)
      throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed", 405, s"Deposit $id is not in DRAFT state.")
  }.recoverWith {
    case e: SwordError => Failure(e)
    case _ => Success(Unit)
  }

  def copyPayloadToFile(deposit: Deposit, zipFile: File): Try[Unit] =
    try {
      log.debug(s"Copying payload to: $zipFile")
      Success(FileUtils.copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  def handleDepositAsync(id: String, auth: AuthCredentials, deposit: Deposit): Try[Unit] = Try {
    if (!deposit.isInProgress) {
      log.info(s"Scheduling deposit ${auth.getUsername}/$id to be finalized")
      depositProcessingStream.onNext((id, deposit))
    } else {
      log.info(s"Received continuing deposit ${auth.getUsername}/$id/${deposit.getFilename}")
    }
  }

  def finalizeDeposit(id: String, mimeType: String)(implicit bf: BagFactory): Try[Unit] = {
    // TODO: remove thread name and id from code (this should be configured in logback)
    log.info(s"Finalizing deposit: $id (thread-name: ${Thread.currentThread().getName} thread-id: ${Thread.currentThread().getId})")
    val tempDir = new File(SwordProps("temp-dir"), id)
    for {
      git      <- initGit(tempDir)
      _        <- extractBagit(id, mimeType)
      bagitDir <- findBagitRoot(tempDir)
      _        <- checkBagValidity(bagitDir)
      _        <- commitSubmitted(git, tempDir)
      _        <- moveBagToStorage(id)
    } yield ()
  }

  private def checkBagValidity(bagitDir: File): Try[Unit] = {
    log.debug(s"Verifying bag validity: ${bagitDir.getPath}")
    val bag = bagFactory.createBag(bagitDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
    val validationResult: SimpleResult = bag.verifyValid
    if (validationResult.isSuccess)
      Success(Unit)
    else
      Failure(new SwordError(validationResult.messagesToString))
  }

  private def initGit(bagDir: File): Try[Option[Git]] =
    if (SwordProps("git-enabled").toBoolean)
      Try {
        log.debug("Initializing git repo for deposit")
        Some(Git.init().setDirectory(bagDir).call())
      }.recoverWith { case e => Failure(new SwordError("Failed to initialize versioning", e)) }
    else
      Success(None)

  private def commitSubmitted(optionalGit: Option[Git], bagDir: File): Try[Option[Ref]] =
    Try {
      optionalGit.map(git => {
        git.add().addFilepattern(".").call()
        git.commit().setCommitter(SwordProps("git-user"), SwordProps("git-email")).setMessage("initial commit").call()
        git.tag().setName("state=SUBMITTED").setMessage("state=SUBMITTED").call()
      })
    }.recoverWith { case e => Failure(new SwordError("Failed to set bag status to SUBMITTED", e)) }

  @tailrec
  private def findBagitRoot(f: File): Try[File] =
    if (f.isDirectory) {
      val children = f.listFiles.filter(_.getName != ".git")
      if (children.length == 1) {
        findBagitRoot(children.head)
      } else if (children.length > 1) {
        Success(f)
      } else {
        Failure(new RuntimeException(s"Bagit folder seems to be empty in: ${f.getName}"))
      }
    } else {
      Failure(new RuntimeException(s"Couldn't find bagit folder, instead found: ${f.getName}"))
    }

  private def extractBagit(id: String, mimeType: String): Try[File] =
    Try {
      log.debug("Extracting bag")
      val tempDir: File = new File(SwordProps("temp-dir"), id)
      val files: Array[File] = tempDir.listFiles().filter(_.getName != ".git")
      if (files == null)
        throw new SwordError("Failed to read temporary dataset")
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw new SwordError(s"Inconsistent dataset: non-file object found: ${file.getName}")
            extract(file, tempDir.getPath)
            FileUtils.deleteQuietly(file)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(tempDir, "dataset.zip")
          files.foreach(f => log.debug(s"[$id] Merging file: ${f.getName}"))
          MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
          extract(mergedZip, tempDir.getPath)
          files.foreach(FileUtils.deleteQuietly)
          FileUtils.deleteQuietly(mergedZip)
        case _ =>
          throw new SwordError(s"Invalid content type: $mimeType")
      }
      tempDir
    }

  def getSequenceNumber(f: File): Int =
    try {
      f.getName.split('.').last.toInt
    } catch {
      case _: Throwable =>
        throw new RuntimeException(s"Partial file ${f.getName} has an incorrect extension. Should be a sequence number.")
    }

  def moveBagToStorage(id: String): Try[File] =
    Try {
      log.debug("Moving bag to permanent storage")
      val tempDir = new File(SwordProps("temp-dir"), id)
      val storageDir = new File(SwordProps("data-dir"), id)
      if(!tempDir.renameTo(storageDir)) throw new SwordError(s"Cannot move $tempDir to $storageDir")
      storageDir
    }.recover { case e => throw new SwordError("Failed to move dataset to storage", e) }

  def doesHashMatch(zipFile: File, MD5: String): Try[Unit] = {
    log.debug(s"Checking Content-MD5 (Received: $MD5)")
    lazy val fail = Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch"))
    val is = Files.newInputStream(Paths.get(zipFile.getPath))
    try {
      if (MD5 == DigestUtils.md5Hex(is)) Success(Unit)
      else fail
    } catch {
      case _: Throwable => fail
    } finally {
      Try { is.close() }
    }
  }

  def createDepositReceipt(deposit: Deposit, id: String): DepositReceipt = {
    val dr = new DepositReceipt
    val editIRI = if (deposit.isInProgress) new IRI(SwordProps("host") + "/collection/" + id) else new IRI(SwordProps("host") + "/container/" + id)
    dr.setEditIRI(editIRI)
    dr.setLocation(editIRI)
    dr.setEditMediaIRI(editIRI)
    dr.setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
    dr.setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    dr
  }

  def extract(file: File, outputPath: String): Unit =
    new ZipFile(file.getPath).extractAll(outputPath)

}
