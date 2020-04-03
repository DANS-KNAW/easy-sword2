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

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.OneInstancePerTest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

trait Sword2Fixture extends AnyFlatSpec with Matchers with OneInstancePerTest {
  implicit val depositId: DepositId = "test"
  val targetBagDir = new File(s"target/test/${ getClass.getName }")
  FileUtils.deleteQuietly(targetBagDir)

  protected def copyToTargetBagDir(sourceDir: File): Unit = FileUtils.copyDirectory(sourceDir, targetBagDir)
}
