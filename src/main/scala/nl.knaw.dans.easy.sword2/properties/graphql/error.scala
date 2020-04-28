package nl.knaw.dans.easy.sword2.properties.graphql

import org.swordapp.server.SwordError

object error {
  abstract class GraphQLError(val msg: String) extends Exception(msg) {
    def toSwordError: SwordError
  }
  case class UnexpectedResponseCode(code: Int, body: String) extends GraphQLError(s"GraphQL service returned code $code with message: $body") {
    override def toSwordError: SwordError = new SwordError(500)
  }
  case class GraphQLQueryError(errors: List[String]) extends GraphQLError(s"Error in GraphQL query: ${ errors.mkString("[", ", ", "]") }") {
    override def toSwordError: SwordError = new SwordError(500)
  }
  case object GraphQLServiceUnavailable extends GraphQLError("GraphQL service is unavailable") {
    override def toSwordError: SwordError = new SwordError(503) {
      override def getMessage: String = "503 Service temporarily unavailable"
    }
  }
}
