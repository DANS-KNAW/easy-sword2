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

import java.net.URI
import java.util
import java.util.Base64

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.naming.ldap.{ InitialLdapContext, LdapContext }
import javax.naming.{ AuthenticationException, Context }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.lang.StringUtils._
import org.swordapp.server.{ AuthCredentials, SwordAuthException, SwordError }
import resource.{ ManagedResource, managed }

import scala.util.{ Failure, Success, Try }

object Authentication extends DebugEnhancedLogging {

  type UserName = String
  type Password = String
  type ProviderUrl = URI
  type UsersParentEntry = String

  def hash(password: String, userName: String): String = {
    val signingKey = new SecretKeySpec(userName.getBytes(), "HmacSHA1")
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(signingKey)
    val rawHmac = mac.doFinal(password.getBytes())
    Base64.getEncoder.encodeToString(rawHmac)
  }

  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  def checkAuthentication(auth: AuthCredentials)(implicit settings: Settings, getLdapContext: (UserName, Password, ProviderUrl, UsersParentEntry) => Try[ManagedResource[LdapContext]] = getInitialContext): Try[Unit] = {
    debug("Checking that onBehalfOf is not specified")
    if (isNotBlank(auth.getOnBehalfOf))
      Failure(new SwordError("http://purl.org/net/sword/error/MediationNotAllowed"))
    else {
      debug(s"Checking credentials for user ${ auth.getUsername }")
      settings.auth match {
        case SingleUserAuthSettings(user, password) =>
          if (user != auth.getUsername || password != hash(auth.getPassword, auth.getUsername)) {
            logger.warn("Single user FAILED log-in attempt")
            throw new SwordAuthException
          }
          else {
            logger.info("Single user log in SUCCESS")
            Success(())
          }
        case authSettings: LdapAuthSettings =>
          authenticateThroughLdap(auth.getUsername, auth.getPassword, authSettings, getLdapContext)
            .map {
              case false =>
                logger.warn("LDAP user FAILED log-in attempt")
                throw new SwordAuthException
              case true =>
                logger.info(s"User ${ auth.getUsername } authentication through LDAP successful")
                debug("LDAP log in SUCCESS")
                Success(())
            }
        case _ => Failure(new RuntimeException("Authentication not properly configured. Contact service admin"))
      }
    }
  }

  @throws(classOf[SwordAuthException])
  def checkThatUserIsOwnerOfDeposit(id: DepositId, user: String, msg: String)(implicit settings: Settings): Try[Unit] = {
    for {
      props <- DepositProperties(id)
      depositor <- props.getDepositorId
      _ <- if (depositor == user) Success(())
           else Failure(new SwordAuthException(msg))
    } yield ()
  }

  private def authenticateThroughLdap(user: String, password: String, authSettings: LdapAuthSettings, getLdapContext: (UserName, Password, ProviderUrl, UsersParentEntry) => Try[ManagedResource[LdapContext]]): Try[Boolean] = {
    getLdapContext(user, password, authSettings.ldapUrl, authSettings.usersParentEntry)
      .flatMap(_.map(ctx => {
        val attrs = ctx.getAttributes(s"uid=$user, ${ authSettings.usersParentEntry }")
        val enabled = attrs.get(authSettings.swordEnabledAttributeName)
        enabled != null && enabled.size == 1 && enabled.get(0) == authSettings.swordEnabledAttributeValue
      }).tried)
      .recoverWith {
        case _: AuthenticationException => Success(false)
        case t =>
          logger.error("Unexpected exception", t)
          Failure(new RuntimeException("Error trying to authenticate", t))
      }
  }

  private def getInitialContext(userName: UserName, password: Password, providerUrl: ProviderUrl, usersParentEntry: UsersParentEntry): Try[ManagedResource[InitialLdapContext]] = Try {
    val env = new util.Hashtable[String, String]() {
      put(Context.PROVIDER_URL, providerUrl.toASCIIString)
      put(Context.SECURITY_AUTHENTICATION, "simple")
      put(Context.SECURITY_PRINCIPAL, s"uid=$userName, $usersParentEntry")
      put(Context.SECURITY_CREDENTIALS, password)
      put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    }
    managed(new InitialLdapContext(env, null))
  }
}
