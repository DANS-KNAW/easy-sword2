package nl.knaw.dans.easy.sword2

import java.net.URI

import org.json4s.JsonAST.{ JArray, JString }
import org.json4s.JsonDSL._
import org.json4s.native.{ JsonMethods, Serialization }
import org.json4s.{ DefaultFormats, Formats, JValue }
import scalaj.http.BaseHttp

/**
 * A client wrapping both the logic of executing a GraphQL query on a particular URL and
 * parsing the response.
 *
 * @param url         the URL of the GraphQL server on which the query is executed
 * @param credentials the credentials (if required) required to execute the query
 * @param http        the ''BaseHttp'' object required for sending the request
 * @param jsonFormats the formatter used for parsing and generating the JSON
 */
class GraphQlClient(url: URI, credentials: Option[(String, String)] = Option.empty)(implicit http: BaseHttp, jsonFormats: Formats = new DefaultFormats {}) {

  /**
   * Execute a GraphQL query, if provided sent together with the variables. The response is parsed
   * into either a sequence of errors (if ''errors'' is present in the response) or the JSON inside
   * the ''data'' object.
   *
   * @param query     the query to be executed
   * @param variables values for the placeholders in the query
   * @param ev        evidence that the values in the variables mapping can be converted to JSON
   * @tparam A the values in the variables mapping
   * @return the JSON object inside ''data'' if the query was successful;
   *         a list of error messages otherwise
   */
  def doQuery[A](query: String, variables: Map[String, A] = Map.empty)(implicit ev: A => JValue): Either[Seq[String], JValue] = {
    val baseHttp = http(url.toString)
      .headers(
        "Accept" -> "application/json",
        "Content-Type" -> "application/json",
      )
      .postData(Serialization.write {
        val q = "query" -> query

        if (variables.nonEmpty) q ~ ("variables" -> variables)
        else q
      })

    val body = credentials.fold(baseHttp) {
      case (username, password) => baseHttp.auth(username, password)
    }.asString.body
    val json = JsonMethods.parse(body)

    parseErrors(json \ "errors")
      .map(Left(_))
      .getOrElse(Right(json \ "data"))
  }

  // following https://graphql.github.io/graphql-spec/June2018/#sec-Response-Format
  private def parseErrors(errors: JValue): Option[List[String]] = {
    errors match {
      case JArray(Nil) => Option.empty
      case JArray(es) => Option {
        es.map(error => {
          val JString(msg) = error \ "message"
          msg
        })
      }
      case _ => Option.empty
    }
  }
}