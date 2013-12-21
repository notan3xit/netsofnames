package controllers

import play.api.mvc.Controller
import play.api.mvc.Action
import models.Entity
import models.Relationship
import play.api.http.MimeTypes
import models.Person
import util.Benchmark._
import play.api.libs.json.util._
import play.api.libs.json.{Format,__,Json}
import play.api.libs.json.Writes._
import play.api.libs.functional.syntax._
import anorm.Pk
import anorm.Id
import models.Organization
import models.EntityType
import models.Person
import models.Organization
import models.Organization
import play.api.Routes
import models.CompleteGraph
import scala.collection.JavaConversions._
import models.Source
import play.Logger
import models.Source
import edu.uci.ics.jung.graph.Graph
import models.NPMIExpander
import models.Expander
import java.util.Date
import org.joda.time.DateTime
import edu.uci.ics.jung.algorithms.filters.FilterUtils
import edu.uci.ics.jung.algorithms.filters.EdgePredicateFilter
import org.apache.commons.collections15.Predicate
import models.SourceClusteringMCL
import models.Source
import models.Sentence
import edu.uci.ics.jung.graph.UndirectedSparseMultigraph
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath
import models.Tag
import scala.collection.{mutable, immutable}
import models.NPMI
import models.Significance
import models.DijkstraWidestPaths
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._
import models.Log

/**
 * Actions concerned with graph retrieval and expansion.
 */
object Graphs extends Controller {
  
  import CompleteGraph._
  
  //
  // JSON combinators (used to transform between object and JSON representations)
  // see http://www.playframework.com/documentation/2.1.2/ScalaJsonCombinators
  //
  
  implicit def entityFormat(implicit graph: Graph[Long, Long]): Format[Entity] = (
    (__ \ "id").format[Long] and
    (__ \ "type").format[Int] and
    (__ \ "name").format[String] and
    (__ \ "freq").format[Int] and
    (__ \ "numNeighbors").format[Int]
  )(
    (id, entityType, name, frequency, _) => EntityType(entityType) match {
      case EntityType.Person => new Person(Id(id), name, frequency)
      case EntityType.Organization => new Organization(Id(id), name, frequency)
    },
    (e: Entity) => (e.id.get, e.entityType.id, e.name, e.frequency, CompleteGraph.graph.getNeighborCount(e.id.get))
  )
  
  import Tags.tagFormat
  
  implicit val relationshipFormat: Format[Relationship] = (
    (__ \ "id").format[Long] and
    (__ \ "source").format[Long] and
    (__ \ "target").format[Long] and
    (__ \ "freq").format[Int] and
    (__ \ "significance").format[Double] and
    (__ \ "tags").format[List[Tag]]
  )(
    (id: Long, e1: Long, e2: Long, freq: Int, significance: Double, _) => new Relationship(Id(id), e1, e2, freq),
    // TODO this tag retrieval does not take the timeframe into account; tags will be selectable in the UI, but not in the sources view
    (r: Relationship) => (r.id.get, r.e1, r.e2, r.frequency, Significance.edgeSignificance(r), Tag.byRelationship(r.id.get).values.toList.flatten)
  )

  implicit val sourceFormat: Format[Source] = (
    (__ \ "id").format[Long] and
    (__ \ "sentence").format(
      ((__ \ "id").format[Long] and
      (__ \ "text").format[String])
      ((id: Long, text: String) => new Sentence(id, text),
      (s: Sentence) => (s.id, s.text))
    ) and
    (__ \ "source").format[String] and
    (__ \ "date").format[String]
  )(
    (id: Long, sentence: Sentence, source: String, date: String) => new Source(Id(id), sentence, source, DateTime.parse(date)),
    (s: Source) => (s.id.get, s.sentence, s.source, s.formattedDate)
  )
  
  //
  // Routes
  //
  
  /**
   * Given two entity names, calculates and returns a relevant subgraph.
   * 
   * @param name1 Name of the first entity.
   * @param name2 Name of the second entity.
   * @param expanderString String identifying the expander (see Expander.byName for Details).
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   * @param num Number of nodes that should constitute the resulting subgraph.
   */
  def relationship(name1: String, name2: String, expanderString: String, from: Option[String] = None, to: Option[String] = None, num: Int) = Action { implicit request =>
    // retrieve entities by their names
    val e1 = Entity.byName(name1).get
    val e2 = Entity.byName(name2).get
    Logger.debug("Showing graph for: %s and %s (from = %s, to = %s)".format(e1, e2, from.getOrElse("undefined"), to.getOrElse("undefined")))
    Log.searchRelationship(e1.id.get, e2.id.get, from map { clientDateStringToTimestamp }, to map { clientDateStringToTimestamp })
    
    // create timeframe filter and get filtered view onto the graph
    val filter = createTimeframeFilter(from, to)
    val graph = timeframedGraph(from, to)
    
    // calculate max capacity path between the two entities
    val algo = if (filter.affectsGraph) new DijkstraWidestPaths(graph) else capacities
    val maxCapacityPath = algo.widestPath(e1.id.get, e2.id.get)
    // take the max capacity path if the number of hops is less or equal 3; otherwise take shortest path
    val selected = if (maxCapacityPath.size > 4) {
      val algo = if (filter.affectsGraph) new DijkstraShortestPath(graph) else distances
      val nodes = algo.getPath(e1.id.get, e2.id.get) flatMap { edge => graph.getEndpoints(edge) }
      mutable.Set(nodes: _*)
    } else mutable.Set(maxCapacityPath: _*)
    
    // initialize expander and expand subgraph
    val expander = Expander.byName(expanderString, Set(e1.id.get, e2.id.get), if (filter.affectsGraph) Some(filter) else None)
    val subgraph = expander.forEntities(selected, num)
    
    Logger.debug("Selected graph: %d nodes, %d edges".format(subgraph.getVertexCount(), subgraph.getEdgeCount()))
    Ok(graphToJson(subgraph)).as(MimeTypes.JSON)
  }
  
  /**
   * Given one entity name, calculates and returns a relevant subgraph.
   * 
   * @param name Name of the first entity.
   * @param expanderString String identifying the expander (see Expander.byName for Details).
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   * @param num Number of nodes that should constitute the resulting subgraph.
   */
  def entity(name: String, expanderString: String, from: Option[String] = None, to: Option[String] = None, num: Int) = Action { implicit request =>
    // retrieve entities by their names
    val entity = Entity.byName(name).get
    Logger.debug("Showing graph for: %s (from = %s, to = %s)".format(entity, from.getOrElse("undefined"), to.getOrElse("undefined")))
    Log.searchName(entity.id.get, from map { clientDateStringToTimestamp }, to map { clientDateStringToTimestamp })
    
    // create timeframe filter
    val filter = createTimeframeFilter(from, to)
    
    // initlize expander and expand subgraph
    val expander = Expander.byName(expanderString, Set(entity.id.get), if (filter.affectsGraph) Some(filter) else None)
    val subgraph = expander.forEntity(entity.id.get, num)
    
    Logger.debug("Selected graph: %d nodes, %d edges".format(subgraph.getVertexCount(), subgraph.getEdgeCount()))
    Ok(graphToJson(subgraph)).as(MimeTypes.JSON)
  }
  
  /**
   * Retrieves up to <tt>numNodes</tt> additional neighbours of a given node (ordered by interestingness of the relationship).
   * 
   * @param nodeId Expands neighbours of this node.
   * @param expandedString List of node ids already present in the graph (encoded by a comma-delimited String).
   * @param ignoredString List of node ids that are not present in the graph, but should not be expanded anyway.
   * @param expanderString String identifying the expander (see Expander.byName for Details).
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   * @param numNodes Number of nodes that should be expanded.
   */
  def expandNeighbors(nodeId: Long, expandedString: String, ignoredString: String, expanderString: String, from: Option[String] = None, to: Option[String] = None, numNodes: Int = 5) = Action { implicit request =>
    // create timeframe filter
    val filter = createTimeframeFilter(from, to)
    
    // parse expanded and ignored node ids
    val expanded = if (!expandedString.trim().isEmpty()) expandedString.split(",").toList.map(_.toLong) else List()
    val ignored = if (!ignoredString.trim().isEmpty()) ignoredString.split(",").toList.map(_.toLong) else List()

    // expand nodes
    val expander = Expander.byName(expanderString, immutable.Set(nodeId), if (filter.affectsGraph) Some(filter) else None)
    val subgraph = expander.neighbors(nodeId, numNodes, expanded.toSet, ignored.toSet)
    
    Ok(graphToJson(subgraph)).as(MimeTypes.JSON)
  }
  
  /**
   * Expands a nodes by a given list of node ids.
   * 
   * @param nodeId Expands neighbours of this node.
   * @param expandedString List of node ids already present in the graph (encoded by a comma-delimited String).
   * @param expandString List of node ids to be expanded.
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   */
  def expandNeighborsById(nodeId: Long, expandedString: String, expandString: String, from: Option[String] = None, to: Option[String] = None) = Action {
    // create filtered view onto the graph
    val graph = timeframedGraph(from, to)
    
    // parse expanded and to-expand node ids
    val expanded = expandedString.split(",").toList.map(_.toLong)
    val expand = expandString.split(",").toList.map(_.toLong)
    
    // expand subgraph
    val subgraph = FilterUtils.createInducedSubgraph[Long, Long, UndirectedSparseMultigraph[Long, Long]](nodeId :: expanded ++ expand, graph)
    Ok(graphToJson(subgraph)).as(MimeTypes.JSON)
  }
  
  /**
   * Given a node id, retrieves a list of its not-yet-expanded neighbours, sorted by the interestingness of the node's relationship to them.
   * 
   * @param nodeId Shows the neighbours of this node.
   * @param exclude List of node ids not to be included in the list (encoded by a comma-delimited String; typically the nodes that are already
   *   present in the graph).
   * @param expanderString String identifying the expander (see Expander.byName for Details).
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   */
  def neighbors(nodeId: Long, excludeString: String, expanderString: String, from: Option[String] = None, to: Option[String] = None) = Action {
    // create a filtered view onto the graph
    implicit val graph = timeframedGraph(from, to)
    
    // parse excluded nodes, get and sort neighbours
    val exclude = excludeString.split(",").toList.map(_.toLong)
    val neighbors = graph.getNeighbors(nodeId)
      .filter { !exclude.contains(_) }
      .map { id => entities(id) }
      .toList
    val sorted = neighbors.sortBy(n => Significance.edgeSignificance(graph.findEdge(nodeId, n.id.get))).reverse
    
    val json = Json.obj(
      "neighbors" -> sorted
    )
    Ok(json).as(MimeTypes.JSON)
  }
  
  /**
   * Retrives and clusters sources for a given relationship.
   * 
   * @param relationshipId Gets sources for this relationship.
   * @param from Optional beginning of the timeframe (specified as "YYYY-MM").
   * @param to Optional end of the timeframe (specified as "YYYY-MM").
   * @param limit Limits the amount of results to 100 iff <tt>limit<tt> is <b>true</b>.
   */
  def clusteredSources(relationshipId: Long, from: Option[String] = None, to: Option[String] = None, limit: Boolean = true) = Action { implicit request =>
    import util.UpList._
    
    // parse input data
    implicit val graph = timeframedGraph(from, to)
    val relationship = relationships(relationshipId)
    val fromTime = from map { clientDateStringToTimestamp }
    val toTime = to map { clientDateStringToTimestamp }
    Log.relationshipAccess(relationship.id.get, fromTime, toTime)
    
    // get and possibly sample sources
    val sources = Source.byRelationship(relationshipId, fromTime, toTime).withBenchmark("Getting sources from database")
    val sentenceIds = (sources map { _.sentence.id }).toSet
    val sampled = if (sources.size > 100 && limit) sources.sample(100) else sources
    
    // cluster selected sources
    val clusters = if (sampled.size > 1)
      (SourceClusteringMCL.cluster(sampled, pGamma = 1.2)).withBenchmark("Clustering sources")
    else oneClusterPerSource(sampled)
    
    // get tags associated with the sentences
    val tags = Tag.byRelationship(relationshipId).filterKeys(sentenceIds.contains(_))
    
    val json = Json.obj(
      "entity1" -> relationship.entity1,
      "entity2" -> relationship.entity2,
      "clusters" -> (clusters map { cluster =>
        val (proxies, rest) = splitCluster(cluster, relationship.entity1, relationship.entity2, tags)
        Json.obj(
          "proxies" -> proxies,
          "rest" -> rest
        )
      }),
      "tags" -> (tags map { case (key, value) => key.toString -> value }),
      "numClusters" -> clusters.size,
      "numSources" -> sampled.size,
      "numAllSources" -> sources.size
    )
    Ok(json).as(MimeTypes.JSON)
  }
  
  /*
   * Splits a cluster into up to three representants and the rest. Representants are selected as follows:
   * The first is the earliest in the cluster, the second is a sentence that might heuristically be good
   * for pattern generation, the third is a sentence that contains an automatically generated, but not yet
   * validated tag. Representants are displayed on the client directly, while the rest is initially hidden
   * from the user.
   */
  private def splitCluster(cluster: List[Source], e1: Entity, e2: Entity, tags: Map[Long, List[Tag]]) = {
    val pattern = """%s \S+ \S+( \S+)? %s"""
    if (cluster.size > 3) {
      val name1 = e1.name
      val name2 = e2.name
      
      val earliest = Some(cluster.head)
      val prettiest = cluster.find { source =>
        source != earliest.get &&
        (pattern.format(name1, name2).r.findFirstIn(source.sentence.text).size > 0 ||
         pattern.format(name2, name1).r.findFirstIn(source.sentence.text).size > 0)
      }
      val tagged = cluster find { source =>
        source != earliest.get && source != prettiest.getOrElse(null) &&
        tags.contains(source.sentence.id) && tags(source.sentence.id).find(tag => tag.automatic && !tag.hasPositiveVotes).isDefined
      }
      
      val proxies = List(earliest, prettiest, tagged).flatten
      val rest = cluster filter { source => !proxies.contains(source) }
      val missing = 3 - proxies.length
      
      (proxies ++ (rest take missing), rest drop missing)
    } else (cluster, List[Source]())
  }
  
  /*
   * Clusters every sentence into its own cluster.
   */
  private def oneClusterPerSource(sources: List[Source]) = {
    sources map { source => List(source) } toArray
  }
  
  /*
   * Converts a graph to JSON format.
   */
  private def graphToJson(implicit graph: Graph[Long, Long]) = {
    Json.obj(
      "nodes" -> Json.toJson(graph.getVertices() map { id => CompleteGraph.entities(id) } ),
      "links" -> Json.toJson(graph.getEdges() map { id => CompleteGraph.relationships(id) } )
    )
  }
  
  /*
   * Creates timeframe filter, given optional timeframe beginning and end (encoded as "YYYY-MM"). The filter
   * is a JUNG <tt>EdgePredicateFilter</tt>, extended by a value <tt>affectsGraph</tt> that is <b>true</b> iff
   * one of the timeframe boundaries is not <tt>None</tt>.
   */
  private def createTimeframeFilter(from: Option[String], to: Option[String]) = {
    val fromTime = if (from.isDefined && from.get.isEmpty()) None else from map { clientDateStringToTimestamp }
    val toTime = if (to.isDefined && to.get.isEmpty()) None else to map { clientDateStringToTimestamp }
    new EdgePredicateFilter[Long, Long](new Predicate[Long]() {
      override def evaluate(edge: Long) = {
        val r = CompleteGraph.relationships(edge)
        val after = fromTime map { time => r.timestamps.get.filter { _ >= time } } getOrElse r.timestamps.get
        val before = toTime map {time => after.filter { _ <= time } } getOrElse after
        before.length > 0
      }
    }) { val affectsGraph = from.isDefined || to.isDefined }
  }
  
  /*
   * Creates a filtered view onto the graph.
   */
  private def timeframedGraph(from: Option[String], to: Option[String]) = {
    val filter = createTimeframeFilter(from, to)
    if (filter.affectsGraph) filter.transform(CompleteGraph.graph).asInstanceOf[UndirectedSparseMultigraph[Long, Long]] else CompleteGraph.graph
  } 
  
  /*
   * Converts clientside date strings "YYYY-MM" to a <tt>DateTime</tt>.
   */
  private def clientDateStringToTimestamp(date: String) = {
    val Array(year, month) = date.split("-")
    new DateTime().withYear(year.toInt).withMonthOfYear(month.toInt).getMillis()
  }
}