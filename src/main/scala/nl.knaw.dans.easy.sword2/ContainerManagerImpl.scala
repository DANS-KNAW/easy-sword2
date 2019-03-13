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

import java.util

import nl.knaw.dans.easy.sword2.DepositHandler._
import org.swordapp.server._

import scala.util.{ Failure, Success, Try }

class ContainerManagerImpl extends ContainerManager {
  this: ApplicationSettings =>

  @throws(classOf[SwordServerException])
  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  override def getEntry(editIRI: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    implicit val settings = config.asInstanceOf[SwordConfig].settings
    SwordID.extract(editIRI) match {
      case Success(id) =>
        val result = for {
          props <- DepositProperties(id)
          receipt <- Try {
            if (props.exists) Some(DepositHandler.createDepositReceipt(settings, id))
            else None
          }
        } yield receipt
        result match {
          case Success(maybeReceipt) => maybeReceipt.getOrElse { throw new SwordError(404) }
          case Failure(e) => throw new SwordError(500)
        }
      case _ => throw new SwordError(500)
    }
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def replaceMetadata(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def replaceMetadataAndMediaResource(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def addMetadataAndResources(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def addMetadata(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def addResources(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    implicit val settings = config.asInstanceOf[SwordConfig].settings
    val result = for {
      _ <- Authentication.checkAuthentication(auth)
      id <- SwordID.extract(editIRI)
      _ <- settings.auth match {
        case _: LdapAuthSettings => Authentication.checkThatUserIsOwnerOfDeposit(id, auth.getUsername, "Not allowed to continue deposit for other user")
        case _ => Success(())
      }
      _ = log.debug(s"[$id] Continued deposit")
      _ <- checkDepositIsInDraft(id)
      depositReceipt <- handleDeposit(deposit)(settings, id)
    } yield (id, depositReceipt)

    result.getOrThrow
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def deleteContainer(editIRI: String, auth: AuthCredentials, config: SwordConfiguration): Unit = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def useHeaders(editIRI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  override def isStatementRequest(editIRI: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): Boolean = {
    false
  }
}

