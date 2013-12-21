package models

import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import java.sql.Connection
import play.Logger

/**
 * Base class for the object representation of entities (people and organizations).
 */
abstract class Entity(val entityType: EntityType.Value) {
  
  //
  // Fields
  //
  
  /**
   * The unique id (which is also the primary key in the database) of the entity.
   */
  def id: Pk[Long]
  
  /**
   * The entity name.
   */
  def name: String
  
  /**
   * The entity's frequency in the underlying data.
   */
  def frequency: Int  
  
  //
  // Auxiliary methods
  //
  
  def isPerson = entityType == EntityType.Person
  def isOrganization = entityType == EntityType.Organization
  def typeString = {
    if (isPerson) "PERSON" else "ORGANIZATION"
  }
}

/**
 * The entity type (<tt>Person</tt> or <tt>Organization</tt>).
 */
object EntityType extends Enumeration {
  
  val Person = Value
  val Organization = Value
}

/**
 * Object representation of a person.
 */
case class Person(val id: Pk[Long] = NotAssigned, val name: String, val frequency: Int) extends Entity(EntityType.Person)

/**
 * Object representation of an organization.
 */
case class Organization(val id: Pk[Long] = NotAssigned, val name: String, val frequency: Int) extends Entity(EntityType.Organization)

/**
 * Companion object and DAO for entities.
 */
object Entity extends {
  
  /**
   * Parser for entity result sets.
   */
  def simple: RowParser[Entity] = {
    get[Pk[Long]]("entities.id")~
    get[Int]("entities.type")~
    get[String]("entities.name")~
    get[Int]("entities.frequency") map {
      case id~1~name~frequency => new Person(id, name, frequency)
      case id~2~name~frequency => new Organization(id, name, frequency) 
    }
  }
  
  /**
   * Retrieves all entities from the database and passes each of them to the provided callback method.
   * This operation is based on the database result stream and acts lazily (with the connection to the database being
   * upheld until all entities are processed).
   * 
   * Parsing and returning a list of entities is infeasible for all entities due to their large amount and a memory
   * leak in Anorm's parser.
   */
  def processAll(op: Entity => Unit) {
    DB.withConnection { implicit connection =>
      allLazy() foreach { entity =>
        op(entity)
      }
    }
  }
  
  /*
   * Retrieves entities from the database and lazily parses the resulting stream without using the Anorm parser,
   * which would result in a heap overflow. 
   */
  private def allLazy()(implicit connection: Connection) = {
    // get entities from database as a Stream
    val results = SQL("""
      SELECT *
      FROM   entities
    """)() // application creates result stream
    
    // create and return an iterator that parses stream elements one by one
    new Iterator[Entity]() {
      private var rest = results
      
      override def hasNext = {
        !rest.isEmpty
      }
      
      override def next() = {
        // parse elements that constitute an entity
        val head = rest.head
        val id = head.get[Long]("id").get
        val entityType = head.get[Int]("type").get
        val name = head.get[String]("name").get
        val frequency = head.get[Int]("frequency").get
        
        // drop the head of the stream
        rest = rest.drop(1)
        
        // construct the entity
        entityType match {
          case 1 => new Person(Id(id), name, frequency)
          case 2 => new Organization(Id(id), name, frequency)
        }
      }
    }
  }
  
  def byId(id: Long) = {
    DB.withConnection { implicit connection =>
      SQL(
        """
        SELECT *
        FROM   entities
        WHERE  id = {id}
        """
      ).on(
        'id -> id
      ).as(simple.single)
    }
  }
  
  def byName(name: String) = {
    DB.withConnection { implicit connection =>
      SQL(
        """
        SELECT *
        FROM   entities
        WHERE  name = {name}
        """
      ).on(
        'name -> name
      ).as(simple.singleOpt)
    }
  }
  
  def byType(t: EntityType.Value) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT *
        FROM   entities
        WHERE  type = {type}
      """).on(
        'type -> (if (t == EntityType.Person) 1 else 2)
      ).as(simple.*)
    }
  }
  
  def fromRelationships(relationships: Iterable[Relationship]) = {
    val entities = scala.collection.mutable.Set[Entity]()
    relationships foreach { relationship =>
      entities += relationship.entity1
      entities += relationship.entity2
    }
    entities
  }
  
  /**
   * Retrieves an ordered list of elements that appear as both types, Person and
   * Organization. Used to fix corrupt databases.
   */
  def listOfDuplicates() = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT * FROM entities e1
        WHERE EXISTS
          (SELECT * FROM entities e2
           WHERE e1.name = e2.name AND e1.id != e2.id)
        ORDER BY name ASC, frequency DESC
      """).as(simple.*)
    }
  }
}