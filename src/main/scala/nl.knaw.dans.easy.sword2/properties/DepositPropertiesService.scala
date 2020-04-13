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

import nl.knaw.dans.easy.sword2.DepositId
import nl.knaw.dans.easy.sword2.State.State

import scala.util.Try

class DepositPropertiesService(depositId: DepositId, client: GraphQLClient) extends DepositProperties {

  override def save(): Try[Unit] = ???

  override def exists: Boolean = ???

  override def getDepositId: DepositId = depositId

  override def setState(state: State, descr: String): Try[DepositProperties] = ???

  override def getState: Try[(State, String)] = ???

  override def setBagName(bagName: String): Try[DepositProperties] = ???

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = ???

  override def removeClientMessageContentType(): Try[DepositProperties] = ???

  override def getClientMessageContentType: Try[String] = ???

  override def getDepositorId: Try[String] = ???

  override def getDoi: Try[Option[String]] = ???

  override def getLastModifiedTimestamp: Try[Option[FileTime]] = ???
}
