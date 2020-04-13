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

import java.nio.file.attribute.FileTime
import java.util.UUID

import nl.knaw.dans.easy.sword2.{ State, TestSupportFixture }
import okhttp3.HttpUrl
import okhttp3.mockwebserver.{ MockResponse, MockWebServer }
import org.joda.time.DateTime
import org.json4s.JsonDSL._
import org.json4s.native.Serialization
import org.json4s.{ DefaultFormats, Formats }
import org.scalatest.BeforeAndAfterAll
import scalaj.http.{ BaseHttp, Http }

import scala.util.{ Failure, Success }

class DepositPropertiesServiceSpec extends TestSupportFixture with BeforeAndAfterAll {

  // configure the mock server
  private val server = new MockWebServer
  server.start()
  private val test_server = "/test_server/"
  private val baseUrl: HttpUrl = server.url(test_server)

  implicit val http: BaseHttp = Http
  implicit val formats: Formats = DefaultFormats
  private val client = new GraphQLClient(baseUrl.url())
  private val depositId = UUID.randomUUID().toString
  private val properties = new DepositPropertiesService(depositId, client)

  override protected def afterAll(): Unit = {
    server.shutdown()
    super.afterAll()
  }

  "exists" should "return true if the deposit exists" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "depositId": "00000000-0000-0000-0000-000000000007"
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.exists shouldBe Success(true)

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.DepositExists.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "return false if the deposit doesn't exists" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.exists shouldBe Success(false)

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.DepositExists.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  "getDepositId" should "return the depositId from the constructor" in {
    properties.getDepositId shouldBe depositId
  }

  "setState" should "call the GraphQL service to update the state" in {
    val label = State.UPLOADED
    val description = "Deposit upload has been completed."
    val response =
      """{
        |  "data": {
        |    "updateState": {
        |      "state": {
        |        "label": "UPLOADED",
        |        "description": "Deposit upload has been completed.",
        |        "timestamp": "2020-04-13T14:28:12.252Z"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.setState(label, description) shouldBe a[Success[_]]

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.UpdateState.query) ~
        ("variables" -> Map(
          "depositId" -> depositId,
          "stateLabel" -> label.toString,
          "stateDescription" -> description,
        ))
    }
  }

  "getState" should "retrieve the current state from the GraphQL service" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "state": {
        |        "label": "REJECTED",
        |        "description": "my message"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getState should matchPattern { case Success((State.REJECTED, "my message")) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetState.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the deposit doesn't exist" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getState should matchPattern { case Failure(DepositDoesNotExist(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetState.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the state wasn't set" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "state": null
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getState should matchPattern { case Failure(NoStateForDeposit(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetState.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  "setBagName" should "call the GraphQL service to set the name of the bag in the deposit" in {
    val bagName = "hello"
    val response =
      """{
        |  "data": {
        |    "addBagName": {
        |      "deposit": {
        |        "depositId": "00000000-0000-0000-0000-000000000007",
        |        "bagName": "hello"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.setBagName(bagName) shouldBe a[Success[_]]

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.SetBagName.query) ~
        ("variables" -> Map(
          "depositId" -> depositId,
          "bagName" -> bagName,
        ))
    }
  }

  "setClientMessageContentType" should "call the GraphQL service to set the client-message-content-type" in {
    val contentType = "application/zip"
    val response =
      """{
        |  "data": {
        |    "setContentType": {
        |      "contentType": {
        |        "value": "application/zip"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.setClientMessageContentType(contentType) shouldBe a[Success[_]]

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.SetContentType.query) ~
        ("variables" -> Map(
          "depositId" -> depositId,
          "contentType" -> contentType,
        ))
    }
  }

  "getClientMessageContentType" should "retrieve the current client-message-content-type from the GraphQL service" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "contentType": {
        |        "value": "application/zip"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getClientMessageContentType should matchPattern { case Success("application/zip") => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetContentType.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the content type wasn't set" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "contentType": null
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getClientMessageContentType should matchPattern { case Failure(NoContentTypeForDeposit(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetContentType.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the deposit doesn't exist" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getClientMessageContentType should matchPattern { case Failure(DepositDoesNotExist(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetContentType.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  "getDepositorId" should "retrieve the current depositorId for this deposit from the GraphQL service" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "depositor": {
        |        "depositorId": "user001"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getDepositorId should matchPattern { case Success("user001") => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetDepositorId.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the deposit doesn't exist" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getDepositorId should matchPattern { case Failure(DepositDoesNotExist(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetDepositorId.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  "getDoi" should "retrieve the doi for this deposit from the GraphQL service" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "identifier": {
        |        "value": "10.5072/dans-p7q-rst8"
        |      }
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getDoi should matchPattern { case Success(Some("10.5072/dans-p7q-rst8")) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetDoi.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "return None if no DOI is set" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "identifier": null
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getDoi should matchPattern { case Success(None) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetDoi.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the deposit doesn't exist" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getDoi should matchPattern { case Failure(DepositDoesNotExist(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetDoi.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  "getLastModifiedTimestamp" should "retrieve the timestamp this deposit was last modified from the GraphQL service" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "lastModified": "2019-05-05T02:05:00.000Z"
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    val expectedFileTime = FileTime.fromMillis(DateTime.parse("2019-05-05T02:05:00.000Z").getMillis)
    properties.getLastModifiedTimestamp should matchPattern { case Success(Some(`expectedFileTime`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetLastModifiedTimestamp.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "return None if no timestamp is available" in {
    val response =
      """{
        |  "data": {
        |    "deposit": {
        |      "lastModified": null
        |    }
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getLastModifiedTimestamp should matchPattern { case Success(None) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetLastModifiedTimestamp.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }

  it should "fail if the deposit doesn't exist" in {
    val response =
      """{
        |  "data": {
        |    "deposit": null
        |  }
        |}""".stripMargin
    server.enqueue(new MockResponse().setBody(response))

    properties.getLastModifiedTimestamp should matchPattern { case Failure(DepositDoesNotExist(`depositId`)) => }

    server.takeRequest().getBody.readUtf8() shouldBe Serialization.write {
      ("query" -> DepositPropertiesService.GetLastModifiedTimestamp.query) ~
        ("variables" -> Map("depositId" -> depositId))
    }
  }
}
