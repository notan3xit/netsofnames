package controllers

import play.api.mvc.Controller
import models.CompleteGraph
import play.api.mvc.Action
import net.sf.javaml.clustering.mcl.MCL
import net.sf.javaml.distance.CosineSimilarity
import models.Relationship
import models.SourceClusteringMCL
import play.Logger
import models.Source
import models.Pattern
import models.Tag
import models.GraphMetrics
import models.Log
import models.Sentence
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import play.api.libs.json.Json
import play.api.http.MimeTypes

/**
 * Actions that calculate and display metrics about the network and system state.
 */
object Metrics extends Controller {
  
  /**
   * Parser for patterns with measures (times applied, accepted, rejected). The measures are retrieved by additional
   * database queries, which is not necessarily the most performant way, but the most clean in terms of code.
   */
  val patternWithMeasuresDirect = {
    get[Pk[Long]]("id")~
    get[String]("rule")~
    get[String]("label")~
    get[Long]("original_tag")~
    get[Boolean]("disabled") map {
      case id~rule~label~tag~disabled =>
        new Pattern(id, rule, label, tag, countApplied(id.get), countPositive(id.get), countNegative(id.get), disabled)
    }
  }
  
  
  private def countApplied(patternId: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT count(*)
        FROM patterns_to_tags p2t
        WHERE p2t.pattern_id = {pId}
      """).on(
        'pId -> patternId
      ).as(scalar[Long].single)
    }
  }
    
  private def countPositive(patternId: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT count(*)
        FROM patterns_to_tags p2t
        WHERE p2t.pattern_id = {pId}
        AND p2t.positive > 0 AND p2t.negative = 0
      """).on(
        'pId -> patternId
      ).as(scalar[Long].single)
    }
  }
  
  private def countNegative(patternId: Long) = {
    DB.withConnection { implicit connection =>
      SQL("""
        SELECT count(*)
        FROM patterns_to_tags p2t
        WHERE p2t.pattern_id = {pId}
        AND p2t.negative > 0
      """).on(
        'pId -> patternId
      ).as(scalar[Long].single)
    }
  }
  
  /**
   * Displays patterns ordered by performance.
   */
  def patternsByPerformance() = Action {
    val (manual, automatic, accepted, rejected, patterns) = patternMetrics()
    Ok(views.html.metrics.patterns(manual, automatic, accepted, rejected, patterns))
  }
  
  /*
   * Returns metrics for tags and patterns: number of manual tags, number of automatic tags, number of accepted tags,
   * number of rejected tags, and a list of patterns (applied more than once) sorted by performance.
   */
  private def patternMetrics() = {
    val automatic = DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM patterns_to_tags").as(scalar[Long].single)
    }
    val manual = DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM tags WHERE auto = false").as(scalar[Long].single)
    }
    val accepted = DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM patterns_to_tags WHERE positive > 0 AND negative = 0").as(scalar[Long].single)
    }
    val rejected = DB.withConnection { implicit connection =>
      SQL("SELECT count(*) FROM patterns_to_tags WHERE negative > 0").as(scalar[Long].single)
    }
    val patterns = DB.withConnection { implicit connection =>
      SQL("""
        SELECT
          p.id AS id,
          p.rule AS rule,
          p.label AS label,
          p.original_tag AS original_tag,
          p.disabled AS disabled
        FROM patterns p
      """).as(patternWithMeasuresDirect.*) filter { _.timesApplied > 1 }
    }
    (manual, automatic, accepted, rejected, patterns.sortBy(p => (-p.performance, -p.timesApplied)))
  }

  /**
   * Displays information on a single pattern.
   */
  def pattern(patternId: Long) = Action {
    val pattern = Pattern.byId(patternId).get
    val tags = pattern.tags.groupBy(_.label).map(_._2.head).toList
    Ok(views.html.metrics.pattern(pattern, tags, Sentence.byPattern(pattern.id.get, limit = Some(30))))
  }
  
  /**
   * Displays statistics on users. This assumes that statistics are present. To gather statistics, action logging
   * must be enabled (<tt>non.log=true</tt> in the conf file).
   */
  def users() = Action {
    Ok(views.html.metrics.users(Log.counts()))
  }
  
  /**
   * Displays graph metrics (for detalis, see <tt>models.GraphMetrics</tt>). This can take very long to compute.
   */
  def graph() = Action {
    Ok(views.html.metrics.graph(GraphMetrics.all))
  }
  
  /**
   * Extracts and returns for each unique user his distinct relationship views. This function is unused in the
   * system and can be seen as an example of how to use the action logging.
   */
  def distinctRelationshipViewsByUser() = Action {
    val values = DB.withConnection { implicit connection =>
      val uids = SQL("SELECT DISTINCT uid FROM logs").as(scalar[String].*)
      uids map { uid => Json.obj(
        uid -> SQL("""
          SELECT DISTINCT
            ref1,
            EXTRACT(YEAR FROM timeframe_from) AS from_y,
            EXTRACT(MONTH FROM timeframe_from) AS from_m,
            EXTRACT(YEAR FROM timeframe_to) AS to_y,
            EXTRACT(MONTH FROM timeframe_to) AS to_m
          FROM   logs
          WHERE  uid = {uid}
          AND    action = 'REL_ACCESS'
        """).on('uid -> uid)().size
      ) }
    }
    Ok(Json.toJson(values)).as(MimeTypes.JSON)
  }
  
  /**
   * Tests clustering with different values for <tt>pGamma</tt> and <tt>loopGain</tt>. Output is printed to console,
   * so application logging needs to be activated to see results.
   */
  def testMCLParams() = Action {
    val sources = Source.byRelationship(100824)
    
    val pGammas = 2.0d to (0.0, -0.2)
    val loopGains = 0.0d to (1.0, 0.2)
    for (pGamma <- pGammas; loopGain <- loopGains) {
      val mcl = new SourceClusteringMCL(sources, pGamma, loopGain)
      val clusters = mcl.clusters()
      Logger.debug("MCL(pGamma = %.2f, loopGain = %.2f): %d sources, %d clusters".format(pGamma, loopGain, sources.size, clusters.size))
    }
    Ok("Done. See console for output.")
  }
}