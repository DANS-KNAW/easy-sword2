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

import gov.loc.repository.bagit.FetchTxt.FilenameSizeUrl

import scala.io.{ Codec, Source }
import scala.util.Success

class PruneFetchTxtSpec extends Sword2Fixture {
  implicit val codec = Codec.UTF8
  val FETCH_TXT = new File(targetBagDir, "fetch.txt")
  val TEST_BAG = new File("src/test/resources/prune-fetchtxt/hello-bag")
  val FETCH_ITEM_1 = new FilenameSizeUrl("data/file/in/bag", 0L, "http://some/url")
  val FETCH_ITEM_2 = new FilenameSizeUrl("data/other/file/in/bag", 0L, "http://some/other/url")
  val CHECKSUM_FETCH_TXT_WITH_BOTH_FETCH_ITEMS = "7f8df7b79eb3f21cab593b214e0a3b2a"
  val CHECKSUM_FETCH_TXT_WITH_ONLY_FETCH_ITEM_2 = "0b571b37d35e7b519d647068b5971fee"


  def getFetchTxtSrc = Source.fromFile(new File(targetBagDir, "fetch.txt")).mkString

  def getTagManifestSrc = Source.fromFile(new File(targetBagDir, "tagmanifest-md5.txt")).mkString

  "pruneFetchTxt" should "remove fetch items listed in second argument" in {
    copyToTargetBagDir(TEST_BAG)
    DepositHandler.pruneFetchTxt(targetBagDir, Seq(FETCH_ITEM_1))
    getFetchTxtSrc should not include "data/file/in/bag"
  }

  it should "leave fetch items that are not listed in second argument" in {
    copyToTargetBagDir(TEST_BAG)
    DepositHandler.pruneFetchTxt(targetBagDir, Seq(FETCH_ITEM_1))
    getFetchTxtSrc should include(FETCH_ITEM_2.getFilename)
  }

  it should "remove fetch.txt if it is left empty after pruning" in {
    copyToTargetBagDir(TEST_BAG)
    DepositHandler.pruneFetchTxt(targetBagDir, Seq(FETCH_ITEM_1, FETCH_ITEM_2))
    FETCH_TXT shouldNot exist
  }

  it should "update checksum for fetch.txt in tagmanifest if fetch.txt is changed" in {
    copyToTargetBagDir(TEST_BAG)
    getTagManifestSrc should include regex s"""$CHECKSUM_FETCH_TXT_WITH_BOTH_FETCH_ITEMS\\s+fetch.txt""" // Double-check preconditions
    DepositHandler.pruneFetchTxt(targetBagDir, Seq(new FilenameSizeUrl("data/file/in/bag", 0L, "http://some/url")))
    getTagManifestSrc should include regex s"""$CHECKSUM_FETCH_TXT_WITH_ONLY_FETCH_ITEM_2\\s+fetch.txt""" // Checksum of fetch.txt has changed because one item was deleted
  }

  it should "remove fetch.txt from tagmanifest if fetch.txt is removed" in {
    copyToTargetBagDir(TEST_BAG)
    DepositHandler.pruneFetchTxt(targetBagDir,
      Seq(new FilenameSizeUrl("data/file/in/bag", 0L, "http://some/url"),
        new FilenameSizeUrl("data/other/file/in/bag", 0L, "http://some/other/url")))
    getTagManifestSrc should not include "fetch.txt"
  }

  it should "succeed if no fetch.txt is present" in {
    copyToTargetBagDir(TEST_BAG)
    FETCH_TXT.delete()
    DepositHandler.pruneFetchTxt(targetBagDir, Seq(new FilenameSizeUrl("data/file/in/bag", 0L, "http://some/url"),
      new FilenameSizeUrl("data/other/file/in/bag", 0L, "http://some/other/url"))) shouldBe a[Success[_]]
  }
}
