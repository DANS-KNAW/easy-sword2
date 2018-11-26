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

import java.nio.file._
import java.nio.file.attribute.PosixFilePermission
import java.util

import org.scalatest.BeforeAndAfterEach

class FilePermissionServiceSpec extends TestSupportFixture with BeforeAndAfterEach {

  lazy private val inputDir = {
    val path = testDir / "input/"
    if (path.exists) path.delete()
    path.createDirectories()
    path
  }
  private val bagSequenceDir = inputDir / "bag-sequence"
  private val bagSequenceId = "bag-sequence"
  private val bagSequenceDirPath = bagSequenceDir.toJava.toPath
  private val ownerAndGroupRightsString = "rwxrwx---"
  private val ownerRights = "rxw------"

  override def beforeEach: Unit = {
    super.beforeEach()
    inputDir.clear()
    better.files.File(getClass.getResource("/input/").toURI).copyTo(inputDir)
  }

  "changePermissionsForAllDepositContent" should "give write access to the group when given string rwxrwx---" in {
    giveDirectoryAndAllContentOnlyOwnerRights(bagSequenceDirPath)

    FilesPermissionService.changePermissionsForDirectoryAndContent(bagSequenceDir.toJava, ownerAndGroupRightsString, bagSequenceId)
    Files.getPosixFilePermissions(bagSequenceDirPath) should contain only(
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.GROUP_READ,
      PosixFilePermission.GROUP_WRITE,
    )
  }

  "changePermissionsForAllDepositContent" should "not give write access to the group when given string rwx------" in {
    giveDirectoryAndAllContentOnlyOwnerRights(bagSequenceDirPath)

    FilesPermissionService.changePermissionsForDirectoryAndContent(bagSequenceDir.toJava, ownerRights, bagSequenceId)
    Files.getPosixFilePermissions(bagSequenceDirPath) should contain only(
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
    )
  }


  private def giveDirectoryAndAllContentOnlyOwnerRights(depositDir: Path) = {
    val onlyOwnerPerm: util.Set[PosixFilePermission] = new util.HashSet()
    onlyOwnerPerm.add(PosixFilePermission.OWNER_EXECUTE)
    onlyOwnerPerm.add(PosixFilePermission.OWNER_READ)
    onlyOwnerPerm.add(PosixFilePermission.OWNER_WRITE)
    Files.setPosixFilePermissions(bagSequenceDir.toJava.toPath, onlyOwnerPerm) // all files in the test dir start with group access

    Files.getPosixFilePermissions(bagSequenceDir.toJava.toPath) should contain only(
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
    )
  }
}
