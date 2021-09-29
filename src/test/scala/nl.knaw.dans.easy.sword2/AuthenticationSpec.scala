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

import java.io.File
import java.net.URI
import java.util.regex.Pattern

import javax.naming.AuthenticationException
import javax.naming.directory.{ Attribute, Attributes }
import javax.naming.ldap.LdapContext
import nl.knaw.dans.easy.sword2.Authentication._
import org.scalamock.scalatest.MockFactory
import org.scalatest.OneInstancePerTest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.swordapp.server.{ AuthCredentials, SwordAuthException }
import resource.{ ManagedResource, managed }

import scala.util.{ Failure, Success, Try }

class AuthenticationSpec extends AnyFlatSpec with Matchers with MockFactory with OneInstancePerTest {

  implicit val settings: Settings = Settings(
    depositRootDir = new File("dummy"),
    archivedDepositRootDir = Option(new File("dummy")),
    outboxDir = None,
    depositPermissions = "dummy",
    tempDir = new File("dummy"),
    serviceBaseUrl = "dummy",
    collectionPath = "dummy",
    auth = LdapAuthSettings(new URI("ldap://localhost"), "ou=easy,dc=dans,dc=knaw,dc=nl", "enabled", "true"),
    urlPattern = Pattern.compile("dummy"),
    bagStoreSettings = None,
    supportMailAddress = "dummy",
    marginDiskSpace = 0,
    cleanup = Map.empty,
    rescheduleDelaySeconds = 0,
    serverPort = 12345,
  )

  private val ldapContext = mock[LdapContext]
  private val attributes = mock[Attributes]
  private val swordEnabledAttribute = mock[Attribute]

  private implicit def getMockedLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[ManagedResource[LdapContext]] = Try {
    managed(ldapContext)
  }

  (ldapContext.getAttributes(_: String)) expects * anyNumberOfTimes() returning attributes
  ldapContext.close _ expects() anyNumberOfTimes()

  private def expectSwordEnabledAttributePresent(present: Boolean) =
    (attributes.get(_: String)) expects "enabled" anyNumberOfTimes() returning (if (present) swordEnabledAttribute
                                                                                else null)

  private def expectNumberOfSwordEnabledAttributeValues(n: Int) = swordEnabledAttribute.size _ expects() anyNumberOfTimes() returning n

  private def expectSwordEnabledAttributeValue(value: String) = (swordEnabledAttribute.get(_: Int)) expects 0 anyNumberOfTimes() returning value

  val command = "echo -n 'SomePassword' | openssl sha1 -hmac 'someUserNameAsSalt' -binary | base64"
  val output = "WjYViDQOdGR8V1kkTs900ZfoLXU="

  "checkAuthentication" should "return Success if correct credentials + swordEnabled attribute are provided " in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")

    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null)) shouldBe a[Success[_]]
  }

  it should "return Failure if swordEnabled set to false" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("false")

    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null)) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Failure if swordEnabled is not set" in {
    expectSwordEnabledAttributePresent(false)
    expectNumberOfSwordEnabledAttributeValues(1)

    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null)) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Failure if swordEnabled attribute is present but somehow has zero values" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(0)

    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null)) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Failure if authentication to LDAP fails" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)

    implicit def getFailedLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[ManagedResource[LdapContext]] = {
      Failure(new AuthenticationException())
    }

    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))(settings, getFailedLdapContext) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Success when auth=FileAuthSettings and userid and password are found in users.properties" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")
    val settings2 = settings.copy(auth = FileAuthSettings("users.properties", Map("testUser" -> "$2a$10$LrPL/Hk2tMsmPuUm6y5hhOpjjTZLBuSyLepIG5avdL6RN/lPO2bVG")))
    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))(settings2) shouldBe a[Success[_]]
  }

  it should "return Failure when auth=FileAuthSettings and the userid is not found in users.properties" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")
    val settings2 = settings.copy(auth = FileAuthSettings("users.properties", Map("testUser" -> "$2a$10$LrPL/Hk2tMsmPuUm6y5hhOpjjTZLBuSyLepIG5avdL6RN/lPO2bVG")))
    Authentication.checkAuthentication(new AuthCredentials("testUser2", "testPassword", null))(settings2) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Failure when auth=FileAuthSettings and the user password does not match in users.properties" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")
    val settings2 = settings.copy(auth = FileAuthSettings("users.properties", Map("testUser" -> "$2a$10$LrPL/Hk2tMsmPuUm6y5hhOpjjTZLBuSyLepIG5avdL6RN/lPO2bVG")))
    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword2", null))(settings2) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }

  it should "return Success when auth=SingleUserAuthSettings and userid and password match" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")
    val settings2 = settings.copy(auth = SingleUserAuthSettings("testUser", "$2a$10$LrPL/Hk2tMsmPuUm6y5hhOpjjTZLBuSyLepIG5avdL6RN/lPO2bVG"))
    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))(settings2) shouldBe a[Success[_]]
  }

  it should "return Failure when auth=SingleUserAuthSettings and userid and password don't match" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")
    val settings2 = settings.copy(auth = SingleUserAuthSettings("testUser", "$2a$10$LrPL/Hk2tMsmPuUm6y5hhOpjjTZLBuSyLepIG5avdL6RN/lPO2bVG"))
    Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword2", null))(settings2) should matchPattern {
      case Failure(_: SwordAuthException) =>
    }
  }
}
