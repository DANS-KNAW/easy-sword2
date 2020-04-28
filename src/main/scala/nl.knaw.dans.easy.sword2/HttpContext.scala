package nl.knaw.dans.easy.sword2

import scalaj.http.BaseHttp

case class HttpContext(applicationVersion: String) {

  lazy val userAgent: String = s"easy-sword2/$applicationVersion"

  object Http extends BaseHttp(userAgent = userAgent)
}
