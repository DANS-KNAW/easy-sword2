/*******************************************************************************
  * Copyright 2015 DANS - Data Archiving and Networked Services
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  ******************************************************************************/

package nl.knaw.dans.api

import java.io.File

import nl.knaw.dans.api.sword2.DepositHandler._
import org.swordapp.server.DepositReceipt

import scala.util.{Failure, Success, Try}

package object sword2 {
  /*
   * HACK ALERT:
   *
   * This is a global variable. It's GLOBAL and VARIABLE, which is bad, but I can see no other way to get
   * a Servlet Init Parameter into the global scope. If there is a better way, please let me know (JvM).
   */
  var homeDir: File = null

  case class InvalidDepositException(id: String, msg: String, cause: Throwable = null) extends Exception(msg, cause)
  case class FailedDepositException(id: String, msg: String, cause: Throwable = null) extends Exception(msg, cause)

  implicit class FileOps(val thisFile: File) extends AnyVal {

    def listFilesSafe: Array[File] =
      thisFile.listFiles match {
        case null => Array[File]()
        case files => files
      }
  }

  def isPartOfDeposit(f: File): Boolean = f.getName != ".git" && f.getName != "deposit.properties"

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

