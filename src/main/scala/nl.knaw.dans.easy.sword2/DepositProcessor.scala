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

import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import rx.lang.scala.Subscription
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

object DepositProcessor extends DebugEnhancedLogging {

  private val depositProcessingStream = PublishSubject[(DepositId, MimeType)]()

  def startDepositProcessingStream(implicit settings: Settings): Subscription = {
    depositProcessingStream
      .onBackpressureBuffer
      .observeOn(NewThreadScheduler())
      .subscribe(
        onNext = {
          case (id, mimeType) => DepositFinalizer.finalizeDeposit(mimeType)(settings, id)
        }
      )
  }

  def startUploadedDeposits()(implicit settings: Settings): Unit = {
    DepositPropertiesFactory.getSword2UploadedDeposits
      .doIfFailure {
        case e => logger.warn(s"Count not fetch uploaded deposits: ${ e.getMessage }", e)
      }
      .unsafeGetOrThrow
      .foreach {
        case (depositId, mimetype) =>
          logger.info(s"[$depositId] Scheduling UPLOADED deposit for finalizing.")
          processDeposit(depositId, mimetype)
      }
  }

  def processDeposit(id: DepositId, mimetype: MimeType): Unit = {
    depositProcessingStream.onNext((id, mimetype))
  }
}
