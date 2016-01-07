/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.api.sword2

import java.io.File
import javax.servlet.{ServletContextEvent, ServletContextListener}

class HomeDirInitializer extends ServletContextListener {
  override def contextInitialized(sce: ServletContextEvent) = {
    val home = if(sce.getServletContext.getInitParameter("EASY_DEPOSIT_HOME") != null) sce.getServletContext.getInitParameter("EASY_DEPOSIT_HOME")
               else System.getenv("EASY_DEPOSIT_HOME")
    if(home == null) throw new RuntimeException("EASY_DEPOSIT_HOME not specified. Specify through servlet init params or environment variable")
    homeDir = new File(home)
  }

  def contextDestroyed(sce: ServletContextEvent) = Unit
}


