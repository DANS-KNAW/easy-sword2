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

import scala.util.Try

trait DepositProperties {

  def save(): Try[Unit]

  def exists: Boolean

  def setState(state: State, descr: String): Try[DepositProperties]

  def getState: Try[(State, String)]

  def setBagName(bagName: String): Try[DepositProperties]

  def setClientMessageContentType(contentType: String): Try[DepositProperties]

  def removeClientMessageContentType(): Try[DepositProperties]

  def getClientMessageContentType: Try[String]

  def getDepositorId: Try[String]

  def getDoi: Try[Option[String]]

  def getLastModifiedTimestamp: Try[Option[FileTime]]
}
