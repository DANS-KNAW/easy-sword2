/**
 * Copyright (C) 2015-2017 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.easy.sword2

import nl.knaw.dans.easy.sword2.servlets._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.eclipse.jetty.ajp.Ajp13SocketConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler

import scala.util.Try

class Sword2Service extends ApplicationSettings with DebugEnhancedLogging  {
  import logger._
  private val port = properties.getInt("daemon.http.port")
  val server = new Server(port)
  val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
  val settings = Settings(
    depositRootDir, depositPermissions, tempDir, baseUrl, collectionPath, auth, urlPattern,
    bagStoreSettings, supportMailAddress)
  context.setAttribute(servlets.EASY_SWORD2_SETTINGS_ATTRIBUTE_KEY, settings)

  /*
   * Map URLs to servlets
   */
  context.addServlet(classOf[HelloServlet], "/hello")
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

  info(s"HTTP port is $port")
  if (properties.containsKey("daemon.ajp.port")) {
    val ajp = new Ajp13SocketConnector()
    val ajpPort = properties.getInt("daemon.ajp.port")
    ajp.setPort(ajpPort)
    server.addConnector(ajp)
    info(s"AJP port is $ajpPort")
  }

  def start(): Try[Unit] = Try {
    info("Starting processing thread ....")
    DepositHandler.startDepositProcessingStream(settings)
    info("Starting HTTP service ...")
    server.start()
  }

  def stop(): Try[Unit] = Try {
    info("Stopping HTTP service ...")
    server.stop()
  }

  def destroy(): Try[Unit] = Try {
    server.destroy()
  }
}

object Sword2Service extends App with DebugEnhancedLogging {
  import logger._
  val service = new Sword2Service()
  Runtime.getRuntime.addShutdownHook(new Thread("service-shutdown") {
    override def run(): Unit = {
      info("Stopping service ...")
      service.stop().map(_ => info("Cleaning up ..."))
        .recover { case t => error("Error during stop phase", t)}
      service.destroy().map(_ => info("Service stopped."))
        .recover { case t => error("Error during destroy phase", t)}
    }
  })

  service.start().map(_ => {
    info("Service started.")
    Thread.currentThread().join()
  })
    .recover { case t => error("Service could not start", t)}
}

