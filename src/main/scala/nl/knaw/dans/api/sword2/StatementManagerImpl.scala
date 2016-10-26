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
package nl.knaw.dans.api.sword2

import java.util

import org.swordapp.server._

import scala.util.{Failure, Success}

class StatementManagerImpl extends StatementManager {
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  override def getStatement(iri: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): Statement = {
    implicit val settings = config.asInstanceOf[SwordConfig].settings

    // TODO: REFACTOR THIS MESS
    Authentication.checkAuthentication(auth).get
    val maybeState = SwordID.extract(iri) match {
      case Success(id) => DepositProperties.getState(id)
      case Failure(t) => throw new SwordError(404)
    }
    maybeState match {
      case Success(state) =>
        val statement = new AtomStatement(iri, "DANS-EASY", s"Deposit ${SwordID.extract(iri).get}", state.timeStamp)
        statement.setState(state.label, state.description)
        statement
      case Failure(t) => throw new SwordError(404)
    }
  }
}
