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

import nl.knaw.dans.easy.sword2.managers._
import nl.knaw.dans.easy.sword2.servlets._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ ServletContextHandler, ServletHolder }
import rx.lang.scala.Subscription

import scala.util.Try

class EasySword2Service(configuration: Configuration) extends DebugEnhancedLogging {

  private implicit val settings: Settings = configuration.settings
  private val serverPort: Int = settings.serverPort
  private val server = new Server(serverPort) {
    setHandler {
      new ServletContextHandler(ServletContextHandler.NO_SESSIONS) {
        // TODO: Refactor this so that we do not need access to the application's wiring from outside the object.
        setAttribute(servlets.EASY_SWORD2_SETTINGS_ATTRIBUTE_KEY, settings)
        /*
         * Map URLs to servlets
         */
        addServlet(new ServletHolder(new EasySword2Servlet(configuration.version)), "/")
        addServlet(classOf[ServiceDocumentServletImpl], "/servicedocument")
        addServlet(classOf[CollectionServletImpl], "/collection/*")
        addServlet(classOf[ContainerServletImpl], "/container/*")
        addServlet(classOf[MediaResourceServletImpl], "/media/*")
        addServlet(classOf[StatementServletImpl], "/statement/*")

        /*
         * Specify classes that implement the SWORD 2.0 behavior.
         */
        setInitParameter("config-impl", classOf[SwordConfig].getName)
        setInitParameter("service-document-impl", classOf[ServiceDocumentManagerImpl].getName)
        setInitParameter("collection-deposit-impl", classOf[CollectionDepositManagerImpl].getName)
        setInitParameter("collection-list-impl", classOf[CollectionListManagerImpl].getName)
        setInitParameter("container-impl", classOf[ContainerManagerImpl].getName)
        setInitParameter("media-resource-impl", classOf[MediaResourceManagerImpl].getName)
        setInitParameter("statement-impl", classOf[StatementManagerImpl].getName)
      }
    }
  }
  logger.info(s"HTTP port is $serverPort")

  private var depositProcessingSubscription: Subscription = _

  def start(): Try[Unit] = Try {
    debug("Starting deposit processing thread...")
    depositProcessingSubscription = DepositProcessor.startDepositProcessingStream
    DepositProcessor.startUploadedDeposits
    logger.info("Starting HTTP service...")
    server.start()
  }

  def stop(): Try[Unit] = Try {
    logger.info("Stopping HTTP service...")
    server.stop()
    depositProcessingSubscription.unsubscribe()
  }

  def destroy(): Try[Unit] = Try {
    server.destroy()
  }
}
