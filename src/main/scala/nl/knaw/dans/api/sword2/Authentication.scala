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

import java.util
import javax.crypto.{Mac, SecretKeyFactory}
import javax.crypto.spec.{SecretKeySpec, PBEKeySpec}
import javax.naming.{AuthenticationException, Context}
import javax.naming.ldap.InitialLdapContext

import org.apache.commons.lang.StringUtils._
import org.slf4j.LoggerFactory
import org.swordapp.server.{AuthCredentials, SwordAuthException, SwordError}

import scala.util.{Failure, Success, Try}

object Authentication {
  val log = LoggerFactory.getLogger(getClass)

  def hash(password: String, userName: String): String = {
    val signingKey = new SecretKeySpec(userName.getBytes(), "HmacSHA1")
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(signingKey)
    val rawHmac = mac.doFinal(password.getBytes())
    new sun.misc.BASE64Encoder().encode(rawHmac)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  def checkAuthentication(auth: AuthCredentials)(implicit settings: Settings) {
    log.debug("Checking that onBehalfOf is not specified")
    if (isNotBlank(auth.getOnBehalfOf)) {
      throw new SwordError("http://purl.org/net/sword/error/MediationNotAllowed")
    }
    log.debug(s"Checking credentials for user ${auth.getUsername} using auth.mode: ${settings.authMode}")
    settings.authMode match {
      case "single" => if (settings.authSingleUser != auth.getUsername || hash(auth.getPassword, auth.getUsername) != settings.authSinglePassword) throw new SwordAuthException
      case "ldap" => if(!authenticateThroughLdap(auth.getUsername, auth.getPassword).get) throw new SwordAuthException
      case _ => throw new RuntimeException("Authentication not properly configured. Contact service admin")
    }
    log.debug("Authentication SUCCESS")
  }

  private def authenticateThroughLdap(user: String, password: String)(implicit settings: Settings): Try[Boolean] = Try {
    getInitialContext(user, password) match {
      case Success(context) =>
        val attrs = context.getAttributes(s"uid=$user, ${settings.authUsersParentEntry.get}") // TODO: Refactor away get call
        val enabled = attrs.get(settings.authSwordEnabledAttributeName.get) // TODO: Refactor away get call
        enabled != null && enabled.size == 1 && enabled.get(0) == settings.authSwordEnabledAttributeValue.get // TODO: Refactor away get call
      case Failure(t: AuthenticationException) => false
      case Failure(t) => throw new RuntimeException("Error trying to authenticate", t)
    }
  }

  private def getInitialContext(user: String, password: String)(implicit settings: Settings): Try[InitialLdapContext] = Try {
    val env = new util.Hashtable[String, String]()
    env.put(Context.PROVIDER_URL, settings.authLdapUrl.get) // TODO: Refactor away get call
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    env.put(Context.SECURITY_PRINCIPAL, s"uid=$user, ${settings.authUsersParentEntry.get}") // TODO: Refactor away get call
    env.put(Context.SECURITY_CREDENTIALS, password)
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    new InitialLdapContext(env, null)
  }
}
