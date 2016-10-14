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

import java.io.File
import java.net.URI

import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers, OneInstancePerTest}

/**
 * Common base class for tests that need to set up a test bag store. This class should only do the set-up that is
 * common to all these tests, nothing more!
 */
abstract class Sword2Fixture extends FlatSpec with Matchers with OneInstancePerTest {
  implicit val id = "test"
  val targetBagDir = new File(s"target/test/resultBagDir")
  FileUtils.deleteQuietly(targetBagDir)

  homeDir = new File("src/main/assembly/dist")
  implicit var baseDir = new File("src/test/resources/bag-store")
  implicit val baseUrl = new URI("http://deasy.dans.knaw.nl/aips")
}


