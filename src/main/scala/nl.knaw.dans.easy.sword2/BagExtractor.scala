package nl.knaw.dans.easy.sword2

import java.io.{ File => JFile }
import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.Files

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging

import scala.collection.JavaConverters._
import scala.collection.convert.Wrappers.JListWrapper
import scala.util.{ Failure, Success, Try }

object BagExtractor extends DebugEnhancedLogging {

  def extractBag(depositDir: JFile, mimeType: MimeType)(implicit settings: Settings, id: DepositId): Try[JFile] = {
    lazy val files = depositDir.listFilesSafe.filter(isPartOfDeposit)
    (mimeType match {
      case "application/zip" => extractZip(files, depositDir)
      case "application/octet-stream" => extractOctetStream(files, depositDir)
      case _ => Failure(InvalidDepositException(id, s"Invalid content type: $mimeType"))
    })
      .map(_ => depositDir)
      .recoverWith { case e: ZipException => Failure(InvalidDepositException(id, s"Invalid bag: ${ e.getMessage }")) }
  }

  private def extractZip(files: Array[JFile], depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    for (file <- files) {
      if (!file.isFile)
        throw InvalidDepositException(id, s"Inconsistent dataset: non-file object found: ${ file.getName }")
      checkAvailableDiskspace(file)
        .map(_ => extract(file, depositDir.getPath))
        .unsafeGetOrThrow
    }
  }

  private def extractOctetStream(files: Array[JFile], depositDir: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    val mergedZip = new JFile(depositDir, "merged.zip")
    for {
      _ <- checkDiskspaceForMerging(files)
      _ <- MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
      _ <- checkAvailableDiskspace(mergedZip)
    } yield extract(mergedZip, depositDir.getPath)
  }

  private def checkDiskspaceForMerging(files: Seq[JFile])(implicit settings: Settings, id: DepositId): Try[Unit] = {
    files.headOption
      .map(f => {
        val availableDiskSize = Files.getFileStore(f.toPath).getUsableSpace
        val sumOfChunks = files.map(_.length).sum
        val required = sumOfChunks + settings.marginDiskSpace
        logger.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Sum of chunk sizes: $sumOfChunks bytes. Margin required: ${ settings.marginDiskSpace } bytes.")
        if (sumOfChunks + settings.marginDiskSpace > availableDiskSize) {
          val diskSizeShort = sumOfChunks + settings.marginDiskSpace - availableDiskSize
          Failure(NotEnoughDiskSpaceException(id, s"Required disk space for concatenating: $required (including ${ settings.marginDiskSpace } margin). Available: $availableDiskSize. Short: $diskSizeShort."))
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
        .getOrElse(throw InvalidDepositException(id, s"Partial file ${ f.getName } has no extension. It should be a positive sequence number."))
        .toInt

      if (seqNumber > 0) seqNumber
      else throw InvalidDepositException(id, s"Partial file ${ f.getName } has an incorrect extension. It should be a positive sequence number (> 0), but was: $seqNumber")
    }
    catch {
      case _: NumberFormatException =>
        throw InvalidDepositException(id, s"Partial file ${ f.getName } has an incorrect extension. Should be a positive sequence number.")
    }
  }

  private def checkAvailableDiskspace(file: JFile)(implicit settings: Settings, id: DepositId): Try[Unit] = Try {
    val zipFile = new ZipFile(file.getPath)
    val headers = zipFile.getFileHeaders.asScala.asInstanceOf[JListWrapper[FileHeader]] // Look out! Not sure how robust this cast is!
    val uncompressedSize = headers.map(_.getUncompressedSize).sum
    val availableDiskSize = Files.getFileStore(file.toPath).getUsableSpace
    val required = uncompressedSize + settings.marginDiskSpace
    logger.debug(s"[$id] Available (usable) disk space currently $availableDiskSize bytes. Uncompressed bag size: $uncompressedSize bytes. Margin required: ${ settings.marginDiskSpace } bytes.")
    if (uncompressedSize + settings.marginDiskSpace > availableDiskSize) {
      val diskSizeShort = uncompressedSize + settings.marginDiskSpace - availableDiskSize
      throw NotEnoughDiskSpaceException(id, s"Required disk space for unzipping: $required (including ${ settings.marginDiskSpace } margin). Available: $availableDiskSize. Short: $diskSizeShort.")
    }
  }

  private def extract(file: JFile, outputPath: String): Unit = {
    implicit val charset: Charset = StandardCharsets.UTF_8
    import better.files._
    file.toScala unzipTo outputPath.toFile
  }
}
