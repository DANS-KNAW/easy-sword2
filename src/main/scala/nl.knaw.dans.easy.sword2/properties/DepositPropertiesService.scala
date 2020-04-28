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

import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.easy.sword2.properties.DepositPropertiesService._
import nl.knaw.dans.easy.sword2.properties.graphql.GraphQLClient
import nl.knaw.dans.easy.sword2.{ DepositId, State }
import org.joda.time.DateTime
import org.json4s.Formats
import org.json4s.JsonDSL.string2jvalue

import scala.util.{ Failure, Success, Try }

class DepositPropertiesService(depositId: DepositId, client: GraphQLClient)(implicit formats: Formats) extends DepositProperties {

  override def save(): Try[Unit] = Success(())

  override def exists: Try[Boolean] = {
    client.doQuery(DepositExists.query, DepositExists.operationName, Map("depositId" -> depositId))
      .map(_.extract[DepositExists.Data].deposit.isDefined)
      .toTry
  }

  override def getDepositId: DepositId = depositId

  override def setState(state: State, descr: String): Try[DepositProperties] = {
    val updateStateVariables = Map(
      "depositId" -> depositId,
      "stateLabel" -> state.toString,
      "stateDescription" -> descr,
    )

    client.doQuery(UpdateState.query, UpdateState.operationName, updateStateVariables)
      .map(_ => this)
      .toTry
  }

  override def getState: Try[(State, String)] = {
    for {
      json <- client.doQuery(GetState.query, GetState.operationName, Map("depositId" -> depositId)).toTry
      deposit = json.extract[GetState.Data].deposit
      state <- deposit.map(_.state
        .map(state => Try { State.withName(state.label) -> state.description })
        .getOrElse(Failure(NoStateForDeposit(depositId))))
        .getOrElse(Failure(DepositDoesNotExist(depositId)))
    } yield state
  }

  override def setBagName(bagName: String): Try[DepositProperties] = {
    val setBagNameVariables = Map(
      "depositId" -> depositId,
      "bagName" -> bagName,
    )

    client.doQuery(SetBagName.query, SetBagName.operationName, setBagNameVariables)
      .map(_ => this)
      .toTry
  }

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = {
    val setContentTypeVariables = Map(
      "depositId" -> depositId,
      "contentType" -> contentType,
    )

    client.doQuery(SetContentType.query, SetContentType.operationName, setContentTypeVariables)
      .map(_ => this)
      .toTry
  }

  override def removeClientMessageContentType(): Try[DepositProperties] = Success(this)

  override def getClientMessageContentType: Try[String] = {
    for {
      json <- client.doQuery(GetContentType.query, GetContentType.operationName, Map("depositId" -> depositId)).toTry
      deposit = json.extract[GetContentType.Data].deposit
      contentType <- deposit.map(_.contentType
        .map(contentType => Success(contentType.value))
        .getOrElse(Failure(NoContentTypeForDeposit(depositId))))
        .getOrElse(Failure(DepositDoesNotExist(depositId)))
    } yield contentType
  }

  override def getDepositorId: Try[String] = {
    for {
      json <- client.doQuery(GetDepositorId.query, GetDepositorId.operationName, Map("depositId" -> depositId)).toTry
      deposit = json.extract[GetDepositorId.Data].deposit
      depositorId <- deposit.map(deposit => Success(deposit.depositor.depositorId))
        .getOrElse(Failure(DepositDoesNotExist(depositId)))
    } yield depositorId
  }

  override def getDoi: Try[Option[String]] = {
    for {
      json <- client.doQuery(GetDoi.query, GetDoi.operationName, Map("depositId" -> depositId)).toTry
      deposit = json.extract[GetDoi.Data].deposit
      doi <- deposit.map(d => Success(d.identifier.map(_.value)))
        .getOrElse(Failure(DepositDoesNotExist(depositId)))
    } yield doi
  }

  override def getLastModifiedTimestamp: Try[Option[FileTime]] = {
    for {
      json <- client.doQuery(GetLastModifiedTimestamp.query, GetLastModifiedTimestamp.operationName, Map("depositId" -> depositId)).toTry
      deposit = json.extract[GetLastModifiedTimestamp.Data].deposit
      lastModified <- deposit.map(lastModified => Try {
        lastModified.lastModified.map(l => FileTime.fromMillis(DateTime.parse(l).getMillis))
      })
        .getOrElse(Failure(DepositDoesNotExist(depositId)))
    } yield lastModified
  }
}

object DepositPropertiesService {

  object DepositExists {
    case class Data(deposit: Option[Deposit])
    case class Deposit(depositId: DepositId)

    val operationName = "DepositExists"
    val query: String =
      """query DepositExists($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    depositId
        |  }
        |}""".stripMargin
  }

  object UpdateState {
    val operationName = "SetDepositState"
    val query: String =
      """mutation SetDepositState($depositId: UUID!, $stateLabel: StateLabel!, $stateDescription: String!) {
        |  updateState(input: { depositId: $depositId, label: $stateLabel, description: $stateDescription }) {
        |    state {
        |      label
        |      description
        |      timestamp
        |    }
        |  }
        |}""".stripMargin
  }

  object GetState {
    case class Data(deposit: Option[Deposit])
    case class Deposit(state: Option[State])
    case class State(label: String, description: String)

    val operationName = "GetDepositState"
    val query: String =
      """query GetDepositState($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    state {
        |      label
        |      description
        |    }
        |  }
        |}""".stripMargin
  }

  object SetBagName {
    val operationName = "SetBagName"
    val query: String =
      """mutation SetBagName($depositId: UUID!, $bagName: String!) {
        |  addBagName(input: { depositId: $depositId, bagName: $bagName }) {
        |    deposit {
        |      depositId
        |      bagName
        |    }
        |  }
        |}""".stripMargin
  }

  object SetContentType {
    val operationName = "SetContentType"
    val query: String =
      """mutation SetContentType($depositId: UUID!, $contentType: String!) {
        |  setContentType(input: { depositId: $depositId, value: $contentType }) {
        |    contentType {
        |      value
        |    }
        |  }
        |}""".stripMargin
  }

  object GetContentType {
    case class Data(deposit: Option[Deposit])
    case class Deposit(contentType: Option[ContentType])
    case class ContentType(value: String)

    val operationName = "GetContentType"
    val query: String =
      """query GetContentType($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    contentType {
        |      value
        |    }
        |  }
        |}""".stripMargin
  }

  object GetDepositorId {
    case class Data(deposit: Option[Deposit])
    case class Deposit(depositor: Depositor)
    case class Depositor(depositorId: String)

    val operationName = "GetDepositorId"
    val query: String =
      """query GetDepositorId($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    depositor {
        |      depositorId
        |    }
        |  }
        |}""".stripMargin
  }

  object GetDoi {
    case class Data(deposit: Option[Deposit])
    case class Deposit(identifier: Option[Identifier])
    case class Identifier(value: String)

    val operationName = "GetDoi"
    val query: String =
      """query GetDoi($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    identifier(type: DOI) {
        |      value
        |    }
        |  }
        |}""".stripMargin
  }

  object GetLastModifiedTimestamp {
    case class Data(deposit: Option[Deposit])
    case class Deposit(lastModified: Option[String])

    val operationName = "GetLastModifiedTimestamp"
    val query: String =
      """query GetLastModifiedTimestamp($depositId: UUID!) {
        |  deposit(id: $depositId) {
        |    lastModified
        |  }
        |}""".stripMargin
  }
}
