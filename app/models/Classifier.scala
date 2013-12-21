package models

import akka.actor.Actor
import play.Logger
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import controllers.Application
import akka.actor.Props

/**
 * A classifier for the discovery and labelling of semantic relationships in natural language text. This module is responsible
 * for automatically learning lexico-syntactic patterns from user-interaction and apply the patterns to find and label other instances
 * of (possibly) similar relationships.
 * 
 * The classifier is implemented as an Akka actor to operate in the background. When a user tags a sentence, learning and application
 * tasks are fired, but control can return instantly.
 * 
 * TODO the classifier currently applies patterns to the complete database. If single source sentences are added to the database,
 * the possibilities of pattern application should be extended to single sentences.
 */
class Classifier extends Actor {

  import util.UpString._
  import util.Benchmark._
  
  /**
   * Accepts three kinds of messages: <tt>Learn</tt>, <tt>Apply</tt> and <tt>CallWhenDone</tt>.
   * 
   * @see Learn
   * @see Apply
   * @see CallWhenDone
   */
  override def receive = {
    case t @ Learn(tag) =>
      // learn new patterns and subsequently apply them
      val patterns = Learner.learn(tag).withBenchmark("Leaning patterns from tag %s".format(tag))
      for (pattern <- patterns) {
        applyPattern(pattern)
      }
    case Apply(pattern) =>
      // apply a specific pattern
      applyPattern(pattern)
    case CallWhenDone(op) =>
      // execute callback
      op()
  }
  
  /*
   * Pattern application is performed in two steps: First, the pattern is transformed to a low-precision
   * SQL-query that finds and returns sentence-relationship paits for sentences that could match the patterns
   * (in that they contain all static parts of the pattern in the correct order). Second, the selected
   * sentences are matched (exactly) against the pattern using regular expressions. If matches are found,
   * the application results in tags that are created and stored.
   */
  private def applyPattern(pattern: Pattern) = {
    import util.Benchmark._
    // get SQL pattern
    val sqlPattern = pattern.sqlPattern
    
    // process all sentence-relationship pairs that could match the pattern
    Sentence.processAllMatchingWithRelationships(sqlPattern, processSentenceWithRelationship(pattern, _, _))
      .withBenchmark("Applying pattern %s...".format(pattern.rule))
  }
  
  private def processSentenceWithRelationship(pattern: Pattern, sentence: Sentence, relationship: Relationship) = {
    // apply the pattern
    val tags = pattern(sentence, relationship)
    
    // persist tags (the list might be empty, if the tag list return from application is empty)
    tags foreach { tag =>
      Tag.createOrGet(tag, Some(pattern))
    }
  }
}

/**
 * Superclass for messages understood by the classifier.
 */
trait ClassifierMessage

/** Given a <tt>tag</tt>, extracts a pattern (if possible), and subsequently applies it.
 *  This task is potentially long-running, because pattern application is long-running.
 *  
 *  Note that pattern learning and application could be decoupled, but are combined here into one task to make it easier
 *  to reason about the system state (for development and evaluation).
 */
case class Learn(tag: Tag) extends ClassifierMessage

/** Given a <tt>pattern</tt>, applies it to relationships in the database to generate tags and persists the resulting tags.
 *  This task is potentially long-running, because it scans the complete database.  
 */
case class Apply(pattern: Pattern) extends ClassifierMessage

/** Makes the classifier call the <tt>callback</tt> once it reaches this message. 
 *  Used to queue tasks for execution once existing learning and application terminates.
 */
case class CallWhenDone(callback: () => Unit) extends ClassifierMessage

/**
 * Classifier submodule that contains all implementation dealing with the extraction of patterns from text, given a user
 * created tag.
 */
object Learner {

  import util.UpString._
  
  /**
   * Given a <tt>tag</tt>, possibly learns a lexico-syntactic pattern. The tag contains information on the relationship
   * (and thus involved entities and their names) as well as the tag label. This information is used to generalize
   * the pattern by replacing entity names and keywords contained in the label by wildcards.
   * 
   * For instance, given a sentence like
   * 
   *   "... said A, chief executive of B, ..."
   * 
   * and the tag "executive of >" for a person A and an organization B, the system will find the keyword "executive" and
   * generalize the following:
   * 
   *   Pattern: "<PERSON/S>, chief <WORD> of <ORGANIZATION/O>"
   *   Label: "<WORD> of", with direction ">" (left-to-right)
   * 
   * The direction is encoded in the pattern by denoting the (simplified) semantic role of the entities by S (for subject) and
   * O (for object) to signal a direction from S to O. An undirected tag is encoded by assigning S as a rule to both entity wildcards.
   */
  def learn(tag: Tag) = {
    val relationship = tag.relationship.get
    val sentence = tag.sentence.get
    val keywords = tag.label.tokensWithoutStopwords(lower = false)
      .filter { sentence.text.contains }
    val patterns = if (keywords.size == 1) {
      learnKeywordPattern(tag, relationship, sentence, keywords.head)
    } else {
      learnSimplePattern(tag, relationship, sentence)
    }
    patterns
  }
  
  /*
   * Implements extraction of patterns with no keyword in the label (this is the case, if the label consists of only stopwords,
   * like "is a", or contains multiple keywords, like in "chief executive officer of").
   * 
   * TODO create learning abstraction for learn*Pattern with less code duplication for easier maintenance.
   */
  private def learnSimplePattern(tag: Tag, relationship: Relationship, sentence: Sentence) = {
    // get entity names
    val name1 = relationship.entity1.name
    val name2 = relationship.entity2.name
    
    // find substrings that contain both entity names and something in between
    val regex = """(?i)(%1$s|%2$s)(.*?[\p{L}-].*?)(%1$s|%2$s)""".format(name1, name2)
    val matches = sentence.text.findAll(regex) filter { m =>
      m.containsOnce(name1, caseInsensitive = true) && m.containsOnce(name2, caseInsensitive = true) && // contains both names
      !m.replaceAll(name1, "").replaceAll(name2, "").trim().tokensWithoutStopwords().isEmpty // contains not only stopword
    }
    
    // replace actual names by types, including the encoding of tag direction by their semantic roles
    val patterns = (matches map { m =>
      val rule =
        m.replaceAll(name1, "<%s/%s>".format(relationship.entity1.typeString, tag.entity1Role))
         .replaceAll(name2, "<%s/%s>".format(relationship.entity2.typeString, tag.entity2Role))
      Pattern.create(new Pattern(NotAssigned, rule, tag.label, 0, 0, 0), tag.id.get)
    }).toList
    
    Logger.debug("Learned %d simple patterns.".format(patterns.size))
    patterns
  }
  
  /*
   * Implements extraction of patterns with a keyword present in the label (this is the case if the label contains exactly
   * one non-stopword).
   * 
   * TODO create learning abstraction for learn*Pattern with less code duplication for easier maintenance.
   */
  private def learnKeywordPattern(tag: Tag, relationship: Relationship, sentence: Sentence, keyword: String) = {
    // get entity names
    val name1 = relationship.entity1.name
    val name2 = relationship.entity2.name
    
    // find substrings that contain the keyword left from, right from, or between the two entity names
    val regexL = """(?i)(%3$s)(.{3,}?)(%1$s|%2$s)(.*?)(%1$s|%2$s)""".format(name1, name2, keyword)
    val regexR = """(?i)(%1$s|%2$s)(.*?)(%1$s|%2$s)(.{3,}?)(%3$s)""".format(name1, name2, keyword)
    val regexI = """(?i)(%1$s|%2$s)(.+?%3$s.*?|.*?%3$s.+?)(%1$s|%2$s)""".format(name1, name2, keyword)
    val matches = List(regexL, regexR, regexI) flatMap { regex =>
      sentence.text.findAll(regex) filter { m =>
        m.containsOnce(name1, caseInsensitive = true) && m.containsOnce(name2, caseInsensitive = true)
      }
    }
    
    // replace actual names by types, including the encoding of tag direction by their semantic roles
    // replace the keyword by a word wildcard
    val patterns = (matches map { m =>
      val rule =
        m.replaceAll(name1, "<%s/%s>".format(relationship.entity1.typeString, tag.entity1Role))
         .replaceAll(name2, "<%s/%s>".format(relationship.entity2.typeString, tag.entity2Role))
         .replaceAll(keyword, "<WORD>")
      Pattern.create(new Pattern(NotAssigned, rule, tag.label.replaceAll(keyword, "<WORD>"), 0, 0, 0), tag.id.get)
    }).toList
    
    Logger.debug("Learned %d keyword patterns.".format(patterns.size))
    patterns
  }
}