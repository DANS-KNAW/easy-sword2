/**
 * Copyright (C) 2015-2017 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.sword2

import java.net.URI
import java.nio.file.Paths

import nl.knaw.dans.easy.sword2.DepositHandler._
import nl.knaw.dans.easy.sword2.State._
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.lang.StringUtils._
import org.swordapp.server._

import scala.util.Try

class CollectionDepositManagerImpl extends CollectionDepositManager {
  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    implicit val settings = config.asInstanceOf[SwordConfig].settings
    val result = for {
      _ <- Authentication.checkAuthentication(auth)
      _ <- checkValidCollectionId(collectionURI)
      maybeSlug = if(isNotBlank(deposit.getSlug)) Some(deposit.getSlug) else None
      id <- SwordID.generate(maybeSlug, auth.getUsername)
      _ = log.info(s"[$id] Created new deposit")
      _ <- setDepositStateToDraft(id, auth.getUsername)
      depositReceipt <- handleDeposit(deposit)(settings, id)
    } yield (id, depositReceipt)

    result.getOrThrow
  }

  def checkValidCollectionId(iri: String)(implicit settings: Settings): Try[Unit] = Try {
    val collectionPath = new URI(iri).getPath
    if(Paths.get("/").relativize(Paths.get(collectionPath)).toString != settings.collectionPath)
      throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed", 405, s"Not a valid collection: $collectionPath (valid collection is ${settings.collectionPath}")
  }

  private def setDepositStateToDraft(id: String, userId: String)(implicit settings: Settings): Try[Unit] =
    DepositProperties.set(
      id = id,
      stateLabel = DRAFT,
      stateDescription = "Deposit is open for additional data",
      userId = Some(userId),
      lookInTempFirst = true)

}
