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
import java.nio.file.{ Files, Path }
import java.util.UUID

import nl.knaw.dans.easy.sword2.State.State
import nl.knaw.dans.lib.logging.DebugEnhancedLogging

import scala.util.{ Success, Try }

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
        case Some(rate) if math.random() < rate => doSampling(id, depositDir, sampleDir.toPath)(depositProperties)
        case Some(_) => skipSampling(id) // not sampling this deposit
        case None => Success(()) // no rate specified for this user
      }
  }

  private def skipSampling(id: String): Try[Unit] = {
    logger.info(s"[$id] Skip sampling")
    Success(())
  }

  private def doSampling(originalId: String, depositDir: File, sampleDir: Path)(implicit depositProperties: DepositProperties): Try[Unit] = {
    logger.info(s"[$originalId] Sampling triggered")
    // new UUID: we don't want to confuse it with the original deposit
    val sampleDirWithId = Files.createDirectory(sampleDir.resolve(UUID.randomUUID().toString))
    for {
      _ <- copyZipFiles(originalId, depositDir, sampleDirWithId)
      _ <- writeReadme(originalId, sampleDirWithId)
    } yield ()
  }

  private def copyZipFiles(originalId: String, depositDir: File, sampleDir: Path)(implicit depositProperties: DepositProperties): Try[Unit] = Try {
    logger.info(s"[$originalId] Copying zip file(s) from $depositDir to $sampleDir")
    for (file <- depositDir.listFiles().toList
         if isPartOfDeposit(file)
         if file.isFile) {
      val sampleFile = sampleDir.resolve(file.getName)
      debug(s"copy $file to $sampleFile")
      Files.copy(file.toPath, sampleFile)
    }
  }

  private def writeReadme(originalId: String, sampleDir: Path)(implicit depositProperties: DepositProperties): Try[Unit] = {
    for {
      depositorId <- depositProperties.getDepositorId
      state <- depositProperties.getState
      description <- depositProperties.getStateDescription
    } yield {
      val lastModified = depositProperties.getLastModifiedTimestamp
      val content = readmeContent(originalId, depositorId, state, description, lastModified)
      val readme = sampleDir.resolve("README.md")
      debug(
        s"""writing README content to $readme:
           |$content""".stripMargin)
      Files.write(readme, content.getBytes(StandardCharsets.UTF_8))
    }
  }

  private def readmeContent(originalId: String, depositorId: String, state: State, description: String, lastModified: Option[FileTime]) = {
    s"""# Deposit info
       |
       |This deposit was sampled by easy-sword2 and can be used as test data.
       |
       |## Deposit information
       |**original id:** $originalId
       |**depositor:** $depositorId
       |**state:** $state
       |**description:** $description
       |**last modified:** ${lastModified.getOrElse("<unknown>")}
       |
       |## Notes from the reviewer
       |* _remarks on this deposit_
       |* _what is the expected outcome of submitting this deposit?_
       |* _why is this deposit interesting?_
       |* _what is special about its (meta)data?_
       |* _did this deposit contain sensitive data (NO_ACCESS file access) that is stubbed by the reviewer?_
       |""".stripMargin
  }
}
