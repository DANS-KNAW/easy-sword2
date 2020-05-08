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
import nl.knaw.dans.lib.logging.DebugEnhancedLogging

import scala.util.Try

trait DepositPropertiesRepository extends DebugEnhancedLogging {

  def load(depositId: DepositId): Try[DepositProperties]

  def create(depositId: DepositId, depositorId: String): Try[Unit]

  def getSword2UploadedDeposits: Try[Iterator[(DepositId, MimeType)]]
}
