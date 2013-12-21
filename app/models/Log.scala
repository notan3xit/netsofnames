package models

import org.joda.time.DateTime
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import play.api.mvc.Request
import util.AnormExtension._
import java.sql.Connection
import controllers.Application

/**
 * Action logging for the system, i.e. interesting interactions performed by the user (this is complementary
 * to application logging for debugging and other similar purposes). Logging is persisted into a predefined
 * database format for easier querying.
 * 
 * The database schema is
 * 
 *   name | ref1 | ref2 | from | to | uid
 * 
 * where name idenfifies the type of action, ref{1,2} contain relevant parameters to the action (if applicable),
 * and from and to specify the timeframe filter active during the operation. UID is used to identify users. It
 * is stored into the user session during first access to the frontend (see <tt>Application.index</tt>).
 */
object Log {
  
  /** User conducts a search for a single name. */
  val SearchName = "SEARCH_NAME"
    
  /** User conducts a search for a a relationship between two names. */
  val SearchRelationship = "SEARCH_REL"
    
  /** User views the sources of a relationship. */
  val RelationshipAccess = "REL_ACCESS"
    
  /** User creates a tag. */
  val TagCreate = "TAG_CREATE"
    
  /** User accepts a tag. */
  val TagVotePositive = "TAG_POSITIVE"
    
  /** User rejects a tag. */
  val TagVoteNegative = "TAG_NEGATIVE"
  
  private def log(action: String, ref1: Option[Long], ref2: Option[Long], from: Option[Long], to: Option[Long])(implicit request: Request[Any]) = {
    if (Application.LogActionsEnabled) {
      val uid = request.session.get("uid").getOrElse("")
      DB.withConnection { implicit connection =>
        SQL("""
          INSERT INTO logs
            (action, ref1, ref2, timeframe_from, timeframe_to, uid)
          VALUES
            ({action}, {ref1}, {ref2}, {from}, {to}, {uid})
        """).on(
          'action -> action,
          'ref1 -> ref1,
          'ref2 -> ref2,
          'from -> from.map(new DateTime(_)),
          'to -> to.map(new DateTime(_)),
          'uid -> uid
        ).execute()
      }
    }
  }
  
  def searchName(entityId: Long, from: Option[Long], to: Option[Long])(implicit request: Request[Any]) = {
    log(SearchName, Some(entityId), None, from, to)
  }
  
  def searchRelationship(entity1Id: Long, entity2Id: Long, from: Option[Long], to: Option[Long])(implicit request: Request[Any]) = {
    log(SearchRelationship, Some(entity1Id), Some(entity2Id), from, to)
  }

  def relationshipAccess(relationshipId: Long, from: Option[Long], to: Option[Long])(implicit request: Request[Any]) = {
    log(RelationshipAccess, Some(relationshipId), None, from, to)
  }
  
  def tagCreate(tagId: Long)(implicit request: Request[Any]) = {
    log(TagCreate, Some(tagId), None, None, None)
  }
  
  def tagVotePositive(tagId: Long)(implicit request: Request[Any]) = {
    log(TagVotePositive, Some(tagId), None, None, None)
  }
  
  def tagVoteNegative(tagId: Long)(implicit request: Request[Any]) = {
    log(TagVoteNegative, Some(tagId), None, None, None)
  }
  
  /**
   * Returns for each UID the counts for each action. The resulting data structure is a map like:
   * 
   * Map(
   *   uid -> Map(
   *     "searches" -> number,
   *     "relviews" -> number,
   *     "tags" -> number,
   *     "pos" -> number,
   *     "neg" -> number
   *   )
   * )
   */
  def counts() = {
    DB.withConnection { implicit connection =>
      val users = SQL("""
        SELECT DISTINCT uid FROM logs
      """).as(scalar[String].*)
      val searchesN = countByAction(SearchName)
      val searchesR = countByAction(SearchRelationship)
      val relviews = countByAction(RelationshipAccess)
      val tags = countByAction(TagCreate)
      val pos = countByAction(TagVotePositive)
      val neg = countByAction(TagVoteNegative)
      
      users map { uid =>
        uid -> Map(
          "searches" -> (searchesN(uid) + searchesR(uid)),
          "relviews" -> relviews(uid),
          "tags" -> tags(uid),
          "pos" -> pos(uid),
          "neg" -> neg(uid)
        )
      } toMap
    }
  }
  
  private def countByAction(action: String)(implicit connection: Connection) = {
    SQL("""
      SELECT uid, count(*) AS c FROM logs WHERE action = {action} GROUP BY uid
    """).on(
      'action -> action
    ).as((get[String]("uid")~get[Long]("c") map flatten).*).toMap.withDefault(_ => 0l)
  }
}