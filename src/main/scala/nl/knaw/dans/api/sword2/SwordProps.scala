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

import java.io.{IOException, FileInputStream, InputStream}
import java.util.Properties
import java.io.File

object SwordProps {
  private val props: Properties = new Properties

  var input: InputStream = null
  try {
    input = new FileInputStream(new File(homeDir, "cfg/application.properties"))
    props.load(input)
  }
  catch {
    case e: IOException => e.printStackTrace()
  } finally {
    if (input != null) {
      try {
        input.close()
      } catch {
        case e: IOException => e.printStackTrace()
      }
    }
  }

  def apply(key: String): String = props.getProperty(key)
}
