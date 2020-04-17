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

import nl.knaw.dans.easy.sword2.TestSupportFixture
import okhttp3.HttpUrl
import okhttp3.mockwebserver.{ MockResponse, MockWebServer }
import org.json4s.JsonDSL._
import org.json4s.native.Serialization
import org.json4s.{ DefaultFormats, Formats }
import org.scalatest.BeforeAndAfterAll
import scalaj.http.{ BaseHttp, Http }

import scala.util.Success

class DepositPropertiesServiceFactorySpec extends TestSupportFixture with BeforeAndAfterAll {

  // configure the mock server
  private val server = new MockWebServer
  server.start()
  private val test_server = "/test_server/"
  private val baseUrl: HttpUrl = server.url(test_server)

  implicit val http: BaseHttp = Http
  implicit val formats: Formats = DefaultFormats
  private val client = new GraphQLClient(baseUrl.url())
  private val factory = new DepositPropertiesServiceFactory(client)

  override protected def afterAll(): Unit = {
    server.shutdown()
    super.afterAll()
  }

  "create" should "create a new deposit by registering it through the GraphQL interface" in {
    val depositId = "00000000-0000-0000-0000-000000000006"
    val depositorId = "user001"
    val response =
      """{
        |  "data": {
        |    "addDeposit": {
        |      "deposit": {
        |        "depositId": "00000000-0000-0000-0000-000000000006"
        |      }
        |    },
        |    "addIdentifier": {
        |      "identifier": {
        |        "type": "BAG_STORE",
        |        "value": "00000000-0000-0000-0000-000000000006"
        |      }
        |    },
        |    "updateState": {
        |      "state": {
        |        "label": "DRAFT",
        |        "description": "Deposit is open for additional data"
        |      }
        |    }
        |  }
        |}""".stripMargin

    server.enqueue(new MockResponse().setBody(response))

    factory.create(depositId, depositorId) shouldBe a[Success[_]]

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesServiceFactory.CreateDeposit.query) ~
        ("operationName" -> DepositPropertiesServiceFactory.CreateDeposit.operationName) ~
        ("variables" -> Map("depositId" -> depositId, "depositorId" -> depositorId, "bagId" -> depositId))
    }
  }

  "getSword2UploadedDeposits" should "fetch all data at once if their aren't many records" in {
    val response =
      """{
        |  "data": {
        |    "deposits": {
        |      "pageInfo": {
        |        "hasNextPage": false,
        |        "startCursor": "YXJyYXljb25uZWN0aW9uOjI="
        |      },
        |      "edges": [
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000004",
        |            "contentType": {
        |              "value": "application/zip"
        |            }
        |          }
        |        },
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000001",
        |            "contentType": {
        |              "value": "application/octet-stream"
        |            }
        |          }
        |        },
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000002",
        |            "contentType": {
        |              "value": "application/zip"
        |            }
        |          }
        |        }
        |      ]
        |    }
        |  }
        |}""".stripMargin

    server.enqueue(new MockResponse().setBody(response))

    factory.getSword2UploadedDeposits.map(_.toList) should matchPattern {
      case Success(
      ("00000000-0000-0000-0000-000000000004", "application/zip") ::
        ("00000000-0000-0000-0000-000000000001", "application/octet-stream") ::
        ("00000000-0000-0000-0000-000000000002", "application/zip") ::
        Nil
      ) =>
    }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.query()) ~
        ("operationName" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.operationName)
    }
  }

  it should "fetch data in a paginated way" in {
    val response1 =
      """{
        |  "data": {
        |    "deposits": {
        |      "pageInfo": {
        |        "hasNextPage": true,
        |        "startCursor": "YXJyYXljb25uZWN0aW9uOjA="
        |      },
        |      "edges": [
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000004",
        |            "contentType": {
        |              "value": "application/zip"
        |            }
        |          }
        |        }
        |      ]
        |    }
        |  }
        |}""".stripMargin
    val response2 =
      """{
        |  "data": {
        |    "deposits": {
        |      "pageInfo": {
        |        "hasNextPage": true,
        |        "startCursor": "YXJyYXljb25uZWN0aW9uOjE="
        |      },
        |      "edges": [
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000001",
        |            "contentType": {
        |              "value": "application/octet-stream"
        |            }
        |          }
        |        }
        |      ]
        |    }
        |  }
        |}""".stripMargin
    val response3 =
      """{
        |  "data": {
        |    "deposits": {
        |      "pageInfo": {
        |        "hasNextPage": false,
        |        "startCursor": "YXJyYXljb25uZWN0aW9uOjI="
        |      },
        |      "edges": [
        |        {
        |          "node": {
        |            "depositId": "00000000-0000-0000-0000-000000000002",
        |            "contentType": {
        |              "value": "application/zip"
        |            }
        |          }
        |        }
        |      ]
        |    }
        |  }
        |}""".stripMargin

    server.enqueue(new MockResponse().setBody(response1))
    server.enqueue(new MockResponse().setBody(response2))
    server.enqueue(new MockResponse().setBody(response3))

    factory.getSword2UploadedDeposits.map(_.toList) should matchPattern {
      case Success(
      ("00000000-0000-0000-0000-000000000004", "application/zip") ::
        ("00000000-0000-0000-0000-000000000001", "application/octet-stream") ::
        ("00000000-0000-0000-0000-000000000002", "application/zip") ::
        Nil
      ) =>
    }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.query()) ~
        ("operationName" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.operationName)
    }
    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.query(Some("YXJyYXljb25uZWN0aW9uOjA="))) ~
        ("operationName" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.operationName)
    }
    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.query(Some("YXJyYXljb25uZWN0aW9uOjE="))) ~
        ("operationName" -> DepositPropertiesServiceFactory.Sword2UploadedDeposits.operationName)
    }
  }
}
