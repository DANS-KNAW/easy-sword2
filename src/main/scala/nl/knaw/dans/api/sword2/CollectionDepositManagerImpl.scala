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
import java.nio.file.{Files, Path, Paths}
import java.util.Collections

import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.{Bag, BagFactory}
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.swordapp.server._

import scala.util.{Failure, Success, Try}

class CollectionDepositManagerImpl extends CollectionDepositManager {

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration) = {
    Authentication.checkAuthentication(auth)

    implicit val bf = new BagFactory

    val result: Try[String] = for {
      id <- SwordID.extractOrGenerate(collectionURI)
      tempDirPath = Paths.get(SwordProps("temp-dir"), id)
      inProgressExists = Files.exists(tempDirPath)
      zipFile <- SwordID.generate.map(zipId => Paths.get(SwordProps("temp-dir"), id, zipId + ".zip").toFile)
      _ <- copyPayloadToFile(deposit, zipFile)
      _ <- doesHashMatch(zipFile, deposit.getMd5)
    } yield (deposit.isInProgress, inProgressExists) match {
      case (false, false) => handleSingleDeposit(id, zipFile)
      case (false, true) => handleLastContinuedDeposit(id)
      case _ => id // in-progress deposit
    }

    result.map(id => createDepositReceipt(deposit, id)).get
  }

  private def cleanup(id: String, zipId: String, isInProgress: Boolean): Try[Unit] =
    Try {
      if (!isInProgress)
        removeTempDir(id).recover { case err => err.printStackTrace() }
      else
        FileUtils.deleteQuietly(Paths.get(SwordProps("temp-dir"), id, zipId + ".zip").toFile)
    }

  private def copyPayloadToFile(deposit: Deposit, zipFile: File): Try[Unit] =
    try {
      Success(FileUtils.copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  private def handleSingleDeposit(id: String, zipFile: File)(implicit bf: BagFactory): String = {
    val bag = bf.createBag(zipFile, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
    val checkValid: SimpleResult = bag.verifyValid
    if (!checkValid.isSuccess) throw new SwordError(checkValid.messagesToString)
    storeSingleDeposit(id, bag)
    id
  }

  private def handleLastContinuedDeposit(id: String)(implicit bf: BagFactory): String =
    try {
      finalizeContinuedDeposit(id) match {
        case Success(tempDir) =>
          val bag = bf.createBag(tempDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
          val checkValid: SimpleResult = bag.verifyValid
          try {
            if (!checkValid.isSuccess) throw new SwordError(checkValid.messagesToString)
            moveBagToStorage(id).recover { case e => throw new SwordError("Failed to move dataset to storage") }
            id
          } finally {
            removeTempDir(id)
          }
        case Failure(e) => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", e.getMessage)
      }
    } catch {
      case e: IOException => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest")
    }

  private def finalizeContinuedDeposit(id: String): Try[File] =
    Try {
      val tempDir: File = Paths.get(SwordProps("temp-dir"), id).toFile
      val files: Array[File] = tempDir.listFiles
      if (files == null) {
        throw new SwordError("Failed to read temporary dataset")
      }
      files.foreach(file =>  {
        if (!file.isFile) {
          throw new SwordError("Inconsistent dataset: non-file object found")
        }
        Bagit.extract(file, Paths.get(SwordProps("temp-dir"), id).toString)
        FileUtils.deleteQuietly(file)
      })
      tempDir
    }

  private def storeSingleDeposit(id: String, bag: Bag): Try[Unit] = {
    val result = Try { moveZippedBagToStorage(id, bag).foreach(zip => Bagit.extract(zip, zip.getParent)) }
    removeTempDir(id)
    result
  }

  private def moveZippedBagToStorage(id: String, bag: Bag): Try[File] =
    Try {
      val tempFile = bag.getFile
      val storedFile = Paths.get(SwordProps("data-dir"), id, tempFile.getName).toFile
      FileUtils.copyFile(tempFile, storedFile)
      storedFile
    }

  private def moveBagToStorage(id: String): Try[File] =
    Try {
      val tempDir = Paths.get(SwordProps("temp-dir"), id).toFile
      val storageDir = Paths.get(SwordProps("data-dir"), id).toFile
      FileUtils.copyDirectory(tempDir, storageDir)
      storageDir
    }

  private def removeTempDir(id: String): Try[Unit] =
    Try { FileUtils.deleteDirectory(Paths.get(SwordProps("temp-dir"), id).toFile) }

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

}
