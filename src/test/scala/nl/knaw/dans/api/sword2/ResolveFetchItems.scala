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

import org.apache.commons.io.FileUtils

import scala.util.{Failure, Success}

class ResolveFetchItems extends Sword2Fixture {

  val VALID_BAG_WITHOUT_FETCH_TEXT = new File("src/test/resources/simple/simple-bag")
  val VALID_BAG_WITH_FETCH_TEXT = new File("src/test/resources/simple/simple-2-bag")
  val INVALID_BAG_INVALID_URL = new File("src/test/resources/simple/simple-2-invalid-url-bag")
  val INVALID_BAG_NOT_ALLOWED_URL = new File("src/test/resources/simple/simple-2-not-allowed-url-bag")
  val INVALID_BAG_NO_DATA = new File("src/test/resources/distributed/part1-invalid/example-bag")

  "resolveFetchItems" should "result in a Success with a valid bag without a fetch.txt" in {
    copyBagFromDirectory(VALID_BAG_WITHOUT_FETCH_TEXT)
    DepositHandler.resolveFetchItems(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Success with a valid bag with a fetch.txt"  in {
    copyBagFromDirectory(VALID_BAG_WITH_FETCH_TEXT)
    DepositHandler.resolveFetchItems(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Failure with a syntactically invalid url in a fetch.txt"  in {
    copyBagFromDirectory(INVALID_BAG_INVALID_URL)
    DepositHandler.resolveFetchItems(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure with a not allowed url in a fetch.txt"  in {
    copyBagFromDirectory(INVALID_BAG_NOT_ALLOWED_URL)
    DepositHandler.resolveFetchItems(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure with a invalid bag"  in {
    copyBagFromDirectory(INVALID_BAG_NO_DATA)
    DepositHandler.resolveFetchItems(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  private def copyBagFromDirectory(sourceDir: File): Unit ={
    FileUtils.copyDirectory(sourceDir, targetBagDir)
  }
}

