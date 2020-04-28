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

import java.io.{ File => JFile }
import java.nio.file.Files

import nl.knaw.dans.easy.sword2.State.{ FAILED, SUBMITTED }
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.joda.time.{ DateTime, DateTimeZone }
import org.swordapp.server.SwordError
import rx.lang.scala.Observable

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{ Failure, Try }

object DepositFinalizer extends DebugEnhancedLogging {

  def finalizeDeposit(mimetype: MimeType)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.info(s"[$id] Finalizing deposit")
    implicit val bagStoreSettings: Option[BagStoreSettings] = settings.bagStoreSettings
    val depositDir = new JFile(settings.tempDir, id)
    lazy val storageDir = new JFile(settings.depositRootDir, id)

    val result = for {
      props <- DepositProperties.load(id)
      _ <- props.setState(State.FINALIZING, "Finalizing deposit")
      _ <- props.save()
      _ <- BagExtractor.extractBag(depositDir, mimetype)
      bagDir <- getBagDir(depositDir)
      _ <- BagValidation.checkFetchItemUrls(bagDir, settings.urlPattern)
      _ <- BagValidation.checkBagVirtualValidity(bagDir)
      props <- DepositProperties.load(id)
      _ <- props.setState(SUBMITTED, "Deposit is valid and ready for post-submission processing")
      _ <- props.setBagName(bagDir.getName)
      _ <- props.save()
      _ <- DepositCleaner.removeZipFiles(depositDir)
      // ATTENTION: first remove content-type property and THEN move bag to ingest-flow-inbox!!
      _ <- props.removeClientMessageContentType()
      _ <- props.save()
      _ <- moveBagToStorage(depositDir, storageDir)
    } yield ()

    result.doIfSuccess(_ => logger.info(s"[$id] Done finalizing deposit"))
      .recoverWith {
        case e: InvalidDepositException => DepositHandler.recoverInvalidDeposit(e, depositDir, e.getMessage)
        case e: NotEnoughDiskSpaceException => recoverNotEnoughDiskSpace(e, mimetype)
        case NonFatal(e) => recoverNonFatalException(e, depositDir)
      }
  }

  private def getBagDir(depositDir: JFile): Try[JFile] = Try {
    val depositFiles = depositDir.listFiles.filter(_.isDirectory)
    if (depositFiles.length != 1) throw InvalidDepositException(depositDir.getName, s"A deposit package must contain exactly one top-level directory, number found: ${ depositFiles.length }")
    depositFiles(0)
  }

  private def moveBagToStorage(depositDir: JFile, storageDir: JFile)(implicit settings: Settings, id: DepositId): Try[JFile] = {
    debug(s"[$id] Moving bag to permanent storage")
    Try { Files.move(depositDir.toPath.toAbsolutePath, storageDir.toPath.toAbsolutePath).toFile }
      .recoverWith { case e => Failure(new SwordError("Failed to move dataset to storage", e)) }
  }

  private def recoverNotEnoughDiskSpace(e: NotEnoughDiskSpaceException, mimetype: MimeType)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.warn(s"[$id] ${ e.getMessage }")
    logger.info(s"[$id] rescheduling after ${ settings.rescheduleDelaySeconds } seconds, while waiting for more disk space")

    // Ignoring result; it would probably not be possible to change the state in the deposit.properties anyway.
    for {
      props <- DepositProperties.load(id)
      _ <- props.setState(State.UPLOADED, "Rescheduled, waiting for more disk space")
      _ <- props.save()
      _ = Observable.timer(settings.rescheduleDelaySeconds seconds)
        .subscribe(_ => DepositProcessor.processDeposit(id, mimetype))
    } yield ()
  }

  private def recoverNonFatalException(e: Throwable, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.error(s"[$id] Internal failure in deposit service", e)
    for {
      props <- DepositProperties.load(id)
      _ <- props.setState(FAILED, genericErrorMessage)
      _ <- props.save()
      _ <- DepositCleaner.cleanupFiles(depositDir, FAILED)
    } yield ()
  }

  private def genericErrorMessage(implicit settings: Settings, id: DepositId): String = {
    val mailaddress = settings.supportMailAddress
    val timestamp = DateTime.now(DateTimeZone.UTC).toString

    s"""The server encountered an unexpected condition.
       |Please contact the SWORD service administrator at $mailaddress.
       |The error occurred at $timestamp. Your 'DepositID' is $id.
    """.stripMargin
  }
}
