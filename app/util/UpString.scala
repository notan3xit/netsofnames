package util

import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import org.tartarus.snowball.ext.GermanStemmer
import edu.stanford.nlp.process.PTBTokenizer
import java.io.StringReader
import scala.collection.JavaConversions._

/**
 * Implements additional methods for <tt>String</tt>.
 * 
 * Language features currently assume that the language is German.
 */
class UpString(underlying: String) {
  
  /**
   * Writes the underlying string to a file given by <tt>path</tt>.
   */
  def writeToFile(path: Path): String = {
    val out = new PrintWriter(path.toFile(), "UTF-8")
    try {
      out.print(underlying)
    } finally { out.close }
    underlying
  }
  
  def writeToFile(location: String): String = {
    val path = Paths.get(location)
    writeToFile(if (path.isAbsolute()) path else Paths.get(System.getProperty("user.home")).resolve(path))
  }
  
  /**
   * Returns a List of all (starting) indexes of a given substring <tt>s</tt> (<tt>String.indexOf</tt> returns
   * only the first occurrence).
   */
  def indexesOf(s: String, offset: Int = 0): List[Int] = {
    underlying.indexOf(s, offset) match {
      case -1 => Nil
      case i: Int => i :: indexesOf(s, offset + i + 1)
    }
  }
  
  /**
   * Converts the string to a list of tokens.
   */
  def tokens(lower: Boolean = true) = {
    LanguageProcessing.tokens(underlying, lower)
  }
  
  /**
   * Converts the string to a list of tokens, with stropwords removed.
   */
  def tokensWithoutStopwords(lower: Boolean = true) = {
    LanguageProcessing.tokensWithoutStopwords(underlying, lower)
  }
  
  /**
   * Convertes the string to a list of stemmed tokens.
   */
  def stems = {
    LanguageProcessing.stems(underlying)
  }
  
  /**
   * Convertes the string to a list of stemmed tokens, with stopwords removed.
   */
  def stemsWithoutStopwords = {
    LanguageProcessing.stemsWithoutStopwords(underlying)
  }
  
  /**
   * Finds all occurrences of a <tt>pattern</tt> (given as a regular expression) in the string. This
   * fixes the problem that matches may not overlap in the default implementation. The regular expression
   * passed to this method is extended to not consume the string, so it is available for further matches.
   */
  def findAll(pattern: String) = {
    val fixed = "(?=(" + pattern + "))"
    fixed.r.findAllMatchIn(underlying) map { _.group(1) }
  }
  
  /**
   * Returns <b>true</b> if a given <tt>substring</tt> exists exactly once in the underlying string.
   */
  def containsOnce(substring: String, caseInsensitive: Boolean = false) = {
    val haystack = if (caseInsensitive) underlying.toLowerCase() else underlying
    val needle = if (caseInsensitive) substring.toLowerCase() else substring
    val first = haystack.indexOf(needle)
    first != -1 && haystack.indexOf(needle, first + 1) == -1
  }
}

object UpString {
  
  implicit def upString(string: String) = {
    new UpString(string)
  }
}

/**
 * Language processing features (for German).
 */
object LanguageProcessing {
  
  // German Porter stemmer.
  val Stemmer = new GermanStemmer();
  
  // German stropwords.
  val Stopwords = Set("and", "the", "of", "to", "einer",
      "eine", "eines", "einem", "einen", "der", "die", "das",
      "dass", "daß", "du", "er", "sie", "es", "was", "wer",
      "wie", "wir", "und", "oder", "ohne", "mit", "am", "im",
      "in", "aus", "auf", "ist", "sein", "war", "wird", "ihr",
      "ihre", "ihres", "ihnen", "ihrer", "als", "für", "von",
      "mit", "dich", "dir", "mich", "mir", "mein", "sein",
      "kein", "durch", "wegen", "wird", "sich", "bei", "beim",
      "noch", "den", "dem", "zu", "zur", "zum", "auf", "ein",
      "auch", "werden", "an", "des", "sein", "sind", "vor",
      "nicht", "sehr", "um", "unsere", "ohne", "so", "da", "nur",
      "diese", "dieser", "diesem", "dieses", "nach", "über",
      "mehr", "hat", "bis", "uns", "unser", "unserer", "unserem",
      "unsers", "euch", "euers", "euer", "eurem", "ihr", "ihres",
      "ihrer", "ihrem", "alle", "vom")
  
  /**
   * Given a stopword, returns <b>true</b> iff it is a stopword.
   */
  def isStopword(word: String) = {
    Stopwords.contains(word.trim().toLowerCase())
  }    
  
  /**
   * Given a <tt>sentence</tt>, returns a list of tokens.
   */
  def tokens(sentence: String, lower: Boolean = true) = {
    PTBTokenizer.newPTBTokenizer(new StringReader(sentence)).tokenize()
      .map { _.word.trim() }
      .filter { !_.isEmpty }
      .map { word => if (lower) word.toLowerCase() else word}
      .toList
  }
  
  /**
   * Given a <tt>sentence</tt>, returns a list of tokens, with stopwords removed.
   */
  def tokensWithoutStopwords(sentence: String, lower: Boolean = true) = {
    tokens(sentence, lower).filter(token => !Stopwords.contains(token.toLowerCase()))
  }
  
  /**
   * Given a <tt>sentence</tt>, returns a list of stemmed tokens.
   */
  def stemsWithoutStopwords(sentence: String) = {
    stems(tokensWithoutStopwords(sentence))
  }
  
  /**
   * Given a <tt>sentence</tt>, returns a list of stemmed tokens, with stopwords removed.
   */
  def stems(sentence: String): List[String] = {
    val ts = tokens(sentence)
    stems(ts)
  }
  
  /**
   * Given a list of <tt>tokens</tt>, returns a corresponding list with stemmed tokens.
   */
  def stems(tokens: List[String]) = {
    tokens map { token =>
      Stemmer.setCurrent(token)
      Stemmer.stem()
      Stemmer.getCurrent()
    }
  }
}