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
import java.net.URI
import java.util.regex.Pattern
import javax.servlet.ServletException

import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.commons.lang.StringUtils.{ isBlank, isNotBlank }

trait ApplicationSettings {
  protected val properties = new PropertiesConfiguration()
  properties.setDelimiterParsingDisabled(true)
  properties.load(new File(new File(System.getProperty("app.home")), "cfg/application.properties"))
  val depositRootDir = new File(properties.getString("deposits.rootdir"))
  if (!depositRootDir.canRead) throw new ServletException("Cannot read deposits dir")
  val depositPermissions = properties.getString("deposits.permissions")
  val tempDir = new File(properties.getString("tempdir"))
  if (!tempDir.canRead) throw new ServletException("Cannot read tempdir")
  val baseUrl = properties.getString("base-url")
  val collectionPath = properties.getString("collection.path")
  val auth = properties.getString("auth.mode") match {
    case "ldap" => LdapAuthSettings(new URI(properties.getString("auth.ldap.url")),
      properties.getString("auth.ldap.users.parent-entry"),
      properties.getString("auth.ldap.sword-enabled-attribute-name"),
      properties.getString("auth.ldap.sword-enabled-attribute-value"))
    case "single" => SingleUserAuthSettings(properties.getString("auth.single.user"), properties.getString("auth.single.password"))
    case _ => throw new RuntimeException(s"Invalid authentication settings: ${ properties.getString("auth.mode") }")
  }
  val urlPattern = Pattern.compile(properties.getString("url-pattern"))
  val bagStoreBaseUri = properties.getString("bag-store.base-url") // TODO: make File, check existence
  val bagStoreBaseDir = properties.getString("bag-store.base-dir") // TODO: make File, check existence
  var bagStoreSettings = Option.empty[BagStoreSettings]
  if (isNotBlank(bagStoreBaseUri) || isNotBlank(bagStoreBaseDir)) {
    if (isBlank(bagStoreBaseDir)) throw new RuntimeException("Only bag store base-url given, bag store base-directory missing")
    if (isBlank(bagStoreBaseUri)) throw new RuntimeException("Only bag store base-directory given, bag store base-url missing")
    val baseDir = new File(bagStoreBaseDir)
    if (!baseDir.exists) throw new RuntimeException(s"Bag store base directory ${ baseDir.getAbsolutePath } doesn't exist")
    if (!baseDir.canRead) throw new RuntimeException(s"Bag store base directory ${ baseDir.getAbsolutePath } is not readable")
    bagStoreSettings = Some(BagStoreSettings(bagStoreBaseDir, bagStoreBaseUri))
  }
  val supportMailAddress = properties.getString("support.mailaddress")
  val marginDiskSpace: Long = properties.getLong("tempdir.margin-available-diskspace-mb") * 1024 * 1024
}
