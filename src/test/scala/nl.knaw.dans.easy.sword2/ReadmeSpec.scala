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

import java.io.{ ByteArrayOutputStream, File }

import org.apache.commons.configuration.PropertiesConfiguration

class ReadmeSpec extends TestSupportFixture with CustomMatchers {
  private val properties = new PropertiesConfiguration() {
    setProperty("daemon.http.port", 20100)
    setProperty("deposits.rootdir", (testDir / "deposits").createDirectoryIfNotExists().toString())
    setProperty("deposits.archived-rootdir", (testDir / "deposits-archived").createDirectoryIfNotExists().toString())
    setProperty("deposits.permissions", "rwxrwx---")
    setProperty("tempdir", (testDir / "temp").createDirectoryIfNotExists().toString())
    setProperty("tempdir.margin-available-diskspace", 2000000000L)
    setProperty("reschedule-delay-seconds", 30)
    setProperty("base-url", "http://localhost:20100/")
    setProperty("collection.path", "collection/1")
    setProperty("auth.mode", "ldap")
    setProperty("auth.ldap.url", "ldap://deasy.dans.knaw.nl")
    setProperty("auth.ldap.users.parent-entry", "ou=users,ou=easy,dc=dans,dc=knaw,dc=nl")
    setProperty("auth.ldap.sword-enabled-attribute-name", "easySwordDepositAllowed")
    setProperty("auth.ldap.sword-enabled-attribute-value", "TRUE")
    setProperty("url-pattern", "^$")
    setProperty("support.mailaddress", "info@yourdomain-here.com")
    setProperty("bag-store.base-url", "")
    setProperty("bag-store.base-dir", "")
    setProperty("auth.single.user", "")
    setProperty("auth.single.password", "")
    setProperty("cleanup.INVALID", "no")
    setProperty("cleanup.REJECTED", "no")
    setProperty("cleanup.FAILED", "no")
  }
  private val clo = new CommandLineOptions(Array[String](), Configuration("1.0.0-SNAPSHOT", properties, "users.properties", null)) {
    // avoids System.exit() in case of invalid arguments or "--help"
    override def verify(): Unit = {}
  }

  private val helpInfo = {
    val mockedStdOut = new ByteArrayOutputStream()
    Console.withOut(mockedStdOut) {
      clo.printHelp()
    }
    mockedStdOut.toString
  }

  "options in help info" should "be part of docs/index.md" in {
    val lineSeparators = s"(${ System.lineSeparator() })+"
    val options = helpInfo.split(s"${ lineSeparators }Options:$lineSeparators")(1)
    options.trim.length shouldNot be(0)
    new File("docs/index.md") should containTrimmed(options)
  }

  "synopsis in help info" should "be part of docs/index.md" in {
    new File("docs/index.md") should containTrimmed(clo.synopsis)
  }

  "description line(s) in help info" should "be part of docs/index.md and pom.xml" in {
    new File("docs/index.md") should containTrimmed(clo.description)
    new File("pom.xml") should containTrimmed(clo.description)
  }
}
