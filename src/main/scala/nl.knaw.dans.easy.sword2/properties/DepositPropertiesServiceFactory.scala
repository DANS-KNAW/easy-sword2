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

import nl.knaw.dans.easy.sword2.properties.DepositPropertiesServiceFactory.{ CreateDeposit, Sword2UploadedDeposits }
import nl.knaw.dans.easy.sword2.properties.GraphQLClient.PageInfo
import nl.knaw.dans.easy.sword2.{ DepositId, MimeType }
import org.json4s.JsonAST.{ JInt, JString }
import org.json4s.JsonDSL.string2jvalue
import org.json4s.{ Formats, JValue }

import scala.util.Try

class DepositPropertiesServiceFactory(client: GraphQLClient)(implicit formats: Formats) extends DepositPropertiesFactory {

  override def load(depositId: DepositId): Try[DepositProperties] = Try {
    new DepositPropertiesService(depositId, client)
  }

  override def create(depositId: DepositId, depositorId: String): Try[DepositProperties] = {
    val registerDepositVariables = Map(
      "depositId" -> depositId,
      "depositorId" -> depositorId,
      "bagId" -> depositId,
    )

    for {
      _ <- client.doQuery(CreateDeposit.query, registerDepositVariables, CreateDeposit.operationName).toTry
      properties <- load(depositId)
    } yield properties
  }

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = {
    implicit val convertJson: Any => JValue = {
      case s: String => JString(s)
      case i: Int => JInt(i)
    }
    client.doPaginatedQuery[Sword2UploadedDeposits.Data](
      Sword2UploadedDeposits.query,
      Map("count" -> 10),
      Sword2UploadedDeposits.operationName,
      _.deposits.pageInfo,
    ).map(_.flatMap(_.deposits.edges.map(_.node).map(node => node.depositId -> node.contentType.value)))
  }

  override def toString: String = "DepositPropertiesServiceFactory"
}

object DepositPropertiesServiceFactory {
  object Sword2UploadedDeposits {
    case class Data(deposits: Deposits)
    case class Deposits(pageInfo: PageInfo, edges: Seq[Edge])
    case class Edge(node: Node)
    case class Node(depositId: String, contentType: ContentType)
    case class ContentType(value: String)

    val operationName = "GetContentTypeForUploadedDatasets"

    val query: String = {
      """query GetContentTypeForUploadedDatasets($count: Int!, $after: String) {
        |  deposits(state: { label: UPLOADED, filter: LATEST }, first: $count, after: $after) {
        |    pageInfo {
        |      hasNextPage
        |      startCursor
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
