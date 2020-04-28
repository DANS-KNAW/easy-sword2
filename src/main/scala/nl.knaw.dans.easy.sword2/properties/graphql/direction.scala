package nl.knaw.dans.easy.sword2.properties.graphql

object direction {

  sealed abstract class PaginationDirectionObject(val hasAnotherPage: Boolean, val cursor: Option[String])
  case class Forwards(hasNextPage: Boolean, endCursor: Option[String]) extends PaginationDirectionObject(hasNextPage, endCursor)
  case class Backwards(hasPreviousPage: Boolean, startCursor: Option[String]) extends PaginationDirectionObject(hasPreviousPage, startCursor)
  
  trait PaginationDirection {
    type ObjectType <: PaginationDirectionObject

    def empty: ObjectType

    def cursorName: String
  }

  object Forwards extends PaginationDirection {
    override type ObjectType = Forwards

    override def empty: ObjectType = Forwards(hasNextPage = true, None)

    override val cursorName = "after"
  }

  object Backwards extends PaginationDirection {
    override type ObjectType = Backwards

    override def empty: ObjectType = Backwards(hasPreviousPage = true, None)

    override val cursorName = "before"
  }
}
