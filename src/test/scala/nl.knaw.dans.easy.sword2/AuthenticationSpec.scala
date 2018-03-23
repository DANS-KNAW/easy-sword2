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
import javax.naming.AuthenticationException
import javax.naming.directory.{ Attribute, Attributes }
import javax.naming.ldap.LdapContext

import nl.knaw.dans.easy.sword2.Authentication._
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside._
import org.scalatest.{ FlatSpec, Matchers, _ }
import org.swordapp.server.{ AuthCredentials, SwordAuthException }

import scala.util.{ Failure, Success, Try }

class AuthenticationSpec extends FlatSpec with Matchers with MockFactory with OneInstancePerTest {
  implicit val settings: Settings = Settings(
    depositRootDir = new File("dummy"),
    depositPermissions = "dummy",
    tempDir = new File("dummy"),
    serviceBaseUrl = "dummy",
    collectionPath = "dummy",
    auth = LdapAuthSettings(new URI("ldap://localhost"), "ou=easy,dc=dans,dc=knaw,dc=nl", "enabled", "true"),
    urlPattern = Pattern.compile("dummy"),
    bagStoreSettings = None,
    supportMailAddress = "dummy",
    marginDiskSpace = 0,
    sample = SampleTestDataEnabled(new File("sample-dummy"), Map.empty))

  private val ldapContext = mock[LdapContext]
  private val attributes = mock[Attributes]
  private val swordEnabledAttribute = mock[Attribute]


  (ldapContext.getAttributes(_: String)) expects * anyNumberOfTimes() returning attributes

  private def expectSwordEnabledAttributePresent(present: Boolean) =
    (attributes.get(_: String)) expects "enabled" anyNumberOfTimes() returning (if (present) swordEnabledAttribute
                                                                                else null)

  private def expectNumberOfSwordEnabledAttributeValues(n: Int) = swordEnabledAttribute.size _ expects() anyNumberOfTimes() returning n

  private def expectSwordEnabledAttributeValue(value: String) = (swordEnabledAttribute.get(_: Int)) expects 0 anyNumberOfTimes() returning value


  val command = "echo -n 'SomePassword' | openssl sha1 -hmac 'someUserNameAsSalt' -binary | base64"
  val output = "WjYViDQOdGR8V1kkTs900ZfoLXU="

  "hash" should s"return same as: $command" in {
    hash("SomePassword", "someUserNameAsSalt") shouldBe output
  }

  it should s"return something else than: $command" in {
    hash("somePassword", "someUserNameAsSalt") should not be output
  }

  it should s"not return same as: $command" in {
    hash("SomePassword", "SomeUserNameAsSalt") should not be output
  }

  "checkAuthentication" should "return Success if correct credentials + swordEnabled attribute are provided " in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("true")

    implicit def getLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[LdapContext] = Try {
      ldapContext
    }

    val result = Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))
    result shouldBe a[Success[_]]
  }

  it should "return Failure if swordEnabled set to false" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)
    expectSwordEnabledAttributeValue("false")

    implicit def getLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[LdapContext] = Try {
      ldapContext
    }

    val result = Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))
    result shouldBe a[Failure[_]]
    inside(result) {
      case Failure(e) => e shouldBe a[SwordAuthException]
    }
  }

  it should "return Failure if swordEnabled is not set" in {
    expectSwordEnabledAttributePresent(false)
    expectNumberOfSwordEnabledAttributeValues(1)

    implicit def getLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[LdapContext] = Try {
      ldapContext
    }

    val result = Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))
    result shouldBe a[Failure[_]]
    inside(result) {
      case Failure(e) => e shouldBe a[SwordAuthException]
    }
  }

  it should "return Failure if swordEnabled attribute is present but somehow has zero values" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(0)

    implicit def getLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[LdapContext] = Try {
      ldapContext
    }

    val result = Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))
    result shouldBe a[Failure[_]]
    inside(result) {
      case Failure(e) => e shouldBe a[SwordAuthException]
    }
  }

  it should "return Failure if authentication to LDAP fails" in {
    expectSwordEnabledAttributePresent(true)
    expectNumberOfSwordEnabledAttributeValues(1)

    implicit def getLdapContext(u: UserName, p: Password, uri: ProviderUrl, parentEntry: UsersParentEntry): Try[LdapContext] = Failure(new AuthenticationException())

    val result = Authentication.checkAuthentication(new AuthCredentials("testUser", "testPassword", null))
    result shouldBe a[Failure[_]]
    inside(result) {
      case Failure(e) => e shouldBe a[SwordAuthException]
    }
  }


}
