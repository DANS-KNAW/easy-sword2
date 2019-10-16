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

import nl.knaw.dans.easy.sword2.DepositProperties._
import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.commons.lang.StringUtils
import org.joda.time.{ DateTime, DateTimeZone }

import scala.util.{ Failure, Success, Try }

/**
 * Interface to the deposit properties. These properties may be saved to a deposit.properties file, to an easy-deposit-properties micro-service or both.
 */
trait DepositProperties {

  def save(): Try[Unit]

  def exists: Boolean

  def setState(state: State, descr: String): Try[DepositProperties]

  def setBagName(bagDir: File): Try[DepositProperties]

  def getState: Try[State]

  def setClientMessageContentType(contentType: String): Try[DepositProperties]

  def removeClientMessageContentType(): Try[DepositProperties]

  def getClientMessageContentType: Try[String]

  def getStateDescription: Try[String]

  def getDepositorId: Try[String]

  def getDoi: Option[String]

  def getLastModifiedTimestamp: Option[FileTime]
}

object DepositProperties {
  val FILENAME = "deposit.properties"
  val CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD = "contentType" // for backwards compatibility
  val CLIENT_MESSAGE_CONTENT_TYPE_KEY = "easy-sword2.client-message.content-type"

  def apply(depositId: DepositId, depositorId: Option[String] = None)(implicit settings: Settings): Try[DepositProperties] = Try {
    new DepositPropertiesFile(depositId, depositorId)
  }
}
