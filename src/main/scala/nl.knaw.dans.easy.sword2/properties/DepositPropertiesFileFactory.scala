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
package nl.knaw.dans.easy.sword2.properties

import java.io.File
import java.nio.file.{ Files, Path }

import nl.knaw.dans.easy.sword2.State.DRAFT
import nl.knaw.dans.easy.sword2.properties.DepositPropertiesFile._
import nl.knaw.dans.easy.sword2.{ DepositId, FileOps, MimeType, State, dateTimeFormatter }
import nl.knaw.dans.lib.error._
import org.apache.commons.configuration.PropertiesConfiguration
import org.joda.time.{ DateTime, DateTimeZone }

import scala.util.Try

class DepositPropertiesFileFactory(tempDir: File,
                                   depositRootDir: File,
                                   archivedDepositRootDir: Option[File],
                                  ) extends DepositPropertiesFactory {

  private def fileLocation(depositId: DepositId): Path = {
    (tempDir #:: depositRootDir #:: archivedDepositRootDir.toStream)
      .map(_.toPath.resolve(depositId))
      .collectFirst { case path if Files.exists(path) => path.resolve(FILENAME) }
      .getOrElse { tempDir.toPath.resolve(depositId).resolve(FILENAME) }
  }

  private def from(depositId: DepositId)(fillProps: (PropertiesConfiguration, Path) => Unit): Try[DepositPropertiesFile] = Try {
    val file = fileLocation(depositId)
    val props = new PropertiesConfiguration() {
      setDelimiterParsingDisabled(true)
      setFile(file.toFile)
    }
    fillProps(props, file)

    new DepositPropertiesFile(props)
  }

  override def load(depositId: DepositId): Try[DepositProperties] = {
    from(depositId) {
      case (props, file) if Files.exists(file) => props.load(file.toFile)
      case (_, file) => throw new Exception(s"deposit $file does not exist")
    }
  }

  override def create(depositId: DepositId, depositorId: String): Try[DepositProperties] = {
    for {
      props <- from(depositId) {
        case (_, file) if Files.exists(file) => throw new Exception(s"deposit $file already exists")
        case (props, _) =>
          props.setProperty(BAGSTORE_BAGID_KEY, depositId)
          props.setProperty(CREATION_TIMESTAMP_KEY, DateTime.now(DateTimeZone.UTC).toString(dateTimeFormatter))
          props.setProperty(DEPOSIT_ORIGIN_KEY, "SWORD2")
          props.setProperty(STATE_LABEL_KEY, DRAFT)
          props.setProperty(STATE_DESCRIPTION_KEY, "Deposit is open for additional data")
          props.setProperty(DEPOSITOR_USERID_KEY, depositorId)
      }
      _ <- props.save()
    } yield props
  }

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = {
    def depositHasState(props: DepositProperties): Boolean = {
      props.getState
        .map { case (label, _) => label }
        .doIfFailure { case _: Throwable => logger.warn(s"[${ props.getDepositId }] Could not get deposit state. Not putting this deposit on the queue.") }
        .fold(_ => false, _ == State.UPLOADED)
    }

    def getContentType(props: DepositProperties): Try[MimeType] = {
      props.getClientMessageContentType
        .doIfFailure { case _: Throwable => logger.warn(s"[${ props.getDepositId }] Could not get deposit Content-Type. Not putting this deposit on the queue.") }
    }

    Try {
      tempDir
        .listFilesSafe
        .toIterator
        .collect { case file if file.isDirectory => load(file.getName).unsafeGetOrThrow }
        .filter(depositHasState)
        .map(props => getContentType(props).map((props.getDepositId, _)).unsafeGetOrThrow)
    }
  }

  override def toString: String = "DepositPropertiesFileFactory"
}
