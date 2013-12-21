package models

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import anorm.Pk
import anorm.NotAssigned
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import util.AnormExtension._
import play.Logger

/**
 * Object representation of sources.
 * 
 * @param id Unique id and primary key.
 * @param sentences The sentence for this source.
 * @param source The source (can be a URL or some form of name of a newspaper issue).
 * @param date Publication date.
 */
case class Source(id: Pk[Long] = NotAssigned, sentence: Sentence, source: String, date: DateTime) extends Ordered[Source] {
  
  val formattedDate = DateTimeFormat.fullDate().print(date)
  
  // compares dates; if dates are equal, subsequently compares ids of the source and its sentence
  // to prevent different sources on the same date or different sentences with the same source from
  // being understood as equal.
  // NOTE this is actually required for clustering; MCL misbehaves otherwise.
  override def compare(that: Source) = {
    val c = this.date.compareTo(that.date)
    if (c != 0) c else {
      val d = id.get.compareTo(that.id.get)
      if (d != 0) d else { sentence.id.compareTo(that.sentence.id)}
    }
  }
}

/**
 * Companion object and DAO for sources.
 */
object Source {
  
  /**
   * Simple parser for source result sets.
   */
  def simple = {
    get[Pk[Long]]("id")~
    get[Long]("sentence_id")~
    get[String]("sentence")~
    get[String]("source")~
    get[DateTime]("date") map {
      case id~sid~sentence~source~date => new Source(id, new Sentence(sid, sentence), source, date)
    }
  }
  
  def byRelationship(relationshipId: Long, from: Option[Long] = None, to: Option[Long] = None) = {
    DB.withConnection { implicit connection =>
      (SQL(
        """
        SELECT
          sources.id AS id,
          sentences.id AS sentence_id,
          sentences.sentence AS sentence,
          sources.source AS source,
          sources.date AS date
        FROM sentences_to_sources AS s2s
          JOIN sentences ON s2s.sentence_id = sentences.id
          JOIN sources ON s2s.source_id = sources.id
        WHERE EXISTS (SELECT sentence_id FROM relationships_to_sentences WHERE relationship_id = {rId} AND sentence_id = s2s.sentence_id)
        ORDER BY date ASC
        """
      ).on(
        'rId -> relationshipId,
        'from -> from,
        'to -> to
      )() map { row =>
        val id = row.get[Long]("id").get
        val sentenceId = row.get[Long]("sentence_id").get
        val sentence = row.get[String]("sentence").get
        val source = row.get[String]("source").get
        val date = row.get[DateTime]("date").get
        new Source(Id(id), new Sentence(sentenceId, sentence), source, date)
      } filter { source =>
        (from map { _ <= source.date.getMillis() } getOrElse true) &&
        (to map { source.date.getMillis() <= _ } getOrElse true)
      }).toList
    }
  }
}