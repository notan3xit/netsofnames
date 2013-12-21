package controllers

import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import scala.collection.JavaConversions.iterableAsScalaIterable
import anorm.SQL
import anorm.SqlParser.scalar
import anorm.sqlToSimple
import anorm.toParameterValue
import models.Entity
import models.EntityType
import models.Relationship
import play.Logger
import play.api.Play.current
import play.api.db.DB
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.templates.Html
import models.CompleteGraph
import models.DOI
import models.DOILog
import scala.io.Source
import util.IO
import models.Tag
import anorm.Pk
import models.Pattern

/**
 * Actions that can be executed in development mode.
 * 
 * To enable development, set <tt>non.dev=true</tt> in the configuration file.
 * 
 * TODO: (Re)creation of tables and indexes as implemented here is not very robust to changes and should be redesigned.
 */
object Administration extends Controller {

  // The directory that is read for import from the preprocessor.
  val Dir = Paths.get(System.getProperty("user.home"), "netsofnames", "Out")

  // A mapping of table names to file names. 
  val FilesToImport = List(
    "entities" -> "entities.tsv",
    "relationships" -> "relationships.tsv",
    "sentences" -> "sentences.tsv",
    "sources" -> "sources.tsv",
    "relationships_to_sentences" -> "relationships_to_sentences.tsv",
    "sentences_to_sources" -> "sentences_to_sources.tsv")

  /**
   * Starts the application (i.e. loads the data required during operation).
   * 
   * @param forceRecompute Forces the recomputation of DOI values. The values are not needed for the default (edge-based) expander.
   */
  def startup(forceRecompute: Boolean = false) = DeveloperAction {
    Action {
      Application.startup(forceRecompute)    
      Ok("Loaded graph: %d nodes, %d edges".format(CompleteGraph.graph.getVertexCount(), CompleteGraph.graph.getEdgeCount()))
    }
  }
  
  /**
   * Form to trigger the removal of unused sentences from the data. Lists all import folders for selection.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  def fixUnusedSentencesForm() = DeveloperAction {
    Action {
      val dirsOnly = new DirectoryStream.Filter[Path]() {
        override def accept(path: Path) = Files.isDirectory(path)
      }
      val dirs = Files.newDirectoryStream(Dir, dirsOnly).toList.sorted
      val options = dirs map { dir => "<option>%s</option>".format(dir.getFileName().toString) } mkString "\n"
      Ok(
        Html("""
          <form method="POST" action="/fix/unused-sentences">
            <label>Data <select name="from">%s</select></label><br />
            <input type="submit">
          </form>""".format(options)))
    }
  }
  
  /**
   * Copies the original sentences.tsv and removes sentences that are not references by the data. This reduces the amount
   * of source data and speeds up loading the data into the database.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  def fixUnusedSentences() = DeveloperAction {
    Action { request =>
      val requestBody = request.body.asFormUrlEncoded.get
      val dir = Dir.resolve(requestBody("from")(0))
      val r2s = dir.resolve("relationships_to_sentences.tsv")
      val e = dir.resolve("sentences.tsv")
      val eb = dir.resolve("sentences_original.tsv")
      Files.move(e, eb)
      val used = (Source.fromFile(r2s.toAbsolutePath().toFile(), "utf-8").getLines map { line =>
        line.split("\t")(1).toLong
      }).toSet
      var n = 0
      IO.printToFile(e) { p =>
        Source.fromFile(eb.toAbsolutePath().toFile(), "utf-8").getLines foreach { line =>
          val id = line.split("\t")(0).toLong
          if (used.contains(id)) p.println(line) else n += 1
        }
      }
      Ok("Done. %d used sentences, %d removed.".format(used.size, n))
    }
  }
  
  /**
   * Re-applies all patterns and fixes existing labels that differ from the re-application results. Used in cases where the
   * classifier (specifically, the pattern application) is subject to bugfixes that need propagation to existing data.
   */
  def fixWrongLabels() = DeveloperAction {
    Action {
      // get all automatic pattern applications from database, group originating patterns by (sentence, relationship) pairs
      // for re-application
      import util.Benchmark._
      (DB.withConnection { implicit connection =>
        val p2t = (SQL("""
          SELECT
            t.id AS t_id, t.relationship_id AS t_rid, t.sentence_id AS t_sid, t.label_id AS t_lid, t.direction AS t_direction, t.auto AS t_auto,
            p.id AS p_id, p.rule AS p_rule, p.label AS p_rule, p.original_tag AS p_original_tag,
            l.label AS l_label
          FROM patterns_to_tags p2t
            JOIN tags t ON t.id = p2t.tag_id
            JOIN patterns p ON p.id = p2t.pattern_id
            JOIN labels l ON l.id = t.label_id
          WHERE t.auto = true
        """)() map { row =>
          val tag = new Tag(row.get[Pk[Long]]("t_id").get, row.get[Long]("t_rid").get, row.get[Long]("t_sid").get,
            row.get[String]("l_label").get, row.get[String]("t_direction").get, true) 
          val pattern = new Pattern(row.get[Pk[Long]]("p_id").get, row.get[String]("p_rule").get, row.get[String]("p_label").get,
            row.get[Long]("p_original_tag").get)
          (tag, pattern)
        }).toList groupBy { case (tag, pattern) => (tag.sentence.get, tag.relationship.get, pattern.id.get) }
        
        // re-apply patterns
        p2t foreach { case ((sentence, relationship, _), values) =>
          val tags = values map { _._1 } sortBy { _.id.get }
          val pattern = values(0)._2
          
          val retags = pattern(sentence, relationship)
          
          tags zip retags map { case (o, n) =>
            // if label from re-application differns from existing label, update it
            if (o.label != n.label) {
              Tag.updateLabel(o.id.get, n.label)
            }
          }
        }
      }).withBenchmark("Fixing labels")
      
      // remove labels that are no longer needed (because they have been replaced)
      (DB.withConnection { implicit connection =>
        SQL("""
          DELETE FROM labels l WHERE NOT EXISTS (SELECT * FROM tags WHERE label_id = l.id)
        """).execute()
      }).withBenchmark("Removing unused labels")
      
      Ok("Done.")
    }
  }

  /**
   * Form to trigger database import. List all import folders for selection.
   */
  def listImports() = DeveloperAction {
    Action {
      val dirsOnly = new DirectoryStream.Filter[Path]() {
        override def accept(path: Path) = Files.isDirectory(path)
      }
      val dirs = Files.newDirectoryStream(Dir, dirsOnly).toList.sorted
      val options = dirs map { dir => "<option>%s</option>".format(dir.getFileName().toString) } mkString "\n"
      Ok(
        Html("""
          <form method="POST" action="/database/import">
            <label>Data <select name="from">%s</select></label><br />
            <label>Clean <input name="clean" type="checkbox" checked="checked" /><br />
            <input type="submit">
          </form>""".format(options)))
    }
  }

  /**
   * Parses users selection and triggers database import. The import drops existing data, imports the data
   * from the specified folder, and possibly triggers data cleaning (which is conceptually part of the preprocessor).
   */
  def populateDatabase() = DeveloperAction {
    Action { implicit request =>
      val requestBody = request.body.asFormUrlEncoded.get
      val from = Dir.resolve(requestBody("from")(0))
      val clean = requestBody.get("clean").isDefined
      importData(from, clean)
      Ok("Done.")
    }
  }
  
  /*
   * Imports data from a given folder into the database.
   * 
   * @param from The folder to import from.
   * @param clean Clean the data after import (this is conceptually part of the preprocessor).
   */
  private def importData(from: Path, clean: Boolean) = {
    Logger.debug("Importing data... (clean =  %s)".format(clean))
    DB.withTransaction { implicit connection =>
      // drop all indexes
      dropIndexes()

      // truncate all tables
      SQL("TRUNCATE TABLE \"tags\"").execute()
      SQL("TRUNCATE TABLE \"patterns\"").execute()
      SQL("TRUNCATE TABLE \"patterns_to_tags\"").execute()
      SQL("TRUNCATE TABLE \"labels\"").execute()
      
      // import data from files
      for ((table, file) <- FilesToImport) {
        SQL("TRUNCATE TABLE \"%s\"".format(table)).execute()
        val sql = SQL(
          """
          COPY "%s"
          FROM '%s'
          DELIMITER E'\t'
          """.format(table, from.resolve(file).toAbsolutePath().toString()))
        sql.execute()
      }
      
      // recreate all indexes
      createIndexes()
      doCreateTagsTables()
      doCreateLogsTable()
    }
    
    // if data cleaning has been request, execute it
    if (clean) {
      DB.withConnection { implicit connection =>
        doCleanFrequency()
        doCleanNames()
        doCleanIsolated()
      }
    }
  }

  /*
   * Drops all indexes.
   */
  private def dropIndexes()(implicit connection: Connection) {
    // tags and related tables
    // create indexes
    // -- labels
    SQL("DROP INDEX IF EXISTS l_pkey_index").execute()
    SQL("DROP INDEX IF EXISTS labels_label_index").execute()
    
    // -- tags
    SQL("ALTER TABLE tags DROP CONSTRAINT IF EXISTS t_r_fkey").execute()
    SQL("DROP INDEX IF EXISTS t_pkey_index").execute()
    SQL("ALTER TABLE tags DROP CONSTRAINT IF EXISTS t_l_fkey").execute()
    SQL("DROP INDEX IF EXISTS t_r_fkey_index").execute()
    SQL("DROP INDEX IF EXISTS t_l_fkey_index").execute()
    
    // -- patterns
    SQL("ALTER TABLE patterns DROP CONSTRAINT IF EXISTS p_t_fkey").execute() // legacy drop, in case it still exists
    SQL("DROP INDEX IF EXISTS p_pkey_index").execute()
    
    // --patterns to tags
    SQL("ALTER TABLE patterns_to_tags DROP CONSTRAINT IF EXISTS p2t_p_fkey").execute()
    SQL("ALTER TABLE patterns_to_tags DROP CONSTRAINT IF EXISTS p2t_t_fkey").execute()
    
    // Foreign Keys
    SQL("ALTER TABLE relationships DROP CONSTRAINT IF EXISTS r_e1_fkey").execute()
    SQL("DROP INDEX IF EXISTS r_e1_fkey_index").execute()
    SQL("ALTER TABLE relationships DROP CONSTRAINT IF EXISTS r_e2_fkey").execute()
    SQL("DROP INDEX IF EXISTS r_e2_fkey_index").execute()
    SQL("ALTER TABLE relationships_to_sentences DROP CONSTRAINT IF EXISTS r2s_r_fkey").execute()
    SQL("DROP INDEX IF EXISTS r2s_r_fkey_index").execute()
    SQL("ALTER TABLE relationships_to_sentences DROP CONSTRAINT IF EXISTS r2s_s_fkey").execute()
    SQL("DROP INDEX IF EXISTS r2s_s_fkey_index").execute()

    // Primary Keys
    SQL("ALTER TABLE entities DROP CONSTRAINT IF EXISTS entities_pkey").execute()
    SQL("ALTER TABLE relationships DROP CONSTRAINT IF EXISTS relationships_pkey").execute()
    SQL("ALTER TABLE sentences DROP CONSTRAINT IF EXISTS sentences_pkey").execute()
    SQL("ALTER TABLE sources DROP CONSTRAINT IF EXISTS sources_pkey").execute()
    SQL("ALTER TABLE tags DROP CONSTRAINT IF EXISTS tags_pkey").execute()
    
    // Indexes for direct access
    SQL("DROP INDEX IF EXISTS r_pkey_index").execute()
    SQL("DROP INDEX IF EXISTS s_pkey_index").execute()
    SQL("DROP INDEX IF EXISTS so_pkey_index").execute()

    // Index for fast JOINs on sentences_to_sources
    SQL("DROP INDEX IF EXISTS s2s_s_index").execute()

    // Full Text Search on Entity Names
    SQL("DROP INDEX IF EXISTS entities_name_gin").execute() // legacy drop, in case it still exists
    SQL("DROP INDEX IF EXISTS entities_name_gist").execute() // legacy drop, in case it still exists
    SQL("ALTER TABLE entities DROP COLUMN IF EXISTS tsname").execute() // legacy drop, in case it still exists
    SQL("DROP INDEX IF EXISTS entities_name_index").execute()
  }

  /*
   * Creates all indexes.
   */
  private def createIndexes()(implicit connection: Connection) {
    // Primary Keys
    SQL("ALTER TABLE entities ADD CONSTRAINT entities_pkey PRIMARY KEY (id)").execute()
    SQL("ALTER TABLE relationships ADD CONSTRAINT relationships_pkey PRIMARY KEY (id)").execute()
    SQL("ALTER TABLE sentences ADD CONSTRAINT sentences_pkey PRIMARY KEY (id)").execute()
    SQL("ALTER TABLE sources ADD CONSTRAINT sources_pkey PRIMARY KEY (id)").execute()
    SQL("ALTER TABLE tags ADD CONSTRAINT tags_pkey PRIMARY KEY (id)").execute()

    // Indexes for direct access
    SQL("CREATE INDEX r_pkey_index ON relationships (id)").execute()
    SQL("CREATE INDEX s_pkey_index ON sentences (id)").execute()
    SQL("CREATE INDEX so_pkey_index ON sources (id)").execute()
    SQL("CREATE INDEX t_pkey_index ON tags (id)").execute()

    // Foreign Keys on relationships
    SQL("ALTER TABLE relationships ADD CONSTRAINT r_e1_fkey FOREIGN KEY (entity1) REFERENCES entities (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX r_e1_fkey_index ON relationships(entity1)").execute()
    SQL("ALTER TABLE relationships ADD CONSTRAINT r_e2_fkey FOREIGN KEY (entity2) REFERENCES entities (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX r_e2_fkey_index ON relationships(entity2)").execute()

    // Foreign Keys on relationships_to_sentences
    SQL("ALTER TABLE relationships_to_sentences ADD CONSTRAINT r2s_r_fkey FOREIGN KEY (relationship_id) REFERENCES relationships (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX r2s_r_fkey_index ON relationships_to_sentences (relationship_id)").execute()
    SQL("ALTER TABLE relationships_to_sentences ADD CONSTRAINT r2s_s_fkey FOREIGN KEY (sentence_id) REFERENCES sentences (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX r2s_s_fkey_index ON relationships_to_sentences (sentence_id)").execute()

    // Index for fast JOINs on sentences_to_sources
    SQL("CREATE INDEX s2s_s_index ON sentences_to_sources (sentence_id)").execute()

    // Full Text Search on Entity Names
    // SQL("ALTER TABLE entities ADD COLUMN tsname tsvector").execute()
    // SQL("UPDATE entities SET tsname = to_tsvector(name)")
    // SQL("CREATE INDEX entities_name_gist ON entities USING gist(tsname)").execute()
    SQL("CREATE INDEX entities_name_index ON entities USING btree(name text_pattern_ops)")
    
    // tags and related tables
    // create indexes
    // -- labels
    SQL("CREATE INDEX l_pkey_index ON labels (id)").execute()
    SQL("CREATE INDEX labels_label_index ON labels USING btree (label)").execute()
    
    // -- tags
    SQL("ALTER TABLE tags ADD CONSTRAINT t_r_fkey FOREIGN KEY (relationship_id) REFERENCES relationships (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX t_r_fkey_index ON tags(relationship_id)").execute()
    SQL("ALTER TABLE tags ADD CONSTRAINT t_l_fkey FOREIGN KEY (label_id) REFERENCES labels (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX t_l_fkey_index ON tags(label_id)").execute()
    
    // -- patterns
    SQL("CREATE INDEX p_pkey_index ON patterns(id)").execute()
    
    // --patterns to tags
    SQL("ALTER TABLE patterns_to_tags ADD CONSTRAINT p2t_p_fkey FOREIGN KEY (pattern_id) REFERENCES patterns (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("ALTER TABLE patterns_to_tags ADD CONSTRAINT p2t_t_fkey FOREIGN KEY (tag_id) REFERENCES tags (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
  }
  
  /**
   * Recreates all database indexes (by successively dropping and creating them).
   */
  def recreateIndexes() = DeveloperAction {
    Action {
      DB.withConnection{ implicit connection =>
        dropIndexes()
        createIndexes()
      }
      Ok("Done.")
    }
  }

  /**
   * Finds duplicate names and aggregates them into a single entry.
   * 
   * This is a legacy action for corrupt data from the preprocessor.
   */
  def fixDuplicates() = DeveloperAction {
    Action {
      // get list of duplicates
      val dups = Entity.listOfDuplicates()
      
      // assuming duplicates appear no more than twice (once as PERSON and once as ORGANIZATION) and are sorted,
      // look at all pairs and replace the lower-frequency version by the higher-frequency version
      dups.sliding(2, 2) foreach {
        case List(first, second) =>
          DB.withConnection { implicit connection =>
            SQL("""
            UPDATE entities
            SET    frequency = frequency + {otherFrequency}
            WHERE  id = {id}
            """).on(
              'id -> first.id,
              'otherFrequency -> second.frequency).executeUpdate()
  
            SQL("""
            UPDATE relationships
            SET    entity1 = {newId}
            WHERE  entity1 = {otherId}
            """).on(
              'newId -> first.id,
              'otherId -> second.id).executeUpdate()
  
            SQL("""
            UPDATE relationships
            SET    entity2 = {newId}
            WHERE  entity2 = {otherId}
            """).on(
              'newId -> first.id,
              'otherId -> second.id).executeUpdate()
  
            SQL("""
            DELETE FROM entities
            WHERE  id = {id}
            """).on(
              'id -> second.id).executeUpdate()
          }
  
      }
      Ok("Done.")
    }
  }
  
  /**
   * Finds and deletes all sentences (and relationships extracted from them) that have no sources.
   * 
   * This is used for posterior correction of errors in the (primary) corpus data.
   */
  def fixMissingSources() = DeveloperAction {
    Action {
      DB.withTransaction { implicit connection =>
        SQL("""
          DELETE FROM sentences s
          WHERE (SELECT count(*) FROM sentences_to_sources WHERE sentence_id = s.id) = 0
        """).execute()
        
        SQL("""
          DELETE FROM relationships r
          WHERE (SELECT count(*) FROM relationships_to_sentences WHERE relationship_id = r.id) = 0
        """).execute()
      }
      Ok("Done.")
    }
  }

  /**
   * Creates all tables required for tagging.
   */
  def createTagsTables() = DeveloperAction {
    Action {
      DB.withTransaction { implicit connection =>
        doCreateTagsTables()
      }
      Ok("Done.")
    }
  }
  
  /*
   * Creates all tables required for tagging by successively dropping them (if exists) and creating them again, along
   * with their indexes. Can be used for truncation.
   */
  private def doCreateTagsTables()(implicit connection: Connection) = {
    // drop existing tables
    SQL("DROP TABLE IF EXISTS patterns_to_tags").execute()
    SQL("DROP TABLE IF EXISTS patterns").execute()
    SQL("DROP TABLE IF EXISTS tags").execute()
    SQL("DROP TABLE IF EXISTS labels").execute()
    
    // (re)create tables
    SQL("""
      CREATE TABLE labels (
        id bigserial NOT NULL,
        label character varying(255) NOT NULL,
        CONSTRAINT labels_pkey PRIMARY KEY (id))
    """).execute()
    SQL("""
      CREATE TABLE tags (
        id bigserial NOT NULL,
        relationship_id bigint NOT NULL,
        sentence_id bigint NOT NULL,
        label_id bigint NOT NULL,
        direction character varying(1) NOT NULL,
        auto boolean NOT NULL DEFAULT false,
        CONSTRAINT tags_pkey PRIMARY KEY (id))
    """).execute()
    SQL("""
      CREATE TABLE patterns (
        id bigserial NOT NULL,
        rule text NOT NULL,
        label text NOT NULL,
        disabled boolean NOT NULL DEFAULT false,
        original_tag bigint NOT NULL,
        created timestamp NOT NULL DEFAULT now(),
        CONSTRAINT patterns_pkey PRIMARY KEY (id))
    """).execute()
    SQL("""
      CREATE TABLE patterns_to_tags (
        pattern_id bigint NOT NULL,
        tag_id bigint NOT NULL,
        positive smallint NOT NULL,
        negative smallint NOT NULL)
    """).execute()
    
    // create indexes
    // -- labels
    SQL("CREATE INDEX l_pkey_index ON labels (id)").execute()
    SQL("CREATE INDEX labels_label_index ON labels USING btree (label)").execute()
    
    // -- tags
    SQL("CREATE INDEX t_pkey_index ON tags (id)").execute()
    SQL("ALTER TABLE tags ADD CONSTRAINT t_r_fkey FOREIGN KEY (relationship_id) REFERENCES relationships (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX t_r_fkey_index ON tags(relationship_id)").execute()
    SQL("ALTER TABLE tags ADD CONSTRAINT t_l_fkey FOREIGN KEY (label_id) REFERENCES labels (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("CREATE INDEX t_l_fkey_index ON tags(label_id)").execute()
    
    // -- patterns
    SQL("CREATE INDEX p_pkey_index ON patterns(id)").execute()
    
    // --patterns to tags
    SQL("ALTER TABLE patterns_to_tags ADD CONSTRAINT p2t_p_fkey FOREIGN KEY (pattern_id) REFERENCES patterns (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
    SQL("ALTER TABLE patterns_to_tags ADD CONSTRAINT p2t_t_fkey FOREIGN KEY (tag_id) REFERENCES tags (id) ON UPDATE NO ACTION ON DELETE CASCADE").execute()
  }
  
  /**
   * Creates table required for logging user actions.
   */
  def createLogsTable() = DeveloperAction {
    Action {
      DB.withTransaction { implicit connection =>
        doCreateLogsTable()
      }
      Ok("Done.")
    }
  }
  
  /*
   * Creates table required for logging user actions by successively dropping it (if exists) and creating it again.
   */
  private def doCreateLogsTable()(implicit connection: Connection) {
    SQL("DROP TABLE IF EXISTS logs").execute()
    SQL("""
      CREATE TABLE logs (
        id bigserial NOT NULL,
        action character varying(255) NOT NULL,
        ref1 bigint,
        ref2 bigint,
        timeframe_from timestamp,
        timeframe_to timestamp,
        created timestamp NOT NULL DEFAULT now(),
        uid character varying(255) NOT NULL,
        CONSTRAINT logs_pkey PRIMARY KEY (id))
    """).execute()
  }
  
  /**
   * Cleans the data by removing low-frequency entities and relationships.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  def cleanFrequency() = DeveloperAction {
    Action {
      DB.withConnection { implicit connection =>
        doCleanFrequency()
      }
      Ok("Done.")
    }
  }
  
  /*
   * Cleans the data by removing low-frequency entities and relationships, specifically all entities with
   * frequencies below a certain threshold (here: 2). Entity statistics before and after cleaning are logged
   * to the console (and possibly other logger output); application logging needs to be enabled to see the
   * output.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  private def doCleanFrequency()(implicit connection: Connection) = {
    val before = countsAndFrequencies()
    val frequencyThreshold = 2
    // delete low-frequency entities; deletion will cascade into other tables
    SQL("DELETE FROM entities WHERE frequency <= {threshold}").on('threshold -> frequencyThreshold).execute()
    SQL("DELETE FROM relationships WHERE frequency <= {threshold}").on('threshold -> frequencyThreshold).execute()
    val after = countsAndFrequencies()
    Logger.debug(renderCountsAndFrequencies(before, after))
  }

  /**
   * Cleans the data by removing blacklisted names.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  def cleanNames() = DeveloperAction {
    Action {
      DB.withConnection { implicit connection =>
        doCleanNames()
      }
      Ok("Done.")
    }
  }

  /*
   * Cleans the data by removing blacklisted names. Specifically: removes names
   * that are single common first names, too short to be meaningful, or blacklisted.
   * Entity statistics before and after cleaning are logged  to the console (and possibly other logger output);
   * application logging needs to be enabled to see the output.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  private def doCleanNames()(implicit connection: Connection) = {
    // delete people with names that are shorter three characters or blacklisted (and not explicitly whitelisted).
    val before = countsAndFrequencies()
    val invalidP = Entity.byType(EntityType.Person)
      .filter { e =>
        !Application.NameWhitelist.contains(e.name) &&
        (e.name.length() < 3 || Application.PersonNameBlacklist.contains(e.name)) }
      .map { e => e.id.get }
    if (invalidP.size > 0) {
      SQL("DELETE FROM entities WHERE id IN (" + invalidP.mkString(",") + ")").execute()
    }
    
    // delete organizations with names shorter than three characters or blacklisted (and not explicitly whitelisted)
    val invalidO = Entity.byType(EntityType.Organization)
      .filter { e =>
        !Application.NameWhitelist.contains(e.name) &&
        (e.name.length() < 3 || Application.OrganizationNameBlacklist.contains(e.name)) }
      .map { e => e.id.get }
    if (invalidO.size > 0) {
      SQL("DELETE FROM entities WHERE id IN (" + invalidO.mkString(",") + ")").execute()
    }
    
    // Remove Reuters of type person, because Stanford NER misclassifies it
    SQL("DELETE FROM entities WHERE name = 'Reuters' OR name = 'REUTERS'").execute()
    val after = countsAndFrequencies()
    Logger.debug(renderCountsAndFrequencies(before, after))
  }

  /**
   * Cleans the data by removing nodes that are isolated or have only one neighbour.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  def cleanIsolated() = DeveloperAction {
    Action {
      DB.withConnection { implicit connection =>
        doCleanIsolated()
      }
      Ok("Done.")
    }
  }

  /*
   * Cleans the data by removing nodes that are isolated or have only one neighbour. Entity statistics before and after
   * cleaning are logged  to the console (and possibly other logger output); application logging needs to be enabled to
   * see the output.
   * 
   * (This is conceptually part of the preprocessor.)
   */
  private def doCleanIsolated()(implicit connection: Connection) = {
    val before = countsAndFrequencies()
    SQL("""
      DELETE FROM entities e
      WHERE (SELECT count(*) FROM relationships WHERE entity1 = e.id OR entity2 = e.id) < 2
    """).execute()
    val after = countsAndFrequencies()
    Logger.debug(renderCountsAndFrequencies(before, after))
  }

  /*
   * Auxiliary method used by data cleaning operations to retrieve statistics about the number of entities.
   */
  private def countsAndFrequencies()(implicit connection: Connection) = {
    val (cEntities, cRelationships, cR2S) = (
      SQL("SELECT count(*) FROM entities").as(scalar[Long].single),
      SQL("SELECT count(*) FROM relationships").as(scalar[Long].single),
      SQL("SELECT count(*) FROM relationships_to_sentences").as(scalar[Long].single))

    val (fEntities, fRelationships) = (
      SQL("SELECT sum(frequency) FROM entities").as(scalar[Long].single),
      SQL("SELECT sum(frequency) FROM relationships").as(scalar[Long].single))

    Map(
      "entities" -> Map(
        "count" -> cEntities,
        "frequency" -> fEntities),
      "relationships" -> Map(
        "count" -> cRelationships,
        "frequency" -> fRelationships),
      "r2s" -> Map(
        "count" -> cR2S))
  }

    /*
   * Auxiliary method used by data cleaning operations to pretty-print statistics about the number of entities.
   */
  private def renderCountsAndFrequencies(before: Map[String, Map[String, Long]], after: Map[String, Map[String, Long]]) = {
    """
      |ENTITIES
      |count     %8s // before
      |          %8s // after
      |frequency %8s // before
      |          %8s // after
      |
      |RELATIONSHIPS
      |count     %8s // before
      |          %8s // after
      |frequency %8s // before
      |          %8s // after
      |
      |R2S
      |count     %8s // before
      |          %8s // after
      """.stripMargin.format(
        before("entities")("count"),
        after("entities")("count"),
        before("entities")("frequency"),
        after("entities")("frequency"),
        before("relationships")("count"),
        after("relationships")("count"),
        before("relationships")("frequency"),
        after("relationships")("frequency"),
        before("r2s")("count"),
        after("r2s")("count")
      )
  }
}