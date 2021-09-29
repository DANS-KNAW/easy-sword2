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

import nl.knaw.dans.easy.sword2.servlets._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ ServletContextHandler, ServletHolder }

import scala.util.Try

class EasySword2Service(configuration: Configuration) extends DebugEnhancedLogging {

  private val serverPort: Int = configuration.settings.serverPort
  private val server = new Server(serverPort)
  val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
  // TODO: Refactor this so that we do not need access to the application's wiring from outside the object.
  context.setAttribute(servlets.EASY_SWORD2_SETTINGS_ATTRIBUTE_KEY, configuration.settings)
  /*
   * Map URLs to servlets
   */
  context.addServlet(new ServletHolder(new EasySword2Servlet(configuration.version)), "/")
  context.addServlet(classOf[ServiceDocumentServletImpl], "/servicedocument")
  context.addServlet(classOf[CollectionServletImpl], "/collection/*")
  context.addServlet(classOf[ContainerServletImpl], "/container/*")
  context.addServlet(classOf[MediaResourceServletImpl], "/media/*")
  context.addServlet(classOf[StatementServletImpl], "/statement/*")

  /*
   * Specify classes that implement the SWORD 2.0 behavior.
   */
  context.setInitParameter("config-impl", classOf[SwordConfig].getName)
  context.setInitParameter("service-document-impl", classOf[ServiceDocumentManagerImpl].getName)
  context.setInitParameter("collection-deposit-impl", classOf[CollectionDepositManagerImpl].getName)
  context.setInitParameter("collection-list-impl", classOf[CollectionListManagerImpl].getName)
  context.setInitParameter("container-impl", classOf[ContainerManagerImpl].getName)
  context.setInitParameter("media-resource-impl", classOf[MediaResourceManagerImpl].getName)
  context.setInitParameter("statement-impl", classOf[StatementManagerImpl].getName)
  server.setHandler(context)
  logger.info(s"HTTP port is $serverPort")

  def start(): Try[Unit] = Try {
    debug("Starting deposit processing thread...")
    DepositHandler.startDepositProcessingStream(configuration.settings)
    logger.info("Starting HTTP service...")
    server.start()
  }

  def stop(): Try[Unit] = Try {
    logger.info("Stopping HTTP service...")
    server.stop()
    // TODO: stop the deposit processing thread before closing
  }

  def destroy(): Try[Unit] = Try {
    server.destroy()
  }
}
