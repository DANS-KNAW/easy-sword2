package nl.knaw.dans.api.sword2

import java.io._

import org.apache.commons.io.IOUtils

import scala.util.Try

object MergeFiles {

  def merge(destination: File, files: Seq[File]): Try[Unit] = Try {
    var output: OutputStream = null
    try {
      output = createAppendableStream(destination)
      files.foreach(appendFile(output))
    } finally {
      IOUtils.closeQuietly(output)
    }
  }

  @throws(classOf[FileNotFoundException])
  private def createAppendableStream(destination: File): BufferedOutputStream =
    new BufferedOutputStream(new FileOutputStream(destination, true))

  @throws(classOf[IOException])
  private def appendFile(output: OutputStream)(file: File) {
    var input: InputStream = null
    try {
      input = new BufferedInputStream(new FileInputStream(file))
      IOUtils.copy(input, output)
    } finally {
      IOUtils.closeQuietly(input)
    }
  }

}


