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
package nl.knaw.dans.easy

import java.io.File
import java.net.URI
import java.util.regex.Pattern

import nl.knaw.dans.easy.sword2.State.State
import org.joda.time.format.{ DateTimeFormatter, ISODateTimeFormat }

package object sword2 {
  val dateTimeFormatter: DateTimeFormatter = ISODateTimeFormat.dateTime()

  type DepositId = String
  type MimeType = String

  sealed abstract class AuthenticationSettings()
  case class LdapAuthSettings(ldapUrl: URI, usersParentEntry: String, swordEnabledAttributeName: String, swordEnabledAttributeValue: String) extends AuthenticationSettings
  case class SingleUserAuthSettings(user: String, password: String) extends AuthenticationSettings

  case class Settings(depositRootDir: File,
                      archivedDepositRootDir: Option[File],
                      outboxDir: Option[File],
                      depositPermissions: String,
                      tempDir: File,
                      serviceBaseUrl: String, // TODO: refactor to URL?
                      collectionPath: String,
                      auth: AuthenticationSettings,
                      urlPattern: Pattern,
                      bagStoreSettings: Option[BagStoreSettings],
                      supportMailAddress: String,
                      marginDiskSpace: Long,
                      cleanup: Map[State, Boolean],
                      rescheduleDelaySeconds: Int,
                      serverPort: Int,
                     )

  case class BagStoreSettings(baseDir: String, baseUrl: String)

  case class InvalidDepositException(id: DepositId, msg: String, cause: Throwable = null) extends Exception(msg, cause)
  case class NotEnoughDiskSpaceException(id: DepositId, msg: String) extends Exception(s"Not enough disk space for processing deposit. $msg")

  implicit class FileOps(val thisFile: File) extends AnyVal {

    def listFilesSafe: Array[File] =
      thisFile.listFiles match {
        case null => Array[File]()
        case files => files
      }
  }

  def isPartOfDeposit(f: File): Boolean = f.getName != DepositProperties.FILENAME
}

