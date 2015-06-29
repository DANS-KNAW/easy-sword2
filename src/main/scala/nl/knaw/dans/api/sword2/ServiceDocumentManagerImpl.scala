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

import org.swordapp.server._

class ServiceDocumentManagerImpl extends ServiceDocumentManager {
  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def getServiceDocument(s: String, authCredentials: AuthCredentials, swordConfiguration: SwordConfiguration): ServiceDocument = {
    val sdoc: ServiceDocument = new ServiceDocument

    val sw: SwordWorkspace = new SwordWorkspace
    sw.setTitle("SWORD V2.0 - EASY Data Deposit")

    val sc: SwordCollection = new SwordCollection
    sc.setTitle("DANS EASY Data Collection")
    sc.addAcceptPackaging("http://easy.dans.knaw.nl/schemas/index.xml")
    sc.setLocation(SwordProps.get("host"))

    sw.addCollection(sc)

    sdoc.addWorkspace(sw)
    sdoc
  }
}
