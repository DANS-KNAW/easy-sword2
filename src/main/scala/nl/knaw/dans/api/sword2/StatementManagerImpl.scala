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

import java.io.File
import java.util

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ObjectId, Constants}
import org.swordapp.server._

import scala.util.{Failure, Try}

import collection.JavaConversions._

class StatementManagerImpl extends StatementManager {
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  override def getStatement(iri: String, accept: util.Map[String, String], auth: AuthCredentials, config: SwordConfiguration): Statement = {
    Authentication.checkAuthentication(auth)
    SwordID.extract(iri).flatMap {
      case Some(id) => getStatus(id)
      case None => Failure(new SwordError(404))
    }.map(tag => new AtomStatement(iri, "DANS-EASY", s"state=$tag", "today")).get
  }

  def getStatus(id: String): Try[String] = Try {
    val dir = new File(SwordProps("data-dir"), id)
    if (!dir.exists) throw new SwordError(404)
    val git = Git.open(dir)
    val head = git.getRepository.resolve(Constants.HEAD)
    val headTag = git.tagList().call().find(_.getObjectId.equals(head))
    if (!git.status().call().isClean)
      "PROCESSING"
    else if (headTag.isEmpty)
      "PROCESSING"
    else
      headTag.get.getName
  }

}
