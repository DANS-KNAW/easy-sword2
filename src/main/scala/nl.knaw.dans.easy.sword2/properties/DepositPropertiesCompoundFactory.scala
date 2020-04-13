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
import nl.knaw.dans.easy.sword2.{ DepositId, MimeType }

import scala.util.{ Failure, Success, Try }

class DepositPropertiesCompoundFactory(file: DepositPropertiesFileFactory,
                                       service: DepositPropertiesServiceFactory,
                                      ) extends DepositPropertiesFactory {

  override def load(depositId: DepositId): Try[DepositProperties] = {
    for {
      propsFile <- file.load(depositId)
      propsService <- service.load(depositId)
    } yield new DepositPropertiesCompound(propsFile, propsService)
  }

  override def create(depositId: DepositId, depositorId: String): Try[DepositProperties] = {
    for {
      propsFile <- file.create(depositId, depositorId)
      propsService <- service.create(depositId, depositorId)
    } yield new DepositPropertiesCompound(propsFile, propsService)
  }

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = {
    for {
      uploadedFile <- file.getSword2UploadedDeposits.map(_.toList)
      uploadedService <- service.getSword2UploadedDeposits.map(_.toList)
      _ <- if (uploadedFile == uploadedService) Success(())
           else Failure(new Exception(s"file and service are not in sync. Result for 'file': $uploadedFile. Result for 'service': $uploadedService."))
    } yield uploadedFile.toIterator
  }

  override def toString: DepositId = s"a combination of $file and $service"
}
