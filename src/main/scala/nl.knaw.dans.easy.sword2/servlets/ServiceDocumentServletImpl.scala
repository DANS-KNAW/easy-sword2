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
package nl.knaw.dans.easy.sword2.servlets

import nl.knaw.dans.easy.sword2.{ Settings, SwordConfig }
import org.swordapp.server.servlets.ServiceDocumentServletDefault

class ServiceDocumentServletImpl extends ServiceDocumentServletDefault {
  override def init(): Unit = {
    super.init()
    config.asInstanceOf[SwordConfig].settings = getServletContext.getAttribute(EASY_SWORD2_SETTINGS_ATTRIBUTE_KEY).asInstanceOf[Settings]
  }
}
