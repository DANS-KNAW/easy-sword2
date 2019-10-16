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

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

import nl.knaw.dans.easy.sword2.DepositProperties.FILENAME
import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.configuration.PropertiesConfiguration
import org.joda.time.{ DateTime, DateTimeZone }

import scala.util.Try

class DepositPropertiesService(depositId: DepositId, depositorId: Option[String] = None)(implicit settings: Settings) extends DepositProperties with DebugEnhancedLogging {
  trace(depositId, depositorId)

  private val (properties, modified) = {
    val props = new PropertiesConfiguration()
    props.setDelimiterParsingDisabled(true)
    val depositInTemp = settings.tempDir.toPath.resolve(depositId)
    val depositInInbox = settings.depositRootDir.toPath.resolve(depositId)
    val file = if (Files.exists(depositInTemp)) depositInTemp.resolve(FILENAME)
               else if (Files.exists(depositInInbox)) depositInInbox.resolve(FILENAME)
               else depositInTemp.resolve(FILENAME)
    props.setFile(file.toFile)
    if (Files.exists(file)) props.load(file.toFile)
    else {
      props.setProperty("bag-store.bag-id", depositId)
      props.setProperty("creation.timestamp", DateTime.now(DateTimeZone.UTC).toString(dateTimeFormatter))
      props.setProperty("deposit.origin", "SWORD2")
    }
    debug(s"Using deposit.properties at $file")
    depositorId.foreach(props.setProperty("depositor.userId", _))
    (props, if (Files.exists(file)) Some(Files.getLastModifiedTime(file))
            else None)
  }




  override def save(): Try[Unit] = ???

  override def exists: Boolean = ???

  override def setState(state: State, descr: String): Try[DepositProperties] = ???

  override def setBagName(bagDir: File): Try[DepositProperties] = ???

  override def getState: Try[State] = ???

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = ???

  override def removeClientMessageContentType(): Try[DepositProperties] = ???

  override def getClientMessageContentType: Try[String] = ???

  override def getStateDescription: Try[String] = ???

  override def getDepositorId: Try[String] = ???

  override def getDoi: Option[String] = ???

  override def getLastModifiedTimestamp: Option[FileTime] = ???
}
