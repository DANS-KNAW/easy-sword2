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

class CompoundDepositProperties(file: DepositProperties,
                                service: DepositProperties,
                               ) extends DepositProperties {

  override def save(): Try[Unit] = {
    for {
      _ <- service.save()
      _ <- file.save()
    } yield ()
  }

  override def exists: Try[Boolean] = {
    for {
      _ <- service.exists
      existsFile <- file.exists
    } yield existsFile
  }

  override def getDepositId: DepositId = {
    val _ = service.getDepositId
    val depositIdFile = file.getDepositId
    depositIdFile
  }

  override def setState(state: State, descr: String): Try[Unit] = {
    for {
      _ <- service.setState(state, descr)
      _ <- file.setState(state, descr)
    } yield ()
  }

  override def getState: Try[(State, String)] = {
    for {
      _ <- service.getState
      stateFile <- file.getState
    } yield stateFile
  }

  override def setBagName(bagName: String): Try[Unit] = {
    for {
      _ <- service.setBagName(bagName)
      _ <- file.setBagName(bagName)
    } yield ()
  }

  override def setStateAndClientMessageContentType(stateLabel: State, stateDescription: String, contentType: String): Try[Unit] = {
    for {
      _ <- service.setStateAndClientMessageContentType(stateLabel, stateDescription, contentType)
      _ <- file.setStateAndClientMessageContentType(stateLabel, stateDescription, contentType)
    } yield ()
  }

  override def removeClientMessageContentType(): Try[Unit] = {
    for {
      _ <- service.removeClientMessageContentType()
      _ <- file.removeClientMessageContentType()
    } yield ()
  }

  override def getClientMessageContentType: Try[String] = {
    for {
      _ <- service.getClientMessageContentType
      contentTypeFile <- file.getClientMessageContentType
    } yield contentTypeFile
  }

  override def getDepositorId: Try[String] = {
    for {
      _ <- service.getDepositorId
      depositorIdFile <- file.getDepositorId
    } yield depositorIdFile
  }

  override def getDoi: Try[Option[String]] = {
    for {
      _ <- service.getDoi
      doiFile <- file.getDoi
    } yield doiFile
  }

  override def getLastModifiedTimestamp: Try[Option[FileTime]] = {
    for {
      _ <- service.getLastModifiedTimestamp
      lastModifiedFile <- file.getLastModifiedTimestamp
    } yield lastModifiedFile
  }
}
