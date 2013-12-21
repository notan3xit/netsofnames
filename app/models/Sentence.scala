package models

import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import java.sql.Connection
import controllers.Application

/**
 * Object representation of sentences.
 */
case class Sentence(id: Long, text: String)

/**
 * Companion object and DAO for sentences.
 */
object Sentence {
  
  /**
   * Simple parser of sentence result sets.
   */
  val simple = {
    get[Long]("id")~
    get[String]("sentence") map {
      case id~sentence => new Sentence(id, sentence)
    }
  }
  
  def byId(id: Long) = {
    DB.withConnection { implicit connection =>
      SQL("SELECT id, sentence FROM sentences WHERE id = {id}")
        .on('id -> id)
        .as(simple.singleOpt)
    }
  }
  
  /**
   * Retrieves all sentences from the database that match a given pattern and passes each of them to the provided callback method.
   * This operation is based on the database result stream and acts lazily (with the connection to the database being
   * upheld until all entities are processed).
   * 
   * Parsing and returning a list of sentences is infeasible for all sentences due to their large amount and a memory
   * leak in Anorm's parser.
   */
  def processAllMatchingWithRelationships(pattern: String, op: (Sentence, Relationship) => Unit) = {
    DB.withConnection { implicit connection =>
      allMatchingWithRelationshipsLazy(pattern) foreach { case (sentence, relationship) =>
        op(sentence, relationship)
      }
    }
  }
  
  /*
   * Retrieves sentences matching a certain pattern from the database and lazily parses the resulting stream without using the
   * Anorm parser, which would result in a heap overflow. 
   */
  private def allMatchingWithRelationshipsLazy(sqlPattern: String)(implicit connection: Connection) = {
    // get sentences from database as a Stream
    val stream = SQL("""
      SELECT
        s.id sentence_id,
        s.sentence sentence,
        r2s.relationship_id relationship_id
      FROM sentences s
      JOIN relationships_to_sentences r2s ON r2s.sentence_id = s.id
      WHERE sentence LIKE {pattern}
    """).on(
      'pattern -> sqlPattern
    )() // application creates result stream
    
    // create and return an iterator that parses stream elements one by one
    new Iterator[(Sentence, Relationship)]() {
      private var rest = stream
      
      override def hasNext = {
        !rest.isEmpty
      }
      
      override def next() = {
        val head = rest.head
        rest = rest.tail
        (new Sentence(head.get[Long]("sentence_id").get, head.get[String]("sentence").get),
          CompleteGraph.relationships(head.get[Long]("relationship_id").get))
      }
    }
  }
  
  def byPattern(patternId: Long, limit: Option[Long] = None) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT s.id, s.sentence
        FROM patterns_to_tags p2t
          JOIN tags t ON p2t.tag_id = t.id
          JOIN sentences s ON t.sentence_id = s.id
        WHERE p2t.pattern_id = {pId}
        %s
      """.format(if (limit.isDefined) "LIMIT " + limit.get else "")).on(
        'pId -> patternId
      ).as(simple.*)
    }
  }
}