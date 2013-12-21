package util

import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import org.tartarus.snowball.ext.GermanStemmer
import edu.stanford.nlp.process.PTBTokenizer
import java.io.StringReader
import scala.collection.JavaConversions._

/**
 * Implements additional methods to Scala's <tt>List</tt>.
 */
class UpList[T](underlying: List[T]) {

  /**
   * Samples <tt>n</tt> elements from the underlying list deterministcally, by taking
   * elements in equally distributed steps.
   */
  def sample(n: Int) = {
    val step = (underlying.length - 1).toFloat / (n - 1)
    ((0 until n) map { i =>
      val index = math.round(i * step)
      underlying(index)
    }).toList
  }
}

object UpList {
  
  implicit def upList[T](list: List[T]) = {
    new UpList(list)
  }
}