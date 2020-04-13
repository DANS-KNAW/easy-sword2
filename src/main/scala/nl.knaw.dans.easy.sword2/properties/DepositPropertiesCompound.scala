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

import scala.util.{ Failure, Success, Try }

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
      existsService <- service.exists
      _ <- if (existsFile == existsService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $existsFile. Result for 'service': $existsService."))
    } yield existsFile
  }

  override def getDepositId: DepositId = {
    val depositIdFile = file.getDepositId
    val depositIdService = file.getDepositId
    if (depositIdFile == depositIdService)
      depositIdFile
    else
      throw new Exception(s"file and service are not in sync. Result for 'file': $depositIdFile. Result for 'service': $depositIdService.")
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
      stateService <- service.getState
      _ <- if (stateFile == stateService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $stateFile. Result for 'service': $stateService."))
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
      contentTypeService <- service.getClientMessageContentType
      _ <- if (contentTypeFile == contentTypeService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $contentTypeFile. Result for 'service': $contentTypeService."))
    } yield contentTypeFile
  }

  override def getDepositorId: Try[String] = {
    for {
      depositorIdFile <- file.getDepositorId
      depositorIdService <- service.getDepositorId
      _ <- if (depositorIdFile == depositorIdService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $depositorIdFile. Result for 'service': $depositorIdService."))
    } yield depositorIdFile
  }

  override def getDoi: Try[Option[String]] = {
    for {
      doiFile <- file.getDoi
      doiService <- service.getDoi
      _ <- if (doiFile == doiService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $doiFile. Result for 'service': $doiService."))
    } yield doiFile
  }

  override def getLastModifiedTimestamp: Try[Option[FileTime]] = {
    for {
      lastModifiedFile <- file.getLastModifiedTimestamp
      lastModifiedService <- service.getLastModifiedTimestamp
      _ <- if (lastModifiedFile == lastModifiedService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $lastModifiedFile. Result for 'service': $lastModifiedService."))
    } yield lastModifiedFile
  }
}
