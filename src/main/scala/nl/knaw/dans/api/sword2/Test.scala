package nl.knaw.dans.api.sword2

import scala.util.{Failure, Success}

object Test {
  def main(args: Array[String]) {

    val tx = Success(13)
    val tf = Failure(new Exception("Boom"))
    val ty = Success(37)

    println(for {
      x <- tx
      _ <- tf
      if x > 1337
      y <- ty
    } yield y)
  }
}
