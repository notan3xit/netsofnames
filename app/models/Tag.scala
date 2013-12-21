package models
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import java.sql.Connection
import util.Existed
import util.Created
import util.CreateOrGet
import controllers.Application
import play.Logger

/**
 * Object representation of tags.
 * 
 * @param id Unique id and primary key of the tag.
 * @param relationshipId References the relationship the tag is associated with. A pair of relationship and sentence unambiguously encodes the association of the tag.
 * @param sentenceId References the sentence the tag is associated with.
 * @param The tag's label.
 * @param The tag's direction (encoded by "r" for left-to-right, "l" for right-to-left, and "b" for undirected).
 * @param automatic Denotes whether this tag was automatically created by the classifier or entered by a user.
 * 
 */
case class Tag(id: Pk[Long], relationshipId: Long, sentenceId: Long, label: String, direction: String, automatic: Boolean = false) {
  
  import Tag._
  
  def sentence() = Sentence.byId(sentenceId)
  def relationship() = Relationship.byId(relationshipId)

  /**
   * Returns <b>true<b> iff there is at least one instance of correct application of a pattern generated from this tag.
   */
  lazy val hasPositiveVotes = (0l /: Pattern.byTag(this))(_ + _.positiveVotes) > 0
  
  /**
   * Returns the semantic role of entity1 (from the associated relationship), based on the tag's direction.
   */
  val entity1Role = direction match {
    case Right => Subject
    case Left => Object
    case Both => Subject
  }
  
  /**
   * Returns the semantic role of entity2 (from the associated relationship), based on the tag's direction.
   */
  val entity2Role = direction match {
    case Right => Object
    case Left => Subject
    case Both => Subject
  }
  
  def withValues(id: Pk[Long] = this.id, relationshipId:Long = this.relationshipId, sentenceId: Long = this.sentenceId,
      label: String = this.label, direction: String = this.direction, automatic: Boolean = this.automatic) = {
    new Tag(id, relationshipId, sentenceId, label, direction, automatic)
  }
}

/**
 * Companion object and DAO for tags.
 */
object Tag {
  
  // directions
  val Right = "r"
  val Left = "l"
  val Both = "b"
  
  // linguistic toles
  val Subject = "S"
  val Object = "O"
  
  /**
   * Simple parser for tag result sets.
   */
  val simple = {
    get[Pk[Long]]("id")~
    get[Long]("relationship_id")~
    get[Long]("sentence_id")~
    get[String]("label")~
    get[String]("direction")~
    get[Boolean]("auto") map {
      case id~relationshipId~sentenceId~label~direction~auto => new Tag(id, relationshipId, sentenceId, label, direction, auto)
    }
  }
  
  def byId(id: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT
          t.id AS id,
          relationship_id,
          sentence_id,
          label,
          direction,
          auto
        FROM tags t
        JOIN labels l ON t.label_id = l.id
        WHERE t.id = {id}
      """).on(
        'id -> id
      ).as(simple.singleOpt)
    }
  }
  
  def byRelationship(relationshipId: Long, excludeDownvoted:Boolean = true) = {
    DB.withConnection { implicit connection =>
      // TODO 'SELECT DISTINCT' here prevents the query from producing tags more than once if mutliple
      // patterns generate them; this would especially need to be altered if directions would be taken into account better
      SQL("""
        SELECT DISTINCT
          t.id AS id,
          t.relationship_id AS relationship_id,
          t.sentence_id AS sentence_id,
          l.label AS label,
          t.direction AS direction,
          t.auto AS auto
        FROM tags t
        JOIN labels l ON t.label_id = l.id
        %s
        WHERE t.relationship_id = {rId}
        %s
      """.format(
        if (excludeDownvoted) "LEFT JOIN patterns_to_tags p2s ON t.id = p2s.tag_id" else "",
        if (excludeDownvoted) "AND (p2s.negative IS NULL OR p2s.negative = 0)" else ""
      )).on(
        'rId -> relationshipId
      ).as(simple.*) groupBy (_.sentenceId)
    }
  }
  
  def byLabel(relationshipId: Long, label: String) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT
          t.id AS id,
          relationship_id,
          sentence_id,
          label,
          direction,
          auto
        FROM  tags t
        JOIN  labels l ON t.label_id = l.id
        WHERE t.relationship_id = {rId}
        AND   l.label = {label}
      """).on(
        'rId -> relationshipId,
        'label -> label
      ).as(simple.*)
    }
  }
  
  def byValues(relationshipId: Long, sentenceId: Long, labelId: Long)(implicit connection: Connection) = {
    SQL("""
      SELECT
        t.id AS id,
        relationship_id,
        sentence_id,
        label,
        direction,
        auto
      FROM  tags t
      JOIN  labels l ON l.id = t.label_id
      WHERE t.relationship_id = {rId}
        AND t.sentence_id = {sId}
        AND t.label_id = {lId}
    """).on(
      'rId -> relationshipId,
      'sId -> sentenceId,
      'lId -> labelId
    ).as(simple.singleOpt)
  }
  
  def byPattern(patternId: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT
          t.id AS id,
          relationship_id,
          sentence_id,
          label,
          direction,
          auto
        FROM patterns_to_tags p2t
        JOIN tags t ON t.id = p2t.tag_id
        JOIN  labels l ON l.id = t.label_id
        WHERE p2t.pattern_id = {pId}
      """).on(
        'pId -> patternId
      ).as(simple.*)
    }
  }
  
  /**
   * Given a tag, creates it in the database or returns an already existing tag. If a tag was created,
   * Existed(tag) is returned, otherwise the result is Created(tag).
   * 
   * @param tag The tag.
   * @param pattern If the tag is automatically created, the pattern it was created from.
   * 
   * @see util.CreateOrGet
   */
  def createOrGet(tag: Tag, pattern: Option[Pattern] = None): CreateOrGet[Tag] = {
    // create new tag or get existing
    val entity: CreateOrGet[Tag] = DB.withTransaction { implicit connection =>
      // get the label id from database (create a new label if it does not exist)
      val labelId = getOrCreateLabel(tag.label)
      
      // get existing tag
      val tagOpt = byValues(tag.relationshipId, tag.sentenceId, labelId)
      
      // if the tag already exists and was automatically created, cast a positive vote and set it
      // to user-created
      if (tagOpt.isDefined && tagOpt.get.automatic) {
        val updated = SQL("""
          UPDATE patterns_to_tags
          SET    negative = 0, positive = 1
          WHERE  tag_id = {tId}
          AND    negative != 0
        """).on(
          'tId -> tagOpt.get.id
        ).executeUpdate()
        if (updated > 0) {
          SQL("UPDATE tags SET auto = false WHERE id = {tId}").on('tId -> tagOpt.get.id).execute()
          Created(tagOpt.get) 
        } else Existed(tagOpt.get)
      // if a user-created tag already exists, return it
      } else if (tagOpt.isDefined) {
        Existed(tagOpt.get)
      // otherwise, create a new tag and return it
      } else {
        val id = SQL("""
          INSERT INTO tags
            (relationship_id, sentence_id, label_id, direction, auto)
          VALUES
            ({rId}, {sId}, {lId}, {direction}, {auto})
        """).on(
          'rId -> tag.relationshipId,
          'sId -> tag.sentenceId,
          'lId -> labelId,
          'direction -> tag.direction,
          'auto -> tag.automatic
        ).executeInsert().get
        Created(tag.withValues(Id(id)))
      }
    }
    
    // if the tag was created automatically, link it to the respective pattern
    pattern foreach { p =>
      DB.withTransaction { implicit connection =>
        val found = SQL("SELECT count(*) FROM patterns_to_tags WHERE pattern_id = {pId} AND tag_id = {tId}")
          .on('pId -> p.id.get, 'tId -> entity.get.id).as(scalar[Long].single)
        if (found == 0) {
          SQL("""
            INSERT INTO patterns_to_tags
              (pattern_id, tag_id, positive, negative)
            VALUES ({pId}, {tId}, {pos}, 0)
          """).on(
            'pId -> p.id.get,
            'tId -> entity.get.id,
            'pos -> (if (!entity.get.automatic) 1 else 0) /* if tag was user-created (and thus already existed */
          ).executeInsert()
        }
      }
    }
    
    entity
  }
  
  def createOrGet(relationshipId: Long, sentenceId: Long, label: String, direction: String): CreateOrGet[Tag] = {
    val tag = new Tag(NotAssigned, relationshipId, sentenceId, label, direction)
    createOrGet(tag)
  }
  
  def remove(tagId: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        DELETE FROM tags
        WHERE id = {tId}"""
      ).on(
        'tId -> tagId
      ).execute()
    }
  }
  
  def updateLabel(tagId: Long, label: String) = {
    val labelId = getOrCreateLabel(label)
    DB.withConnection { implicit connection =>
      SQL("""
       UPDATE tags SET label_id = {lId} WHERE id = {tId} 
      """).on(
        'tId -> tagId,
        'lId -> labelId
      ).execute()
    }
  }
  
  def getOrCreateLabel(label: String) = {
    DB.withTransaction { implicit connection =>
      val idOpt = SQL("SELECT id FROM labels WHERE label = {label}")
        .on('label -> label)
        .as(get[Long]("id").singleOpt)
        
      idOpt getOrElse {
        SQL("INSERT INTO labels (label) VALUES ({label})").on('label -> label).executeInsert().get
      }
    }
  }
  
  def countAutomatic() = {
    DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM tags WHERE auto = true").as(scalar[Long].single)
    }
  }
  
  def countManual() = {
    DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM tags WHERE auto = false").as(scalar[Long].single)
    }
  }
}