/*******************************************************************************
  * Copyright 2015 DANS - Data Archiving and Networked Services
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  ******************************************************************************/

package nl.knaw.dans.api.sword2

import nl.knaw.dans.api.sword2.Authentication.hash
import org.scalatest.{FlatSpec, Matchers}

class AuthenticationSpec extends FlatSpec with Matchers {

  // TODO somehow match the implemetation of 'hash' with some command line

  "hash" should "return same as 'echo -n SomePassword | openssl sha1 -hmac someUserNameAsSalt'" in {
    hash("SomePassword","someUserNameAsSalt") shouldBe "5a361588340e74647c5759244ecf74d197e82d75"
  }

  "hash" should "return same as 'echo -n SomePassword | openssl sha1 -hmac someUserNameAsSalt -binary | base64'" in {
    new sun.misc.BASE64Encoder().encode(
      hash("SomePassword","someUserNameAsSalt")
    ) shouldBe "WjYViDQOdGR8V1kkTs900ZfoLXU="
  }
}
