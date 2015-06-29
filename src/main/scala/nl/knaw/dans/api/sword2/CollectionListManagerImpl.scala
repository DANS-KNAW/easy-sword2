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

import org.apache.abdera.Abdera
import org.apache.abdera.i18n.iri.IRI
import org.apache.abdera.model.{Entry, Feed}
import org.swordapp.server._

class CollectionListManagerImpl extends CollectionListManager {
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  @throws(classOf[SwordError])
  def listCollectionContents(collectionIRI: IRI, auth: AuthCredentials, config: SwordConfiguration): Feed = {
    Authentication.checkAuthentication(auth)
    val id = SwordID.extract(collectionIRI.toString)
    val abdera = new Abdera
    abdera.newFeed.addEntry(createEntry(id, abdera))
  }

  private def createEntry(id: String, abdera: Abdera): Entry = abdera.newEntry
}
