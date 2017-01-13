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

import org.swordapp.server.SwordError

import scala.util.{Failure, Success, Try}

object SwordID {
  def generate(maybeSlug: Option[String], user: String)(implicit settings: Settings): Try[String] = Try {
    val postfix = maybeSlug match {
      case Some(slug) => slug
      case None => generateTimeBasedPostfix.get
    }
    val prefix = settings.auth match {
      case _ : SingleUserAuthSettings => ""
      case _ => s"$user-"
    }
    s"$prefix$postfix"
  }

  def generateTimeBasedPostfix = synchronized {
    try {
      val id: Long = System.currentTimeMillis
      Thread.sleep(2)
      Success(id.toString)
    } catch {
      case e: InterruptedException => Failure(e)
    }
  }

  def extract(IRI: String): Try[String] = {
    val parts = IRI.split("/")
    if (parts.length < 1) Failure(new SwordError(404))
    else Success(parts.last)
  }
}
