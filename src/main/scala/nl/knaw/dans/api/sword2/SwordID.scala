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

import org.swordapp.server.SwordError

object SwordID {
  def generate: String = {
    val id: Long = System.currentTimeMillis
    try {
      Thread.sleep(2)
    } catch {
      case e: InterruptedException => e.printStackTrace()
    }
    id.toString
  }

  @throws(classOf[SwordError])
  def extract(IRI: String): String = {
    val parts: Array[String] = IRI.split("/")
    if (parts.length < 1) {
      throw new SwordError(404)
    }
    val lastPart: String = parts(parts.length - 1)
    if (lastPart == "collection") null else lastPart
  }

  @throws(classOf[SwordError])
  def extractOrGenerate(IRI: String): String = {
    val id: String = extract(IRI)
    if (id != null) id else SwordID.generate
  }
}
