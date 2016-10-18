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

  val SIMPLE_SEQUENCE_A           = new File("src/test/resources/simple-sequence/a")
  val SIMPLE_SEQUENCE_B           = new File("src/test/resources/simple-sequence/b")
  val SIMPLE_SEQUENCE_C           = new File("src/test/resources/simple-sequence/c")
  val REQUIRED_FILE_MISSING       = new File("src/test/resources/simple-sequence/missing-required-file")
  val FETCH_ITEM_FILE_MISSING     = new File("src/test/resources/simple-sequence/file-missing-in-fetch-text")
  val INCORRECT_CHECKSUM          = new File("src/test/resources/simple-sequence/incorrect-checksum")
  val NONEXISTENT_FETCH_ITEM_PATH = new File("src/test/resources/simple-sequence/nonexistent-fetchtext-path")
  val FETCH_ITEM_ALREADY_IN_BAG   = new File("src/test/resources/simple-sequence/fetch-item-already-in-bag")
  val URL_OUTSIDE_BAGSTORE_BAG    = new File("src/test/resources/simple/url-outside-bagstore-bag")
  val INVALID_URL_BAG             = new File("src/test/resources/simple/invalid-url-bag")
  val NOT_ALLOWED_URL_BAG         = new File("src/test/resources/simple/not-allowed-url-bag")
  val NO_DATA_BAG                 = new File("src/test/resources/distributed/part1-invalid/example-bag")

  "resolveFetchItems" should "result in a Success with a valid bag without a fetch.txt" in {
    copyBagFromDirectory(SIMPLE_SEQUENCE_A)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Success with a valid bag with a fetch.txt"  in {
    copyBagFromDirectory(SIMPLE_SEQUENCE_B)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Success with another valid bag with a fetch.txt"  in {
    copyBagFromDirectory(SIMPLE_SEQUENCE_C)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Failure when a required file is missing"  in {
    copyBagFromDirectory(REQUIRED_FILE_MISSING)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure when a file is missing in the fetch.txt"  in {
    copyBagFromDirectory(FETCH_ITEM_FILE_MISSING)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure when a file checksum is incorrect"  in {
    copyBagFromDirectory(INCORRECT_CHECKSUM)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure when there is a nonexistent path in the fetch.txt"  in {
    copyBagFromDirectory(NONEXISTENT_FETCH_ITEM_PATH)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure when a file in the fetch.txt is already in the bag"  in {
    copyBagFromDirectory(FETCH_ITEM_ALREADY_IN_BAG)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Success with a valid fetch.txt url referring outside the bagstore"  in {
    copyBagFromDirectory(URL_OUTSIDE_BAGSTORE_BAG)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Success[_]]
  }

  it should "result in a Failure with a syntactically invalid url in the fetch.txt"  in {
    copyBagFromDirectory(INVALID_URL_BAG)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure with a not allowed url in the fetch.txt"  in {
    copyBagFromDirectory(NOT_ALLOWED_URL_BAG)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure with an empty bag"  in {
    copyBagFromDirectory(NO_DATA_BAG)
    DepositHandler.checkFetchItemUrls(targetBagDir) shouldBe a[Success[_]]
    DepositHandler.checkBagVirtualValidity(targetBagDir) shouldBe a[Failure[_]]
  }

  it should "result in a Failure when the Base Directory doesn't exist"  in {
    copyBagFromDirectory(SIMPLE_SEQUENCE_A)
    baseDir = new File("non/existent/dir")
    DepositHandler.checkBagStoreBaseDir() shouldBe a[Failure[_]]
  }

  private def copyBagFromDirectory(sourceDir: File): Unit ={
    FileUtils.copyDirectory(sourceDir, targetBagDir)
  }
}

