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
import ch.qos.logback.core.rolling.helper.FileStoreUtil
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.{FileUtils, IOUtils}

import java.io.{FileOutputStream, File => JFile}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.convert.Wrappers.JListWrapper
import scala.util.{Failure, Success, Try}

object BagExtractor extends DebugEnhancedLogging {

  def extractBag(depositDir: JFile, mimeType: MimeType)(implicit settings: Settings, id: DepositId): Try[JFile] = {
    lazy val files = depositDir.listFilesSafe.filter(isPartOfDeposit)
    (mimeType match {
      case "application/zip" => extractZip(files, depositDir)
      case "application/octet-stream" => extractOctetStream(files, depositDir)
      case _ => Failure(InvalidDepositException(id, s"Invalid content type: $mimeType"))
    })
      .map(_ => depositDir)
      .recoverWith { case e: ZipException => Failure(InvalidDepositException(id, s"Invalid bag: ${e.getMessage}")) }
  }

  private def extractZip(files: Array[JFile], depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    for (file <- files) {
      if (!file.isFile)
        throw InvalidDepositException(id, s"Inconsistent dataset: non-file object found: ${file.getName}")
      checkAvailableDiskspace(file)
        .flatMap(_ => extract(file, depositDir))
        .unsafeGetOrThrow
    }
  }

  private def extractOctetStream(files: Array[JFile], depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    val mergedZip = new JFile(depositDir, "merged.zip")
    for {
      _ <- checkDiskspaceForMerging(files)
      _ <- MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
      _ <- checkAvailableDiskspace(mergedZip)
      _ <- extract(mergedZip, depositDir)
    } yield ()
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

  private def extract(file: JFile, depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    implicit val charset: Charset = StandardCharsets.UTF_8
    import better.files._
    for {
      _ <-
        if (settings.filepathMappingDepositors.contains(id)) extractWithFilepathMapping(file, depositDir)
        else Try {
          file.toScala unzipTo depositDir.toScala
        }
      _ <- FilesPermission.changePermissionsRecursively(depositDir, settings.depositPermissions, id)
    } yield ()
  }

  def extractWithFilepathMapping(zipFile: JFile, depositDir: JFile): Try[Unit] = {
    for {
      mapping <- createFilePathMapping(zipFile, "data/")
//      _ <- unzipWithMappedFilePaths(zipFile, depositDir, mapping)
      // _ <- writeOriginalFilePaths(depositDir, mapping)
      // _ <- renamePayloadManifestEntries(depositDir, mapping)
      // _ <- addOriginalFilePathsToTagManifests(depositDir, mapping)
      // _ <- updateTagManifests(depositDir)
    } yield ()
  }

  /**
   * Creates a mapping from old to new name for file entries with the given prefix (e.g. "data/")
   *
   * @param zipFile      the zip file to create the mapping for
   * @param prefixFilter only create mappings for file entries whose name starts with this prefix
   * @return a map from old to new entry name
   */
  def createFilePathMapping(zipFile: JFile, prefixFilter: String): Try[Map[String, String]] = Try {
    ???
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
  def unzipWithMappedFilePaths(zip: JFile, outDir: JFile, mappedFilePaths: Map[String, String]): Unit = {
    FileUtils.forceMkdir(outDir)
    val zis = zip.toScala.newZipInputStream
    zis.mapEntries {
      e => {
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
    }.toList
  }

}
