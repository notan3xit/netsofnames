package models

import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import org.joda.time.DateTime
import java.util.Date
import play.Logger
import java.sql.Connection

/**
 * Object representation for relationships.
 * 
 * @param id Unique id and primary key of the relationship.
 * @param e1 First entity. The order of entities is determined alphabetically. Especially, if (e1, e2) is a relationship,
 *   (e2, e1) is not.
 * @param e2 Second entity.
 * @param frequency Frequency of the relationship (i.e. co-occurrence) in the underlying data.
 * @param timestamps List of timestamps that represent dates of sources for this relationship. Used when filtering the graph.
 */
case class Relationship(id: Pk[Long], e1: Long, e2: Long, frequency: Int, timestamps: Option[List[Long]] = None) {
  
  def relationshipType = RelationshipType.Sentence
  
  def entity1 = CompleteGraph.entities(e1)
  def entity2 = CompleteGraph.entities(e2)
  
  /**
   * Returns all sources for this relationship.
   */
  def sources = Source.byRelationship(id.get)
  
  /**
   * Returns all tags for this relationships as a Map mapping the respective sentence ids to tags associated with them. 
   */
  def tags() = {
    Tag.byRelationship(id.get)
  }
  
  /**
   * Returns <b>true<b> iff a person is part of this relationship.
   */
  def hasPerson() = {
    entity1.isPerson || entity2.isPerson
  }
  
  /**
   * Returns <b>true<b> iff an organization is part of this relationship.
   */
  def hasOrganization() = {
    entity1.isOrganization || entity2.isOrganization
  }
  
  override def toString = {
    "Relationship(%d, %s, %s, %d)".format(id.get, entity1, entity2, frequency)
  }
}

/**
 * Possible relationship types.
 */
object RelationshipType extends Enumeration {
  val Sentence = Value
}

/**
 * Companion object and DAO for relationships.
 */
object Relationship extends ((Pk[Long], Long, Long, Int, Option[List[Long]]) => Relationship) {
  
  import util.AnormExtension._
  
  // NOTE no parser is present for result sets, because relationships are either batch processed
  // (which causes heap overflows with Anorm anyway) or require aggregation of timestamps.
  
  /**
   * Retrieves all relationships from the database and passes each of them to the provided callback method.
   * This operation is based on the database result stream and acts lazily (with the connection to the database being
   * upheld until all entities are processed).
   * 
   * Parsing and returning a list of relationships is infeasible for all relationships due to their large amount and a memory
   * leak in Anorm's parser.
   */
  def processAll(op: Relationship => Unit) = {
    DB.withConnection { implicit connection =>
      allLazy() foreach { relationship =>
        op(relationship)
      }
    }
  }
  
  /*
   * Retrieves entities from the database and lazily parses the resulting stream without using an Anorm parser,
   * which would result in a heap overflow. 
   */
  private def allLazy()(implicit connection: Connection) = {
    import util.Benchmark._
    
    // get relationships from the database as a Stream
    var results = SQL("""
      SELECT r.id AS id, r.type as type, r.entity1 AS e1, r.entity2 AS e2, r.frequency AS frequency, s.date AS date
      FROM relationships AS r
      JOIN relationships_to_sentences AS r2s ON r2s.relationship_id = r.id
      JOIN sentences_to_sources AS s2s ON s2s.sentence_id = r2s.sentence_id
      JOIN sources AS s ON s.id = s2s.source_id
      ORDER BY r.id
    """)() // application creates result stream
    
    // create and return an iterator that parses stream elements one by one
    new Iterator[Relationship]() {
      private var rest = results
      
      override def hasNext = {
        !rest.isEmpty
      }
      
      override def next() = {
        // parse head element
        val head = rest.head
        val (id, e1, e2, frequency) = (
          head.get[Long]("id").get,
          head.get[Long]("e1").get,
          head.get[Long]("e2").get,
          head.get[Int]("frequency").get
        )
        
        // get all timestamps (as long as the results refer to the same primary key)
        val (same, others) = rest.span(_.get[Long]("id").get == id)
        val times = same.map(_.get[Date]("date").get.getTime()).toList
        
        // drop processed elements and construct relationship
        rest = others
        new Relationship(Id(id), e1, e2, frequency, Some(times))
      }
    }
  }
  
  def byId(id: Long) = {
    DB.withConnection { implicit connection =>
      val results = SQL("""
        SELECT r.id AS id, r.type as type, r.entity1 AS e1, r.entity2 AS e2, r.frequency AS frequency, s.date AS date
        FROM relationships AS r
        JOIN relationships_to_sentences AS r2s ON r2s.relationship_id = r.id
        JOIN sentences_to_sources AS s2s ON s2s.sentence_id = r2s.sentence_id
        JOIN sources AS s ON s.id = s2s.source_id
        WHERE r.id = {id}
      """).on(
        'id -> id
      )()
      results.headOption map { head =>
        val (id, e1, e2, frequency) = (
          head.get[Long]("id").get,
          head.get[Long]("e1").get,
          head.get[Long]("e2").get,
          head.get[Int]("frequency").get
        )
        val times = results.map(_.get[Date]("date").get.getTime()).toList 
        new Relationship(Id(id), e1, e2, frequency, Some(times))
      }
    }
  }
  
  /**
   * Returns all relationship ids associated with a given sentence.
   */
  def idsBySentence(sId: Long)(implicit connection: Connection) = {
    SQL("""
      SELECT r.id
      FROM relationships_to_sentences r2s
      JOIN relationships r ON r.id = r2s.relationship_id
      WHERE r2s.sentence_id = {sId}
    """).on(
      'sId -> sId
    ).as(scalar[Long].*)
  }
}