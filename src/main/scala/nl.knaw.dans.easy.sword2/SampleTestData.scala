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
import java.nio.file.{ Files, Path }

import nl.knaw.dans.easy.sword2.DepositHandler.log
import nl.knaw.dans.lib.logging.DebugEnhancedLogging

import scala.util.{ Success, Try }

object SampleTestData extends DebugEnhancedLogging {

  def sampleData(id: String, depositDir: File, depositProperties: DepositProperties)(implicit settings: SampleTestDataSettings): Try[Unit] = {
    trace(depositDir, depositProperties.getDepositorId, settings)
    depositDir.list().foreach(debug)
    settings match {
      case SampleTestDataDisabled => Success(()) // move on, sampling is disabled
      case SampleTestDataEnabled(sampleDir, rates) =>
        depositProperties.getDepositorId
          .map(rates.get)
          .flatMap {
            case Some(rate) if math.random() < rate => doSampling(id, depositDir, sampleDir.toPath)
            case Some(_) => Success(()) // not sampling this deposit
            case None => Success(()) // no rate specified for this user
          }
    }
  }

  private def doSampling(id: String, depositDir: File, sampleDir: Path): Try[Unit] = {
    logger.info(s"sample triggered for $depositDir with id $id")

    copyZipFiles(depositDir, Files.createDirectory(sampleDir.resolve(id)))
  }

  private def copyZipFiles(depositDir: File, sampleDir: Path): Try[Unit] = Try {
    log.debug(s"Copying zip files to $sampleDir")
    for (file <- depositDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      val sampleFile = sampleDir.resolve(file.getName)
      log.debug(s"copy $file to $sampleFile")
      Files.copy(file.toPath, sampleFile)
    }
  }
}
