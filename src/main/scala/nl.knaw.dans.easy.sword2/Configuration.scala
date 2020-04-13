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
import java.net.{ URI, URL }
import java.nio.file.{ Files, Path, Paths }
import java.util.regex.Pattern

import javax.servlet.ServletException
import nl.knaw.dans.easy.sword2.properties.{ DepositMode, DepositPropertiesCompoundFactory, DepositPropertiesFileFactory, DepositPropertiesServiceFactory, GraphQLClient, HttpContext }
import nl.knaw.dans.lib.string._
import org.apache.commons.configuration.PropertiesConfiguration
import resource.managed
import scalaj.http.BaseHttp

import scala.io.Source
import scala.util.{ Success, Try }

case class Configuration(version: String, properties: PropertiesConfiguration) {
  private val bagStoreBaseUri = properties.getString("bag-store.base-url") // TODO: make File, check existence
  private val bagStoreBaseDir = properties.getString("bag-store.base-dir") // TODO: make File, check existence

  private val tempDir = new File(properties.getString("tempdir"))
  private val depositRootDir = new File(properties.getString("deposits.rootdir"))
  private val archivedDepositRootDir = properties.getString("deposits.archived-rootdir").toOption.map(new File(_)).filter(d => d.exists && d.canRead)
  implicit private val http: BaseHttp = HttpContext(version).Http
  private val propertiesFactory = DepositMode.withName(properties.getString("easy-deposit-properties.mode")) match {
    case DepositMode.FILE => new DepositPropertiesFileFactory(tempDir, depositRootDir, archivedDepositRootDir)
    case DepositMode.SERVICE => new DepositPropertiesServiceFactory(
      new GraphQLClient(
        url = new URL(properties.getString("easy-deposit-properties.url")),
        credentials = for {
          username <- Option(properties.getString("easy-deposit-properties.username"))
          password <- Option(properties.getString("easy-deposit-properties.password"))
        } yield (username, password),
        timeout = for {
          conn <- Option(properties.getInt("easy-deposit-properties.conn-timeout-ms"))
          read <- Option(properties.getInt("easy-deposit-properties.read-timeout-ms"))
        } yield (conn, read),
      ))
    case DepositMode.BOTH => new DepositPropertiesCompoundFactory
  }

  val settings: Settings = Settings(
    depositRootDir = depositRootDir,
    depositPermissions = properties.getString("deposits.permissions"),
    tempDir = tempDir,
    serviceBaseUrl = properties.getString("base-url"),
    collectionPath = properties.getString("collection.path"),
    auth = properties.getString("auth.mode") match {
      case "ldap" => LdapAuthSettings(new URI(properties.getString("auth.ldap.url")),
        properties.getString("auth.ldap.users.parent-entry"),
        properties.getString("auth.ldap.sword-enabled-attribute-name"),
        properties.getString("auth.ldap.sword-enabled-attribute-value"))
      case "single" => SingleUserAuthSettings(properties.getString("auth.single.user"), properties.getString("auth.single.password"))
      case _ => throw new RuntimeException(s"Invalid authentication settings: ${ properties.getString("auth.mode") }")
    },
    urlPattern = Pattern.compile(properties.getString("url-pattern")),
    bagStoreSettings = {
      if (!bagStoreBaseUri.isBlank || !bagStoreBaseDir.isBlank) {
        if (bagStoreBaseDir.isBlank) throw new RuntimeException("Only bag store base-url given, bag store base-directory missing")
        if (bagStoreBaseUri.isBlank) throw new RuntimeException("Only bag store base-directory given, bag store base-url missing")
        val baseDir = new File(bagStoreBaseDir)
        if (!baseDir.exists) throw new RuntimeException(s"Bag store base directory ${ baseDir.getAbsolutePath } doesn't exist")
        if (!baseDir.canRead) throw new RuntimeException(s"Bag store base directory ${ baseDir.getAbsolutePath } is not readable")
        Some(BagStoreSettings(bagStoreBaseDir, bagStoreBaseUri))
      }
      else Option.empty
    },
    supportMailAddress = properties.getString("support.mailaddress"),
    marginDiskSpace = properties.getLong("tempdir.margin-available-diskspace"),
    cleanup = State.values.toSeq
      .map(state => state -> Try(properties.getBoolean(s"cleanup.$state")))
      .collect { case (key, Success(cleanupSetting)) => key -> cleanupSetting }
      .toMap,
    rescheduleDelaySeconds = properties.getInt("reschedule-delay-seconds"),
    serverPort = properties.getInt("daemon.http.port"),
    depositPropertiesFactory = propertiesFactory,
  )

  if (!depositRootDir.canRead) throw new ServletException("Cannot read deposits dir")
  if (!tempDir.canRead) throw new ServletException("Cannot read tempdir")
}

object Configuration {

  def apply(home: Path): Configuration = {
    val cfgPath = Seq(
      Paths.get(s"/etc/opt/dans.knaw.nl/easy-sword2/"),
      home.resolve("cfg"))
      .find(Files.exists(_))
      .getOrElse { throw new IllegalStateException("No configuration directory found") }

    new Configuration(
      version = managed(Source.fromFile(home.resolve("bin/version").toFile)).acquireAndGet(_.mkString),
      properties = new PropertiesConfiguration() {
        // Needed, because we need values with comma's in them, such as LDAP names
        setDelimiterParsingDisabled(true)
        load(cfgPath.resolve("application.properties").toFile)
      }
    )
  }
}
