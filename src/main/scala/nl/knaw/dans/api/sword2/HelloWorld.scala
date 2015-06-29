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

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.ServletException
import java.io.IOException
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

object HelloWorld {
  @throws(classOf[Exception])
  def main(args: Array[String]) {
    val server: Server = new Server(8080)
    server.setHandler(new HelloWorld)
    server.start()
    server.join()
  }
}

class HelloWorld extends AbstractHandler {
  @throws(classOf[IOException])
  @throws(classOf[ServletException])
  def handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
    response.setContentType("text/html;charset=utf-8")
    response.setStatus(HttpServletResponse.SC_OK)
    baseRequest.setHandled(true)
    response.getWriter.println("<h1>hello, world</h1>")
  }
}
