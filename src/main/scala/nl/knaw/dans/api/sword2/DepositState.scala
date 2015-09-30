package nl.knaw.dans.api.sword2

import java.io.{PrintWriter, StringWriter, File, IOException}

import org.apache.commons.configuration.PropertiesConfiguration
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory

import scala.util.Try

object DepositState {
  val log = LoggerFactory.getLogger(getClass)
  case class State(label: String, description: String, timeStamp: String)

  def setDepositState(id: String, state: String, description: String, lookInTempFirst: Boolean = false, throwable: Throwable = null): Try[Unit] = Try {
    val depositDir = new File(if (lookInTempFirst) SwordProps("tempdir")
                              else SwordProps("deposits-root"), id)
    val stateFile = new PropertiesConfiguration(new File(depositDir, "state.properties"))
    stateFile.setProperty("state", state)
    stateFile.setProperty("description",
      s"""
        |$description
        |${if(throwable != null) throwable.getMessage else ""}
      """.stripMargin.trim)
    stateFile.save()
  }

  def getDepositState(id: String): Try[State] = {
    log.debug(s"[$id] Trying to retrieve state")
    readState(id, new File(SwordProps("tempdir"), s"$id/state.properties")).recoverWith {
      case f: IOException => readState(id, new File(SwordProps("deposits-root"), s"$id/state.properties"))
    }
  }
  private def readState(id: String, f: File): Try[State] = Try {
    val s = new PropertiesConfiguration(f)
    log.debug(s"[$id] Trying to retrieve state from $f")
    if(!f.exists()) throw new IOException(s"$f does not exist")
    State(s.getString("state"), s.getString("description"), new DateTime(s.getFile.lastModified()).withZone(DateTimeZone.UTC).toString)
  }

  private def stackTraceToString(t: Throwable): String = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    t.printStackTrace(pw)
    pw.flush()
    sw.toString
  }
}
