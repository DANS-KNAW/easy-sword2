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

import java.nio.file.Files
import java.nio.file.attribute.FileTime

import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.easy.sword2.properties.DepositPropertiesFile._
import nl.knaw.dans.easy.sword2.{ DepositId, State }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.commons.lang.StringUtils

import scala.util.{ Failure, Success, Try }

/**
 * Loads the current `deposit.properties` for the specified deposit. This class is not thread-safe, so it is assumed
 * that it is used from one processing thread only (at least per deposit). It looks for the deposit first in the
 * temporary download directory, and if not found there, in the ingest-flow inbox.
 *
 * @param properties the deposit's properties
 */
class DepositPropertiesFile(properties: PropertiesConfiguration) extends DepositProperties with DebugEnhancedLogging {

  debug(s"Using deposit.properties at ${ properties.getFile }")

  /**
   * Saves the deposit file to disk.
   *
   * @return
   */
  override def save(): Try[Unit] = Try {
    debug("Saving deposit.properties")
    properties.save()
  }

  override def exists: Try[Boolean] = Try {
    properties.getFile.exists
  }

  def getDepositId: DepositId = {
    properties.getFile.getParentFile.getName
  }

  override def setState(state: State, descr: String): Try[DepositProperties] = Try {
    properties.setProperty(STATE_LABEL_KEY, state)
    properties.setProperty(STATE_DESCRIPTION_KEY, descr)
    this
  }

  /**
   * Returns the state when the properties were loaded.
   *
   * @return
   */
  override def getState: Try[(State, String)] = {
    for {
      label <- Try { Option(properties.getString(STATE_LABEL_KEY)).map(State.withName) }
      descr <- Try { Option(properties.getString(STATE_DESCRIPTION_KEY)) }
      result <- label.flatMap(state => descr.map((state, _)))
        .map(Success(_))
        .getOrElse(Failure(new IllegalStateException("Deposit without state")))
    } yield result
  }

  override def setBagName(bagName: String): Try[DepositProperties] = Try {
    properties.setProperty(BAGSTORE_BAGNAME_KEY, bagName)
    this
  }

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = Try {
    properties.setProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY, contentType)
    this
  }

  override def removeClientMessageContentType(): Try[DepositProperties] = Try {
    properties.clearProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY)
    properties.clearProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD) // Also clean up old contentType property if still found
    this
  }

  override def getClientMessageContentType: Try[String] = {
    Seq(properties.getString(CLIENT_MESSAGE_CONTENT_TYPE_KEY),
      properties.getString(CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD)) // Also look for old contentType to support pre-upgrade deposits
      .find(StringUtils.isNotBlank)
      .map(Success(_))
      .getOrElse(Failure(new IllegalStateException(s"Deposit without $CLIENT_MESSAGE_CONTENT_TYPE_KEY")))
  }

  override def getDepositorId: Try[String] = {
    Option(properties.getString(DEPOSITOR_USERID_KEY))
      .map(Success(_))
      .getOrElse(Failure(new IllegalStateException("Deposit without depositor")))
  }

  override def getDoi: Try[Option[String]] = Try {
    Option(properties.getString(IDENTIFIER_DOI_KEY))
  }

  /**
   * Returns the last modified timestamp when the properties were loaded.
   *
   * @return
   */
  override def getLastModifiedTimestamp: Try[Option[FileTime]] = Try {
    Option(properties.getFile.toPath)
      .filter(Files.exists(_))
      .map(Files.getLastModifiedTime(_))
  }
}

object DepositPropertiesFile {
  val STATE_LABEL_KEY = "state.label"
  val STATE_DESCRIPTION_KEY = "state.description"
  val BAGSTORE_BAGID_KEY = "bag-store.bag-id"
  val BAGSTORE_BAGNAME_KEY = "bag-store.bag-name"
  val CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD = "contentType" // for backwards compatibility
  val CLIENT_MESSAGE_CONTENT_TYPE_KEY = "easy-sword2.client-message.content-type"
  val DEPOSITOR_USERID_KEY = "depositor.userId"
  val IDENTIFIER_DOI_KEY = "identifier.doi"
  val CREATION_TIMESTAMP_KEY = "creation.timestamp"
  val DEPOSIT_ORIGIN_KEY = "deposit.origin"

  val FILENAME = "deposit.properties"
}
