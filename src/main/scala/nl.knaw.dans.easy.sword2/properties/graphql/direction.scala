/**
 * Copyright (C) 2015 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
