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
package nl.knaw.dans.easy.sword2

import java.io.{ File, IOException }
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }
import java.nio.file.attribute.{ BasicFileAttributes, PosixFilePermissions }

import nl.knaw.dans.easy.sword2.DepositHandler.{ isOnPosixFileSystem, log }

import scala.util.Try

object FilesPermissionService {

  def changePermissionsForDirectoryAndContent(depositDir: File, permissions: String, id: String): Try[Unit] = Try {
    if (isOnPosixFileSystem(depositDir))
      Files.walkFileTree(depositDir.toPath, ChangePermissionsForDirectoryAndContent(permissions, id))
  }

  // previous classname MakeAllGroupWritable was deceptive as the argument (posix permission string) can be something else than rwxrwx---
  case class ChangePermissionsForDirectoryAndContent(permissions: String, id: String) extends SimpleFileVisitor[Path] {
    override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on file $path")
      try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        FileVisitResult.CONTINUE
      } catch {
        case usoe: UnsupportedOperationException => log.error("Not on a POSIX supported file system"); FileVisitResult.TERMINATE
        case cce: ClassCastException => log.error("No file permission elements in set"); FileVisitResult.TERMINATE
        case ioe: IOException => log.error(s"Could not set file permissions on $path"); FileVisitResult.TERMINATE
        case se: SecurityException => log.error(s"Not enough privileges to set file permissions on $path",se); FileVisitResult.TERMINATE
      }
    }

    override def postVisitDirectory(dir: Path, ex: IOException): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on directory $dir")
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions))
      if (ex == null) FileVisitResult.CONTINUE
      else FileVisitResult.TERMINATE
    }
  }
}
