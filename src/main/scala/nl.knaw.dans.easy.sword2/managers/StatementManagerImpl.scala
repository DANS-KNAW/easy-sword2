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
package nl.knaw.dans.easy.sword2.managers

import java.net.URI
import java.util

import nl.knaw.dans.easy.sword2.{ Authentication, DepositId, DepositProperties, LdapAuthSettings, Settings, SwordConfig, SwordID }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.swordapp.server._

import scala.util.{ Success, Try }

class StatementManagerImpl extends StatementManager with DebugEnhancedLogging {

  @throws(classOf[SwordServerException])
  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  override def getStatement(iri: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): Statement = {
    trace(iri, accept, auth, config)
    implicit val settings: Settings = config.asInstanceOf[SwordConfig].settings
    val result = for {
      _ <- Authentication.checkAuthentication(auth)
      id <- SwordID.extract(iri)
      _ = debug(s"id = $id")
      _ <- authenticate(id, auth)
      statementIri = s"${ settings.serviceBaseUrl }statement/$id"
      statement <- createStatement(id, statementIri)
    } yield statement

    result.getOrElse { throw new SwordError(404) }
  }

  private def authenticate(id: DepositId, auth: AuthCredentials)(implicit settings: Settings): Try[Unit] = {
    settings.auth match {
      case _: LdapAuthSettings => Authentication.checkThatUserIsOwnerOfDeposit(id, auth.getUsername, "Not allowed to retrieve statement for other user.")
      case _ => Success(())
    }
  }

  private def createStatement(id: DepositId, statementIri: String)(implicit settings: Settings): Try[AtomStatement] = {
    for {
      props <- DepositProperties(id)
      _ = debug(s"Read ${ DepositProperties.FILENAME }")
      state <- props.getState
      _ = debug(s"State = $state")
      stateDesc <- props.getStateDescription
      _ = debug(s"State desc = $stateDesc")
      optDoi = props.getDoi
    } yield new AtomStatement(statementIri, "DANS-EASY", s"Deposit $id", props.getLastModifiedTimestamp.get.toString) {
      addState(state.toString, stateDesc)
      val archivalResource = new ResourcePart(new URI(s"urn:uuid:$id").toASCIIString)
      archivalResource.setMediaType("multipart/related")

      optDoi.foreach(doi => {
        archivalResource.addSelfLink(new URI(s"https://doi.org/$doi").toASCIIString)
      })

      addResource(archivalResource)
    }
  }
}
