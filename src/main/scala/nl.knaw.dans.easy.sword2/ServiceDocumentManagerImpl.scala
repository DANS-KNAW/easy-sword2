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
package nl.knaw.dans.easy.sword2

import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.swordapp.server._

class ServiceDocumentManagerImpl extends ServiceDocumentManager with DebugEnhancedLogging {
  val BAGIT_URI = "http://purl.org/net/sword/package/BagIt"

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def getServiceDocument(s: String, authCredentials: AuthCredentials, config: SwordConfiguration): ServiceDocument = {
    implicit val settings: Settings = config.asInstanceOf[SwordConfig].settings
    val username = if (authCredentials.getUsername.isEmpty) "Anonymous user"
                   else authCredentials.getUsername
    logger.info(s"Service document retrieved by $username")
    if (Authentication.checkAuthentication(authCredentials).isFailure) throw new SwordAuthException()

    new ServiceDocument {
      addWorkspace(new SwordWorkspace {
        setTitle("EASY SWORD2 Deposit Service")
        addCollection(new SwordCollection {
          setTitle("DANS Default Data Collection")
          addAcceptPackaging(BAGIT_URI)
          setLocation(settings.serviceBaseUrl + settings.collectionPath)
        })
      })
    }
  }
}
