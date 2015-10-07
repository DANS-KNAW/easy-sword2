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

import java.util
import javax.naming.{AuthenticationException, Context}
import javax.naming.ldap.InitialLdapContext

import org.apache.commons.lang.StringUtils._
import org.slf4j.LoggerFactory
import org.swordapp.server.{AuthCredentials, SwordAuthException, SwordError}

import scala.util.{Failure, Success, Try}

object Authentication {
  val log = LoggerFactory.getLogger(getClass)


  @throws(classOf[SwordError])
  @throws(classOf[SwordAuthException])
  def checkAuthentication(auth: AuthCredentials) {
    log.debug("Checking that onBehalfOf is not specified")
    if (isNotBlank(auth.getOnBehalfOf)) {
      throw new SwordError("http://purl.org/net/sword/error/MediationNotAllowed")
    }
    log.debug(s"Checking credentials for user ${auth.getUsername} using auth.mode: ${SwordProps("auth.mode")}")
    SwordProps("auth.mode") match {
      case "single" => if (!(auth.getUsername == SwordProps("auth.single.user")) || !(auth.getPassword == SwordProps("auth.single.password"))) throw new SwordAuthException
      case "ldap" => if(!authenticateThroughLdap(auth.getUsername, auth.getPassword).get) throw new SwordAuthException
      case _ => throw new RuntimeException("Authentication not properly configured. Contact service admin")
    }
    log.debug("Authentication SUCCESS")
  }

  private def authenticateThroughLdap(user: String, password: String): Try[Boolean] = Try {
    getInitialContext(user, password) match {
      case Success(context) =>
        val attrs = context.getAttributes(s"uid=$user, ${SwordProps("auth.ldap.users.parent-entry")}")
        val enabled = attrs.get(SwordProps("auth.ldap.deposit-enabled-attribute-name"))
        enabled != null && enabled.size == 1 && enabled.get(0) == SwordProps("auth.ldap.deposit-enabled-attribute-value")
      case Failure(t: AuthenticationException) => false
      case Failure(t) => throw new RuntimeException("Error trying to authenticate", t)
    }
  }

  private def getInitialContext(user: String, password: String): Try[InitialLdapContext] = Try {
    val env = new util.Hashtable[String, String]()
    env.put(Context.PROVIDER_URL, SwordProps("auth.ldap.url"))
    env.put(Context.SECURITY_AUTHENTICATION, "simple")
    env.put(Context.SECURITY_PRINCIPAL, s"uid=$user, ${SwordProps("auth.ldap.users.parent-entry")}")
    env.put(Context.SECURITY_CREDENTIALS, password)
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    new InitialLdapContext(env, null)
  }
}
