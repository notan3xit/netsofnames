package controllers

import play.api.mvc.Controller
import models.Tag
import play.api.mvc.Action
import views.html.defaultpages.badRequest
import play.api.libs.json.{Json, JsString, JsArray, JsValue, JsResult, JsSuccess, Format, __}
import play.api.libs.json.Json._
import play.api.libs.json.Writes._
import play.api.libs.functional.syntax._
import anorm.Id
import play.api.http.MimeTypes
import play.Logger
import models.Learner
import models.Sentence
import models.Pattern
import models.Classifier
import models.Log
import models.Learn
import models.Apply

/**
 * Actions that handle tag creation, validation and retrieval.
 */
object Tags extends Controller {

  //
  // JSON combinators (used to transform between object and JSON representations)
  // see http://www.playframework.com/documentation/2.1.2/ScalaJsonCombinators
  //
  
  import util.JsonExtension.mapFormat
  
  implicit val tagFormat: Format[Tag] = (
    (__ \ "id").format[Long] and
    (__ \ "relationship").format[Long] and
    (__ \ "sentence").format[Long] and
    (__ \ "label").format[String] and
    (__ \ "direction").format[String] and
    (__ \ "auto").format[Boolean] and
    (__ \ "hasPositive").format[Boolean]
  )(
    (id: Long, relationshipId: Long, sentenceId: Long, label: String, direction: String, auto: Boolean, _) => new Tag(Id(id), relationshipId, sentenceId, label, direction, auto),
    (t: Tag) => (t.id.get, t.relationshipId, t.sentenceId, t.label, t.direction, t.automatic, if(t.automatic) t.hasPositiveVotes else true)
  )
  
  //
  // Routes
  //
  
  /**
   * Adds a new tag to a releationship and a sentence (both are needed since a sentence may contain several relationships),
   * a label and a direction.
   */
  def add(relationshipId: Long, sentenceId: Long, label: String, direction: String) = Action { implicit request =>
    Logger.debug("Adding tag: %s (sentence = %d, relationship = %d)".format(label, sentenceId, relationshipId))
    val result = Tag.createOrGet(relationshipId, sentenceId, label, direction)
    result match {
      case util.Created(tag) =>
        Application.Classifier ! Learn(tag)
        Log.tagCreate(tag.id.get)
        Ok(Json.toJson(tag)).as(MimeTypes.JSON)
      case util.Existed(tag) =>
        Ok
    }
  }
  
  /**
   * Removoes a tag given its id.
   */
  def remove(tagId: Long) = Action {
    Tag.byId(tagId) foreach { tag =>
      if (!tag.automatic) {
        Logger.debug("Removing tag: %s".format(tag))
        Tag.remove(tagId)
      }
    }
    Ok
  }
  
  /**
   * Accepts a given tag.
   */
  def castPositiveVote(tagId: Long) = Action { implicit request =>
    Logger.debug("Casting positive vote for Tag %d".format(tagId))
    val updated = Pattern.castPositiveVote(tagId)
    Log.tagVotePositive(tagId)
    Logger.debug("%d Patterns recieved positive vote.".format(updated))
    Ok
  }

  /**
   * Given a label and a relationship, accepts all tags for that relationships that bear the label.
   * This is the common acceptance operation in Networks of Names and serves the purpose of extrapolating
   * the user's intent: If a tag is correct in one case, it is likely to be correct in all other cases
   * for the same relationship.
   */
  def castPositiveVoteByLabel(relationshipId: Long, label: String) = Action { implicit request =>
    Logger.debug("Casting positive vote for all Tags labelled %s for Relationship %d".format(label, relationshipId))
    var updated = 0;
    Tag.byLabel(relationshipId, label) foreach { tag =>
      val u = Pattern.castPositiveVote(tag.id.get)
      Log.tagVotePositive(tag.id.get)
      updated += u
    }
    Logger.debug("%d Patterns recieved positive vote.".format(updated))
    Ok
  }
  
  /**
   * Rejects a given tag.
   */
  def castNegativeVote(tagId: Long) = Action { implicit request =>
    Logger.debug("Casting negative vote for Tag %d".format(tagId))
    val updated = Pattern.castNegativeVote(tagId)
    Log.tagVoteNegative(tagId)
    Logger.debug("%d Patterns recieved negative vote.".format(updated))
    Ok
  }
  
  /**
   * Given a label and a relationship, rejects all tags for that relationship that bear the label.
   * This is the common rejection operation in Networks of Names with the same reasoning as for
   * <tt>castPositiveVoteByLabel</tt>.
   */
  def castNegativeVoteByLabel(relationshipId: Long, label: String) = Action { implicit request =>
    Logger.debug("Casting negative vote for all Tags labelled %s for Relationship %d".format(label, relationshipId))
    var updated = 0;
    Tag.byLabel(relationshipId, label) foreach { tag =>
      val u = Pattern.castNegativeVote(tag.id.get)
      Log.tagVoteNegative(tag.id.get)
      updated += u
    }
    Logger.debug("%d Patterns recieved negative vote.".format(updated))
    Ok
  }
  
  /**
   * For a given relationship, retrieves a <tt>Map</tt> mapping sentence ids to the tags that
   * have been assigned to the respective sentence.
   */
  def byRelationship(relationshipId: Long) = Action {
    val sentences2tags = Tag.byRelationship(relationshipId) map {
      case (key, value) => key.toString -> value
    }
    Ok(Json.toJson(sentences2tags)).as(MimeTypes.JSON)
  }
  
  /**
   * Disables a pattern and removes all its applications.
   */
  def disablePattern(patternId: Long) = DeveloperAction {
    Action {
      Pattern.byId(patternId) match {
        case Some(pattern) =>
          Pattern.disable(pattern)
          Ok("Done.")
        case None =>
          BadRequest("No such pattern.")
      }
    }
  }
  
  /**
   * Enables a pattern (if it was previously disabled), and (re)applies it.
   */
  def applyPattern(patternId: Long) = DeveloperAction {
    Action{
      Pattern.byId(patternId) match {
        case Some(pattern) =>
          Pattern.enable(pattern)
          Application.Classifier ! Apply(pattern)
          Ok("Done. Pattern application runs as a background task.")
        case None =>
          BadRequest("No such pattern.")
      }
    }
  }
}