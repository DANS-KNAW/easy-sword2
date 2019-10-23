import java.net.URI

import nl.knaw.dans.easy.sword2.{ DepositPropertiesService, GraphQlClient }
import scalaj.http.{ BaseHttp, Http }

object Test extends App {

  implicit val baseHttp: BaseHttp = Http
  implicit val depositPropertiesClient: GraphQlClient =
    new GraphQlClient(
      url = new URI("http://deasy.dans.knaw.nl:20200/graphql"),
      credentials = Option((
        "easy-deposit-properties",
        "easy_deposit_properties"))
    )

  val dp = new DepositPropertiesService("85a705ac-f4cd-11e9-8300-9f38b2e04e7d")




}
