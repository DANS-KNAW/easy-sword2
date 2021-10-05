/*
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

import better.files.FileExtensions
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import java.io.{File => JFile}
import java.util.regex.Pattern
import scala.util.Success

class BagExtractorSpec extends TestSupportFixture with BeforeAndAfterEach {

  import BagExtractor._

  private val outDir = (testDir / "out").toJava

  override def beforeEach: Unit = {
    super.beforeEach()
    FileUtils.deleteDirectory(outDir)
  }

  private def getZipFile(name: String): JFile = {
    new JFile("src/test/resources/zips", name)
  }

  "createFilePathMapping" should "generate empty map for empty zip" in {
    val maybeMap = createFilePathMapping(getZipFile("empty.zip"), Pattern.compile(""))
    maybeMap shouldBe a[Success[_]]
    maybeMap.get shouldBe empty
  }

  it should "generate mappings for files under prefix" in {
    val maybeMap = createFilePathMapping(getZipFile("mix.zip"), Pattern.compile("subfolder/"))
    maybeMap shouldBe a[Success[_]]
    maybeMap.get.keySet should contain("subfolder/test.txt")
  }

  "unzipWithMappedFilePaths" should "unzip empty zip" in {
    unzipWithMappedFilePaths(getZipFile("empty.zip"), outDir, Map[String, String]()) shouldBe a[Success[_]]
    outDir.list() shouldBe empty
  }

  it should "unzip zip with one unmapped root entry" in {
    unzipWithMappedFilePaths(getZipFile("one-entry.zip"), outDir, Map[String, String]()) shouldBe a[Success[_]]
    outDir.list().length shouldBe 1
    FileUtils.readFileToString((outDir.toScala / "test.txt").toJava, "UTF-8").trim shouldBe "test"
  }

  it should "unzip zip with one mapped root entry" in {
    unzipWithMappedFilePaths(getZipFile("one-entry.zip"), outDir, Map("test.txt" -> "renamed.txt")) shouldBe a[Success[_]]
    outDir.list().length shouldBe 1
    FileUtils.readFileToString((outDir.toScala / "renamed.txt").toJava, "UTF-8").trim shouldBe "test"
  }

  it should "unzip zip with one unmapped entry in subfolder" in {
    unzipWithMappedFilePaths(getZipFile("one-entry-in-subfolder.zip"), outDir, Map[String, String]()) shouldBe a[Success[_]]
    outDir.list().length shouldBe 1
    FileUtils.readFileToString((outDir.toScala / "subfolder" / "test.txt").toJava, "UTF-8").trim shouldBe "test"
  }

  it should "unzip zip with one mapped entry in subfolder" in {
    unzipWithMappedFilePaths(getZipFile("one-entry-in-subfolder.zip"), outDir, Map("subfolder/test.txt" -> "renamed.txt")) shouldBe a[Success[_]]
    outDir.list().length shouldBe 1
    FileUtils.readFileToString((outDir.toScala / "renamed.txt").toJava, "UTF-8").trim shouldBe "test"
  }

  it should "unzip zip with several entries some in subfolders, some mapped" in {
    unzipWithMappedFilePaths(getZipFile("mix.zip"), outDir, Map("subfolder/test.txt" -> "renamed.txt", "subfolder2/subsubfolder/leaf.txt" -> "renamed2.txt")) shouldBe a[Success[_]]
    outDir.list().length shouldBe 3
    FileUtils.readFileToString((outDir.toScala / "root.txt").toJava, "UTF-8").trim shouldBe "in root"
    FileUtils.readFileToString((outDir.toScala / "renamed.txt").toJava, "UTF-8").trim shouldBe "test"
    FileUtils.readFileToString((outDir.toScala / "renamed2.txt").toJava, "UTF-8").trim shouldBe "in leaf"
  }

  "extractWithFilepathMapping" should "correctly unzip medium bag and leave it valid" in {
    extractWithFilepathMapping(getZipFile("medium.zip"), outDir, "dummyId")
  }

  it should "accept multiple payload files with the same checksum" in {
    extractWithFilepathMapping(getZipFile("double-image.zip"), outDir, "dummyId")
  }
}
