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

class DepositPropertiesCompound(file: DepositProperties,
                                service: DepositProperties,
                               ) extends DepositProperties {

  override def save(): Try[Unit] = {
    for {
      _ <- file.save()
      _ <- service.save()
    } yield ()
  }

  override def exists: Try[Boolean] = {
    for {
      existsFile <- file.exists
      _ <- service.exists
    } yield existsFile
  }

  override def getDepositId: DepositId = {
    val depositIdFile = file.getDepositId
    val _ = file.getDepositId
    depositIdFile
  }

  override def setState(state: State, descr: String): Try[DepositProperties] = {
    for {
      _ <- file.setState(state, descr)
      _ <- service.setState(state, descr)
    } yield this
  }

  override def getState: Try[(State, String)] = {
    for {
      stateFile <- file.getState
      _ <- service.getState
    } yield stateFile
  }

  override def setBagName(bagName: String): Try[DepositProperties] = {
    for {
      _ <- file.setBagName(bagName)
      _ <- service.setBagName(bagName)
    } yield this
  }

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = {
    for {
      _ <- file.setClientMessageContentType(contentType)
      _ <- service.setClientMessageContentType(contentType)
    } yield this
  }

  override def removeClientMessageContentType(): Try[DepositProperties] = {
    for {
      _ <- file.removeClientMessageContentType()
      _ <- service.removeClientMessageContentType()
    } yield this
  }

  override def getClientMessageContentType: Try[String] = {
    for {
      contentTypeFile <- file.getClientMessageContentType
      _ <- service.getClientMessageContentType
    } yield contentTypeFile
  }

  override def getDepositorId: Try[String] = {
    for {
      depositorIdFile <- file.getDepositorId
      _ <- service.getDepositorId
    } yield depositorIdFile
  }

  override def getDoi: Try[Option[String]] = {
    for {
      doiFile <- file.getDoi
      _ <- service.getDoi
    } yield doiFile
  }

  override def getLastModifiedTimestamp: Try[Option[FileTime]] = {
    for {
      lastModifiedFile <- file.getLastModifiedTimestamp
      _ <- service.getLastModifiedTimestamp
    } yield lastModifiedFile
  }
}
