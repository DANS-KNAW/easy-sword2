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

import nl.knaw.dans.easy.sword2.properties.ServiceDepositPropertiesRepository.{ CreateDeposit, Sword2UploadedDeposits }
import nl.knaw.dans.easy.sword2.properties.graphql.GraphQLClient
import nl.knaw.dans.easy.sword2.properties.graphql.direction.Forwards
import nl.knaw.dans.easy.sword2.{ DepositId, MimeType }
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.json4s.JsonAST.{ JInt, JString }
import org.json4s.JsonDSL.string2jvalue
import org.json4s.native.JsonMethods
import org.json4s.{ Formats, JValue }

import scala.util.Try

class ServiceDepositPropertiesRepository(client: GraphQLClient)(implicit formats: Formats) extends DepositPropertiesRepository with DebugEnhancedLogging {

  private def format(json: JValue): String = JsonMethods.compact(JsonMethods.render(json))

  private def logMutationOutput(operationName: String)(json: JValue): Unit = {
    logger.debug(s"Mutation $operationName returned ${ format(json) }")
  }

  override def load(depositId: DepositId): Try[DepositProperties] = Try {
    new ServiceDepositProperties(depositId, client)
  }

  override def create(depositId: DepositId, depositorId: String): Try[Unit] = {
    val registerDepositVariables = Map(
      "depositId" -> depositId,
      "depositorId" -> depositorId,
      "bagId" -> depositId,
    )

    client.doQuery(CreateDeposit.query, CreateDeposit.operationName, registerDepositVariables)
      .toTry
      .doIfSuccess(logMutationOutput(CreateDeposit.operationName))
      .map(_ => ())
  }

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = {
    implicit val convertJson: Any => JValue = {
      case s: String => JString(s)
      case i: Int => JInt(i)
    }
    client.doPaginatedQuery[Sword2UploadedDeposits.Data](
      query = Sword2UploadedDeposits.query,
      operationName = Sword2UploadedDeposits.operationName,
      variables = Map("count" -> 10),
      direction = Forwards,
    )(_.deposits.pageInfo)
      .map(_.flatMap(_.deposits.edges.map(_.node).map(node => node.depositId -> node.contentType.value)))
  }

  override def toString: String = "DepositPropertiesServiceFactory"
}

object ServiceDepositPropertiesRepository {
  object Sword2UploadedDeposits {
    case class Data(deposits: Deposits)
    case class Deposits(pageInfo: Forwards, edges: Seq[Edge])
    case class Edge(node: Node)
    case class Node(depositId: String, contentType: ContentType)
    case class ContentType(value: String)

    val operationName = "GetContentTypeForUploadedDatasets"

    val query: String = {
      """query GetContentTypeForUploadedDatasets($count: Int!, $after: String) {
        |  deposits(state: { label: UPLOADED, filter: LATEST }, first: $count, after: $after) {
        |    pageInfo {
        |      hasNextPage
        |      endCursor
        |    }
        |    edges {
        |      node {
        |        depositId
        |        contentType {
        |          value
        |        }
        |      }
        |    }
        |  }
        |}""".stripMargin
    }
  }

  object CreateDeposit {
    val operationName = "RegisterDeposit"
    val query: String =
      """mutation RegisterDeposit($depositId: UUID!, $depositorId: String!, $bagId: String!) {
        |  addDeposit(input: { depositId: $depositId, depositorId: $depositorId, origin: SWORD2 }) {
        |    deposit {
        |      depositId
        |    }
        |  }
        |  addIdentifier(input: { depositId: $depositId, type: BAG_STORE, value: $bagId }) {
        |    identifier {
        |      type
        |      value
        |    }
        |  }
        |  updateState(input: { depositId: $depositId, label: DRAFT, description: "Deposit is open for additional data" }) {
        |    state {
        |      label
        |      description
        |    }
        |  }
        |}""".stripMargin
  }
}
