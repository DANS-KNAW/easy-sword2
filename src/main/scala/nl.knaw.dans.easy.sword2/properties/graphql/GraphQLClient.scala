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

import java.net.{ ConnectException, URL }

import cats.syntax.either._
import nl.knaw.dans.easy.sword2.properties.graphql.direction.PaginationDirection
import nl.knaw.dans.easy.sword2.properties.graphql.error.{ GraphQLError, GraphQLQueryError, GraphQLServiceUnavailable }
import nl.knaw.dans.lib.error._
import org.json4s.JsonAST.{ JArray, JString }
import org.json4s.JsonDSL._
import org.json4s.native.{ JsonMethods, Serialization }
import org.json4s.{ DefaultFormats, Formats, JValue }
import scalaj.http.BaseHttp

import scala.util.Try

/**
 * A client wrapping both the logic of executing a GraphQL query on a particular URL and
 * parsing the response.
 *
 * @param url         the URL of the GraphQL server on which the query is executed
 * @param credentials the credentials (if required) required to execute the query
 * @param http        the ''BaseHttp'' object required for sending the request
 * @param jsonFormats the formatter used for parsing and generating the JSON
 */
class GraphQLClient(url: URL, timeout: Option[(Int, Int)] = Option.empty, credentials: Option[(String, String)] = Option.empty)
                   (implicit http: BaseHttp, jsonFormats: Formats = new DefaultFormats {}) {

  /**
   * Execute a GraphQL query, if provided sent together with the variables. The response is parsed
   * into either a sequence of errors (if ''errors'' is present in the response) or the JSON inside
   * the ''data'' object.
   *
   * @param query         the query to be executed
   * @param operationName the name of the operation
   * @param variables     values for the placeholders in the query
   * @param ev            evidence that the values in the variables mapping can be converted to JSON
   * @tparam A the values in the variables mapping
   * @return the JSON object inside ''data'' if the query was successful;
   *         a list of error messages otherwise
   */
  def doQuery[A](query: String, operationName: String, variables: Map[String, A] = Map.empty)(implicit ev: A => JValue): Either[GraphQLError, JValue] = {
    val baseHttp = http(url.toString)
      .headers(
        "Accept" -> "application/json",
        "Content-Type" -> "application/json",
      )
      .postData(Serialization.write {
        val q = ("query" -> query) ~ ("operationName" -> operationName)

        if (variables.nonEmpty) q ~ ("variables" -> variables)
        else q
      })

    val body1 = credentials.fold(baseHttp) {
      case (username, password) => baseHttp.auth(username, password)
    }
    val body2 = timeout.fold(body1) {
      case (connTimeout, readTimeout) => body1.timeout(connTimeout, readTimeout)
    }

    Either.catchOnly[ConnectException] { body2.asString }
      .leftMap(_ => GraphQLServiceUnavailable)
      .flatMap {
        case response if response.is2xx =>
          val json = JsonMethods.parse(response.body)

          parseErrors(json \ "errors")
            .map(GraphQLQueryError(_).asLeft)
            .getOrElse((json \ "data").asRight)
        case response => GraphQLQueryError(List(response.body)).asLeft
      }
  }

  def doPaginatedQuery[A: Manifest](query: String,
                                    operationName: String,
                                    variables: Map[String, Any],
                                    direction: PaginationDirection,
                                   )(getPageInfo: A => direction.ObjectType)
                                   (implicit ev: Any => JValue): Try[Iterator[A]] = Try {
    new Iterator[A] {
      private var nextPageInfo: direction.ObjectType = direction.empty

      override def hasNext: Boolean = nextPageInfo.hasAnotherPage

      override def next(): A = {
        val allVariables = nextPageInfo.cursor.map(cursor => variables + (direction.cursorName -> cursor)).getOrElse(variables)
        val json = doQuery(query, operationName, allVariables).toTry.unsafeGetOrThrow
        val data = json.extract[A]

        nextPageInfo = getPageInfo(data)
        data
      }
    }
  }

  // following https://graphql.github.io/graphql-spec/June2018/#sec-Response-Format
  private def parseErrors(errors: JValue): Option[List[String]] = {
    errors match {
      case JArray(Nil) => Option.empty
      case JArray(es) => Option {
        es.map(error => {
          val JString(msg) = error \ "message"
          msg
        })
      }
      case _ => Option.empty
    }
  }
}
