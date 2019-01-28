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

import org.scalatest.Inside.inside

import scala.util.{ Failure, Success }

class CheckFilenamesSpec extends Sword2Fixture with BagStoreFixture {

  val INPUT_BASEDIR = new File("src/test/resources/input")
  val NOT_RESERVED_SPECIAL_CHARACTERS = new File(INPUT_BASEDIR, "not-reserved-special-characters-bag")
  val RESERVED_SPECIAL_CHARACTERS = new File(INPUT_BASEDIR, "reserved-special-characters-bag")

  "checkFilenames" should "result in a Success when there are not-reserved special characters in file names" in {
    copyToTargetBagDir(NOT_RESERVED_SPECIAL_CHARACTERS)
    DepositHandler.checkFilenames(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Failure when there are reserved special characters in file names" in {
    copyToTargetBagDir(RESERVED_SPECIAL_CHARACTERS)
    val validity = DepositHandler.checkFilenames(targetBagDir)
    inside(validity) {
      case Failure(e) =>
        e shouldBe a[InvalidDepositException]
        e.getMessage should include("Reserved character ';' found in folder name 'target/test/nl.knaw.dans.easy.sword2.CheckFilenamesSpec/data/random;images#'")
        e.getMessage should include("Reserved character '&' found in file name 'target/test/nl.knaw.dans.easy.sword2.CheckFilenamesSpec/data/random;images#/image&01.png'")
        e.getMessage should include("Reserved character '\"' found in file name 'target/test/nl.knaw.dans.easy.sword2.CheckFilenamesSpec/data/random;images#/\"image03\".jpeg'")
        e.getMessage should include("Reserved character '>' found in file name 'target/test/nl.knaw.dans.easy.sword2.CheckFilenamesSpec/data/a/deeper/path/With some > file.txt'")
    }
  }
}

