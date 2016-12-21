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

import java.net.{URI, URL}
import java.util
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.naming.ldap.{InitialLdapContext, LdapContext}
import javax.naming.{AuthenticationException, Context}

import org.apache.commons.lang.StringUtils._
import org.slf4j.LoggerFactory
import org.swordapp.server.{AuthCredentials, SwordAuthException, SwordError}

import scala.util.{Failure, Success, Try}



object Authentication {
  type UserName = String
  type Password = String
  type ProviderUrl = URI
  type UsersParentEntry = String

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
  def checkAuthentication(auth: AuthCredentials)(implicit settings: Settings, getLdapContext: (UserName, Password, ProviderUrl, UsersParentEntry) => Try[LdapContext] = getInitialContext): Try[Unit] = {
    log.debug("Checking that onBehalfOf is not specified")
    if (isNotBlank(auth.getOnBehalfOf)) {
      Failure(new SwordError("http://purl.org/net/sword/error/MediationNotAllowed"))
    }
    else {
      log.debug(s"Checking credentials for user ${auth.getUsername}")
      settings.auth match {
        case SingleUserAuthSettings(user, password) =>
          if (user != auth.getUsername || password != hash(auth.getPassword, auth.getUsername)) {
            log.warn("Single user FAILED log-in attempt")
            throw new SwordAuthException
          }
          else {
            log.info("Single user log in SUCCESS")
            Success(())
          }
        case authSettings: LdapAuthSettings => authenticateThroughLdap(auth.getUsername, auth.getPassword, authSettings, getLdapContext).map {
          case false => {
            log.warn("LDAP user FAILED log-in attempt")
            throw new SwordAuthException
          }
          case true => log.info(s"User ${auth.getUsername} authentication through LDAP successful")
            log.info("LDAP log in SUCCESS")
            Success(())
        }
        case _ => Failure(new RuntimeException("Authentication not properly configured. Contact service admin"))
      }
    }
  }

  private def authenticateThroughLdap(user: String, password: String, authSettings: LdapAuthSettings, getLdapContext: (UserName, Password, ProviderUrl, UsersParentEntry) => Try[LdapContext]): Try[Boolean] = {
    getLdapContext(user, password, authSettings.ldapUrl, authSettings.usersParentEntry).map {
      context =>
        val attrs = context.getAttributes(s"uid=$user, ${authSettings.usersParentEntry}")
        val enabled = attrs.get(authSettings.swordEnabledAttributeName)
        enabled != null && enabled.size == 1 && enabled.get(0) == authSettings.swordEnabledAttributeValue
    }.recoverWith {
        case t: AuthenticationException => Success(false)
        case t =>
          log.debug("Unexpected exception", t)
          Failure(new RuntimeException("Error trying to authenticate", t))
    }
  }

  private def getInitialContext(userName: UserName, password: Password, providerUrl: ProviderUrl, usersParentEntry: UsersParentEntry): Try[InitialLdapContext] = Try {
    val env = new util.Hashtable[String, String]()
    env.put(Context.PROVIDER_URL, providerUrl.toASCIIString)
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    env.put(Context.SECURITY_PRINCIPAL, s"uid=$userName, $usersParentEntry")
    env.put(Context.SECURITY_CREDENTIALS, password)
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    new InitialLdapContext(env, null)
  }
}
