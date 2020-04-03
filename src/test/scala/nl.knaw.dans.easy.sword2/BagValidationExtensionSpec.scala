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
import gov.loc.repository.bagit.BagFactory
import gov.loc.repository.bagit.utilities.SimpleResult
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers

import scala.util.{ Failure, Success }

class BagValidationExtensionSpec extends TestSupportFixture
  with Matchers
  with BeforeAndAfterEach
  with BagValidationExtension {

  lazy private val inputDir = {
    val path = testDir / "input/"
    if (path.exists) path.delete()
    path.createDirectories()
    path
  }
  private val shaDir = inputDir / "sha3"
  private val seqADir = inputDir / "bag-sequence" / "a"
  private val bagFactory = new BagFactory

  override def beforeEach: Unit = {
    super.beforeEach()
    inputDir.clear()
    File(getClass.getResource("/input/").toURI).copyTo(inputDir)
  }

  "verifyBagIsValid" should "fail if a not recognized sha-algorithm is given" in {
    implicit val settings: SwordConfig = createMinimalSettings(shaDir)

    val testBag = bagFactory.createBag(shaDir.toJava)
    implicit val depositId: DepositId = "1234566"
    verifyBagIsValid(testBag) should matchPattern {
      case Failure(e: InvalidDepositException)  if e.getMessage.contains(s"Unrecognized algorithm for manifest: manifest-sha384.txt. Supported algorithms are: ${ BagValidationExtension.acceptedValues }.") =>
    }
  }

  it should "succeed if an recognized sha-algorhithm is given" in {
    implicit val settings: SwordConfig = createMinimalSettings(seqADir)

    val testBag = bagFactory.createBag(seqADir.toJava)
    implicit val depositId: DepositId = "1234566"
    verifyBagIsValid(testBag) should matchPattern {
      case Success(s: SimpleResult) if s.isSuccess =>
    }
  }
}
