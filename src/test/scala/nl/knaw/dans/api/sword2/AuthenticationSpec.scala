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

import nl.knaw.dans.api.sword2.Authentication.hash
import org.scalatest.{FlatSpec, Matchers}

class AuthenticationSpec extends FlatSpec with Matchers {

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
}
