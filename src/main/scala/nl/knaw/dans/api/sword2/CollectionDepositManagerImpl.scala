/*******************************************************************************
  * Copyright 2015 DANS - Data Archiving and Networked Services
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  ******************************************************************************/
package nl.knaw.dans.api.sword2

import java.nio.file.Paths

import nl.knaw.dans.api.sword2.DepositHandler._
import org.swordapp.server._

import scala.util.{Try, Failure, Success}

class CollectionDepositManagerImpl extends CollectionDepositManager {
  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def createNew(collectionURI: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration) = {
    log.info(s"${formatPrefix(auth.getUsername, "<new>")} Creating new deposit")
    Authentication.checkAuthentication(auth)

    val result = for {
      - <- checkValidCollectionId(collectionURI)
      id <- SwordID.generate
      _ = log.debug(s"${formatPrefix(auth.getUsername, id)} Assigned deposit ID")
      _ <- checkDepositStatus(id)
      payload = Paths.get(SwordProps("temp-dir"), id, deposit.getFilename.split("/").last).toFile
      _ <- copyPayloadToFile(deposit, payload)
      _ <- setDepositStateToDraft(id)
      _ <- doesHashMatch(payload, deposit.getMd5)
      _ <- handleDepositAsync(id, auth, deposit)
    } yield (id, createDepositReceipt(deposit, id))

    result match {
      case Success((id,depositReceipt)) =>
        log.info(s"${formatPrefix(auth.getUsername, id)} Sending deposit receipt for deposit: $id")
        depositReceipt
      case Failure(e) =>
        log.error("Error(s) occurred", e)
        throw e
    }
  }

  private def setDepositStateToDraft(id: String): Try[Unit] = Try {
    DepositState.setDepositState(id, "DRAFT", "Deposit is open for additional data", true)
  }
}
