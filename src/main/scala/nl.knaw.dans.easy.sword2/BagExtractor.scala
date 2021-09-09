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

import better.files.{FileExtensions, ZipInputStreamExtensions}
import gov.loc.repository.bagit.transformer.impl.TagManifestCompleter
import gov.loc.repository.bagit.writer.impl.FileSystemWriter
import gov.loc.repository.bagit.{Bag, BagFactory}
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.{FileUtils, IOUtils}

import java.io.{FileOutputStream, File => JFile}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.regex.Pattern
import scala.collection.JavaConverters._
import scala.collection.convert.Wrappers.JListWrapper
import scala.util.{Failure, Success, Try}

object BagExtractor extends DebugEnhancedLogging {
  private val bagFactory: BagFactory = new BagFactory
  private lazy val prefixPattern = Pattern.compile("^[^/]+/data/")

  def extractBag(depositDir: JFile, mimeType: MimeType, depositor: String)(implicit settings: Settings, id: DepositId): Try[JFile] = {
    lazy val files = depositDir.listFilesSafe.filter(isPartOfDeposit)
    (mimeType match {
      case "application/zip" => extractZip(files, depositDir, depositor)
      case "application/octet-stream" => extractOctetStream(files, depositDir, depositor)
      case _ => Failure(InvalidDepositException(id, s"Invalid content type: $mimeType"))
    })
      .map(_ => depositDir)
      .recoverWith { case e: ZipException => Failure(InvalidDepositException(id, s"Invalid bag: ${e.getMessage}")) }
  }

  private def extractZip(files: Array[JFile], depositDir: JFile, depositor: String)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    for (file <- files) {
      if (!file.isFile)
        throw InvalidDepositException(id, s"Inconsistent dataset: non-file object found: ${file.getName}")
//      verifyValid(id, file).get
      checkAvailableDiskspace(file)
        .flatMap(_ => extract(file, depositDir, depositor))
        .unsafeGetOrThrow
    }
  }

  private def extractOctetStream(files: Array[JFile], depositDir: JFile, depositor: String)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    val mergedZip = new JFile(depositDir, "merged.zip")
    for {
      _ <- checkDiskspaceForMerging(files)
      _ <- MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
      _ <- checkAvailableDiskspace(mergedZip)
//      _ <- verifyValid(id, mergedZip)
      _ <- extract(mergedZip, depositDir, depositor)
    } yield ()
  }

  private def verifyValid(id: DepositId, zippedBag: JFile): Try[Unit] = {
    val result = bagFactory.createBag(zippedBag).verifyValid()
    if (result.isSuccess) Success(())
    else Failure(InvalidDepositException(id, result.messagesToString()))
  }

  private def checkDiskspaceForMerging(files: Seq[JFile])(implicit settings: Settings, id: DepositId): Try[Unit] = {
    files.headOption
      .map(f => {
        val availableDiskSize = Files.getFileStore(f.toPath).getUsableSpace
        val sumOfChunks = files.map(_.length).sum
        val required = sumOfChunks + settings.marginDiskSpace
        logger.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Sum of chunk sizes: $sumOfChunks bytes. Margin required: ${settings.marginDiskSpace} bytes.")
        if (sumOfChunks + settings.marginDiskSpace > availableDiskSize) {
          val diskSizeShort = sumOfChunks + settings.marginDiskSpace - availableDiskSize
          Failure(NotEnoughDiskSpaceException(id, s"Required disk space for concatenating: $required (including ${settings.marginDiskSpace} margin). Available: $availableDiskSize. Short: $diskSizeShort."))
        }
        else Success(())
      })
      .getOrElse(Success(()))
  }

  @throws[InvalidDepositException]
  private def getSequenceNumber(f: JFile)(implicit id: DepositId): Int = {
    try {
      val seqNumber = f.getName
        .split('.')
        .lastOption
        .getOrElse(throw InvalidDepositException(id, s"Partial file ${f.getName} has no extension. It should be a positive sequence number."))
        .toInt

      if (seqNumber > 0) seqNumber
      else throw InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. It should be a positive sequence number (> 0), but was: $seqNumber")
    }
    catch {
      case _: NumberFormatException =>
        throw InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. Should be a positive sequence number.")
    }
  }

  private def checkAvailableDiskspace(file: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    val zipFile = new ZipFile(file.getPath)
    val headers = zipFile.getFileHeaders.asScala.asInstanceOf[JListWrapper[FileHeader]] // Look out! Not sure how robust this cast is!
    val uncompressedSize = headers.map(_.getUncompressedSize).sum
    val availableDiskSize = Files.getFileStore(file.toPath).getUsableSpace
    val required = uncompressedSize + settings.marginDiskSpace
    logger.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Uncompressed bag size: $uncompressedSize bytes. Margin required: ${settings.marginDiskSpace} bytes.")
    if (uncompressedSize + settings.marginDiskSpace > availableDiskSize) {
      val diskSizeShort = uncompressedSize + settings.marginDiskSpace - availableDiskSize
      throw NotEnoughDiskSpaceException(id, s"Required disk space for unzipping: $required (including ${settings.marginDiskSpace} margin). Available: $availableDiskSize. Short: $diskSizeShort.")
    }
  }

  private def extract(file: JFile, depositDir: JFile, depositor: String)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    implicit val charset: Charset = StandardCharsets.UTF_8
    import better.files._
    for {
      _ <-
        if (settings.filepathMappingDepositors.contains(depositor)) extractWithFilepathMapping(file, depositDir)
        else Try {
          file.toScala unzipTo depositDir.toScala
        }
      _ <- FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
    } yield ()
  }

  def extractWithFilepathMapping(zipFile: JFile, depositDir: JFile): Try[Unit] = {
    for {
      mapping <- createFilePathMapping(zipFile, prefixPattern)
      _ <- unzipWithMappedFilePaths(zipFile, depositDir, mapping)
      bagDir <- findBagDir(depositDir)
      bagRelativeMapping <- toBagRelativeMapping(mapping, bagDir.getName)
      _ <- writeOriginalFilePaths(bagDir, bagRelativeMapping)
      _ <- renameAllPayloadManifestEntries(bagDir, bagRelativeMapping)
      _ <- updateTagManifests(bagDir)
    } yield ()
  }

  /**
   * Creates a mapping from old to new name for file entries with the given pattern
   *
   * @param zip           the zip file to create the mapping for
   * @param prefixPattern only create mappings for file entries whose name matches this pattern
   * @return a map from old to new entry name
   */
  def createFilePathMapping(zip: JFile, prefixPattern: Pattern): Try[Map[String, String]] = Try {
    val zis = zip.toScala.newZipInputStream
    zis.mapEntries(identity)
      .filterNot(_.isDirectory)
      .filter(e => prefixPattern.matcher(e.getName).find())
      .map {
        e =>
          val m = prefixPattern.matcher(e.getName)
          m.find()
          val prefix = m.group()
          (e.getName, Paths.get(prefix, UUID.randomUUID().toString).toString)
      }.toList.toMap
  }

  /**
   * Unzips file `zip` to directory `outDir`. Entries whose name is in the keys of mappedFilesPaths are stored under the name
   * stored in corresponding value. Other entries are stored under the name provided in the entry. Empty directories are not
   * recreated.
   *
   * @param zip             the zip file
   * @param outDir          the output directory
   * @param mappedFilePaths the mapping from old to new filepath
   */
  def unzipWithMappedFilePaths(zip: JFile, outDir: JFile, mappedFilePaths: Map[String, String]): Try[Unit] = Try {
    FileUtils.forceMkdir(outDir)
    val zis = zip.toScala.newZipInputStream
    zis.mapEntries {
      e => {
        if (!e.isDirectory) {
          val filePath =
            if (mappedFilePaths.keySet.contains(e.getName)) (outDir.toScala / mappedFilePaths(e.getName)).path
            else (outDir.toScala / e.getName).path
          FileUtils.forceMkdirParent(filePath.toFile)
          val newFile = Files.createFile(filePath)
          val fos = new FileOutputStream(newFile.toFile)
          try {
            IOUtils.copyLarge(zis, fos, 0, e.getSize)
          } finally {
            fos.close()
          }
        }
      }
    }.toList
  }

  def findBagDir(depositDir: JFile): Try[JFile] = Try {
    val dirs = depositDir.listFiles().filter(_.isDirectory)
    if (dirs.length != 1) throw new IllegalStateException(s"Deposit has ${dirs.length} directories, but have exactly 1")
    dirs.head
  }

  def toBagRelativeMapping(zipRelativeMapping: Map[String, String], bagName: String): Try[Map[String, String]] = Try {
    zipRelativeMapping.map {
      case (orgName, newName) => (Paths.get(bagName).relativize(Paths.get(orgName)).toString, Paths.get(bagName).relativize(Paths.get(newName)).toString)
    }
  }

  def writeOriginalFilePaths(bagDir: JFile, mappings: Map[String, String]): Try[Unit] = Try {
    FileUtils.write(new JFile(bagDir, "original-filepaths.txt"), mappings.map {
      case (orgName, newName) => s"$newName  $orgName"
    }.toList.mkString("\n"), StandardCharsets.UTF_8)
  }

  def renameAllPayloadManifestEntries(bagDir: JFile, mappings: Map[String, String]): Try[Unit] = Try {
    bagDir
      .listFiles()
      .filter(_.isFile)
      .filter(_.getName.startsWith("manifest-"))
      .map(renamePayloadManifestEntries(mappings))
  }

  def renamePayloadManifestEntries(mappings: Map[String, String])(manifestFile: JFile): Try[Unit] = {
    for {
      checksum2Path <- readManifest(manifestFile)
      newChecksum2Path = checksum2Path.map {
        case (cs, p) => (cs, mappings.getOrElse(p, p))
      }
      _ <- writeManifest(manifestFile, newChecksum2Path)
    } yield ()
  }

  def readManifest(manifestFile: JFile): Try[Map[String, String]] = Try {
    FileUtils.readFileToString(manifestFile, StandardCharsets.UTF_8).split("\n")
      .map(_.split("""\s+""", 2))
      .map {
        case Array(checksum, path) => (checksum, path)
      }.toMap
  }

  def writeManifest(manifestFile: JFile, map: Map[String, String]): Try[Unit] = Try {
    FileUtils.writeStringToFile(manifestFile, map.map {
      case (checksum, path) => s"$checksum  $path"
    }.mkString("\n"), StandardCharsets.UTF_8)
  }

  def updateTagManifests(bagDir: JFile): Try[Unit] = Try {
    getBag(bagDir)
      .map(bag => {
        bag.getTagManifests.asScala.map(_.getAlgorithm).foreach(a => {
          val completer = new TagManifestCompleter(bagFactory)
          completer.setTagManifestAlgorithm(a)
          completer complete bag
        })
        val writer = new FileSystemWriter(bagFactory)
        writer.setTagFilesOnly(true)
        bag.write(writer, bagDir)
      }).getOrElse(Success(()))
  }

  private def getBag(bagDir: JFile): Try[Bag] = Try {
    bagFactory.createBag(bagDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }
}
