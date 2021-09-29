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

import gov.loc.repository.bagit.Manifest.Algorithm
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.{ Bag, Manifest }
import nl.knaw.dans.easy.sword2.DepositHandler.formatMessages
import nl.knaw.dans.lib.error._

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }

trait BagValidationExtension {

  def verifyBagIsValid(bag: Bag)(implicit depositId: DepositId): Try[SimpleResult] = {
    (bag.getPayloadManifests.asScala.toList ::: bag.getTagManifests.asScala.toList)
      .map(verifyPayloadManifestAlgorithm)
      .collectResults
      .recoverWith {
        case e @ CompositeException(throwables) => Failure(InvalidDepositException(depositId, formatMessages(throwables.map(_.getMessage), ""), e))
      }
      .map(_ => bag.verifyValid)
  }

  private def verifyPayloadManifestAlgorithm(manifest: Manifest)(implicit depositId: DepositId): Try[Unit] = {
    Try(manifest.getAlgorithm) //throws message-less IllegalArgumentException when manifest cannot be found
      .fold(_ => Failure(InvalidDepositException(depositId, s"Unrecognized algorithm for manifest: ${ manifest.getFilepath }. Supported algorithms are: ${ BagValidationExtension.acceptedValues }.")), _ => Success(()))
  }
}

object BagValidationExtension {
  lazy val acceptedValues: String = Algorithm
    .values()
    .map(_.bagItAlgorithm)
    .mkString(", ")
}
