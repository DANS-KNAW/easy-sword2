/**
 * Copyright (C) 2015 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.sword2

import java.io.{ File, IOException }
import java.net.{ MalformedURLException, URL, UnknownHostException }
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.util.regex.Pattern
import java.util.{ Collections, NoSuchElementException }

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl
import gov.loc.repository.bagit.transformer.impl.TagManifestCompleter
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.verify.CompleteVerifier
import gov.loc.repository.bagit.writer.impl.FileSystemWriter
import gov.loc.repository.bagit.{ Bag, BagFactory, FetchTxt }
import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import nl.knaw.dans.easy.sword2.State._
import nl.knaw.dans.lib.error.{ CompositeException, TraversableTryExtensions, _ }
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils._
import org.joda.time.{ DateTime, DateTimeZone }
import org.slf4j.{ Logger, LoggerFactory }
import org.swordapp.server.{ Deposit, DepositReceipt, SwordError, UriRegistry }
import resource.Using
import rx.lang.scala.Observable
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.collection.JavaConverters._
import scala.collection.convert.Wrappers.JListWrapper
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object DepositHandler {
  val log: Logger = LoggerFactory.getLogger(getClass)
  private implicit val bagFactory: BagFactory = new BagFactory

  private val depositProcessingStream = PublishSubject[(DepositId, MimeType)]()

  def startDepositProcessingStream(implicit settings: Settings): Unit = {
    depositProcessingStream
      .onBackpressureBuffer
      .observeOn(NewThreadScheduler())
      .foreach { case (id, mimetype) =>
        finalizeDeposit(mimetype)(settings, id)
      }
    settings
      .tempDir.listFiles().toSeq
      .filter(_.isDirectory)
      .filter {
        d =>
          getDepositState(d)
            .map(_ == State.UPLOADED)
            .recoverWith {
              case _: Throwable =>
                log.warn(s"[${ d.getName }] Could not get deposit state. Not putting this deposit on the queue.")
                Success(false)
            }.get
      }.foreach {
      d =>
        getContentType(d).map {
          mimeType =>
            log.info(s"[${ d.getName }] Scheduling UPLOADED deposit for finalizing.")
            depositProcessingStream.onNext((d.getName, mimeType))
        }.recover {
          case _: Throwable =>
            log.warn(s"[${ d.getName }] Could not get deposit Content-Type. Not putting this deposit on the queue.")
        }
    }
  }

  private def getDepositState(dir: File)(implicit settings: Settings): Try[State] = {
    for {
      props <- DepositProperties(dir.getName)
      state <- props.getState
    } yield state
  }

  private def getContentType(dir: File)(implicit settings: Settings): Try[String] = {
    for {
      props <- DepositProperties(dir.getName)
      contentType <- props.getClientMessageContentType
    } yield contentType
  }

  def handleDeposit(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[DepositReceipt] = {
    val contentLength = deposit.getContentLength
    if (contentLength == -1) {
      log.warn(s"[$id] Request did not contain a Content-Length header. Skipping disk space check.")
    }

    val payload = Paths.get(settings.tempDir.toString, id, deposit.getFilename.split("/").last).toFile
    val depositDir = Paths.get(settings.tempDir.toString, id).toFile
    // to ensure all files in deposit are accessible for the deposit group, the method setFilePermissions is always
    // executed (regardless of whether extractAndValidatePayloadAndGetDepositReceipt was successful or not).
    // Otherwise operators don't have the proper permissions to clean or fix the invalid zip files or bags that might stay behind
    extractAndValidatePayloadAndGetDepositReceipt(deposit, contentLength, payload, depositDir) match {
      case Success(receipt) =>
        setFilePermissions(depositDir).map(_ => receipt)
      case Failure(exception) =>
        setFilePermissions(depositDir).flatMap(_ => Failure(exception))
    }
  }

  private def setFilePermissions(depositDir: File)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
      .doIfFailure {
        case e: Exception => log.error(s"[$id] error while setting filePermissions for deposit: ${ e.getMessage }")
      }
  }

  private def extractAndValidatePayloadAndGetDepositReceipt(deposit: Deposit, contentLength: Long, payload: File, depositDir: File)(implicit settings: Settings, id: DepositId): Try[DepositReceipt] = {
    for {
      _ <- if (contentLength > -1) assertTempDirHasEnoughDiskspaceMarginForFile(contentLength)
           else Success(())
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)(id)
      _ <- handleDepositAsync(deposit)
      dr = createDepositReceipt(settings, id)
      _ = dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    } yield dr
  }

  def genericErrorMessage(implicit settings: Settings, id: DepositId): String = {
    val mailaddress = settings.supportMailAddress
    val timestamp = DateTime.now(DateTimeZone.UTC).toString

    s"""The server encountered an unexpected condition.
       |Please contact the SWORD service administrator at $mailaddress.
       |The error occurred at $timestamp. Your 'DepositID' is $id.
    """.stripMargin
  }

  private def assertTempDirHasEnoughDiskspaceMarginForFile(len: Long)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    if (log.isDebugEnabled) {
      log.debug(s"Free space  = ${ settings.tempDir.getFreeSpace }")
      log.debug(s"File length = $len")
      log.debug(s"Margin      = ${ settings.marginDiskSpace }")
      log.debug(s"Extra space = ${ settings.tempDir.getFreeSpace - len - settings.marginDiskSpace }")
    }

    if (settings.tempDir.getFreeSpace - len < settings.marginDiskSpace) {
      log.warn(s"[$id] Not enough disk space for request + margin ($len + ${ settings.marginDiskSpace } = ${ len + settings.marginDiskSpace }). Available: ${ settings.tempDir.getFreeSpace }, Short: ${ len + settings.marginDiskSpace - settings.tempDir.getFreeSpace }.")

      throw new SwordError(503) {
        override def getMessage: String = "503 Service temporarily unavailable"
      }
    }
  }

  def finalizeDeposit(mimetype: MimeType)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    log.info(s"[$id] Finalizing deposit")
    implicit val bagStoreSettings: Option[BagStoreSettings] = settings.bagStoreSettings
    val depositDir = new File(settings.tempDir, id)
    lazy val storageDir = new File(settings.depositRootDir, id)

    val result = for {
      props <- DepositProperties(id)
      _ <- props.setState(State.FINALIZING, "Finalizing deposit")
      _ <- props.save()
      _ <- extractBag(depositDir, mimetype)
      bagDir <- getBagDir(depositDir)
      _ <- checkFetchItemUrls(bagDir, settings.urlPattern)
      _ <- checkBagVirtualValidity(bagDir)
      props <- DepositProperties(id)
      _ <- props.setState(SUBMITTED, "Deposit is valid and ready for post-submission processing")
      _ <- props.save()
      _ <- SampleTestData.sampleData(id, depositDir, props)(settings.sample)
      _ <- removeZipFiles(depositDir)
      // ATTENTION: first remove content-type property and THEN move bag to ingest-flow-inbox!!
      _ <- props.removeClientMessageContentType()
      _ <- props.save()
      _ <- moveBagToStorage(depositDir, storageDir)
    } yield ()

    result.doIfSuccess(_ => log.info(s"[$id] Done finalizing deposit")).recover {
      case InvalidDepositException(_, msg, cause) =>
        log.error(s"[$id] Invalid deposit: $msg", cause)
        for {
          props <- DepositProperties(id)
          _ <- props.setState(INVALID, msg)
          _ <- props.save()
          // we don't sample in this case, given that the deposit is invalid and we cannot automate
          // replacing sensitive data
          _ <- cleanupFiles(depositDir, INVALID)
        } yield ()
      case e: NotEnoughDiskSpaceException =>
        log.warn(s"[$id] ${ e.getMessage }")
        log.info(s"[$id] rescheduling after ${ settings.rescheduleDelaySeconds } seconds, while waiting for more disk space")

        // Ignoring result; it would probably not be possible to change the state in the deposit.properties anyway.
        for {
          props <- DepositProperties(id)
          _ <- props.setState(State.UPLOADED, "Rescheduled, waiting for more disk space")
          _ <- props.save()
        } yield ()
        Observable.timer(settings.rescheduleDelaySeconds seconds)
          .subscribe(_ => depositProcessingStream.onNext((id, mimetype)))
      case NonFatal(e) =>
        log.error(s"[$id] Internal failure in deposit service", e)
        for {
          props <- DepositProperties(id)
          _ <- props.setState(FAILED, genericErrorMessage)
          _ <- props.save()
          _ <- cleanupFiles(depositDir, FAILED)
        } yield ()
    }
  }

  private def cleanupFiles(depositDir: File, state: State)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (settings.cleanup.getOrElse(state, false)) {
      log.info(s"[$id] cleaning up zip files and bag directory for deposit due to state $state")
      for {
        _ <- removeZipFiles(depositDir)
        bagDir <- getBagDir(depositDir)
        _ <- Try {
          if (bagDir.exists()) {
            log.debug(s"[$id] removing bag $bagDir")
            deleteQuietly(bagDir)
          }
          else {
            log.debug(s"[$id] bag did not exist; no removal necessary")
          }
        }
      } yield ()
    }
    else
      Success(())
  }

  private def removeZipFiles(depositDir: File)(implicit id: DepositId): Try[Unit] = Try {
    log.debug(s"[$id] removing zip files")
    for (file <- depositDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      log.debug(s"[$id] removing $file")
      deleteQuietly(file)
    }
  }

  private def extractBag(depositDir: File, mimeType: MimeType)(implicit settings: Settings, id: DepositId): Try[File] = {
    def checkAvailableDiskspace(file: File): Try[Unit] = Try {
      val zipFile = new ZipFile(file.getPath)
      val headers = zipFile.getFileHeaders.asScala.asInstanceOf[JListWrapper[FileHeader]] // Look out! Not sure how robust this cast is!
      val uncompressedSize = headers.map(_.getUncompressedSize).sum
      val availableDiskSize = Files.getFileStore(file.toPath).getUsableSpace
      val required = uncompressedSize + settings.marginDiskSpace
      if (log.isDebugEnabled)
        log.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Uncompressed bag size: $uncompressedSize bytes. Margin required: ${ settings.marginDiskSpace } bytes.")
      if (uncompressedSize + settings.marginDiskSpace > availableDiskSize) {
        val diskSizeShort = uncompressedSize + settings.marginDiskSpace - availableDiskSize
        throw NotEnoughDiskSpaceException(id, s"Required disk space for unzipping: $required (including ${ settings.marginDiskSpace } margin). Available: $availableDiskSize. Short: $diskSizeShort.")
      }
    }

    def checkDiskspaceForMerging(files: Seq[File]): Try[Unit] = {
      val sumOfChunks = files.map(_.length).sum
      files.headOption.map {
        f =>
          val availableDiskSize = Files.getFileStore(f.toPath).getUsableSpace
          val required = sumOfChunks + settings.marginDiskSpace
          log.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Sum of chunk sizes: $sumOfChunks bytes. Margin required: ${ settings.marginDiskSpace } bytes.")
          if (sumOfChunks + settings.marginDiskSpace > availableDiskSize) {
            val diskSizeShort = sumOfChunks + settings.marginDiskSpace - availableDiskSize
            Failure(NotEnoughDiskSpaceException(id, s"Required disk space for concatenating: $required (including ${ settings.marginDiskSpace } margin). Available: $availableDiskSize. Short: $diskSizeShort."))
          }
          else Success(())
      }.getOrElse(Success(()))
    }

    def extract(file: File, outputPath: String): Unit = {
      new ZipFile(file.getPath) {
        setFileNameCharset(StandardCharsets.UTF_8.name)
      }.extractAll(outputPath)
    }

    def getSequenceNumber(f: File): Int = {
      try {
        val seqNumber = f.getName
          .split('.')
          .lastOption
          .getOrElse(throw InvalidDepositException(id, s"Partial file ${ f.getName } has no extension. It should be a positive sequence number."))
          .toInt

        if (seqNumber > 0) seqNumber
        else throw InvalidDepositException(id, s"Partial file ${ f.getName } has an incorrect extension. It should be a positive sequence number (> 0), but was: $seqNumber")
      }
      catch {
        case _: NumberFormatException =>
          throw InvalidDepositException(id, s"Partial file ${ f.getName } has an incorrect extension. Should be a positive sequence number.")
      }
    }

    Try {
      val files = depositDir.listFilesSafe.filter(isPartOfDeposit)
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw InvalidDepositException(id, s"Inconsistent dataset: non-file object found: ${ file.getName }")
            checkAvailableDiskspace(file).get
            extract(file, depositDir.getPath)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(depositDir, "merged.zip")
          checkDiskspaceForMerging(files).map {
            _ =>
              MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
                .map(_ => checkAvailableDiskspace(mergedZip))
                .map(_ => extract(mergedZip, depositDir.getPath)).get
          }.get
        case _ =>
          throw InvalidDepositException(id, s"Invalid content type: $mimeType")
      }
      depositDir
    }.recoverWith {
      case e: ZipException => Failure(InvalidDepositException(id, s"Invalid bag: ${ e.getMessage }"))
    }
  }

  private def getBagDir(depositDir: File): Try[File] = Try {
    val depositFiles = depositDir.listFiles.filter(_.isDirectory)
    if (depositFiles.length != 1) throw InvalidDepositException(depositDir.getName, s"A deposit package must contain exactly one top-level directory, number found: ${ depositFiles.length }")
    depositFiles(0)
  }

  def checkDepositIsInDraft(id: DepositId)(implicit settings: Settings): Try[Unit] = {
    for {
      props <- DepositProperties(id)
      state <- props.getState
      _ <- if (state == DRAFT) Success(())
           else Failure(new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED, s"Deposit $id is not in DRAFT state."))
    } yield ()
  }

  def copyPayloadToFile(deposit: Deposit, zipFile: File)(implicit id: DepositId): Try[Unit] =
    try {
      log.debug(s"[$id] Copying payload to: $zipFile")
      Success(copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError(UriRegistry.ERROR_BAD_REQUEST, t))
    }

  def handleDepositAsync(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (!deposit.isInProgress) {
      log.info(s"[$id] Scheduling deposit to be finalized")
      for {
        props <- DepositProperties(id)
        _ <- props.setState(UPLOADED, "Deposit upload has been completed.")
        _ <- props.setClientMessageContentType(deposit.getMimeType)
        _ <- props.save()
      } yield depositProcessingStream.onNext((id, deposit.getMimeType))
    }
    else Try {
      log.info(s"[$id] Received continuing deposit: ${ deposit.getFilename }")
    }
  }

  def formatMessages(seq: Seq[String], in: String): String = {
    seq match {
      case Seq() => s"No errors found in $in"
      case Seq(msg) => s"One error found in $in:\n\t- $msg"
      case msgs => msgs.map(msg => s"\t- $msg").mkString(s"Multiple errors found in $in:\n", "\n", "")
    }
  }

  def checkFetchItemUrls(bagDir: File, urlPattern: Pattern)(implicit id: DepositId): Try[Unit] = {
    log.debug(s"[$id] Checking validity of urls in fetch.txt")

    getFetchTxt(bagDir)
      .map(_.asScala) // Option map
      .getOrElse(Seq.empty)
      .map(item => checkUrlValidity(item.getUrl, urlPattern)) // Seq map
      .collectResults
      .map(_ => ()) // Try map
      .recoverWith {
      case e @ CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage), "fetch.txt URLs"), e))
    }
  }

  private def checkUrlValidity(url: String, urlPattern: Pattern)(implicit id: DepositId): Try[Unit] = {
    def checkUrlSyntax: Try[URL] = {
      Try(new URL(url)).recoverWith {
        case _: MalformedURLException => throw InvalidDepositException(id, s"Invalid url in Fetch Items ($url)")
      }
    }

    def checkUrlAllowed: Try[Unit] = {
      if (urlPattern.matcher(url).matches()) Success(())
      else Failure(InvalidDepositException(id, s"Not allowed url in Fetch Items ($url)"))
    }

    for {
      _ <- checkUrlSyntax
      _ <- checkUrlAllowed
    } yield ()
  }

  def checkBagVirtualValidity(bagDir: File)(implicit id: DepositId, bagStoreSettings: Option[BagStoreSettings]): Try[Unit] = {
    log.debug(s"[$id] Verifying bag validity")

    def handleValidationResult(bag: Bag, validationResult: SimpleResult, fetchItemsInBagStore: Seq[FilenameSizeUrl]): Try[Unit] = {
      (fetchItemsInBagStore, validationResult.isSuccess) match {
        case (Seq(), true) => Success(())
        case (Seq(), false) => Failure(InvalidDepositException(id, validationResult.messagesToString))
        case (_, true) => Failure(InvalidDepositException(id, s"There is a fetch.txt file, but all the files are present in the bag."))
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
              noFetchItemsAlreadyInBag(bagDir, itemsFromBagStore)
                .flatMap(_ => bagStoreSettings.map(implicit bs => validateChecksumsFetchItems(bag, itemsFromBagStore))
                  .getOrElse(Failure(new NoSuchElementException("BagStore is not configured - SOMETHING WENT WRONG, YOU SHOULD NEVER REACH THIS PART OF CODE!"))))
            else
              Failure(InvalidDepositException(id, s"Missing payload files not in the fetch.txt: ${ missingFilesNotInFetchText.mkString }."))
          }
          else
            Failure(InvalidDepositException(id, s"Validation of bag did not succeed: ${ otherThanMissingPayloadFilesMessages.mkString("\n") }"))
      }
    }

    val fetchItems = getFetchTxt(bagDir).map(_.asScala).getOrElse(Seq())
    val (fetchItemsInBagStore, itemsToResolve) = fetchItems.partition(bagStoreSettings.nonEmpty && _.getUrl.startsWith(bagStoreSettings.get.baseUrl))
    for {
      _ <- resolveFetchItems(bagDir, itemsToResolve)
      _ <- if (itemsToResolve.isEmpty) Success(())
           else pruneFetchTxt(bagDir, itemsToResolve)
      bag <- getBag(bagDir)
      validationResult <- verifyBagIsValid(bag)
      _ <- handleValidationResult(bag, validationResult, fetchItemsInBagStore)
    } yield ()
  }

  private def verifyBagIsValid(bag: Bag)(implicit depositId: DepositId): Try[SimpleResult] = {
    Try {
      bag.verifyValid // throws empty IllegalArgumentException if algorithm type in the name of the manifest is not recognized
    }.recoverWith {
      case _: Exception => Failure(InvalidDepositException(depositId, "unrecognized javaSecurityAlgorithm"))
    }
  }

  def getFetchTxt(bagDir: File): Try[FetchTxt] = getBag(bagDir).map(_.getFetchTxt).filter(_ != null)

  def pruneFetchTxt(bagDir: File, items: Seq[FetchTxt.FilenameSizeUrl]): Try[Unit] =
    getBag(bagDir)
      .map(bag => {
        Option(bag.getFetchTxt).map(fetchTxt => Try {
          fetchTxt.removeAll(items.asJava)
          if (fetchTxt.isEmpty) bag.removeBagFile(bag.getBagConstants.getFetchTxt)
          // TODO: Remove the loop. Complete needs to be called only once for all tagmanifests. See easy-ingest-flow FlowStepEnrichMetadata.updateTagManifests
          bag.getTagManifests.asScala.map(_.getAlgorithm).foreach(a => {
            val completer = new TagManifestCompleter(bagFactory)
            completer.setTagManifestAlgorithm(a)
            completer complete bag
          })
          val writer = new FileSystemWriter(bagFactory)
          writer.setTagFilesOnly(true)
          bag.write(writer, bagDir)
        }).getOrElse(Success(()))
      })

  private def getBag(bagDir: File): Try[Bag] = Try {
    bagFactory.createBag(bagDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }

  private def resolveFetchItems(bagDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId): Try[Unit] = {
    if (fetchItems.nonEmpty) log.debug(s"[$id] Resolving files in fetch.txt, those referring outside the bag store.")

    fetchItems
      .map(item => Using.urlInputStream(new URL(item.getUrl))
        .map(src => {
          val file = new File(bagDir.getAbsoluteFile, item.getFilename)
          if (file.exists)
            Failure(InvalidDepositException(id, s"File ${ item.getFilename } in the fetch.txt is already present in the bag."))
          else
            Try {
              file.getParentFile.mkdirs()
              Files.copy(src, file.toPath)
            }
        })
        .tried
        .flatten
        .recoverWith {
          case e: UnknownHostException => Failure(InvalidDepositException(id, s"The URL for ${ item.getFilename } contains an unknown host.", e))
          case e: IOException => Failure(InvalidDepositException(id, s"File ${ item.getFilename } in the fetch.txt could not be downloaded.", e))
        })
      .collectResults
      .map(_ => ())
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage), "resolving files from fetch.txt"), e))
      }
  }

  private def noFetchItemsAlreadyInBag(bagDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId): Try[Unit] = {
    log.debug(s"[$id] Checking that the files in fetch.txt are absent in the bag.")

    val presentFiles = fetchItems.filter(item => new File(bagDir.getAbsoluteFile, item.getFilename).exists)
    if (presentFiles.nonEmpty)
      Failure(InvalidDepositException(id, s"Fetch.txt file ${ presentFiles.head.getFilename } is already present in the bag."))
    else
      Success(())
  }

  private def validateChecksumsFetchItems(bag: Bag, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    log.debug(s"[$id] Validating checksums of those files in fetch.txt, that refer to the bag store.")

    val fetchItemFiles = fetchItems.map(_.getFilename)
    val urls = fetchItems.map(file => file.getFilename -> file.getUrl).toMap

    val checksumMapping = bag.getPayloadManifests.asScala
      .flatMap(_.asScala) // mapping from file -> checksum
      .filter { case (file, _) => fetchItemFiles.contains(file) }
      .map { case (file, checksum) => (file, checksum, urls(file)) }
    validateChecksums(checksumMapping)
  }

  private def validateChecksums(checksumMapping: Seq[(String, String, String)])(implicit id: DepositId, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    checksumMapping
      .map {
        case (file, checksum, url) => compareChecksumAgainstReferredBag(file, checksum, url)
      }.collectResults
      .map(_ => ())
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage), "validating checksums of files in fetch.txt"), e))
      }
  }

  private def compareChecksumAgainstReferredBag(file: String, checksum: String, url: String)(implicit id: DepositId, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    val referredFile = getReferredFile(url, bagStoreSettings.baseUrl)
    getReferredBagChecksums(url).flatMap(seq => {
      if (seq.contains(referredFile -> checksum))
        Success(())
      else if (seq.map { case (rFile, _) => rFile }.contains(referredFile))
             Failure(InvalidDepositException(id, s"Checksum $checksum of the file $file differs from checksum of the file $referredFile in the referred bag."))
      else
        Failure(InvalidDepositException(id, s"While validating checksums, the file $referredFile was not found in the referred bag."))
    })
  }

  private def getReferredFile(url: String, baseUrl: String): String = {
    val afterBaseUrl = url.stripPrefix(baseUrl)
    afterBaseUrl.substring(afterBaseUrl.indexOf("/data/") + 1)
  }

  def isOnPosixFileSystem(file: File): Boolean = Try(Files.getPosixFilePermissions(file.toPath)).fold(_ => false, _ => true)

  def moveBagToStorage(depositDir: File, storageDir: File)(implicit settings: Settings, id: DepositId): Try[File] = {
    log.debug(s"[$id] Moving bag to permanent storage")
    FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
      .map(_ => Files.move(depositDir.toPath.toAbsolutePath, storageDir.toPath.toAbsolutePath).toFile)
      .recoverWith { case e => Failure(new SwordError("Failed to move dataset to storage", e)) }
  }

  def doesHashMatch(zipFile: File, MD5: String)(implicit id: DepositId): Try[Unit] = {
    log.debug(s"[$id] Checking Content-MD5 (Received: $MD5)")
    lazy val fail = Failure(new SwordError(UriRegistry.ERROR_CHECKSUM_MISMATCH))

    Using.fileInputStream(zipFile)
      .map(is => {
        if (MD5 == DigestUtils.md5Hex(is)) Success(())
        else fail
      })
      .tried
      .flatten
  }

  def createDepositReceipt(settings: Settings, id: DepositId): DepositReceipt = {
    val dr = new DepositReceipt
    val editIRI = new IRI(settings.serviceBaseUrl + "container/" + id)
    val editMediaIri = new IRI(settings.serviceBaseUrl + "media/" + id)
    val stateIri = settings.serviceBaseUrl + "statement/" + id
    dr.setEditIRI(editIRI)
    dr.setLocation(editIRI)
    dr.setEditMediaIRI(editMediaIri)
    dr.setSwordEditIRI(editIRI)
    dr.setAtomStatementURI(stateIri)
    dr.setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
    dr.setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    dr
  }

  // TODO: RETRIEVE VIA AN INTERFACE
  private def getReferredBagChecksums(url: String)(implicit bagStoreSettings: BagStoreSettings): Try[Seq[(String, String)]] =
    getBag(getReferredBagDir(url)).map(bag => {
      bag.getPayloadManifests
        .asScala
        .flatMap(_.asScala)
    })

  private def getReferredBagDir(url: String)(implicit bagStoreSettings: BagStoreSettings): File = {
    //  http://deasy.dans.knaw.nl/aips/31aef203-55ed-4b1f-81f6-b9f67f324c87.2/data/x -> 31/aef20355ed4b1f81f6b9f67f324c87/2
    val Array(uuid, version) = url.stripPrefix(bagStoreSettings.baseUrl)
      .split("/data").head.replaceAll("-", "")
      .split("\\.")
    val (topDir, uuidDir) = uuid.splitAt(3)

    getFile(bagStoreSettings.baseDir, topDir, uuidDir, version)
  }
}
