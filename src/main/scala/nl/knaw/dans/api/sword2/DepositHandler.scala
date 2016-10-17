/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.api.sword2

import java.io.{File, IOException}
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermissions}
import java.nio.file._
import java.util.Collections
import java.net.{MalformedURLException, URI, URL}
import java.util.regex.Pattern

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl
import gov.loc.repository.bagit.{Bag, BagFactory, FetchTxt}
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.verify.CompleteVerifier
import net.lingala.zip4j.core.ZipFile
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils._
import org.slf4j.LoggerFactory
import org.swordapp.server.{Deposit, DepositReceipt, SwordError}
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._
import resource.Using
import nl.knaw.dans.lib.error.TraversableTryExtensions

object DepositHandler {
  val log = LoggerFactory.getLogger(getClass)
  implicit val bagFactory = new BagFactory

  val depositProcessingStream = PublishSubject[(String, Deposit)]()

  depositProcessingStream
    .onBackpressureBuffer
    .observeOn(NewThreadScheduler())
    .doOnEach(_ match { case (id, deposit) => finalizeDeposit(deposit.getMimeType)(id) })
    .subscribe(_ match { case (id, deposit) => log.info(s"Done finalizing deposit $id") })

  def handleDeposit(deposit: Deposit)(implicit id: String): Try[DepositReceipt] = {
    val payload = Paths.get(SwordProps("tempdir"), id, deposit.getFilename.split("/").last).toFile
    for {
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)
      _ <- handleDepositAsync(deposit)
    } yield createDepositReceipt(deposit, id)
  }

  def finalizeDeposit(mimeType: String)(implicit id: String): Try[Unit] = {
    log.info(s"[$id] Finalizing deposit")
    implicit val baseDir: File = new File(SwordProps("bag-store.base-dir"))
    implicit val baseUrl: URI  = new URI(SwordProps("bag-store.base-url"))
    val tempDir = new File(SwordProps("tempdir"), id)

    val result = for {
      _        <- checkBaseDir()
      _        <- extractBag(mimeType)
      bagitDir <- getBagDir(tempDir)
      _        <- checkFetchItemUrls(bagitDir)
      _        <- checkBagValidity(bagitDir)
      _        <- DepositProperties.set(id, "SUBMITTED", "Deposit is valid and ready for post-submission processing", lookInTempFirst = true)
      dataDir  <- moveBagToStorage()
    } yield ()

    result.recover {
      case InvalidDepositException(_, msg, cause) =>
        log.error(s"[$id] Invalid deposit", cause)
        DepositProperties.set(id, "INVALID", msg, lookInTempFirst = true)
      case FailedDepositException(_, msg, cause) =>
        log.error(s"[$id] Failed deposit", cause)
        DepositProperties.set(id, "FAILED", msg, lookInTempFirst = true)
      case _: Throwable =>
        log.error(s"[$id] Unexpected failure in deposit")
        DepositProperties.set(id, "FAILED", "Unexpected failure in deposit", lookInTempFirst = true)
    }
  }

  private def extractBag(mimeType: String)(implicit id: String): Try[File] = {
    def extract(file: File, outputPath: String): Unit = new ZipFile(file.getPath).extractAll(outputPath)

    def getSequenceNumber(f: File): Int =
      try {
        f.getName.split('.').last.toInt
      } catch {
        case _: Throwable =>
          throw new InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. Should be a sequence number.")
      }

    Try {
      log.debug(s"[$id] Extracting bag")
      val tempDir: File = new File(SwordProps("tempdir"), id)
      val files = tempDir.listFilesSafe.filter(isPartOfDeposit)
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw new FailedDepositException(id, s"Inconsistent dataset: non-file object found: ${file.getName}")
            extract(file, tempDir.getPath)
            deleteQuietly(file)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(tempDir, "merged.zip")
          files.foreach(f => log.debug(s"[$id] Merging file: ${f.getName}"))
          MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
          extract(mergedZip, tempDir.getPath)
          files.foreach(deleteQuietly)
          deleteQuietly(mergedZip)
        case _ =>
          throw new InvalidDepositException(id, s"Invalid content type: $mimeType")
      }
      tempDir
    }
  }

  def checkBaseDir()(implicit id: String, baseDir: File): Try[Unit] = {
    if (!baseDir.exists()) {
      log.error(s"[$id] Bag store base directory ${baseDir.getAbsolutePath} doesn't exist")
      Failure(FailedDepositException(id, s"Bag store base directory ${baseDir.getAbsolutePath} doesn't exist"))
    }
    else if (!baseDir.canRead) {
      log.error(s"[$id] Bag store base directory ${baseDir.getAbsolutePath} is not readable")
      Failure(FailedDepositException(id, s"Bag store base directory ${baseDir.getAbsolutePath} is not readable"))
    }
    else {
      Success(())
    }
  }

  private def getBagDir(depositDir: File): Try[File] = Try {
    depositDir.listFiles.find(f => f.isDirectory && isPartOfDeposit(f)).get
  }

  def checkDepositIsInDraft(id: String): Try[Unit] =
    (for {
      state <- DepositProperties.getState(id)
      if state.label == "DRAFT"
    } yield ())
      .recoverWith {
        case t => Failure(new SwordError("http://purl.org/net/sword/error/MethodNotAllowed", 405, s"Deposit $id is not in DRAFT state."))
      }

  def copyPayloadToFile(deposit: Deposit, zipFile: File)(implicit id: String): Try[Unit] =
    try {
      log.debug(s"[$id] Copying payload to: $zipFile")
      Success(copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  def handleDepositAsync(deposit: Deposit)(implicit id: String): Try[Unit] = Try {
    if (!deposit.isInProgress) {
      log.info(s"[$id] Scheduling deposit to be finalized")
      DepositProperties.set(id, "FINALIZING", "Deposit is being reassembled and validated", lookInTempFirst = true)
      depositProcessingStream.onNext((id, deposit))
    } else {
      log.info(s"[$id] Received continuing deposit: ${deposit.getFilename}")
    }
  }

  private def getFetchTxt(bagitDir: File): Option[FetchTxt] = Option {
    getBagFromDir(bagitDir).getFetchTxt
  }

  def checkFetchItemUrls(bagitDir: File)(implicit id: String): Try[Unit] = {
    log.debug(s"[$id] Checking validity of urls in fetch.txt")

    getFetchTxt(bagitDir)
      .map(_.asScala)
      .getOrElse(Seq.empty)
      .map(item => checkUrlValidity(item.getUrl))
      .collectResults
      .map(_ => ())
  }

  private def checkUrlValidity(url: String)(implicit id: String): Try[Unit] = {
    // check if the url is syntactically correct
    def urlIsValid: Try[URL] = {
      Try(new URL(url)).recoverWith {
        case e: MalformedURLException => throw InvalidDepositException(id, s"Invalid url in Fetch Items ($url)")
      }
    }

    // check if the url complies with the allowed url-structure
    def urlIsAllowed: Try[Unit] = {
      val urlPattern = Pattern.compile(SwordProps("url-pattern"))
      if (urlPattern.matcher(url).matches()) Success(())
      else Failure(InvalidDepositException(id, s"Not allowed url in Fetch Items ($url)"))
    }

    for {
      _ <- urlIsValid
      _ <- urlIsAllowed
    } yield ()
  }

  def checkBagValidity(bagitDir: File)(implicit id: String, baseDir: File, baseUrl: URI): Try[Unit] = {
    log.debug(s"[$id] Verifying bag validity")

    val fetchItems = getFetchTxt(bagitDir).map(_.asScala).getOrElse(Seq())
    val fetchItemsInBagStore = fetchItems.filter(_.getUrl.startsWith(baseUrl.toString))

    def handleValidationResult(bag: Bag, validationResult: SimpleResult, fetchItemsInBagStore: Seq[FilenameSizeUrl]) = {
      (fetchItemsInBagStore, validationResult.isSuccess) match {
        case (Seq(), true) => Success(())
        case (Seq(), false) => Failure(InvalidDepositException(id, validationResult.messagesToString))
        case (items, true) => Failure(InvalidDepositException(id, s"There is a fetch.txt file, while all the files are present in the bag."))
        case (itemsFromBagStore, false) =>
          val otherThanMissingPayloadFilesMessages = validationResult.getSimpleMessages
            .asScala
            .filterNot(_.getCode == CompleteVerifier.CODE_PAYLOAD_MANIFEST_CONTAINS_MISSING_FILE)

          if (otherThanMissingPayloadFilesMessages.isEmpty) {
            val missingPayloadFiles = validationResult.getSimpleMessages
              .asScala
              .flatMap(_.getObjects.asScala)
            val fetchItemFilesFromBagStore = itemsFromBagStore.map(_.getFilename)
            val missingFilesNotInFetchText = missingPayloadFiles diff fetchItemFilesFromBagStore

            if (missingFilesNotInFetchText.isEmpty)
              noFetchItemsAlreadyInBag(bagitDir, itemsFromBagStore)
                .flatMap(_ => validateChecksumsFetchItems(bag, itemsFromBagStore))
            else
              Failure(InvalidDepositException(id, s"Missing payload files not in the fetch.txt: ${missingFilesNotInFetchText.mkString}."))
          }
          else
            Failure(InvalidDepositException(id, otherThanMissingPayloadFilesMessages.mkString))
      }
    }

    for {
      _ <- resolveFetchItems(bagitDir, fetchItems diff fetchItemsInBagStore)
      bag = getBagFromDir(bagitDir)
      validationResult = bag.verifyValid()
      _ <- handleValidationResult(bag, validationResult, fetchItemsInBagStore)
    } yield ()
  }

  private def resolveFetchItems(bagitDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String): Try[Unit] = {
    if (fetchItems.nonEmpty) {
      log.debug(s"[$id] Resolving files in fetch.txt, those referring outside the bag store.")
    }

    fetchItems
      .map(item => Using.urlInputStream(new URL(item.getUrl))
        .map(src => {
          val file = new File(bagitDir.getAbsoluteFile, item.getFilename)
          if (file.exists()) {
            throw InvalidDepositException(id, s"File ${item.getFilename} in the fetch.txt is already present in the bag.")
          }
          else {
            file.getParentFile.mkdirs()
            Files.copy(src, file.toPath)
          }
        })
        .tried)
      .collectResults
      .map(_ => ())
  }

  private def noFetchItemsAlreadyInBag(bagitDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String): Try[Unit] = {
    log.debug(s"[$id] Checking that the files in fetch.txt are absent in the bag.")

    val presentFiles = fetchItems.filter(item => new File(bagitDir.getAbsoluteFile, item.getFilename).exists())
    if (presentFiles.nonEmpty)
      Failure(InvalidDepositException(id, s"Fetch.txt file ${presentFiles.head.getFilename} is already present in the bag."))
    else
      Success(())
  }

  private def validateChecksumsFetchItems(bag: Bag, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String, baseDir: File, baseUrl: URI): Try[Unit] = {
    log.debug(s"[$id] Validating checksums of those files in fetch.txt, that refer to the bag store.")

    val fetchItemFiles = fetchItems.map(_.getFilename)
    val urls = fetchItems.map(file => file.getFilename -> file.getUrl).toMap

    val checksumMapping = bag.getPayloadManifests.asScala
      .flatMap(_.asScala)
      .filter { case (file, _) => fetchItemFiles.contains(file) }
      .map { case (file, checksum) =>
        urls.get(file)
          .map(url => Try(file, checksum, url))
          .getOrElse(Failure(InvalidDepositException(id, s"Checksum validation failed: missing Payload Manifest file $file not found in the fetch.txt.")))
      }
      .collectResults

    for {
      csMap <- checksumMapping
      valid <- validateChecksums(csMap)
    } yield ()
  }

  private def validateChecksums(checksumMapping: Seq[(String, String, String)])(implicit id: String, baseDir: File, baseUrl: URI): Try[Unit] = {
    val errors = checksumMapping.flatMap { // we use the fact that an Option is a Seq with 0 or 1 element here!
      case (file, checksum, url) => compareChecksumAgainstReferredBag(file, checksum, url)
    }

    if (errors.isEmpty) {
      Success(())
    }
    else {
      Failure(InvalidDepositException(id, errors.mkString))
    }
  }

  private def compareChecksumAgainstReferredBag(file: String, checksum: String, url: String)(implicit id: String, baseDir: File, baseUrl: URI): Option[String] = {
    val referredFile = getReferredFile(url, baseUrl)
    val referredBagChecksums = getReferredBagChecksums(url)
    if (referredBagChecksums.contains(referredFile -> checksum))
      Option.empty
    else if (referredBagChecksums.map { case (file, _) => file }.contains(referredFile))
      Option(s"Checksum $checksum of the file $file differs from checksum of the file $referredFile in the referred bag.")
    else
      Option(s"While validating checksums, the file $referredFile was not found in the referred bag.")
  }

  private def getReferredFile(url: String, baseUrl: URI): String = {
    val afterBaseUrl = url.stripPrefix(baseUrl.toString)
    afterBaseUrl.substring(afterBaseUrl.indexOf("/data/") + 1)
  }

  private def getBagFromDir(dir: File): Bag = {
    bagFactory.createBag(dir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }

  case class MakeAllGroupWritable(permissions: String) extends SimpleFileVisitor[Path] {
    override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
      log.debug(s"Setting the following permissions $permissions on file $path")
      try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        FileVisitResult.CONTINUE
      } catch {
        case usoe: UnsupportedOperationException => log.error("Not on a POSIX supported file system"); FileVisitResult.TERMINATE
        case cce: ClassCastException => log.error("Non file permission elements in set"); FileVisitResult.TERMINATE
        case ioe: IOException => log.error(s"Could not set file permissions on $path"); FileVisitResult.TERMINATE
        case se: SecurityException => log.error(s"Not enough privileges to set file permissions on $path"); FileVisitResult.TERMINATE
      }
    }

    override def postVisitDirectory(dir: Path, ex: IOException): FileVisitResult = {
      log.debug(s"Setting the following permissions $permissions on directory $dir")
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions))
      if (ex == null) FileVisitResult.CONTINUE
      else FileVisitResult.TERMINATE
    }
  }

  def isOnPosixFileSystem(file: File): Boolean = {
    try {
      Files.getPosixFilePermissions(file.toPath)
      true
    }
    catch {
      case e: UnsupportedOperationException => false
    }
  }

  def moveBagToStorage()(implicit id: String): Try[File] =
    Try {
      log.debug("Moving bag to permanent storage")
      val tempDir = new File(SwordProps("tempdir"), id)
      val storageDir = new File(SwordProps("deposits.rootdir"), id)
      if (isOnPosixFileSystem(tempDir))
        Files.walkFileTree(tempDir.toPath, MakeAllGroupWritable(SwordProps("deposits.permissions")))
      if (!tempDir.renameTo(storageDir)) throw new SwordError(s"Cannot move $tempDir to $storageDir")
      storageDir
    }.recover { case e => throw new SwordError("Failed to move dataset to storage", e) }

  def doesHashMatch(zipFile: File, MD5: String): Try[Unit] = {
    log.debug(s"Checking Content-MD5 (Received: $MD5)")
    lazy val fail = Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch"))
    val is = Files.newInputStream(Paths.get(zipFile.getPath))
    try {
      if (MD5 == DigestUtils.md5Hex(is)) Success(())
      else fail
    } catch {
      case _: Throwable => fail
    } finally {
      Try {
        is.close()
      }
    }
  }

  def createDepositReceipt(deposit: Deposit, id: String): DepositReceipt = {
    val dr = new DepositReceipt
    val editIRI = new IRI(SwordProps("base-url") + "/container/" + id)
    val editMediaIri = new IRI(SwordProps("base-url") + "/media/" + id)
    val stateIri = SwordProps("base-url") + "/statement/" + id
    dr.setEditIRI(editIRI)
    dr.setLocation(editIRI)
    dr.setEditMediaIRI(editMediaIri)
    dr.setSwordEditIRI(editMediaIri)
    dr.setAtomStatementURI(stateIri)
    dr.setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
    dr.setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    dr
  }


  // TO DO: RETRIEVE VIA AN INTERFACE
  private def getReferredBagChecksums(url: String)(implicit baseDir: File, baseUrl: URI): List[(String, String)] = {
    getBagFromDir(getReferredBagDir(url)).
      getPayloadManifests.asScala.toList.
      flatMap(_.asScala)
  }

  private def getReferredBagDir(url: String)(implicit baseDir: File, baseUrl: URI): File = {
    //  http://deasy.dans.knaw.nl/aips/31aef203-55ed-4b1f-81f6-b9f67f324c87.2/data/x -> 31/aef20355ed4b1f81f6b9f67f324c87/2
    val uuidPlusVersion = url.stripPrefix(baseUrl.toString).split("/data").head.replaceAll("-", "")
    val topDir = uuidPlusVersion.splitAt(3)._1
    val uuidDir = uuidPlusVersion.substring(3, uuidPlusVersion.lastIndexOf("."))
    val versionDir = uuidPlusVersion.drop(uuidPlusVersion.lastIndexOf(".") + 1)
    getFile(baseDir, topDir, uuidDir, versionDir)
  }
}
