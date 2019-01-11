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
import java.nio.file._
import java.nio.file.attribute.{ BasicFileAttributes, PosixFilePermissions }

import nl.knaw.dans.easy.sword2.DepositHandler.isOnPosixFileSystem
import nl.knaw.dans.lib.error._
import org.slf4j.{ Logger, LoggerFactory }
import scala.language.postfixOps

import scala.util.Try

object FilesPermission {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def changePermissionsRecursively(depositDir: File, permissions: String, id: DepositId): Try[Unit] = Try {
    log.debug(s"[$id] starting with setting permissions $permissions for file ${ depositDir.getName }")
    if (isOnPosixFileSystem(depositDir)) {
      Files.walkFileTree(depositDir.toPath, RecursiveFilePermissionVisitor(permissions, id))
      log.info(s"[$id] Successfully given $permissions to ${ depositDir.getName }")
    }
    else throw new UnsupportedOperationException("Not on a POSIX supported file system")
  }

  case class RecursiveFilePermissionVisitor(permissions: String,
                                            id: DepositId) extends SimpleFileVisitor[Path] {
    override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on file $path")
      Try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        FileVisitResult.CONTINUE
      } doIfFailure {
        case uoe: UnsupportedOperationException => log.error("Not on a POSIX supported file system", uoe)
        case cce: ClassCastException => log.error("No file permission elements in set", cce)
        case ioe: IOException => log.error(s"Could not set file permissions on $path", ioe)
        case se: SecurityException => log.error(s"Not enough privileges to set file permissions on $path", se)
        case iae: IllegalArgumentException => log.error(s"Incorrect permissions input string: $permissions, could not set permission on $path", iae)
        case e => log.error(s"Unexpected exception on $path", e)
      } unsafeGetOrThrow
    }

    override def postVisitDirectory(dir: Path, ex: IOException): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on directory $dir")
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions))
      if (ex == null) FileVisitResult.CONTINUE
      else FileVisitResult.TERMINATE
    }
  }
}
