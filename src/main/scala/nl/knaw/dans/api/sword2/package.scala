/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.api

import java.io.File
import java.net.{URI, URL}
import java.util.regex.Pattern

import nl.knaw.dans.api.sword2.DepositHandler._
import org.swordapp.server.DepositReceipt

import scala.util.{Failure, Success, Try}

package object sword2 {

  // TODO make authMode + authentication into separate class structure (sealed abstract class + 2 case classes)
  // TODO make bagStoreBaseXXX into class and give the Settings an Option of this object
  // TODO field naming (baseUrl occurs in multiple contexts within DepositHandler)

  case class Settings(
                       depositRootDir: File,
                       depositPermissions: String,
                       tempDir: File,
                       baseUrl: String, // TODO: refactor to URL?
                       collectionIri: String,
                       authMode: String,
                       authLdapUrl: Option[String],
                       authUsersParentEntry: Option[String],
                       authSwordEnabledAttributeName: Option[String],
                       authSwordEnabledAttributeValue: Option[String],
                       authSingleUser: Option[String],
                       authSinglePassword: Option[String],
                       urlPattern: Pattern,
                       bagStoreBaseUri: String, // TODO refactor to URI
                       bagStoreBaseDir: String, // TODO refactor to File
                       supportMailAddress: String)

  case class InvalidDepositException(id: String, msg: String, cause: Throwable = null) extends Exception(msg, cause)

  implicit class FileOps(val thisFile: File) extends AnyVal {

    def listFilesSafe: Array[File] =
      thisFile.listFiles match {
        case null => Array[File]()
        case files => files
      }
  }

  def isPartOfDeposit(f: File): Boolean = f.getName != "deposit.properties"

  implicit class TryDepositResultOps(val thisResult: Try[(String, DepositReceipt)]) extends AnyVal {

    def getOrThrow: DepositReceipt = {
      thisResult match {
        case Success((id,depositReceipt)) =>
          log.info(s"[$id] Sending deposit receipt")
          depositReceipt
        case Failure(e) =>
          log.error(s"Error(s) occurred", e)
          throw e
      }
    }
  }
}

