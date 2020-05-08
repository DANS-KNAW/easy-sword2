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
import java.nio.file.Paths

import gov.loc.repository.bagit.BagFactory
import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.swordapp.server.{ Deposit, DepositReceipt, SwordError, UriRegistry }
import resource.Using

import scala.language.postfixOps
import scala.util.{ Failure, Success, Try }

object DepositHandler extends DebugEnhancedLogging {
  private implicit val bagFactory: BagFactory = new BagFactory

  def handleDeposit(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[DepositReceipt] = {
    val contentLength = deposit.getContentLength
    if (contentLength == -1) {
      logger.warn(s"[$id] Request did not contain a Content-Length header. Skipping disk space check.")
    }

    val payload = Paths.get(settings.tempDir.toString, id, deposit.getFilename.split("/").last).toFile
    val depositDir = Paths.get(settings.tempDir.toString, id).toFile
    for {
      _ <- verifyDiskspace(contentLength, depositDir)
      _ <- copyPayloadToFile(deposit, payload, depositDir)
      _ <- doesHashMatch(payload, deposit.getMd5, depositDir)
      _ <- FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
        .recoverWith { case e => recoverDepositSetState(e, depositDir, State.FAILED, "Failed to change file permissions").flatMap(_ => Failure(e)) }
      _ <- handleDepositAsync(deposit)
      // Attention: do not access the deposit after this call. handleDepositAsync will finalize the deposit on a different thread than this one and so we cannot know if the
      // deposit is still in the easy-sword2 temp directory.
      dr = SwordDocument.createDepositReceipt(id)
      _ = dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    } yield dr
  }

  private def verifyDiskspace(contentLength: Long, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (contentLength > -1)
      assertTempDirHasEnoughDiskspaceMarginForFile(contentLength)
        .recoverWith { case e => recoverDepositSetState(e, depositDir, State.FAILED, "Not enough disk space available to store payload").flatMap(_ => Failure(e)) }
    else Success(())
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

  private def copyPayloadToFile(deposit: Deposit, zipFile: JFile, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    debug(s"[$id] Copying payload to: $zipFile")
    FileUtils.copyInputStreamToFile(deposit.getInputStream, zipFile)
  }.recoverWith {
    case t: Throwable =>
      for {
        _ <- recoverInvalidDeposit(t, depositDir, "Failed to copy payload to file system")
        _ <- Failure(new SwordError(UriRegistry.ERROR_BAD_REQUEST, t))
      } yield ()
  }

  def recoverInvalidDeposit(e: Throwable, depositDir: JFile, errorMsg: String)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    for {
      _ <- recoverDepositSetState(e, depositDir, State.INVALID, errorMsg)
      _ <- DepositCleaner.cleanupFiles(depositDir, State.INVALID)
    } yield ()
  }

  private def recoverDepositSetState(e: Throwable, depositDir: JFile, errorState: State, errorMsg: String)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    logger.error(s"[$id] ${ errorState.toString.toLowerCase.capitalize } deposit: ${ errorMsg }", e)
    for {
      props <- DepositProperties.load(id)
      _ <- props.setState(errorState, errorMsg)
      _ <- props.save()
    } yield ()
  }

  private def doesHashMatch(zipFile: JFile, MD5: String, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    debug(s"[$id] Checking Content-MD5 (Received: $MD5)")

    Using.fileInputStream(zipFile)
      .map {
        case is if DigestUtils.md5Hex(is) == MD5 => Success(())
        case _ => Failure(new SwordError(UriRegistry.ERROR_CHECKSUM_MISMATCH))
      }
      .tried
      .flatten
      .recoverWith {
        case e =>
          for {
            _ <- FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
              .recoverWith {
                case e2 =>
                  // if the permission change fails, return the original exception, as that one is more important
                  logger.error(s"Content hash doesn't match, but also changing the file permissions failed in recovery: ${ e2.getMessage }", e2)
                  Failure(e)
              }
            _ <- recoverInvalidDeposit(e, depositDir, "Checksum mismatch between expected MD5 checksum (provided in request) and received content")
            _ <- Failure(e)
          } yield ()
      }
  }

  private def handleDepositAsync(deposit: Deposit)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (!deposit.isInProgress) {
      logger.info(s"[$id] Scheduling deposit to be finalized")
      for {
        props <- DepositProperties.load(id)
        _ <- props.setStateAndClientMessageContentType(State.UPLOADED, "Deposit upload has been completed.", deposit.getMimeType)
        _ <- props.save()
      } yield DepositProcessor.processDeposit(id, deposit.getMimeType)
    }
    else Try {
      logger.info(s"[$id] Received continuing deposit: ${ deposit.getFilename }")
    }
  }
}
