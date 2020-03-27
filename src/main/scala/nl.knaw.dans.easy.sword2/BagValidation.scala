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
import java.nio.file.Files
import java.util.regex.Pattern

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl
import gov.loc.repository.bagit.Manifest.Algorithm
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.verify.CompleteVerifier
import gov.loc.repository.bagit.{ Bag, FetchTxt, Manifest }
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.FileUtils
import resource.Using

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }

object BagValidation extends DebugEnhancedLogging {

  lazy val acceptedValues: String = Algorithm
    .values()
    .map(_.bagItAlgorithm)
    .mkString(", ")

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

    val fetchItems = BagInteractor.getFetchTxt(bagDir).map(_.asScala).getOrElse(Seq())
    val (fetchItemsInBagStore, itemsToResolve) = fetchItems.partition(bagStoreSettings.nonEmpty && _.getUrl.startsWith(bagStoreSettings.get.baseUrl))
    for {
      _ <- resolveFetchItems(bagDir, itemsToResolve)
      _ <- if (itemsToResolve.isEmpty) Success(())
           else BagInteractor.pruneFetchTxt(bagDir, itemsToResolve)
      bag <- BagInteractor.getBag(bagDir)
      validationResult <- verifyBagIsValid(bag)
      _ <- handleValidationResult(bag, validationResult, fetchItemsInBagStore)
    } yield ()
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

  def verifyBagIsValid(bag: Bag)(implicit depositId: DepositId): Try[SimpleResult] = {
    (bag.getPayloadManifests.asScala.toList ::: bag.getTagManifests.asScala.toList)
      .map(verifyPayloadManifestAlgorithm)
      .collectResults
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(depositId, formatMessages(throwables.map(_.getMessage), ""), e))
      }
      .map(_ => bag.verifyValid)
  }

  private def verifyPayloadManifestAlgorithm(manifest: Manifest)(implicit depositId: DepositId): Try[Unit] = {
    Try(manifest.getAlgorithm) //throws message-less IllegalArgumentException when manifest cannot be found
      .fold(_ => Failure(InvalidDepositException(depositId, s"Unrecognized algorithm for manifest: ${ manifest.getFilepath }. Supported algorithms are: ${ BagValidation.acceptedValues }.")), _ => Success(()))
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

  private def getReferredBagChecksums(url: String)(implicit bagStoreSettings: BagStoreSettings): Try[Seq[(String, String)]] = {
    BagInteractor.getBag(getReferredBagDir(url))
      .map(bag => {
        bag.getPayloadManifests
          .asScala
          .flatMap(_.asScala)
      })
  }

  private def getReferredBagDir(url: String)(implicit bagStoreSettings: BagStoreSettings): JFile = {
    //  http://deasy.dans.knaw.nl/aips/31aef203-55ed-4b1f-81f6-b9f67f324c87.2/data/x -> 31/aef20355ed4b1f81f6b9f67f324c87/2
    val Array(uuid, version) = url.stripPrefix(bagStoreSettings.baseUrl)
      .split("/data").head.replaceAll("-", "")
      .split("\\.")
    val (topDir, uuidDir) = uuid.splitAt(3)

    FileUtils.getFile(bagStoreSettings.baseDir, topDir, uuidDir, version)
  }

  def checkFetchItemUrls(bagDir: JFile, urlPattern: Pattern)(implicit id: DepositId): Try[Unit] = {
    debug(s"[$id] Checking validity of urls in fetch.txt")

    BagInteractor.getFetchTxt(bagDir)
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

  private def formatMessages(seq: Seq[String], in: String): String = {
    seq match {
      case Seq() => s"No errors found in $in"
      case Seq(msg) => s"One error found in $in:\n\t- $msg"
      case msgs => msgs.map(msg => s"\t- $msg").mkString(s"Multiple errors found in $in:\n", "\n", "")
    }
  }
}
