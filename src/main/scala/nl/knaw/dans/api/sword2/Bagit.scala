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

import java.io._
import java.util.zip.{ZipEntry, ZipInputStream}

object Bagit {
  private val BUFFER_SIZE: Int = 4096

  @throws(classOf[IOException])
  def extract(bagitZip: File, outputFolder: String) {
    val destDir: File = new File(outputFolder)
    if (!destDir.exists) {
      destDir.mkdir
    }
    val zipIn: ZipInputStream = new ZipInputStream(new FileInputStream(bagitZip))
    var entry: ZipEntry = zipIn.getNextEntry
    while (entry != null) {
      val filePath: String = outputFolder + File.separator + entry.getName
      if (!entry.isDirectory) {
        extractFile(zipIn, filePath)
      } else {
        val dir: File = new File(filePath)
        dir.mkdir()
      }
      zipIn.closeEntry()
      entry = zipIn.getNextEntry
    }
    zipIn.close()
  }

  @throws(classOf[IOException])
  private def extractFile(zipIn: ZipInputStream, filePath: String) {
    val bos: BufferedOutputStream = new BufferedOutputStream(new FileOutputStream(filePath))
    val bytesIn: Array[Byte] = new Array[Byte](BUFFER_SIZE)
    var read: Int = 0
    while ({
      read = zipIn.read(bytesIn)
      read
    } != -1) {
      bos.write(bytesIn, 0, read)
    }
    bos.close()
  }

}

