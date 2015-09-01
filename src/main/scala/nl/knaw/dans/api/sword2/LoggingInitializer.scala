package nl.knaw.dans.api.sword2

import java.io.File
import javax.servlet.{ServletContextEvent, ServletContextListener}

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory

class LoggingInitializer extends ServletContextListener {
  val log = LoggerFactory.getLogger(getClass)

  def contextInitialized(sce: ServletContextEvent) = {
    LoggerFactory.getILoggerFactory match {
      case lc: LoggerContext => {
        val logConfigFile = new File(homeDir, "cfg/logback.xml")
        val configurator = new JoranConfigurator
        configurator.setContext(lc)
        lc.reset()
        configurator.doConfigure(logConfigFile)
        log.info(s"Home directory = $homeDir")
        log.info(s"Logback configuration file = $logConfigFile")
      }
      case _ => println("Logback not found. Could not configure logging")
    }
  }

  def contextDestroyed(sce: ServletContextEvent) = Unit
}