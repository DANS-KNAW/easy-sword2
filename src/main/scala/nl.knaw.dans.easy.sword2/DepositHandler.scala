/**
 * Copyright (C) 2015-2017 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.easy.sword2

import java.io.{File, IOException}
import java.net.{MalformedURLException, URL, UnknownHostException}
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermissions}
import java.util.regex.Pattern
import java.util.{Collections, NoSuchElementException}

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl
import gov.loc.repository.bagit.transformer.impl.TagManifestCompleter
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.verify.CompleteVerifier
import gov.loc.repository.bagit.writer.impl.FileSystemWriter
import gov.loc.repository.bagit.{Bag, BagFactory, FetchTxt}
import net.lingala.zip4j.core.ZipFile
import nl.knaw.dans.easy.sword2.State._
import nl.knaw.dans.lib.error.{CompositeException, TraversableTryExtensions}
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import org.swordapp.server.{Deposit, DepositReceipt, SwordError}
import resource.Using
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object DepositHandler {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val bagFactory = new BagFactory

  private val depositProcessingStream = PublishSubject[(String, Deposit)]()

  def startDepositProcessingStream(settings: Settings): Unit = {
    depositProcessingStream
      .onBackpressureBuffer
      .observeOn(NewThreadScheduler())
      .doOnEach(_ match { case (id, deposit) => finalizeDeposit(deposit.getMimeType)(settings, id) })
      .subscribe(_ match { case (id, deposit) => log.info(s"Done finalizing deposit $id") })
  }

  def handleDeposit(deposit: Deposit)(implicit settings: Settings, id: String): Try[DepositReceipt] = {
    val payload = Paths.get(settings.tempDir.toString, id, deposit.getFilename.split("/").last).toFile
    for {
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)
      _ <- handleDepositAsync(deposit)
      dr = createDepositReceipt(settings, id)
      _ = dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    } yield dr
  }

  def genericErrorMessage(implicit settings: Settings, id: String): String = {
    val mailaddress = settings.supportMailAddress
    val timestamp = DateTime.now(DateTimeZone.UTC).toString

    s"""The server encountered an unexpected condition.
      |Please contact the SWORD service administrator at $mailaddress.
      |The error occurred at $timestamp. Your 'DepositID' is $id.
    """.stripMargin
  }

  def finalizeDeposit(mimeType: String)(implicit settings: Settings, id: String): Try[Unit] = {
    log.info(s"[$id] Finalizing deposit")
    implicit val bagStoreSettings = settings.bagStoreSettings
    val depositDir = new File(settings.tempDir, id)

    val result = for {
      _        <- extractBag(mimeType)
      bagDir   <- getBagDir(depositDir)
      _        <- checkFetchItemUrls(bagDir, settings.urlPattern)
      _        <- checkBagVirtualValidity(bagDir)
      props    <- DepositProperties(id)
      _        <- props.setState(SUBMITTED, "Deposit is valid and ready for post-submission processing")
      _        <- props.save()
      _        <- removeZipFiles(depositDir)
      dataDir  <- moveBagToStorage()
    } yield ()

    result.recover {
      case InvalidDepositException(_, msg, cause) =>
        log.error(s"[$id] Invalid deposit", cause)
        for {
          props <- DepositProperties(id)
          _ <- props.setState(INVALID, msg)
          _ <- props.save()
        } yield ()
      case NonFatal(e) =>
        log.error(s"[$id] Internal failure in deposit service", e)
        for {
          props <- DepositProperties(id)
          _ <- props.setState(FAILED, genericErrorMessage)
          _ <- props.save()
        } yield ()
    }
  }

  private def removeZipFiles(bagDir: File): Try[Unit] = Try {
    log.debug("Removing zip files")
    for (file <- bagDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      log.debug(s"Removing $file")
      deleteQuietly(file)
    }
  }

  private def extractBag(mimeType: String)(implicit settings: Settings, id: String): Try[File] = {
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
          .getOrElse(throw InvalidDepositException(id, s"Partial file ${f.getName} has no extension. It should be a positive sequence number."))
          .toInt

        if (seqNumber > 0) seqNumber
        else throw InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. It should be a positive sequence number (> 0), but was: $seqNumber")
      }
      catch {
        case _: NumberFormatException =>
          throw InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. Should be a positive sequence number.")
      }
    }

    Try {
      log.debug(s"[$id] Extracting bag")
      val depositDir: File = new File(settings.tempDir, id)
      val files = depositDir.listFilesSafe.filter(isPartOfDeposit)
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw InvalidDepositException(id, s"Inconsistent dataset: non-file object found: ${file.getName}")
            extract(file, depositDir.getPath)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(depositDir, "merged.zip")
          files.foreach(f => log.debug(s"[$id] Merging file: ${f.getName}"))
          MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
          extract(mergedZip, depositDir.getPath)
        case _ =>
          throw InvalidDepositException(id, s"Invalid content type: $mimeType")
      }
      depositDir
    }
  }

  private def getBagDir(depositDir: File): Try[File] = Try {
    val depositFiles = depositDir.listFiles.filter(_.isDirectory)
    if (depositFiles.length != 1) throw InvalidDepositException(depositDir.getName, s"A deposit package must contain exactly one top-level directory, number found: ${depositFiles.size}")
    depositFiles(0)
  }

  def checkDepositIsInDraft(id: String)(implicit settings: Settings): Try[Unit] = {
    for {
      props <- DepositProperties(id)
      state <- props.getState
      _ <- if (state == DRAFT) Success(()) else Failure(new SwordError("http://purl.org/net/sword/error/MethodNotAllowed", 405, s"Deposit $id is not in DRAFT state."))
    } yield ()
  }

  def copyPayloadToFile(deposit: Deposit, zipFile: File)(implicit id: String): Try[Unit] =
    try {
      log.debug(s"[$id] Copying payload to: $zipFile")
      Success(copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  def handleDepositAsync(deposit: Deposit)(implicit settings: Settings, id: String): Try[Unit] = Try {
    if (!deposit.isInProgress) {
      log.info(s"[$id] Scheduling deposit to be finalized")
      val result = for {
        props <- DepositProperties(id)
        _ <- props.setState(FINALIZING, "Deposit is being reassembled and validated")
        _ <- props.save()
      } yield ()
      result.get // Trigger exception if properties could not be updated
      depositProcessingStream.onNext((id, deposit))
    } else {
      log.info(s"[$id] Received continuing deposit: ${deposit.getFilename}")
    }
  }

  def formatMessages(seq: Seq[String], in: String): String = {
    seq match {
      case Seq() => s"No errors found in $in"
      case Seq(msg) => s"One error found in $in:\n\t- $msg"
      case msgs => msgs.map(msg => s"\t- $msg").mkString(s"Multiple errors found in $in:\n", "\n", "")
    }
  }

  def checkFetchItemUrls(bagDir: File, urlPattern: Pattern)(implicit id: String): Try[Unit] = {
    log.debug(s"[$id] Checking validity of urls in fetch.txt")

    getFetchTxt(bagDir)
      .map(_.asScala) // Option map
      .getOrElse(Seq.empty)
      .map(item => checkUrlValidity(item.getUrl, urlPattern)) // Seq map
      .collectResults
      .map(_ => ()) // Try map
      .recoverWith {
        case e@CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage).toSeq, "fetch.txt URLs"), e))
      }
  }

  private def checkUrlValidity(url: String, urlPattern: Pattern)(implicit id: String): Try[Unit] = {
    def checkUrlSyntax: Try[URL] = {
      Try(new URL(url)).recoverWith {
        case e: MalformedURLException => throw InvalidDepositException(id, s"Invalid url in Fetch Items ($url)")
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

  def checkBagVirtualValidity(bagDir: File)(implicit id: String, bagStoreSettings: Option[BagStoreSettings]): Try[Unit] = {
    log.debug(s"[$id] Verifying bag validity")

    def handleValidationResult(bag: Bag, validationResult: SimpleResult, fetchItemsInBagStore: Seq[FilenameSizeUrl]): Try[Unit] = {
      (fetchItemsInBagStore, validationResult.isSuccess) match {
        case (Seq(), true) => Success(())
        case (Seq(), false) => Failure(InvalidDepositException(id, validationResult.messagesToString))
        case (items, true) => Failure(InvalidDepositException(id, s"There is a fetch.txt file, but all the files are present in the bag."))
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
              Failure(InvalidDepositException(id, s"Missing payload files not in the fetch.txt: ${missingFilesNotInFetchText.mkString}."))
          }
          else
            Failure(InvalidDepositException(id, s"Validation of bag did not succeed: ${otherThanMissingPayloadFilesMessages.mkString("\n")}"))
      }
    }

    val fetchItems = getFetchTxt(bagDir).map(_.asScala).getOrElse(Seq())
    val (fetchItemsInBagStore, itemsToResolve) = fetchItems.partition(bagStoreSettings.nonEmpty && _.getUrl.startsWith(bagStoreSettings.get.baseUrl))
    for {
      _ <- resolveFetchItems(bagDir, itemsToResolve)
      _ <- if(itemsToResolve.isEmpty) Success(()) else pruneFetchTxt(bagDir, itemsToResolve)
      bag <- getBag(bagDir)
      validationResult = bag.verifyValid
      _ <- handleValidationResult(bag, validationResult, fetchItemsInBagStore)
    } yield ()
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

  private def getBag(bagDir: File): Try[Bag] =  Try {
    bagFactory.createBag(bagDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }

  private def resolveFetchItems(bagDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String): Try[Unit] = {
    if (fetchItems.nonEmpty) log.debug(s"[$id] Resolving files in fetch.txt, those referring outside the bag store.")

    fetchItems
      .map(item => Using.urlInputStream(new URL(item.getUrl))
        .map(src => {
          val file = new File(bagDir.getAbsoluteFile, item.getFilename)
          if (file.exists)
            Failure(InvalidDepositException(id, s"File ${item.getFilename} in the fetch.txt is already present in the bag."))
          else
            Try {
              file.getParentFile.mkdirs()
              Files.copy(src, file.toPath)
            }
        })
        .tried
        .flatten
        .recoverWith {
          case e: UnknownHostException => Failure(InvalidDepositException(id, s"The URL for ${item.getFilename} contains an unknown host.", e))
          case e: IOException => Failure(InvalidDepositException(id, s"File ${item.getFilename} in the fetch.txt could not be downloaded.", e))
        })
      .collectResults
      .map(_ => ())
      .recoverWith {
        case e@CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage).toSeq, "resolving files from fetch.txt"), e))
      }
  }

  private def noFetchItemsAlreadyInBag(bagDir: File, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String): Try[Unit] = {
    log.debug(s"[$id] Checking that the files in fetch.txt are absent in the bag.")

    val presentFiles = fetchItems.filter(item => new File(bagDir.getAbsoluteFile, item.getFilename).exists)
    if (presentFiles.nonEmpty)
      Failure(InvalidDepositException(id, s"Fetch.txt file ${presentFiles.head.getFilename} is already present in the bag."))
    else
      Success(())
  }

  private def validateChecksumsFetchItems(bag: Bag, fetchItems: Seq[FetchTxt.FilenameSizeUrl])(implicit id: String, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    log.debug(s"[$id] Validating checksums of those files in fetch.txt, that refer to the bag store.")

    val fetchItemFiles = fetchItems.map(_.getFilename)
    val urls = fetchItems.map(file => file.getFilename -> file.getUrl).toMap

    val checksumMapping = bag.getPayloadManifests.asScala
      .flatMap(_.asScala) // mapping from file -> checksum
      .filter { case (file, _) => fetchItemFiles.contains(file) }
      .map { case (file, checksum) => (file, checksum, urls(file)) }
    validateChecksums(checksumMapping)
  }

  private def validateChecksums(checksumMapping: Seq[(String, String, String)])(implicit id: String, bagStoreSettings: BagStoreSettings): Try[Unit] = {
    checksumMapping
      .map {
        case (file, checksum, url) => compareChecksumAgainstReferredBag(file, checksum, url)
      }.collectResults
      .map(_ => ())
      .recoverWith {
        case e@CompositeException(throwables) => Failure(InvalidDepositException(id, formatMessages(throwables.map(_.getMessage).toSeq, "validating checksums of files in fetch.txt"), e))
      }
  }

  private def compareChecksumAgainstReferredBag(file: String, checksum: String, url: String)(implicit id: String, bagStoreSettings: BagStoreSettings): Try[Unit] = {
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

  case class MakeAllGroupWritable(permissions: String) extends SimpleFileVisitor[Path] {
    override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
      log.debug(s"Setting the following permissions $permissions on file $path")
      try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        FileVisitResult.CONTINUE
      } catch {
        case usoe: UnsupportedOperationException => log.error("Not on a POSIX supported file system"); FileVisitResult.TERMINATE
        case cce: ClassCastException => log.error("No file permission elements in set"); FileVisitResult.TERMINATE
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

  def moveBagToStorage()(implicit settings: Settings, id: String): Try[File] =
    Try {
      log.debug("Moving bag to permanent storage")
      val tempDir = new File(settings.tempDir, id)
      val storageDir = new File(settings.depositRootDir, id)
      if (isOnPosixFileSystem(tempDir))
        Files.walkFileTree(tempDir.toPath, MakeAllGroupWritable(settings.depositPermissions))
      Files.move(tempDir.toPath.toAbsolutePath, storageDir.toPath.toAbsolutePath).toFile
    }.recover { case e => throw new SwordError("Failed to move dataset to storage", e) }

  def doesHashMatch(zipFile: File, MD5: String): Try[Unit] = {
    log.debug(s"Checking Content-MD5 (Received: $MD5)")
    lazy val fail = Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch"))

    Using.fileInputStream(zipFile)
      .map(is => {
        if (MD5 == DigestUtils.md5Hex(is)) Success(())
        else fail
      })
      .tried
      .flatten
  }

  def createDepositReceipt(settings: Settings, id: String): DepositReceipt = {
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
