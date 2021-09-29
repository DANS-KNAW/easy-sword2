/*
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
package nl.knaw.dans.easy.sword2

import java.io.File

import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.io.Source

/** Does not dump the full file but just the searched content if it is not found.
 *
 * See also <a href="http://www.scalatest.org/user_guide/using_matchers#usingCustomMatchers">CustomMatchers</a> */
trait CustomMatchers {
  class ContentMatcher(content: String) extends Matcher[File] {
    def apply(left: File): MatchResult = {
      def trimLines(s: String): String = s.split("\n").map(_.trim).mkString("\n")

      MatchResult(
        trimLines(Source.fromFile(left).mkString).contains(trimLines(content)),
        s"$left did not contain: $content",
        s"$left contains $content"
      )
    }
  }
  def containTrimmed(content: String) = new ContentMatcher(content)
}
