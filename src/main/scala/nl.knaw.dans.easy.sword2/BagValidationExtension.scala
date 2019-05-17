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

import gov.loc.repository.bagit.Manifest.Algorithm
import gov.loc.repository.bagit.utilities.SimpleResult
import gov.loc.repository.bagit.{ Bag, Manifest }

import scala.collection.JavaConverters._
import scala.util.{ Failure, Success, Try }

trait BagValidationExtension {

  def verifyBagIsValid(bag: Bag)(implicit depositId: DepositId): Try[SimpleResult] = {
     verifyPayloadManifestAlgorithm(bag.getPayloadManifests.asScala.toList)
         .flatMap(_ => Success(bag.verifyValid))
  }

  private def verifyPayloadManifestAlgorithm(manifests: List[Manifest])(implicit depositId: DepositId): Try[Unit] = {
    manifests.map { manifest =>
      Try(manifest.getAlgorithm)
        .fold(_ => Failure(InvalidDepositException(depositId, s"unrecognized algorithm for payload manifest: supported algorithms are: ${ BagValidationExtension.acceptedValues }")), _ => Success(()))
    }.collectFirst { case f @ Failure(_: Exception) => f }
      .getOrElse(Success(()))
  }
}

object BagValidationExtension {
  lazy val acceptedValues: String = getSupportedAlgorithms

  private def getSupportedAlgorithms: String = {
    Algorithm
      .values()
      .flatMap(algo => List(algo.toString.toUpperCase(), algo.toString.toLowerCase()))
      .mkString(", ")
  }
}


