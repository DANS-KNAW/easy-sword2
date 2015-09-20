package nl.knaw.dans.api.sword2

import java.io.{File, IOException}

import org.apache.commons.configuration.PropertiesConfiguration
import org.joda.time.{DateTime, DateTimeZone}

import scala.util.Try

object DepositState {
  case class State(state: String, description: String, timeStamp: String)

  def setDepositState(id: String, state: String, description: String, inTemp: Boolean = false): Try[Unit] = Try {
    val depositDir = new File(if (inTemp) SwordProps("temp-dir")
                              else SwordProps("data-dir"), id)
    val stateFile = new PropertiesConfiguration(new File(depositDir, "state.properties"))
    stateFile.setProperty("state", state)
    stateFile.setProperty("descripion", description)
    stateFile.save()
  }

  def getDepositState(id: String): Try[State] = {
    readState(new File(SwordProps("temp-dir"), s"$id/state.properties")).recoverWith {
      case f: IOException => readState(new File(SwordProps("data-dir"), s"$id/state.properties"))
    }
  }
  private def readState(f: File): Try[State] = Try {
    val s = new PropertiesConfiguration(f)
    State(s.getString("state"), s.getString("description"), new DateTime(s.getFile.lastModified()).withZone(DateTimeZone.UTC).toString)
  }
}
