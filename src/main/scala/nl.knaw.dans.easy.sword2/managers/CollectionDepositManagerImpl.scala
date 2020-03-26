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
package nl.knaw.dans.easy.sword2.managers

import java.net.URI
import java.nio.file.Paths

import nl.knaw.dans.easy.sword2.DepositHandler._
import nl.knaw.dans.easy.sword2._
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.lang.StringUtils._
import org.swordapp.server._

import scala.util.Try

class CollectionDepositManagerImpl extends CollectionDepositManager with DebugEnhancedLogging {

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    implicit val settings: Settings = config.asInstanceOf[SwordConfig].settings
    val result = for {
      _ <- Authentication.checkAuthentication(auth)
      _ <- checkValidCollectionId(collectionURI)
      maybeSlug = if (isNotBlank(deposit.getSlug)) Some(deposit.getSlug)
                  else None
      id <- SwordID.generate(maybeSlug, auth.getUsername)
      _ = logger.info(s"[$id] Created new deposit")
      _ <- DepositPropertiesFactory.create(id, auth.getUsername)
      depositReceipt <- handleDeposit(deposit)(settings, id)
      _ = logger.info(s"[$id] Sending deposit receipt")
    } yield depositReceipt
    
    result
      .doIfFailure { case e => logger.warn(s"Returning error to client: ${ e.getMessage }") }
      .unsafeGetOrThrow
  }

  def checkValidCollectionId(iri: String)(implicit settings: Settings): Try[Unit] = Try {
    val collectionPath = new URI(iri).getPath
    if (Paths.get("/").relativize(Paths.get(collectionPath)).toString != settings.collectionPath)
      throw new SwordError(UriRegistry.ERROR_METHOD_NOT_ALLOWED, s"Not a valid collection: $collectionPath (valid collection is ${ settings.collectionPath }")
  }
}
