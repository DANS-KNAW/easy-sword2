package nl.knaw.dans.api.sword2

import javax.servlet.ServletContextListener
import javax.servlet.ServletContextEvent
import org.slf4j.LoggerFactory
import java.io.File
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.ILoggerFactory
import ch.qos.logback.classic.LoggerContext

class LoggingInitializer extends ServletContextListener {
  val homeDir = new File(System.getenv("EASY_DEPOSIT_HOME"))
  val log = LoggerFactory.getLogger(getClass)

  def contextInitialized(sce: ServletContextEvent) = {
    LoggerFactory.getILoggerFactory match {
      case lc: LoggerContext => {
        val logConfigFile = new File(homeDir, "cfg/logback.xml")
        val configurator = new JoranConfigurator
        configurator.setContext(lc)
        lc.reset()
        configurator.doConfigure(logConfigFile)
        log.info("Configured Logback with config file from: {}", logConfigFile.getAbsolutePath)
      }
      case _ => System.out.println("Logback not found. Could not configure logging")
    }
  }

  def contextDestroyed(sce: ServletContextEvent) = Unit
}