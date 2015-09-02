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

package nl.knaw.dans.api

import java.io.File

package object sword2 {
  /*
   * HACK ALERT:
   *
   * This is a global variable. It's GLOBAL and VARIABLE, which is bad, but I can see no other way to get
   * a Servlet Init Parameter into the global scope. If there is a better way, please let me know (JvM).
   */
  var homeDir: File = null
}

