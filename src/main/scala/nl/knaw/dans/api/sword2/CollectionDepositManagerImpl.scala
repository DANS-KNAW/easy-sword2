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

import java.io.{File, IOException}
import java.nio.file.{Files, Paths}
import java.util.Collections

import gov.loc.repository.bagit.BagFactory
import gov.loc.repository.bagit.utilities.SimpleResult
import net.lingala.zip4j.core.ZipFile
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.swordapp.server._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class CollectionDepositManagerImpl extends CollectionDepositManager {

  implicit val bf = new BagFactory

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration) = {
    Authentication.checkAuthentication(auth)

    val result: Try[String] = for {
      id <- SwordID.extractOrGenerate(collectionURI)
      tempDirPath = Paths.get(SwordProps("temp-dir"), id)
      inProgressExists = Files.exists(tempDirPath)
      zipFile = Paths.get(SwordProps("temp-dir"), id, deposit.getFilename).toFile
      _ <- copyPayloadToFile(deposit, zipFile)
      _ <- doesHashMatch(zipFile, deposit.getMd5)
    } yield if (!deposit.isInProgress) handleSingleOrLastContinuedDeposit(id, deposit.getMimeType) else id

    result.map(id => createDepositReceipt(deposit, id)).get
  }

  private def copyPayloadToFile(deposit: Deposit, zipFile: File): Try[Unit] =
    try {
      Success(FileUtils.copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  private def handleSingleOrLastContinuedDeposit(id: String, mimeType: String)(implicit bf: BagFactory): String =
    try {
      extractBagit(id, mimeType).flatMap(findBagitRoot) match {
        case Success(bagitDir) =>
          val bag = bf.createBag(bagitDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
          val checkValid: SimpleResult = bag.verifyValid
          try {
            if (!checkValid.isSuccess) throw new SwordError(checkValid.messagesToString)
            moveBagToStorage(id).recover { case e => throw new SwordError("Failed to move dataset to storage") }
            id
          } finally {
            removeTempDir(id)
          }
        case Failure(e) => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", e)
      }
    } catch {
      case e: IOException => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest")
    }

  @tailrec
  private def findBagitRoot(f: File): Try[File] =
    if (f.isDirectory) {
      val children = f.listFiles
      if (children.size == 1) {
        findBagitRoot(children.head)
      } else if (children.size > 1) {
        Success(f)
      } else {
        Failure(new RuntimeException(s"Bagit folder seems to be empty in: ${f.getName}"))
      }
    } else {
      Failure(new RuntimeException(s"Couldn't find bagit folder, instead found: ${f.getName}"))
    }

  private def extractBagit(id: String, mimeType: String): Try[File] =
    Try {
      val tempDir: File = new File(SwordProps("temp-dir"), id)
      val files: Array[File] = tempDir.listFiles
      if (files == null)
        throw new SwordError("Failed to read temporary dataset")
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw new SwordError("Inconsistent dataset: non-file object found")
            extract(file, tempDir.getPath)
            FileUtils.deleteQuietly(file)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(tempDir, "dataset.zip")
          MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
          extract(mergedZip, tempDir.getPath)
          files.foreach(FileUtils.deleteQuietly)
          FileUtils.deleteQuietly(mergedZip)
        case _ =>
          throw new RuntimeException(s"Invalid content type: $mimeType")
      }
      tempDir
    }

  private def getSequenceNumber(f: File): Int =
    try {
      f.getName.split('.').last.toInt
    } catch {
      case _: Throwable =>
        throw new RuntimeException(s"Partial file ${f.getName} has an incorrect extension. Should be a sequence number.")
    }

  private def moveBagToStorage(id: String): Try[File] =
    Try {
      val tempDir = new File(SwordProps("temp-dir"), id)
      val storageDir = new File(SwordProps("data-dir"), id)
      FileUtils.copyDirectory(tempDir, storageDir)
      storageDir
    }

  private def removeTempDir(id: String): Try[Unit] =
    Try { FileUtils.deleteDirectory(new File(SwordProps("temp-dir"), id)) }

  private def doesHashMatch(zipFile: File, MD5: String): Try[Unit] = {
    lazy val fail = Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch"))
    try {
      if (MD5 == DigestUtils.md5Hex(Files.newInputStream(Paths.get(zipFile.getPath)))) Success(Unit)
      else fail
    } catch {
      case _: Throwable => fail
    }
  }

  private def createDepositReceipt(deposit: Deposit, id: String): DepositReceipt = {
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

  private def extract(file: File, outputPath: String): Unit =
    new ZipFile(file.getPath).extractAll(outputPath)

}
