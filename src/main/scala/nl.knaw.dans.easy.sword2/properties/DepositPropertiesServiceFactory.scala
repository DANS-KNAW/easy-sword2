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
import nl.knaw.dans.easy.sword2.{ DepositId, MimeType }
import nl.knaw.dans.lib.error._
import org.json4s.JsonDSL.string2jvalue
import org.json4s.{ DefaultFormats, Formats }

import scala.util.Try

class DepositPropertiesServiceFactory(client: GraphQLClient) extends DepositPropertiesFactory {
  implicit val formats: Formats = DefaultFormats

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
      _ <- client.doQuery(CreateDeposit.query, registerDepositVariables).toTry
      properties <- load(depositId)
    } yield properties
  }

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = Try {
    new Iterator[Seq[(DepositId, MimeType)]] {
      private var nextPageInfo = Sword2UploadedDeposits.PageInfo(hasNextPage = true, null)

      override def hasNext: Boolean = nextPageInfo.hasNextPage

      override def next(): Seq[(DepositId, MimeType)] = {
        val json = client.doQuery(Sword2UploadedDeposits.query(Option(nextPageInfo.startCursor))).toTry.unsafeGetOrThrow
        val data = json.extract[Sword2UploadedDeposits.Data]
        val newPageInfo = data.deposits.pageInfo
        val nextValues = data.deposits.edges.map(_.node).map(node => node.depositId -> node.contentType.value)

        nextPageInfo = newPageInfo
        nextValues
      }
    }.flatMap(_.toIterator)
  }

  override def toString: String = "DepositPropertiesServiceFactory"
}

object DepositPropertiesServiceFactory {
  object Sword2UploadedDeposits {
    case class Data(deposits: Deposits)
    case class Deposits(pageInfo: PageInfo, edges: Seq[Edge])
    case class PageInfo(hasNextPage: Boolean, startCursor: String)
    case class Edge(node: Node)
    case class Node(depositId: String, contentType: ContentType)
    case class ContentType(value: String)

    def query(after: Option[String] = Option.empty): String = {
      s"""query GetContentTypeForUploadedDatasets {
         |  deposits(state: { label: UPLOADED, filter: LATEST }, first: 10${ after.fold("")(s => s""", after: "$s"""") }) {
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
