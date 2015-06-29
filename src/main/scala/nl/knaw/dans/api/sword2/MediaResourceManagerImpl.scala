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

import java.util

import org.swordapp.server._

class MediaResourceManagerImpl extends MediaResourceManager {
  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  override def getMediaResourceRepresentation(uri: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): MediaResource = {
    /*
      // We're not serving files from the SWORD interface currently!
      val id: String = SwordID.extract(uri)
      val dir: File = new File(SwordProps.get("data-dir") + "/" + id)
      if (!dir.exists) {
        throw new SwordError(404)
      }
      try {
        new MediaResource(Files.newInputStream(Paths.get(dir.getPath)), "application/zip", "http://purl.org/net/sword/package/BagIt")
      } catch {
        case e: IOException => throw new SwordServerException("Invalid resource found on server")
      }
    */
    throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed")
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def replaceMediaResource(uri: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed")
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def deleteMediaResource(uri: String, auth: AuthCredentials, config: SwordConfiguration) {
    throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed")
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def addResource(uri: String, deposit: Deposit, auth: AuthCredentials, config: SwordConfiguration): DepositReceipt = {
    throw new SwordError("http://purl.org/net/sword/error/MethodNotAllowed")
  }

}
