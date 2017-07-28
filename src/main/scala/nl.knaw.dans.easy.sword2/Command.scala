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

import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import resource.managed

import scala.language.reflectiveCalls
import scala.util.control.NonFatal
import scala.util.{ Failure, Try }

object Command extends App with DebugEnhancedLogging {
  type FeedBackMessage = String

  private val configuration = Configuration()
  private val commandLine = new CommandLineOptions(args, configuration) {
    verify()
  }
  val app = new EasySword2App(new ApplicationWiring(configuration))
  managed(app)
    .acquireAndGet(app => {
      for {
        _ <- app.init()
        msg <- runSubcommand()
      } yield msg
    })
    .doIfSuccess(msg => println(s"OK: $msg"))
    .doIfFailure { case e => logger.error(e.getMessage, e) }
    .doIfFailure { case NonFatal(e) => println(s"FAILED: ${ e.getMessage }") }

  private def runSubcommand(): Try[FeedBackMessage] = {
    commandLine.subcommand match {
      case Some(cmd @ commandLine.runService) => runAsService()
      case _ => Failure(new IllegalArgumentException(s"Unknown command: ${ commandLine.subcommand }"))
    }
  }

  private def runAsService(): Try[FeedBackMessage] = Try {
    val service = new EasySword2Service(configuration.properties.getInt("daemon.http.port"), app)
    Runtime.getRuntime.addShutdownHook(new Thread("service-shutdown") {
      override def run(): Unit = {
        service.stop()
        service.destroy()
      }
    })
    service.start()
    Thread.currentThread.join()
    "Service terminated normally."
  }
}
