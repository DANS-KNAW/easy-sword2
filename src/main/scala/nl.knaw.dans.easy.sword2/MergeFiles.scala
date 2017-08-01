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

import java.io._

import org.apache.commons.io.{ FileUtils, IOUtils }

import scala.util.Try

object MergeFiles {

  def merge(destination: File, files: Seq[File]): Try[Unit] = Try {
    var output: OutputStream = null
    try {
      output = createAppendableStream(destination)
      files.foreach(appendFile(output))
    } finally {
      files.foreach(FileUtils.deleteQuietly)
      IOUtils.closeQuietly(output)
    }
  }

  @throws(classOf[FileNotFoundException])
  private def createAppendableStream(destination: File): BufferedOutputStream =
    new BufferedOutputStream(new FileOutputStream(destination, true))

  @throws(classOf[IOException])
  private def appendFile(output: OutputStream)(file: File) {
    var input: InputStream = null
    try {
      input = new BufferedInputStream(new FileInputStream(file))
      IOUtils.copy(input, output)
    } finally {
      IOUtils.closeQuietly(input)
    }
  }

}
