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

import java.io.{ File => JFile }

import gov.loc.repository.bagit.transformer.impl.TagManifestCompleter
import gov.loc.repository.bagit.writer.impl.FileSystemWriter
import gov.loc.repository.bagit.{ Bag, BagFactory, FetchTxt }

import scala.collection.JavaConverters._
import scala.util.{ Success, Try }

object BagInteractor {

  private val bagFactory: BagFactory = new BagFactory

  def getBag(bagDir: JFile): Try[Bag] = Try {
    bagFactory.createBag(bagDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
  }

  def getFetchTxt(bagDir: JFile): Try[FetchTxt] = {
    getBag(bagDir).map(_.getFetchTxt).filter(_ != null)
  }

  def pruneFetchTxt(bagDir: JFile, items: Seq[FetchTxt.FilenameSizeUrl]): Try[Unit] = {
    getBag(bagDir)
      .map(bag => {
        Option(bag.getFetchTxt).map(fetchTxt => Try {
          fetchTxt.removeAll(items.asJava)
          if (fetchTxt.isEmpty) bag.removeBagFile(bag.getBagConstants.getFetchTxt)
          // TODO: Remove the loop. Complete needs to be called only once for all tagmanifests. See easy-ingest-flow FlowStepEnrichMetadata.updateTagManifests
          bag.getTagManifests.asScala.map(_.getAlgorithm).foreach(a => {
            val completer = new TagManifestCompleter(bagFactory)
            completer.setTagManifestAlgorithm(a)
            completer.complete(bag)
          })
          val writer = new FileSystemWriter(bagFactory)
          writer.setTagFilesOnly(true)
          bag.write(writer, bagDir)
        }).getOrElse(Success(()))
      })
  }
}
