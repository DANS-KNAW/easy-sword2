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
package nl.knaw.dans.api.sword2.servlets

import java.io.File
import java.net.URI
import java.util.regex.Pattern
import javax.servlet.{ServletContextEvent, ServletContextListener, ServletException}

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import nl.knaw.dans.api.sword2.{BagStoreSettings, DepositHandler, LdapAuthSettings, Settings, SingleUserAuthSettings}
import org.apache.commons.configuration.PropertiesConfiguration
import org.slf4j.LoggerFactory

class ServiceInitializer extends ServletContextListener {
  override def contextInitialized(sce: ServletContextEvent) = {
    println("Starting initialization")
    val home = if(sce.getServletContext.getInitParameter("EASY_SWORD2_HOME") != null) sce.getServletContext.getInitParameter("EASY_SWORD2_HOME")
               else System.getenv("EASY_SWORD2_HOME")
    if(home == null) throw new RuntimeException("EASY_SWORD2_HOME not specified. Specify through servlet init params or environment variable")
    val homeDir = new File(home)
    initLogging(homeDir)
    val settings = readSettings(homeDir)
    val log = LoggerFactory.getLogger(getClass)
    log.info(s"Using the following settings: $settings")
    sce.getServletContext.setAttribute(EASY_SWORD2_SETTINGS_ATTRIBUTE_KEY, settings)
    log.info("Starting deposit processing thread ...")
    DepositHandler.startDepositProcessingStream(settings)
    log.info("Initialization completed.")
  }

  def initLogging(homeDir: File): Unit = {
    try {
      LoggerFactory.getILoggerFactory match {
        case lc: LoggerContext =>
          val logConfigFile = new File(homeDir, "cfg/logback.xml")
          val configurator = new JoranConfigurator
          configurator.setContext(lc)
          lc.reset()
          configurator.doConfigure(logConfigFile)
          val log = LoggerFactory.getLogger(getClass)
          log.info(s"Home directory = $homeDir")
          log.info(s"Logback configuration file = $logConfigFile")
        case _ => println("Logback not found. Could not configure logging")
      }
    } catch {
      // Printing this, because servlet container log message is sometimes very terse
      case t: Throwable => println(s"Error during logging configuration $t"); throw t
    }
  }
  def readSettings(homeDir: File): Settings = {
    val config = {
      val ps = new PropertiesConfiguration()
      ps.setDelimiterParsingDisabled(true)
      ps.load(new File(homeDir, "cfg/application.properties"))
      ps
    }
    val depositRootDir = new File(config.getString("deposits.rootdir"))
    if (!depositRootDir.canRead) throw new ServletException("Cannot read deposits dir")
    val depositPermissions = config.getString("deposits.permissions")
    val tempDir = new File(config.getString("tempdir"))
    if (!tempDir.canRead) throw new ServletException("Cannot read tempdir")
    val baseUrl = config.getString("base-url")
    val collectionIri = config.getString("collection.iri")
    val auth = config.getString("auth.mode") match {
      case "ldap" => LdapAuthSettings(new URI(config.getString("auth.ldap.url")),
        config.getString("auth.ldap.users.parent-entry"),
        config.getString("auth.ldap.sword-enabled-attribute-name"),
        config.getString("auth.ldap.sword-enabled-attribute-value"))
      case "single" => SingleUserAuthSettings(config.getString("auth.single.user"), config.getString("auth.single.password"))
      case _ => throw new RuntimeException(s"Invalid authentication settings: ${config.getString("auth.mode")}")
    }
    val urlPattern = Pattern.compile(config.getString("url-pattern"))
    val bagStoreBaseUri = config.getString("bag-store.base-url") // TODO: make File, check existence
    val bagStoreBaseDir = config.getString("bag-store.base-dir") // TODO: make File, check existence
    var bagStoreSettings = None: Option[BagStoreSettings]
    if (bagStoreBaseUri.trim.nonEmpty || bagStoreBaseDir.trim.nonEmpty) {
      if (bagStoreBaseDir.trim.isEmpty) throw new RuntimeException("Only bag store base-url given, bag store base-directory missing")
      if (bagStoreBaseUri.trim.isEmpty) throw new RuntimeException("Only bag store base-directory given, bag store base-url missing")
      bagStoreSettings = Some(BagStoreSettings(bagStoreBaseDir, bagStoreBaseUri))
    }

    val supportMailAddress = config.getString("support.mailaddress")
    Settings(
      depositRootDir, depositPermissions, tempDir, baseUrl, collectionIri, auth, urlPattern,
      bagStoreSettings, supportMailAddress)
  }

  override def contextDestroyed(sce: ServletContextEvent): Unit = {}
}


