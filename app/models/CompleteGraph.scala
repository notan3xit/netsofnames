package models

import edu.uci.ics.jung.graph.SparseMultigraph
import edu.uci.ics.jung.graph.UndirectedSparseMultigraph
import edu.uci.ics.jung.algorithms.filters.FilterUtils
import scala.collection.JavaConversions._
import play.Logger
import edu.uci.ics.jung.graph.DirectedSparseGraph
import edu.uci.ics.jung.algorithms.flows.EdmondsKarpMaxFlow
import org.apache.commons.collections15.Transformer
import org.apache.commons.collections15.Factory
import scala.collection.mutable.PriorityQueue
import play.api.Play.current
import anorm._
import anorm.SqlParser._
import play.api.db.DB
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.StaxDriver
import java.nio.file.Files
import controllers.Application
import scala.collection.mutable.HashMap
import play.api.libs.json.Json
import com.thoughtworks.xstream.io.json.JsonHierarchicalStreamDriver
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath
import scala.collection.JavaConversions._
import java.math.BigDecimal

/**
 * The in-memory representation of the social graph and its properties.
 */
object CompleteGraph {

  import scala.collection.mutable.Map
  import scala.collection.mutable.Set
  
  /**
   * Object representation of entities.
   * 
   * @see Entity 
   */
  val entities = HashMap[Long, Entity]()
  /**
   * Object representation of relationships.
   * 
   * @see Relationship
   */
  val relationships = HashMap[Long, Relationship]()
  
  // maximum vertex and edge frequencies for the current graph
  var maxVertexFrequency = 0
  var maxEdgeFrequency = 0
  
  /**
   * The complete graph.
   */
  lazy val graph = {
    Logger.debug("Loading graph...")
    val graph = new UndirectedSparseMultigraph[Long, Long]()
    entities.clear()
    relationships.clear()
    
    import util.Benchmark._
    import controllers.Graphs.relationshipFormat
    
    // read entities and relationships from database
    Entity.processAll(processEntity(_: Entity, graph)).withBenchmark("Loading entities")
    Relationship.processAll(processRelationship(_: Relationship, graph)).withBenchmark("Loading relationships")
    Logger.debug("Graph loaded: %d nodes, %d edges".format(graph.getVertexCount(), graph.getEdgeCount()))
    graph
  }
  
  /*
   * Add the entity to entities, add a vertex (represented by the entity id) to the graph and store the current
   * maximum entity frequency.
   */
  private def processEntity(e: Entity, graph: UndirectedSparseMultigraph[Long, Long]) = {
    entities += e.id.get -> e
    graph.addVertex(e.id.get)
    maxVertexFrequency = Math.max(maxVertexFrequency, e.frequency)
  }
  
  /*
   * Add the relationship to relationships, add an edge (represented by the relationship id) to the graph and store the current
   * maximum relationship frequency.
   */
  private def processRelationship(r: Relationship, graph: UndirectedSparseMultigraph[Long, Long]) = {
    if (entities.contains(r.e1) && entities.contains(r.e2)) {
      relationships += r.id.get -> r
      graph.addEdge(r.id.get, r.e1, r.e2)
      maxEdgeFrequency = Math.max(maxEdgeFrequency, r.frequency)
    }
  }
  
  /**
   * Shortest paths between vertices.
   */
  lazy val distances = new DijkstraShortestPath(graph)
  
  /**
   * Maximum capacity paths between vertices.
   */
  lazy val capacities = new DijkstraWidestPaths(graph)
}