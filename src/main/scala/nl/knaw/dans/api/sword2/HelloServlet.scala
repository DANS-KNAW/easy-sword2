/*******************************************************************************
  * Copyright 2015 DANS - Data Archiving and Networked Services
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  ******************************************************************************/
package nl.knaw.dans.api.sword2

import java.io._
import javax.servlet._
import javax.servlet.http._

class HelloServlet extends HttpServlet {
  @throws(classOf[ServletException])
  @throws(classOf[IOException])
  override def doGet(request: HttpServletRequest, response: HttpServletResponse) {
    val out: PrintWriter = response.getWriter
    out.println("hello, world")
    out.flush()
    out.close()
  }
}

