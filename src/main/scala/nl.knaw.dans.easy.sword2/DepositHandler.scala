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

import java.io.{ IOException, File => JFile }
import java.net.{ MalformedURLException, URL, UnknownHostException }
import java.nio.file._
import java.util.regex.Pattern
import java.util.{ Collections, NoSuchElementException }

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl
import gov.loc.repository.bagit.transformer.impl.TagManifestCompleter
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.verify.CompleteVerifier
import gov.loc.repository.bagit.writer.impl.FileSystemWriter
import gov.loc.repository.bagit.{ Bag, BagFactory, FetchTxt }
import nl.knaw.dans.easy.sword2.State._
import nl.knaw.dans.lib.error.{ CompositeException, TraversableTryExtensions, _ }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils._
import org.joda.time.{ DateTime, DateTimeZone }
import org.swordapp.server.{ Deposit, DepositReceipt, SwordError, UriRegistry }
import resource.Using
import rx.lang.scala.Observable
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

object DepositHandler extends BagValidationExtension with DebugEnhancedLogging {
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
      .tempDir
      .listFiles()
      .withFilter(_.isDirectory)
      .withFilter(isDepositUploaded)
      .foreach(getContentTypeOnNext(_))
  }

  private def getContentTypeOnNext(d: JFile)(implicit settings: Settings): Try[Unit] = {
    getContentType(d)
      .doIfSuccess(_ => logger.info(s"[${ d.getName }] Scheduling UPLOADED deposit for finalizing."))
      .doIfFailure { case _: Throwable => logger.warn(s"[${ d.getName }] Could not get deposit Content-Type. Not putting this deposit on the queue.") }
      .map(mimeType => depositProcessingStream.onNext((d.getName, mimeType)))
  }

  private def isDepositUploaded(deposit: JFile)(implicit settings: Settings): Boolean = {
    getDepositState(deposit)
      .doIfFailure { case _: Throwable => logger.warn(s"[${ deposit.getName }] Could not get deposit state. Not putting this deposit on the queue.") }
      .fold(_ => false, _ == State.UPLOADED)
  }

  private def getDepositState(dir: JFile)(implicit settings: Settings): Try[State] = {
    DepositProperties(dir.getName)
      .flatMap(_.getState)
  }

  private def getContentType(dir: JFile)(implicit settings: Settings): Try[String] = {
    DepositProperties(dir.getName)
      .flatMap(_.getClientMessageContentType)
  }

  def handleDeposit(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[DepositReceipt] = {
    val contentLength = deposit.getContentLength
    if (contentLength == -1) {
      logger.warn(s"[$id] Request did not contain a Content-Length header. Skipping disk space check.")
    }

    val payload = Paths.get(settings.tempDir.toString, id, deposit.getFilename.split("/").last).toFile
    val depositDir = Paths.get(settings.tempDir.toString, id).toFile
    // to ensure all files in deposit are accessible for the deposit group, the method setFilePermissions is always
    // executed (regardless of whether extractAndValidatePayloadAndGetDepositReceipt was successful or not).
    // Otherwise operators don't have the proper permissions to clean or fix the invalid zip files or bags that might stay behind
    extractAndValidatePayloadAndGetDepositReceipt(deposit, contentLength, payload, depositDir)
  }

  private def extractAndValidatePayloadAndGetDepositReceipt(deposit: Deposit, contentLength: Long, payload: JFile, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[DepositReceipt] = {
    for {
      _ <- if (contentLength > -1) assertTempDirHasEnoughDiskspaceMarginForFile(contentLength)
           else Success(())
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)(id)
      _ <- FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
      _ <- handleDepositAsync(deposit)
      // Attention: do not access the deposit after this call. handleDepositAsync will finalize the deposit on a different thread than this one and so we cannot know if the
      // deposit is still in the easy-sword2 temp directory.
      dr = createDepositReceipt(id)
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
    if (logger.underlying.isDebugEnabled) {
      debug(s"Free space  = ${ settings.tempDir.getFreeSpace }")
      debug(s"File length = $len")
      debug(s"Margin      = ${ settings.marginDiskSpace }")
      debug(s"Extra space = ${ settings.tempDir.getFreeSpace - len - settings.marginDiskSpace }")
    }

    if (settings.tempDir.getFreeSpace - len < settings.marginDiskSpace) {
      logger.warn(s"[$id] Not enough disk space for request + margin ($len + ${ settings.marginDiskSpace } = ${ len + settings.marginDiskSpace }). Available: ${ settings.tempDir.getFreeSpace }, Short: ${ len + settings.marginDiskSpace - settings.tempDir.getFreeSpace }.")

      throw new SwordError(503) {
        override def getMessage: String = "503 Service temporarily unavailable"
      }
    }
  }

  def finalizeDeposit(mimetype: MimeType)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.info(s"[$id] Finalizing deposit")
    implicit val bagStoreSettings: Option[BagStoreSettings] = settings.bagStoreSettings
    val depositDir = new JFile(settings.tempDir, id)
    lazy val storageDir = new JFile(settings.depositRootDir, id)

    val result = for {
      props <- DepositProperties(id)
      _ <- props.setState(State.FINALIZING, "Finalizing deposit")
      _ <- props.save()
      _ <- BagExtractor.extractBag(depositDir, mimetype)
      bagDir <- getBagDir(depositDir)
      _ <- checkFetchItemUrls(bagDir, settings.urlPattern)
      _ <- checkBagVirtualValidity(bagDir)
      props <- DepositProperties(id)
      _ <- props.setState(SUBMITTED, "Deposit is valid and ready for post-submission processing")
      _ <- props.setBagName(bagDir)
      token <- getSwordToken(bagDir, id)
      _ <- props.setSwordToken(token)
      _ <- props.save()
      _ <- removeZipFiles(depositDir)
      // ATTENTION: first remove content-type property and THEN move bag to ingest-flow-inbox!!
      _ <- props.removeClientMessageContentType()
      _ <- props.save()
      _ <- moveBagToStorage(depositDir, storageDir)
    } yield ()

    result.doIfSuccess(_ => logger.info(s"[$id] Done finalizing deposit"))
      .recoverWith {
        case e: InvalidDepositException => recoverInvalidDeposit(e, depositDir)
        case e: NotEnoughDiskSpaceException => recoverNotEnoughDiskSpace(e, mimetype)
        case NonFatal(e) => recoverNonFatalException(e, depositDir)
      }
  }

  private def getSwordToken(bagDir: JFile, defaultToken: String): Try[String]  = {
    for {
      bag <- getBag(bagDir)
      token = s"sword:${Option(bag.getBagInfoTxt.get("Is-Version-Of")).getOrElse(defaultToken)}"
    } yield token
  }

  private def recoverInvalidDeposit(e: InvalidDepositException, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.error(s"[$id] Invalid deposit: ${ e.msg }", e.cause)
    for {
      props <- DepositProperties(id)
      _ <- props.setState(INVALID, e.msg)
      _ <- props.save()
      _ <- cleanupFiles(depositDir, INVALID)
    } yield ()
  }

  private def recoverNotEnoughDiskSpace(e: NotEnoughDiskSpaceException, mimetype: MimeType)(implicit settings: Settings, id: DepositId) = {
    logger.warn(s"[$id] ${ e.getMessage }")
    logger.info(s"[$id] rescheduling after ${ settings.rescheduleDelaySeconds } seconds, while waiting for more disk space")

    // Ignoring result; it would probably not be possible to change the state in the deposit.properties anyway.
    for {
      props <- DepositProperties(id)
      _ <- props.setState(State.UPLOADED, "Rescheduled, waiting for more disk space")
      _ <- props.save()
      _ = Observable.timer(settings.rescheduleDelaySeconds seconds)
        .subscribe(_ => depositProcessingStream.onNext((id, mimetype)))
    } yield ()
  }

  private def recoverNonFatalException(e: Throwable, depositDir: JFile)(implicit settings: Settings, id: DepositId) = {
    logger.error(s"[$id] Internal failure in deposit service", e)
    for {
      props <- DepositProperties(id)
      _ <- props.setState(FAILED, genericErrorMessage)
      _ <- props.save()
      _ <- cleanupFiles(depositDir, FAILED)
    } yield ()
  }

  private def cleanupFiles(depositDir: JFile, state: State)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (settings.cleanup.getOrElse(state, false)) {
      logger.info(s"[$id] cleaning up zip files and bag directory for deposit due to state $state")
      for {
        _ <- removeZipFiles(depositDir)
        bagDir <- getBagDir(depositDir)
        _ <- Try {
          if (bagDir.exists()) {
            debug(s"[$id] removing bag $bagDir")
            deleteQuietly(bagDir)
          }
          else {
            debug(s"[$id] bag did not exist; no removal necessary")
          }
        }
      } yield ()
    }
    else
      Success(())
  }

  private def removeZipFiles(depositDir: JFile)(implicit id: DepositId): Try[Unit] = Try {
    debug(s"[$id] removing zip files")
    for (file <- depositDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      debug(s"[$id] removing $file")
      deleteQuietly(file)
    }
  }

  private def getBagDir(depositDir: JFile): Try[JFile] = Try {
    val depositFiles = depositDir.listFiles.filter(_.isDirectory)
    if (depositFiles.length != 1) throw InvalidDepositException(depositDir.getName, s"A deposit package must contain exactly one top-level directory, number found: ${ depositFiles.length }")
    depositFiles(0)
  }

  def checkDepositIsInDraft(id: DepositId)(implicit settings: Settings): Try[Unit] = {
    DepositProperties(id)
      .flatMap(_.getState)
      .flatMap {
        case State.DRAFT => Success(())
        case _ => Failure(new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED, s"Deposit $id is not in DRAFT state."))
      }
  }

  def copyPayloadToFile(deposit: Deposit, zipFile: JFile)(implicit id: DepositId): Try[Unit] = Try {
    debug(s"[$id] Copying payload to: $zipFile")
    copyInputStreamToFile(deposit.getInputStream, zipFile)
  } recoverWith {
    case t: Throwable => Failure(new SwordError(UriRegistry.ERROR_BAD_REQUEST, t))
  }

  def handleDepositAsync(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (!deposit.isInProgress) {
      logger.info(s"[$id] Scheduling deposit to be finalized")
      for {
        props <- DepositProperties(id)
        _ <- props.setState(UPLOADED, "Deposit upload has been completed.")
        _ <- props.setClientMessageContentType(deposit.getMimeType)
        _ <- props.save()
      } yield depositProcessingStream.onNext((id, deposit.getMimeType))
    }
    else Try {
      logger.info(s"[$id] Received continuing deposit: ${ deposit.getFilename }")
    }
  }

  def formatMessages(seq: Seq[String], in: String): String = {
    seq match {
      case Seq() => s"No errors found in $in"
      case Seq(msg) => s"One error found in $in:\n\t- $msg"
      case msgs => msgs.map(msg => s"\t- $msg").mkString(s"Multiple errors found in $in:\n", "\n", "")
    }
  }

  def checkFetchItemUrls(bagDir: JFile, urlPattern: Pattern)(implicit id: DepositId): Try[Unit] = {
    debug(s"[$id] Checking validity of urls in fetch.txt")

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

  def checkBagVirtualValidity(bagDir: JFile)(implicit id: DepositId, bagStoreSettings: Option[BagStoreSettings]): Try[Unit] = {
    debug(s"[$id] Verifying bag validity")

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

  def getFetchTxt(bagDir: JFile): Try[FetchTxt] = getBag(bagDir).map(_.getFetchTxt).filter(_ != null)

  def pruneFetchTxt(bagDir: JFile, items: Seq[FetchTxt.FilenameSizeUrl]): Try[Unit] =
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

  private def getBag(bagDir: JFile): Try[Bag] = Try {
    bagFactory.createBag(bagDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }

  private def resolveFetchItems(bagDir: JFile, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId): Try[Unit] = {
    if (fetchItems.nonEmpty)
      debug(s"[$id] Resolving files in fetch.txt, those referring outside the bag store.")

    fetchItems
      .map(resolveFetchItem(bagDir))
      .collectResults
      .map(_ => ())
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage), "resolving files from fetch.txt"), e))
      }
  }

  private def resolveFetchItem(bagDir: JFile)(item: FetchTxt.FilenameSizeUrl)(implicit id: DepositId): Try[Long] = {
    Using.urlInputStream(new URL(item.getUrl))
      .map(src => {
        val file = new JFile(bagDir.getAbsoluteFile, item.getFilename)
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
      }
  }

  private def noFetchItemsAlreadyInBag(bagDir: JFile, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId): Try[Unit] = {
    debug(s"[$id] Checking that the files in fetch.txt are absent in the bag.")

    val presentFiles = fetchItems.filter(item => new JFile(bagDir.getAbsoluteFile, item.getFilename).exists)
    if (presentFiles.nonEmpty)
      Failure(InvalidDepositException(id, s"Fetch.txt file ${ presentFiles.head.getFilename } is already present in the bag."))
    else
      Success(())
  }

  private def validateChecksumsFetchItems(bag: Bag, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: DepositId, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    debug(s"[$id] Validating checksums of those files in fetch.txt, that refer to the bag store.")

    val fetchItemFiles = fetchItems.map(_.getFilename)
    val urls = fetchItems.map(file => file.getFilename -> file.getUrl).toMap

    bag.getPayloadManifests.asScala
      .flatMap(_.asScala) // mapping from file -> checksum
      .withFilter { case (file, _) => fetchItemFiles contains file }
      .map { case (file, checksum) => compareChecksumAgainstReferredBag(file, checksum, urls(file)) }
      .collectResults
      .map(_ => ())
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage), "validating checksums of files in fetch.txt"), e))
      }
  }

  private def compareChecksumAgainstReferredBag(file: String, checksum: String, url: String)(implicit id: DepositId, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    val referredFile = getReferredFile(url, bagStoreSettings.baseUrl)
    getReferredBagChecksums(url)
      .flatMap {
        case seq if seq.contains(referredFile -> checksum) => Success(())
        case seq if seq.map { case (rFile, _) => rFile }.contains(referredFile) =>
          Failure(InvalidDepositException(id, s"Checksum $checksum of the file $file differs from checksum of the file $referredFile in the referred bag."))
        case _ =>
          Failure(InvalidDepositException(id, s"While validating checksums, the file $referredFile was not found in the referred bag."))
      }
  }

  private def getReferredFile(url: String, baseUrl: String): String = {
    val afterBaseUrl = url.stripPrefix(baseUrl)
    afterBaseUrl.substring(afterBaseUrl.indexOf("/data/") + 1)
  }

  def moveBagToStorage(depositDir: JFile, storageDir: JFile)(implicit settings: Settings, id: DepositId): Try[JFile] = {
    debug(s"[$id] Moving bag to permanent storage")
    Try { Files.move(depositDir.toPath.toAbsolutePath, storageDir.toPath.toAbsolutePath).toFile }
      .recoverWith { case e => Failure(new SwordError("Failed to move dataset to storage", e)) }
  }

  def doesHashMatch(zipFile: JFile, MD5: String)(implicit id: DepositId): Try[Unit] = {
    debug(s"[$id] Checking Content-MD5 (Received: $MD5)")

    Using.fileInputStream(zipFile)
      .map {
        case is if DigestUtils.md5Hex(is) == MD5 => Success(())
        case _ => Failure(new SwordError(UriRegistry.ERROR_CHECKSUM_MISMATCH))
      }
      .tried
      .flatten
  }

  def createDepositReceipt(id: DepositId)(implicit settings: Settings): DepositReceipt = {
    new DepositReceipt {
      val editIRI = new IRI(settings.serviceBaseUrl + "container/" + id)
      setEditIRI(editIRI)
      setLocation(editIRI)
      setEditMediaIRI(new IRI(settings.serviceBaseUrl + "media/" + id))
      setSwordEditIRI(editIRI)
      setAtomStatementURI(settings.serviceBaseUrl + "statement/" + id)
      setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
      setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    }
  }

  // TODO: RETRIEVE VIA AN INTERFACE
  private def getReferredBagChecksums(url: String)(implicit bagStoreSettings: BagStoreSettings): Try[Seq[(String, String)]] =
    getBag(getReferredBagDir(url)).map(bag => {
      bag.getPayloadManifests
        .asScala
        .flatMap(_.asScala)
    })

  private def getReferredBagDir(url: String)(implicit bagStoreSettings: BagStoreSettings): JFile = {
    //  http://deasy.dans.knaw.nl/aips/31aef203-55ed-4b1f-81f6-b9f67f324c87.2/data/x -> 31/aef20355ed4b1f81f6b9f67f324c87/2
    val Array(uuid, version) = url.stripPrefix(bagStoreSettings.baseUrl)
      .split("/data").head.replaceAll("-", "")
      .split("\\.")
    val (topDir, uuidDir) = uuid.splitAt(3)

    getFile(bagStoreSettings.baseDir, topDir, uuidDir, version)
  }
}
