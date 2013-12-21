package controllers

import play.api._
import play.api.mvc._
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import play.api.libs.json.Json
import play.api.http.MimeTypes
import models.Entity
import java.nio.file.Path
import java.nio.file.Paths
import scala.io.Source
import akka.actor.ActorSystem
import akka.actor.Props
import models.Learner
import models.Classifier
import models.CompleteGraph
import org.apache.commons.collections15.Transformer
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.batik.transcoder.TranscoderInput
import play.api.libs.iteratee.Enumerator
import org.apache.batik.transcoder.TranscoderOutput
import play.api.http.Writeable
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.util.zip.ZipOutputStream
import java.util.UUID
import java.io.FileOutputStream
import views.html.defaultpages.badRequest
import scala.util.Random
import scala.concurrent.Future
import play.api.mvc.ActionBuilder
import models.DOI
import models.DOILog

/**
 * Application-wide constants and actions that concern the application on a top-level, or do not apply to specific parts of it.
 */
object Application extends Controller {
  
  // path where the backend writes output to
  val OutputPath = Paths.get(System.getProperty("user.home"))
    .resolve("netsofnames")
    .resolve("server")
  
  // flags for development mode and action logging, read from the application's conf file
  val DevModeEnabled = Play.current.configuration.getBoolean("non.dev").getOrElse(false)
  val LogActionsEnabled = Play.current.configuration.getBoolean("non.log").getOrElse(false)
  
  // caches of pre-computed DOI values (not needed if edge-based expansion is used)
  val DOICache = OutputPath.resolve("doi.xml")
  val DOILogCache = OutputPath.resolve("doi-log.xml")
  
  // actor system for background tasks and the actor for classification tasks
  val actorSystem = ActorSystem("actorsystem")
  val Classifier = actorSystem.actorOf(Props[Classifier])
  
  // cache used to export to PNG and then serve the resulting image file to the user
  val fileCache = scala.collection.mutable.Map[String, Path]()
  
  // black- and whitelists of person and organization names
  lazy val PersonNameBlacklist = (Source.fromInputStream(Play.classloader.getResourceAsStream("resources/names-blacklist")).getLines ++
    Source.fromInputStream(Play.classloader.getResourceAsStream("resources/names-first"), "utf-8").getLines).toSet
  lazy val OrganizationNameBlacklist = (Source.fromInputStream(Play.classloader.getResourceAsStream("resources/names-blacklist"), "utf-8").getLines ++
    Source.fromInputStream(Play.classloader.getResourceAsStream("resources/names-newsagencies"), "utf-8").getLines).toSet
  lazy val NameWhitelist = Source.fromInputStream(Play.classloader.getResourceAsStream("resources/names-whitelist"), "utf-8").getLines.toSet
  
  // starts the application by loading data that is required during operation into memory
  def startup(forceRecompute: Boolean) = {
    // recompute DOI measures
    // this is normally not needed, unless (node-based) DOI expansion is explicitly used 
    if (forceRecompute) {
      if (Files.exists(Application.DOICache)) Files.delete(Application.DOICache)
      if (Files.exists(Application.DOILogCache)) Files.delete(Application.DOILogCache)
    }
    
    // initialize graph
    CompleteGraph.graph // initializes lazy variable
    CompleteGraph.distances // initializes lazy variable
    
    // if pre-calculated DOI measures exist, load them
    if (Files.exists(Application.DOICache)) DOI.apidiffs // initializes lazy variable
    if (Files.exists(Application.DOILogCache)) DOILog.apidiffs // initializes lazy variable
  }
  
  /**
   * Serves the Networks of Names frontend to the client.
   */
  def index = Action { implicit request =>
    // assign the user a UID (used to associate action logs with user sessions)
    val uid = request.session.get("uid").getOrElse { (Random.alphanumeric take 8).mkString }
    Logger.debug("Session UID: " + uid)
    
    // show main page
    Ok(views.html.index()).withSession("uid" -> uid)
  }
  
  /**
   * Retrieves entity names for auto-completion, by conducting a prefix search of the user input
   * on entity names in the database.
   */
  def typeahead(input: String) = Action {
    val options = DB.withConnection { implicit connection =>
      SQL("""
        SELECT name FROM entities WHERE lower(name) LIKE {prefix} ORDER BY frequency DESC LIMIT 5
      """).on(
        'prefix -> (input.trim().toLowerCase() + "%")
      ).as(get[String]("name").*)
    }
    val json = Json.obj(
      "options" -> Json.toJson(options.take(5))
    )
    println(json)
    Ok(json).as(MimeTypes.JSON)
  }
  
  /**
   * Checks if a name entered by the user exists. Used to mark input fields valid and invalid, respectively,
   * before the user conducts a search.
   */
  def nameExists(name: String) = Action {
    val exists = Entity.byName(name).isDefined
    val json = Json.obj(
      "exists" -> exists
    )
    Ok(json).as(MimeTypes.JSON)
  }
  
  /**
   * Converts a SVG given in the request body to PNG, caches the result and returns the respective cache id
   * to the user.
   */
  def svgToPNG = Action { implicit request =>
    val svg = request.body.asText.get
    
    val uuid = UUID.randomUUID().toString()
    val file = Files.createTempFile(uuid, "png")
    val stream = new FileOutputStream(file.toFile())
    
    val input = new TranscoderInput(new StringReader(svg))
    val output = new TranscoderOutput(stream)
    val transcoder = new PNGTranscoder()
        
    transcoder.transcode(input, output)
    stream.flush()
    stream.close()
    
    fileCache += uuid -> file    
    Ok(Json.obj("uuid" -> uuid)).as(MimeTypes.JSON)
  }
  
  /**
   * Given a cache id, serves the according image file to the user (and removes it).
   */
  def serveFile(uuid: String) = Action {
    if (fileCache.contains(uuid))
      Ok.sendFile(
        content = fileCache(uuid).toFile(),
        fileName = _ => "non-export.png",
        onClose = { () =>
          fileCache.remove(uuid) }
      ).withHeaders("Content-Type" -> "image/png")
    else BadRequest
  }
  
  /**
   * Routes to be used by AJAX calls from the frontend. See http://www.playframework.com/documentation/2.1.0/ScalaJavascriptRouting for more
   * on JavaScript routing in Play.
   */
  def javascriptRoutes = Action { implicit request =>
    import routes.javascript._
    Ok(
      Routes.javascriptRouter("jsRoutes")(
        routes.javascript.Application.typeahead, routes.javascript.Application.nameExists, routes.javascript.Application.svgToPNG, routes.javascript.Application.serveFile,
        Graphs.relationship, Graphs.entity, Graphs.clusteredSources, Graphs.expandNeighbors, Graphs.expandNeighborsById, Graphs.neighbors,
        Tags.add, Tags.remove, Tags.byRelationship, Tags.castPositiveVote, Tags.castPositiveVoteByLabel, Tags.castNegativeVote, Tags.castNegativeVoteByLabel
      )
    ).as("text/javascript")
  }
}

/**
 * Wrapper action that rejects user requests for developer actions if development mode is not enabled.
 */
case class DeveloperAction[A](action: Action[A]) extends Action[A] {
  
  def apply(request: Request[A]): Result = {
    if (controllers.Application.DevModeEnabled) {
      action(request)  
    } else controllers.Application.BadRequest("Start application in development mode to execute developer actions.")
    
  }
  lazy val parser = action.parser
}