/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.api.sword2

import java.net.{MalformedURLException, URL}
import java.util
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import org.swordapp.server._

import scala.util.{Failure, Success, Try}

class StatementManagerImpl extends StatementManager {
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  override def getStatement(iri: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): Statement = {
    implicit val settings = config.asInstanceOf[SwordConfig].settings

    // TODO: REFACTOR THIS MESS
    Authentication.checkAuthentication(auth).get
    val maybeState = SwordID.extract(iri) match {
      case Success(id) => DepositProperties.getProperties(id)
      case Failure(t) => throw new SwordError(404)
    }
    maybeState match {
      case Success(properties) =>
        val statement = new AtomStatement(iri, "DANS-EASY", s"Deposit ${SwordID.extract(iri).get}", properties.timeStamp)
        statement.setState(properties.label, properties.description)
        if (properties.label == State.ARCHIVED.toString)
          properties.resources.foreach(resources => setResources(statement, resources))
        statement
      case Failure(t) => throw new SwordError(404)
    }
  }

  private def setResources(statement: AtomStatement, resources: PropertiesResources)(implicit settings: Settings): Unit =  {
    val bagUriResource = new ResourcePart(resources.bagUri.trim)
    bagUriResource.setMediaType("multipart/related;type=bag")
    val fileUrisResources = resources.fileUris.map(resource => {
      checkUrlValidity(resource.trim, settings.urlPattern)
      val resourcePart = new ResourcePart(resource.trim)
      resourcePart.setMediaType("multipart/related;type=file")
      resourcePart
    })
    val bagResources = (List(bagUriResource) ++ fileUrisResources).asJava
    statement.setResources(bagResources)
  }

  private def checkUrlValidity(url: String, urlPattern: Pattern): Unit = {
    // NOTICE: because this whole module will soon be refactored, I didn't pay much attention how the exceptions should be thrown and what they should be
    new URL(url)
    if (!urlPattern.matcher(url).matches()) throw InvalidDepositException("", s"Not allowed url in Fetch Items ($url)")
  }
}
