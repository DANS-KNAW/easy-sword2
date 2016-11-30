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

import java.io.{File, IOException}
import nl.knaw.dans.api.sword2.State._
import org.apache.commons.configuration.PropertiesConfiguration
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import scala.util.Try

case class DepositProperties(label: String, description: String, timeStamp: String)

object DepositProperties {
  val log = LoggerFactory.getLogger(getClass)

  def set(id: String, stateLabel: State, stateDescription: String, userId: Option[String] = None, lookInTempFirst: Boolean = false, throwable: Throwable = null)(implicit settings: Settings): Try[Unit] = Try {
    val depositDir = new File(if (lookInTempFirst) settings.tempDir
                              else settings.depositRootDir, id)
    val props = readPropertiesConfiguration(new File(depositDir, "deposit.properties"))
    props.setProperty("state.label", stateLabel)
    props.setProperty("state.description",
      s"""
        |$stateDescription
        |${if(throwable != null) throwable.getMessage else ""}
      """.stripMargin.trim)
    userId.foreach(uid => props.setProperty("depositor.userId", uid))
    props.save()
  }

  def getState(id: String)(implicit settings: Settings): Try[String] = {
    log.debug(s"[$id] Trying to retrieve deposit state")
    getProperties(id).map(_.label)
  }

  def getProperties(id: String)(implicit settings: Settings): Try[DepositProperties] = {
    log.debug(s"[$id] Trying to retrieve deposit properties")
    readProperties(id, new File(settings.tempDir, s"$id/deposit.properties")).recoverWith {
      case f: IOException => readProperties(id, new File(settings.depositRootDir, s"$id/deposit.properties"))
    }
  }

  private def readProperties(id: String, f: File): Try[DepositProperties] = Try {
    val ps = readPropertiesConfiguration(f)
    log.debug(s"[$id] Trying to retrieve state from $f")
    if(!f.exists()) throw new IOException(s"$f does not exist")
    val state = Option(ps.getString("state.label")).getOrElse("")
    val userId = Option(ps.getString("depositor.userId")).getOrElse("")
    if(state.isEmpty || userId.isEmpty) {
      if (state.isEmpty) log.error(s"[$id] State not present in $f")
      if (userId.isEmpty) log.error(s"[$id] User ID not present in $f")
      DepositProperties(FAILED.toString, "There occured unexpected failure in deposit", new DateTime(ps.getFile.lastModified()).withZone(DateTimeZone.UTC).toString)
    }
    else
      DepositProperties(state, ps.getString("state.description"), new DateTime(ps.getFile.lastModified()).withZone(DateTimeZone.UTC).toString)
  }

  private def readPropertiesConfiguration(f: File) = {
    val ps = new PropertiesConfiguration()
    ps.setDelimiterParsingDisabled(true)
    if(f.exists) ps.load(f)
    ps.setFile(f)
    ps
  }
}
