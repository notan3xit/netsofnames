package models

import edu.uci.ics.jung.algorithms.filters.FilterUtils
import scala.collection.mutable.PriorityQueue
import play.api.Logger
import edu.uci.ics.jung.graph.UndirectedSparseMultigraph
import scala.collection.JavaConversions._
import controllers.Graphs
import com.thoughtworks.xstream.XStream
import java.nio.file.Files
import controllers.Application
import com.thoughtworks.xstream.io.xml.StaxDriver
import edu.uci.ics.jung.algorithms.filters.EdgePredicateFilter
import scala.collection.{mutable, immutable}
import java.math.BigDecimal

/**
 * Defines different types of significance metrics. This object is used to centrally define what is the
 * default implementation for edge significance (and other values).
 */
object Significance {
  
  import CompleteGraph._ 
  
  /**
   * Defines edge significance (default is positive normalized pointwise-mutual information). The value
   * ranges betwen 0 and 1 (as opposed to -1 and 1 in NPMI). It is positive, because positive values can
   * easily be treated as percentages and normalized, because plain PMI does not have set bounds.
   */
  def edgeSignificance(relationship: Relationship) = {
    NPMI.posnpmi(relationship, 2)
  }
  
  def edgeSignificance(edge: Long): Double = {
    edgeSignificance(relationships(edge))
  }
  
  /**
   * Edge strength is a value that is used in cases where where flows are calculated (as in the widest
   * paths problem). Strength is also modelled by positive normalized pointwise-mutual information, but
   * rounded to two decimal digits to prevent small differences from having an impact on algorithms.
   */
  def edgeStrength(relationship: Relationship) = {
    val s = NPMI.posnpmi(relationship)
    new BigDecimal(s).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()
  }

  def edgeStrength(edge: Long): Double = {
	  edgeStrength(relationships(edge))
  }
}

/**
 * The base implementation for behaviour when expanding graphs by degree of interest measures. This base
 * class defines basic algorithms for the expansion by nodes and by edges, leaving the implementation of DOI measures to its subclasses.
 * 
 * @param focus The set of focal nodes, i.e. the entities specified by the user search. The cardinality of this
 *   set is typically one (when searching for a single name) or two (if searching relationships between two entities).
 * @param filter Optional timeframe filter (as concstructuted in <tt>Graphs</tt>).
 */
abstract class Expander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]] = None) {
  
  import CompleteGraph.{entities, relationships}
  
  // the graph: either unfiltered (if no timeframe filter is specified) or filtered
  val graph: UndirectedSparseMultigraph[Long, Long] = {
    filter map { f => f.transform(CompleteGraph.graph).asInstanceOf[UndirectedSparseMultigraph[Long, Long]] } getOrElse CompleteGraph.graph
  }
  
  /**
   * Calculates the distance from the focus to a given edge, given by the minimum of shortest path lengths between all focal nodes
   * and edge endpoints.
   */
  def distanceOfEdge(e: Long): Double = {
    (focus flatMap { f => graph.getEndpoints(e) map { CompleteGraph.distances.getDistance(f, _).doubleValue() } }).min
  }
  
  /**
   * Calculates the distance from the focus to a given node, given by the minimum of shortest path lengths between all focal nodes
   * and the node.
   */
  def distanceOfNode(n: Long) = {
    (focus map { f => CompleteGraph.distances.getDistance(f, n).doubleValue() }).min
  }
  
  /**
   * Initializes the expander (i.e. by loading pre-computed values).
   */
  def initialize(): Unit
  
  /**
   * Given a set of entities, expands an interesting subgraph that contains them.
   * 
   * This method needs to be implemented by subclasses, typically by calling <tt>expandByNodes</tt> or <tt>expandByEdges</tt>
   * (depending on the DOI measure used).
   * 
   * @param selected The nodes that should be present in the resulting subgraph.
   * @param numNodes The number of nodes that should be in the resulting subgraph. The difference between |<tt>selected</tt>|
   *   and <tt>numNodes</tt> is the number of additional nodes that will be added to the graph (if they exist).
   */
  def forEntities(selected: scala.collection.mutable.Set[Long], numNodes: Int): UndirectedSparseMultigraph[Long, Long]
  
  def forEntity(nodeId: Long, numNodes: Int): UndirectedSparseMultigraph[Long, Long] = {
    forEntities(scala.collection.mutable.Set(nodeId), numNodes)
  }
  
  /**
   * Returns a subgraph that includes additional neighbours of a given node.
   * 
   * This method needs to be implemented by subclasses, typically by calling <tt>neighborsByNodes</tt> or <tt>neighborsByEdges</tt>
   * (depending on the DOI measure used).
   * 
   * @param nodeId Neighbours of this node are eligible for selection.
   * @param numNodes The subgraph should contain this number of additional nodes.
   * @param expanded A set of nodes that should not be chosen (because they are already in the graph); these nodes are part
   *   of the resulting subgraph, but do not count towards <tt>numNodes</tt>.
   * @param ignored A set of node that should note be chosen (because the user chose to ignore them); these nodes are not part
   *   of the resulting subgraph and are not counted towards <tt>numNodes</tt>.
   */
  def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]): UndirectedSparseMultigraph[Long, Long]

  /**
   * Expands an interesting subgraph given a set of pre-selected nodes, employing an algorithm that operates on DOI values of nodes.
   * Expands a given set of nodes to a target size and returns the subgraph induced by the resulting set of nodes.
   * 
   * @param selected The set of nodes already present (e.g. the focal nodes). This set will be modified by this method.
   * @param numNodes The target size of the resulting subgraph.
   */
  protected final def expandByNodes(selected: mutable.Set[Long], numNodes: Int)(implicit ordering: Ordering[Long], expandCondition: Long => Boolean): UndirectedSparseMultigraph[Long, Long] = {
    Logger.debug("Expanding graph for %s".format(selected))
    
    // create a queue of eligible nodes (the neighbours of nodes in selected), ordered by a given ordering
    // which is passed from the implementing subclass and should be based on the DOI measure used.
    val queue = new PriorityQueue()(ordering)
    for (nodeId <- selected) {
      queue ++= graph.getNeighbors(nodeId)
    }
    
    // add nodes to selected until the target size is reached or no eligible nodes are left
    while (selected.size < numNodes && queue.size > 0) {
      val next = queue.dequeue
      if (!selected.contains(next)) {
        selected += next
        if (expandCondition(next)) { // prevents hubs (high-degree nodes) from being expanded
          Logger.debug("Expanding graph for %s (%d)".format(entities(next).name, entities(next).id.get))
          var neighbors = graph.getNeighbors(next) filter { n => !selected.contains(n) }
          queue ++= neighbors
        }
      }
    }
    
    // return the subgraph induced by all nodes from selected
    Logger.debug("Selected: %s".format(selected))
    FilterUtils.createInducedSubgraph[Long, Long, UndirectedSparseMultigraph[Long, Long]](selected, graph)
  }
  
  protected final def expandByNodes(nodeId: Long, numNodes: Int)(implicit ordering: Ordering[Long], expandCondition: Long => Boolean): UndirectedSparseMultigraph[Long, Long] = {
    expandByNodes(mutable.Set(nodeId), numNodes)
  }
  
  /**
   * Expands an interesting subgraph given a set of pre-selected nodes, employing an algorithm that operates on DOI values of edges.
   * Expands a given set of nodes to a target size and returns the subgraph induced by the resulting set of nodes.
   * 
   * @param selected The set of nodes already present (e.g. the focal nodes). This set will be modified by this method.
   * @param numNodes The target size of the resulting subgraph.
   */
  protected final def expandByEdges(selected: mutable.Set[Long], numNodes: Int)(implicit ordering: Ordering[Long], expandCondition: Long => Boolean): UndirectedSparseMultigraph[Long, Long] = {
    Logger.debug("Expanding graph by significance measure for %s".format(selected))
    
    // create a queue of eligible edges (connections to neighbours of nodes in selected), ordered by a given ordering
    // which is passed from the implementing subclass and should be based on the DOI measure used.
    val queue = new PriorityQueue()(ordering)
    for (nodeId <- selected) {
      queue ++= graph.getIncidentEdges(nodeId)
    }
    
    // add nodes to selected until the target size is reached or no eligible nodes are left
    while (selected.size < numNodes && queue.size > 0) {
      val next = queue.dequeue
      val candidates = graph.getEndpoints(next) // 
      if (!selected.containsAll(candidates)) {
        val node = if (selected.contains(candidates.getFirst())) candidates.getSecond() else candidates.getFirst()
        selected += node
        if (expandCondition(node)) { // prevents hubs (high-degree nodes) from being expanded
          Logger.debug("Expanding graph for %s (%d)".format(entities(node).name, entities(node).id.get))
          var incident = graph.getIncidentEdges(node) filter { e => !selected.containsAll(graph.getEndpoints(e)) }
          queue ++= incident
        }
      }
    }
    
    // return the subgraph induced by all nodes from selected
    Logger.debug("Selected: %s".format(selected))
    FilterUtils.createInducedSubgraph[Long, Long, UndirectedSparseMultigraph[Long, Long]](selected, graph)
  }
  protected final def expandByEdges(nodeId: Long, numNodes: Int)(implicit ordering: Ordering[Long], expandCondition: Long => Boolean): UndirectedSparseMultigraph[Long, Long] = {
    expandByEdges(mutable.Set(nodeId), numNodes)
  }
  
  /**
   * Adds a number of most interesting neighbours to a given subgraph and returns the resulting subgraph, employing an algorithm that
   * operates of DOI values for nodes.
   * 
   * @param nodeId Neighbours of this node are eligible for selection.
   * @param numNodes This number of nodes should be added to the subgraph.
   * @param expanded A set of nodes already present in the graph.
   * @param ignored A set of nodes that should not be selected for the resulting subgraph.
   */
  protected final def neighborsByNodes(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long])(implicit ordering: Ordering[Long]) = {
    Logger.debug("Expanding neighbors of %d (num = %d) by neighboring nodes, excluding %s, ignoring %s".format(nodeId, numNodes, expanded, ignored))
    
    // create a queue of eligible neighbours
    val queue = new PriorityQueue()(ordering)
    queue ++= graph.getNeighbors(nodeId)
    
    // take numNodes neighbours that are neither already expanded nor ignored
    val selected = (queue.filter(node => !expanded.contains(node) && !ignored.contains(node)) take numNodes)
    
    // return the subgraph induced by the complete set of nodes (existing and selected)
    Logger.debug("Selected: %s".format(selected))
    FilterUtils.createInducedSubgraph[Long, Long, UndirectedSparseMultigraph[Long, Long]](selected ++ expanded += nodeId, graph)
  }
  
  /**
   * Adds a number of most interesting neighbours to a given subgraph and returns the resulting subgraph, employing an algorithm that
   * operates of DOI values for edges.
   * 
   * @param nodeId Neighbours of this node are eligible for selection.
   * @param numNodes This number of nodes should be added to the subgraph.
   * @param expanded A set of nodes already present in the graph.
   * @param ignored A set of nodes that should not be selected for the resulting subgraph.
   */
  protected final def neighborsByEdges(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long])(implicit ordering: Ordering[Long]) = {
    Logger.debug("Expanding neighbors of %d (num = %d) by incident edges, excluding %s, ignoring %s".format(nodeId, numNodes, expanded, ignored))
    
    // create a queue of eligible neighbours
    val queue = new PriorityQueue()(ordering)
    queue ++= graph.getIncidentEdges(nodeId)
    
    // take numNodes neighbours that are neither already expanded nor ignored
    // NOTE: it is important to convert the PriorityQueue to another collection at the end, before converting edge ids to node selection;
    // otherwise the ordering will fail assuming that ids belong to edges
    val selectedEdges = (queue
      .filter { edge =>
        val node = graph.getOpposite(nodeId, edge)
        !expanded.contains(node) && !ignored.contains(node)
      }).take(numNodes).toList
    val p = selectedEdges map { edge => graph.getOpposite(nodeId, edge) }
    val selected = selectedEdges flatMap { edge => graph.getEndpoints(edge) } filter (node => !expanded.contains(node) && !ignored.contains(node))
    
    // return the subgraph induced by the complete set of nodes (existing and selected)
    Logger.debug("Selected: %s".format(selected))
    FilterUtils.createInducedSubgraph[Long, Long, UndirectedSparseMultigraph[Long, Long]](selected ++ expanded :+ nodeId, graph)
  }
}

/**
 * Companion ob
 */
object Expander {
  
  import CompleteGraph._
  
  /**
   * The default factor by which distances are included into DOI values. I.e. nodes with distance 1 from the
   * focal node have <tt>DefaultDistanceDiscountFactor</tt> times their actual DOI value, nodes with
   * distance 2 have <tt>DefaultDistanceDiscountFactor</tt>^2 times their actual DOI value, and so on.
   */
  val DefaultDistanceDiscountFactor = 0.8d
  
  /**
   * The default condition that is used to determine that a node is a hub (i.e. has very high degree) and should
   * not be expanded further once selected to an interesting subgraph. This is used to prevent hubs from introducing
   * a large number of eligible neighbours, thereby driving the focus away from the original search to a-priori
   * interesting parts of the graph.
   */
  val DefaultExpandCondition = (node: Long) => graph.getNeighborCount(node) < 500
  
  /**
   * Constructs an expander by a string identifier.
   * 
   * @param name String identifier of an Expander implementation.
   * @param focus The set of focal nodes.
   * @param filter Optional timeframe filter (as constructed in <tt>Graphs</tt>). 
   */
  def byName(name: String, focus: immutable.Set[Long], filter: Option[EdgePredicateFilter[Long, Long]] = None) = {
    name match {
      case "npmi" => new NPMIExpander(focus, filter)
      case "doi-freq" => new DOIExpander(focus, filter)
      case "doi-freq-log" => new DOILogExpander(focus, filter)
      case "pmi" => new PMIExpander(focus, filter)
      case "npmi-independence" => new NPMIIndependenceExpander(focus, filter)
      case "npmi-dependence" => new NPMIDependenceExpander(focus, filter)
      case "freq" => new FrequencyExpander(focus, filter)
      case _ => throw new RuntimeException("Invalid expander selection.")
    }
  }
}

/**
 * Implementation of [[positive] normalized] pointwise-mutual information.
 */
object NPMI {
  
  import CompleteGraph._
  
  def pmi(r: Relationship, exponent: Double = 1): Double = {
    val fAB = r.frequency.toDouble / CompleteGraph.graph.getEdgeCount()
    val fA = r.entity1.frequency.toDouble / CompleteGraph.graph.getVertexCount()
    val fB = r.entity2.frequency.toDouble / CompleteGraph.graph.getVertexCount()
    Math.log(math.pow(fAB, exponent) / (fA * fB))
  }
  
  def npmi(r: Relationship, exponent: Double = 1): Double = {
    val fAB = r.frequency.toDouble / CompleteGraph.graph.getEdgeCount()
    (pmi(r, exponent) / -Math.log(math.pow(fAB, exponent)))
  }
  
  def posnpmi(r: Relationship, exponent: Double = 1): Double = {
    (npmi(r, exponent) + 1) / 2
  }
}

/**
 * Expander based on the default implementation of edge significance (based on normalized pointwise-mutual information).
 * 
 * TODO This is not strictly an NPMIExpander, but a DefaultExpander, since it delegates calculation of significance
 * values to <tt>Significance</tt>.
 */
class NPMIExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]] = None) extends Expander(focus, filter) {
  
  import Expander._
  import CompleteGraph._
  import NPMI._
  
  implicit val ordering = new Ordering[Long] {
    override def compare(link1: Long, link2: Long) = {
      doi(link1).compare(doi(link2))
    }
  }
  
  /**
   * Degree of interest calculated by
   * 
   *   1/d(edge) * significance(edge)
   *   
   * where d is the distance of an edge from the focal node (i.e. its closest endpoint) and significance is the default
   * measure for edge interestingness (i.e. NPMI).
   */
  def doi(edge: Long) = {
    Significance.edgeSignificance(edge) * Math.pow(DefaultDistanceDiscountFactor, distanceOfEdge(edge))
  }
  
  implicit val expandCondition = Expander.DefaultExpandCondition
  
  override def initialize() = {}
  
  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByEdges(selected, numNodes)
  }
  
  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByEdges(nodeId, numNodes, expanded, ignored)
  }
}