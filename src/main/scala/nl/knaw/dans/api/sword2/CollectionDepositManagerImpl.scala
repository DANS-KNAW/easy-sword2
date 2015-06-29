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

    val id: String = SwordID.extractOrGenerate(collectionURI)
    val tempDirPath: Path = Paths.get(SwordProps.get("temp-dir"), id)
    val inProgressExists: Boolean = Files.exists(tempDirPath)
    val zipFile: File = Paths.get(SwordProps.get("temp-dir"), id, SwordID.generate + ".zip").toFile

    val copyZipAction = Try {
      FileUtils.copyInputStreamToFile(deposit.getInputStream, zipFile)
    }.recoverWith { case e => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", e)) }

    copyZipAction.flatMap(_ => doesHashMatch(zipFile, deposit.getMd5)) match {
      case Failure(e) =>
        removeTempDir(id).recover { case err => err.printStackTrace() }
        throw e
      case Success(false) =>
        removeTempDir(id).recover { case err => err.printStackTrace() }
        throw new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch")
      case _ => // checksum correct
    }

    (deposit.isInProgress, inProgressExists) match {
      case (false, false) => handleSingleDeposit(id, zipFile)
      case (false, true) => handleLastContinuedDeposit(id)
      case _ => // in-progress deposit
    }

    createDepositReceipt(deposit, id)
  }

  private def handleSingleDeposit(id: String, zipFile: File)(implicit bf: BagFactory) = {
    val bag = bf.createBag(zipFile, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
    val checkValid: SimpleResult = bag.verifyValid
    if (checkValid.isSuccess) storeSingleDeposit(id, bag)
    else throw new SwordError(checkValid.messagesToString)
  }

  private def handleLastContinuedDeposit(id: String)(implicit bf: BagFactory) = {
    try {
      finalizeContinuedDeposit(id) match {
        case Success(tempDir) =>
          val bag = bf.createBag(tempDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
          val checkValid: SimpleResult = bag.verifyValid
          try {
            if (checkValid.isSuccess) moveBagToStorage(id).recover { case e => throw new SwordError("Failed to move dataset to storage") }
            else throw new SwordError(checkValid.messagesToString)
          } finally {
            removeTempDir(id)
          }
        case Failure(e) => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", e.getMessage)
      }
    } catch {
      case e: IOException => throw new SwordError("http://purl.org/net/sword/error/ErrorBadRequest")
    }
  }

  private def finalizeContinuedDeposit(id: String): Try[File] =
    Try {
      val tempDir: File = Paths.get(SwordProps.get("temp-dir"), id).toFile
      val files: Array[File] = tempDir.listFiles
      if (files == null) {
        throw new SwordError("Failed to read temporary dataset")
      }
      files.foreach(file =>  {
        if (!file.isFile) {
          throw new SwordError("Inconsistent dataset: non-file object found")
        }
        Bagit.extract(file, Paths.get(SwordProps.get("temp-dir"), id).toString)
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
      val storedFile = Paths.get(SwordProps.get("data-dir"), id, tempFile.getName).toFile
      FileUtils.copyFile(tempFile, storedFile)
      storedFile
    }

  private def moveBagToStorage(id: String): Try[File] =
    Try {
      val tempDir = Paths.get(SwordProps.get("temp-dir"), id).toFile
      val storageDir = Paths.get(SwordProps.get("data-dir"), id).toFile
      FileUtils.copyDirectory(tempDir, storageDir)
      storageDir
    }

  private def removeTempDir(id: String): Try[Unit] =
    Try { FileUtils.deleteDirectory(Paths.get(SwordProps.get("temp-dir"), id).toFile) }

  private def doesHashMatch(zipFile: File, MD5: String): Try[Boolean] =
    try {
      Success(MD5 == DigestUtils.md5Hex(Files.newInputStream(Paths.get(zipFile.getPath))))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch", t))
    }

  private def createDepositReceipt(deposit: Deposit, id: String): DepositReceipt = {
    val dr = new DepositReceipt
    val editIRI = if (deposit.isInProgress) new IRI(SwordProps.get("host") + "/collection/" + id) else new IRI(SwordProps.get("host") + "/container/" + id)
    dr.setEditIRI(editIRI)
    dr.setLocation(editIRI)
    dr.setEditMediaIRI(editIRI)
    dr.setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
    dr.setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    dr
  }

}
