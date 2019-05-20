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

import better.files.File
import better.files.File.currentWorkingDirectory
import org.scalatest.enablers.Existence
import org.scalatest.{ FlatSpec, Matchers, OptionValues }

trait TestSupportFixture extends FlatSpec with Matchers with OptionValues {
  implicit def existenceOfFile[FILE <: better.files.File]: Existence[FILE] = _.exists

  lazy val testDir: File = {
    val path = currentWorkingDirectory / s"target/test/${ getClass.getSimpleName }"
    if (path.exists) path.delete()
    path.createDirectories()
    path
  }

  def createMinimalSettings(bag: File): SwordConfig = {
    new SwordConfig {
      settings = Settings(depositRootDir = bag.toJava,
        "rwxrwxrwx",
        new java.io.File("dummy"),
        "",
        "",
        null,
        null,
        None,
        "does.not.exists@dans.knaw.nl",
        9090000L,
        null,
        Map(),
        90000)
    }
  }
}
