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

import java.io.{ File => JFile }

import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.FileUtils

import scala.util.{ Success, Try }

object DepositCleaner extends DebugEnhancedLogging {

  def cleanupFiles(depositDir: JFile, state: State)(implicit settings: Settings, id: DepositId): Try[Unit] = {
    if (settings.cleanup.getOrElse(state, false)) {
      logger.info(s"[$id] cleaning up zip files and bag directory for deposit due to state $state")
      for {
        _ <- removeZipFiles(depositDir)
        bagDir <- getBagDir(depositDir)
        _ <- Try {
          if (bagDir.exists()) {
            debug(s"[$id] removing bag $bagDir")
            FileUtils.deleteQuietly(bagDir)
          }
          else {
            debug(s"[$id] bag did not exist; no removal necessary")
          }
        }
      } yield ()
    }
    else
      Success(())
  }

  def removeZipFiles(depositDir: JFile)(implicit id: DepositId): Try[Unit] = Try {
    debug(s"[$id] removing zip files")
    for (file <- depositDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      debug(s"[$id] removing $file")
      FileUtils.deleteQuietly(file)
    }
  }

  private def getBagDir(depositDir: JFile): Try[JFile] = Try {
    val depositFiles = depositDir.listFiles.filter(_.isDirectory)
    if (depositFiles.length != 1) throw InvalidDepositException(depositDir.getName, s"A deposit package must contain exactly one top-level directory, number found: ${ depositFiles.length }")
    depositFiles(0)
  }
}
