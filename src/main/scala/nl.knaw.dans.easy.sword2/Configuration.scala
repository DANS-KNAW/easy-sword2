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

import nl.knaw.dans.lib.string._
import org.apache.commons.configuration.PropertiesConfiguration
import resource.managed

import java.io.{ File, FileInputStream }
import java.net.URI
import java.nio.file.{ Files, Path, Paths }
import java.util.Properties
import java.util.regex.Pattern
import javax.servlet.ServletException
import scala.io.Source
import scala.util.{ Success, Try }
import scala.collection.JavaConverters._

case class Configuration(version: String, properties: PropertiesConfiguration, usersPropertiesFile: String, users: Map[String, String]) {
  private val bagStoreBaseUri = properties.getString("bag-store.base-url") // TODO: make File, check existence
  private val bagStoreBaseDir = properties.getString("bag-store.base-dir") // TODO: make File, check existence

  val settings: Settings = Settings(
    depositRootDir = new File(properties.getString("deposits.rootdir")),
    archivedDepositRootDir = properties.getString("deposits.archived-rootdir").toOption.map(new File(_)).filter(d => d.exists && d.canRead),
    outboxDir = properties.getString("deposits.outbox").toOption.map(new File(_)).filter(d => d.exists() && d.canRead),
    depositPermissions = properties.getString("deposits.permissions"),
    tempDir = new File(properties.getString("tempdir")),
    serviceBaseUrl = properties.getString("base-url"),
    collectionPath = properties.getString("collection.path"),
    auth = properties.getString("auth.mode") match {
      case "ldap" => LdapAuthSettings(new URI(properties.getString("auth.ldap.url")),
        properties.getString("auth.ldap.users.parent-entry"),
        properties.getString("auth.ldap.sword-enabled-attribute-name"),
        properties.getString("auth.ldap.sword-enabled-attribute-value"))
      case "file" => FileAuthSettings(usersPropertiesFile, users)
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
  )

  if (!settings.depositRootDir.canRead) throw new ServletException("Cannot read deposits dir")
  if (!settings.tempDir.canRead) throw new ServletException("Cannot read tempdir")
}

object Configuration {

  def apply(home: Path): Configuration = {
    val cfgPath = Seq(
      Paths.get(s"/etc/opt/dans.knaw.nl/easy-sword2/"),
      home.resolve("cfg"))
      .find(Files.exists(_))
      .getOrElse { throw new IllegalStateException("No configuration directory found") }

    val version = managed(Source.fromFile(home.resolve("bin/version").toFile)).acquireAndGet(_.mkString)
    val properties = new PropertiesConfiguration() {
      // Needed, because we need values with comma's in them, such as LDAP names
      setDelimiterParsingDisabled(true)
      load(cfgPath.resolve("application.properties").toFile)
    }

    val usersPropertiesFile = "users.properties"
    val users = if (properties.getString("auth.mode") == "file")
                  new Properties() {
                    load(new FileInputStream(cfgPath.resolve(usersPropertiesFile).toString))
                  }.asScala.toMap
                else null

    new Configuration(
      version,
      properties,
      usersPropertiesFile,
      users
    )
  }
}
