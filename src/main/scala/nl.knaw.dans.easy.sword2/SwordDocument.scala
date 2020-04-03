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

import java.net.URI
import java.nio.file.attribute.FileTime
import java.util.Collections

import nl.knaw.dans.easy.sword2.State.State
import org.apache.abdera.i18n.iri.IRI
import org.swordapp.server._

object SwordDocument {

  private val BAGIT_URI = "http://purl.org/net/sword/package/BagIt"

  def createDepositReceipt(id: DepositId)(implicit settings: Settings): DepositReceipt = {
    new DepositReceipt {
      val editIRI = new IRI(settings.serviceBaseUrl + "container/" + id)
      setEditIRI(editIRI)
      setLocation(editIRI)
      setEditMediaIRI(new IRI(settings.serviceBaseUrl + "media/" + id))
      setSwordEditIRI(editIRI)
      setAtomStatementURI(settings.serviceBaseUrl + "statement/" + id)
      setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
      setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    }
  }

  def serviceDocument(implicit settings: Settings): ServiceDocument = {
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

  def createStatement(id: DepositId,
                      statementIri: String,
                      stateLabel: State,
                      stateDescription: String,
                      doi: Option[String],
                      lastModifiedTimestamp: Option[FileTime],
                     )(implicit settings: Settings): AtomStatement = {
    new AtomStatement(statementIri, "DANS-EASY", s"Deposit $id", lastModifiedTimestamp.get.toString) {
      addState(stateLabel.toString, stateDescription)
      val archivalResource = new ResourcePart(new URI(s"urn:uuid:$id").toASCIIString)
      archivalResource.setMediaType("multipart/related")

      doi.foreach(doi => {
        archivalResource.addSelfLink(new URI(s"https://doi.org/$doi").toASCIIString)
      })

      addResource(archivalResource)
    }
  }
}