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
import org.json4s.{ DefaultFormats, Formats }

import scala.util.Try

class DepositPropertiesServiceFactory(client: GraphQLClient) extends DepositPropertiesFactory {
  implicit val formats: Formats = DefaultFormats

  override def load(depositId: DepositId): Try[DepositProperties] = ???

  override def create(depositId: DepositId, depositorId: String): Try[DepositProperties] = ???

  override def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]] = ???
}
