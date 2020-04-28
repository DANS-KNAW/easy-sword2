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
package nl.knaw.dans.easy.sword2.properties.graphql

import org.swordapp.server.SwordError

object error {
  abstract class GraphQLError(val msg: String) extends Exception(msg) {
    def toSwordError: SwordError
  }
  case class UnexpectedResponseCode(code: Int, body: String) extends GraphQLError(s"GraphQL service returned code $code with message: $body") {
    override def toSwordError: SwordError = new SwordError(500)
  }
  case class GraphQLQueryError(errors: List[String]) extends GraphQLError(s"Error in GraphQL query: ${ errors.mkString("[", ", ", "]") }") {
    override def toSwordError: SwordError = new SwordError(500)
  }
  case object GraphQLServiceUnavailable extends GraphQLError("GraphQL service is unavailable") {
    override def toSwordError: SwordError = new SwordError(503) {
      override def getMessage: String = "503 Service temporarily unavailable"
    }
  }
}
