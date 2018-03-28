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
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, NoSuchFileException, Path, Paths }
import java.security.MessageDigest
import java.util.UUID

import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.ZipParameters
import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.FileUtils

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }
import scala.xml.XML

object SampleTestData extends DebugEnhancedLogging {

  def sampleData(id: String, depositDir: File, depositProperties: DepositProperties)(implicit settings: SampleTestDataSettings): Try[Unit] = {
    trace(depositDir, depositProperties.getDepositorId, settings)
    val result = settings match {
      case SampleTestDataDisabled => sampleDataDisabled() // move on, sampling is disabled
      case SampleTestDataEnabled(sampleDir, rates) => sampleDataEnabled(depositProperties, id, depositDir, sampleDir, rates)
    }

    result.recoverWith {
      case e =>
        logger.error(s"[$id] Failed to sample test data; error is discarded", e)
        Success(())
    }
  }

  private def sampleDataDisabled(): Try[Unit] = Success(())

  private def sampleDataEnabled(depositProperties: DepositProperties,
                                id: String,
                                depositDir: File,
                                sampleDir: File,
                                rates: Map[String, Double]): Try[Unit] = {
    depositProperties.getDepositorId
      .map(rates.get)
      .flatMap {
        case Some(rate) if math.random() < rate => doSampling(depositDir, sampleDir.toPath)(depositProperties, id)
        case Some(_) => skipSampling(id) // not sampling this deposit
        case None => Success(()) // no rate specified for this user
      }
  }

  private def skipSampling(id: String): Try[Unit] = {
    logger.info(s"[$id] Skip sampling")
    Success(())
  }

  private def doSampling(depositDir: File, sampleDir: Path)(implicit depositProperties: DepositProperties, originalId: String): Try[Unit] = {
    logger.info(s"[$originalId] Sampling triggered")
    for {
      // new UUID: we don't want to confuse it with the original deposit
      sampleDirWithId <- Try { Files.createDirectory(sampleDir.resolve(UUID.randomUUID().toString)) }
      zipFiles <- copyZipFile(originalId, depositDir, sampleDirWithId)
      zipFile <- onlyOneZipFileFound(zipFiles)
      bag <- extractBagFromZip(zipFile, sampleDirWithId)
      sensitiveData <- handleSensitiveData(bag, zipFile)
      _ <- deleteExtractedBag(bag.getParent)
      _ <- writeReadme(originalId, sampleDirWithId, sensitiveData)
    } yield ()
  }

  private def copyZipFile(originalId: String, depositDir: File, sampleDir: Path)(implicit depositProperties: DepositProperties): Try[Array[Path]] = Try {
    logger.info(s"[$originalId] Copying zip file from $depositDir to $sampleDir")

    for {
      file <- depositDir.listFilesSafe
      if isPartOfDeposit(file)
      if file.isFile
      sampleFile = sampleDir.resolve(file.getName)
      _ = debug(s"copy $file to $sampleFile")
      _ = Files.copy(file.toPath, sampleFile)
    } yield sampleFile
  }

  private def onlyOneZipFileFound(paths: Array[Path]): Try[Path] = {
    paths.toList match {
      case Nil => Failure(new NoSuchFileException("no zips found"))
      case zip :: Nil => Success(zip)
      case zips => Failure(new NoSuchFileException(s"multiple files found: ${ zips.mkString("[", ", ", "]") }"))
    }
  }

  private def extractBagFromZip(zip: Path, targetDir: Path): Try[Path] = Try {
    val extractSampleDir = targetDir.resolve("unzipped")
    debug(s"extracting $zip to $extractSampleDir")

    new ZipFile(zip.toString) {
      setFileNameCharset(StandardCharsets.UTF_8.name)
    }.extractAll(extractSampleDir.toString)

    if (logger.underlying.isDebugEnabled)
      debug(s"extracted $zip into $extractSampleDir: ${ extractSampleDir.toFile.listFilesSafe.mkString("[", ", ", "]") }")

    extractSampleDir.toFile.listFilesSafe.toList match {
      case Nil => Failure(new NoSuchFileException("no directory found as the unzipped bag"))
      case file :: Nil if file.isDirectory => Success(file.toPath)
      case file :: Nil => Failure(new NoSuchFileException(s"no directory found as the unzipped bag; only a file: $file"))
      case files => Failure(new NoSuchFileException(s"multiple directory found as the unzipped bag: ${ files.mkString("[", ", ", "]") }"))
    }
  }.flatten

  private def handleSensitiveData(bag: Path, zipFile: Path)(implicit originalId: String): Try[Seq[Path]] = {
    findSensitiveData(bag)
      .flatMap {
        case Seq() => Success(Seq.empty)
        case sensitiveData => replaceSensitiveData(bag, sensitiveData)
          .flatMap(_ => zipBag(bag, zipFile))
          .map(_ => sensitiveData)
      }
  }

  // returns a list of RELATIVE paths of files that contain sensitive data
  private def findSensitiveData(bag: Path)(implicit originalId: String): Try[Seq[Path]] = {
    trace(bag)
    val filesXml = bag.resolve("metadata").resolve("files.xml")
    for {
      xml <- Try { XML.loadFile(filesXml.toFile) }
      sensitiveData = (xml \ "file")
        .map(file => (file \@ "filepath") -> Option(file \ "visibleToRights").filter(_.nonEmpty).map(_.text))
        .collect { case (filepath, Some("NONE")) => Paths.get(filepath) }
      _ = logger.info(s"[$originalId] found sensitive data: ${ sensitiveData.mkString("[", ", ", "]") }")
      result <- {
        val notExistingPaths = sensitiveData.map(bag.resolve).filter(!Files.exists(_))
        if (notExistingPaths.isEmpty) Success(sensitiveData)
        else Failure(new NoSuchFileException(s"Found several files containing sensitive data, but some of them do not exist: ${ notExistingPaths.mkString("[", ", ", "]") }"))
      }
    } yield result
  }

  private def replaceSensitiveData(bag: Path, sensitiveData: Seq[Path])(implicit originalId: String): Try[Unit] = {
    trace(bag, sensitiveData)

    for {
      _ <- sensitiveData.map(file => writeStubData(bag.resolve(file))).collectResults
      changedManifests <- updateManifests(bag, "manifest", sensitiveData)
      _ = logger.debug(s"changed manifest files: ${ changedManifests.mkString("[", ", ", "]") }")
      changedTagmanifests <- updateManifests(bag, "tagmanifest", changedManifests)
      _ = logger.debug(s"changed tagmanifest files: ${ changedTagmanifests.mkString("[", ", ", "]") }")
    } yield ()
  }

  private def writeStubData(path: Path)(implicit originalId: String): Try[Unit] = Try {
    logger.info(s"[$originalId] replace $path containing sensitive data with a stub text")
    Files.write(path, "This file contained sensitive data. We replaced it with this stub text.".getBytes(StandardCharsets.UTF_8))
  }

  private def updateManifests(bag: Path, name: String, paths: Seq[Path]): Try[Seq[Path]] = {
    trace(bag, name, paths)
    findManifest(bag, name)
      .flatMap(manifests => manifests.map {
        case (manifest, algorithm) =>
          readManifest(manifest)
            .map(content => {
              var changed = false

              val newContent = paths.foldLeft(content) {
                case (ct, path) if ct contains path =>
                  val checksum = calculateChecksum(bag.resolve(path), algorithm).unsafeGetOrThrow
                  if (!ct.get(path).contains(checksum)) {
                    changed = true
                    ct.updated(path, checksum)
                  }
                  else ct
                case (ct, _) => ct
              }

              if (changed) logger.debug(s"manifest $manifest changed")
              else logger.debug(s"manifest $manifest not changed")

              (newContent, changed)
            })
            .flatMap {
              case (nc, true) => writeManifest(manifest, nc).map(_ => Some(bag.relativize(manifest)))
              case (_, false) => Success(None)
            }
      }.collectResults.map(_.flatten.toSeq))
  }

  private def findManifest(bag: Path, name: String): Try[Map[Path, String]] = Try {
    bag.toFile.listFilesSafe
      .withFilter(_.getName startsWith s"$name-")
      .withFilter(_.getName endsWith ".txt")
      .map(manifest => manifest.toPath -> manifest.getName.stripPrefix(s"$name-").stripSuffix(".txt"))
      .toMap
  }

  private def readManifest(manifest: Path): Try[Map[Path, String]] = Try {
    trace(manifest)
    Files.readAllLines(manifest, StandardCharsets.UTF_8).asScala
      .map(s => s.split("  ", 2))
      .map { case Array(checksum, path) => Paths.get(path) -> checksum }
      .toMap
  }

  private def writeManifest(manifest: Path, checksums: Map[Path, String]): Try[Unit] = Try {
    trace(manifest, checksums)
    val content = checksums.map { case (path, checksum) => s"$checksum  $path" }.asJava
    Files.write(manifest, content, StandardCharsets.UTF_8)
  }

  private def calculateChecksum(file: Path, algorithm: String): Try[String] = {
    trace(file, algorithm)
    toAlgorithm(algorithm)
      .map(algo => {
        debug(s"using algorithm $algo to digest $file")
        MessageDigest.getInstance(algo)
          .digest(Files.readAllBytes(file))
          .map("%02x".format(_))
          .mkString
      })
  }

  private def toAlgorithm(name: String): Try[String] = {
    name match {
      case "md5" => Success("MD5")
      case "sha1" => Success("SHA-1")
      case "sha256" => Success("SHA-256")
      case "sha512" => Success("SHA-512")
      case _ => Failure(new NoSuchElementException("invalid algorithm found"))
    }
  }

  private def zipBag(bag: Path, target: Path)(implicit originalId: String): Try[Unit] = Try {
    logger.info(s"[$originalId] zipping sanitized bag $bag to $target")

    Files.deleteIfExists(target)

    val zip = new ZipFile(target.toFile) {
      setFileNameCharset(StandardCharsets.UTF_8.name)
    }
    zip.addFolder(bag.toFile, new ZipParameters)
  }

  private def deleteExtractedBag(path: Path)(implicit originalId: String) = Try {
    logger.info(s"[$originalId] deleting extracted bag")
    FileUtils.deleteDirectory(path.toFile)
  }

  private def writeReadme(originalId: String, sampleDir: Path, sensitiveFiles: Seq[Path])(implicit depositProperties: DepositProperties): Try[Unit] = {
    for {
      depositorId <- depositProperties.getDepositorId
      state <- depositProperties.getState
      description <- depositProperties.getStateDescription
    } yield {
      val lastModified = depositProperties.getLastModifiedTimestamp
      val content = readmeContent(originalId, depositorId, state, description, lastModified, sensitiveFiles)
      val readme = sampleDir.resolve("README.md")
      debug(
        s"""writing README content to $readme:
           |$content""".stripMargin)
      Files.write(readme, content.getBytes(StandardCharsets.UTF_8))
    }
  }

  private def readmeContent(originalId: String,
                            depositorId: String,
                            state: State,
                            description: String,
                            lastModified: Option[FileTime],
                            sensitiveFiles: Seq[Path]) = {
    val sensitive = if (sensitiveFiles.isEmpty) ""
                    else
                      s"""## Sensitive files
                         |The following files were replaces with stub files because they contain sensitive data:
                         |${ sensitiveFiles.map(file => s"* $file").mkString("\n") }
                         |""".stripMargin

    s"""# Deposit info
       |
       |This deposit was sampled by `easy-sword2` and can be used as test data.
       |
       |## Deposit information
       |**original id:** $originalId
       |**depositor:** $depositorId
       |**state:** $state
       |**description:** $description
       |**last modified:** ${ lastModified.getOrElse("<unknown>") }
       |
       |$sensitive
       |## Notes from the reviewer
       |* _remarks on this deposit_
       |* _what is the expected outcome of submitting this deposit?_
       |* _why is this deposit interesting?_
       |* _what is special about the (meta)data?_
       |""".stripMargin
  }
}
