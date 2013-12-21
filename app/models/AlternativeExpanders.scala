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
import scala.collection.{ mutable, immutable }

/*
 * Expanders that are normally not used in Networks of Names (but were implemented for development and comparison
 * purposes. For the expander based on npmi, as used by the system, see <tt>NPMIExpander</tt> (and its related
 * classes and objects) in <tt>Expander.scala</tt>.
 */

/**
 * Implements graph expansion by plain node frequency, i.e. nodes with higher frequency are considered more interesting.
 * 
 * @see Expander
 */
class FrequencyExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]]) extends Expander(focus) {

  import Expander._
  import CompleteGraph._

  implicit val ordering = new Ordering[Long] {
    override def compare(node1: Long, node2: Long) = {
      doi(node1).compare(doi(node2))
    }
  }
  implicit val expandCondition = Expander.DefaultExpandCondition

  override def initialize() = {}

  /**
   * Degree of interest defined as
   *       1
   * -------------- * frequency(node)
   * d(focus, node)
   * 
   * where d is the distance of a node from the focal node and frequency is the plain node frequency (as given in the
   * underlying data).
   */
  def doi(node: Long): Double = {
    entities(node).frequency * Math.pow(DefaultDistanceDiscountFactor, distanceOfNode(node))
  }

  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByNodes(selected, numNodes)
  }

  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByNodes(nodeId, numNodes, expanded, ignored)
  }
}

/**
 * Implements relevant degree-of-interest function as specified in "Search, Show Context, Expand on Demand" by
 * van Ham and Perer.
 *
 * Specifically, functions to calculate
 *
 *   API_diff(x) = max(API(x), δ ⋅ max(n ∈ N(x) : 1/EI(e,x,n) ⋅ API_diff(n)))
 *
 * are given, where
 *
 *   - API_diff is the diffused a-priori interest of a node x, diffused meaning that the a-priori interest of
 *     neighbors is taken into account to prevent many unconnected "interesting" subgraphs
 *   - API_diff is initially set to API, the undiffused a-priory interest given by node metadata
 *   - EI is the edge interest function
 *   
 * API, i.e. the inherent node interestingness is given by plain node frequency.
 */
object DOI {

  import CompleteGraph._

  // δ, i.e. the factor that discounts DOI values for each hop during diffusion
  val delta = 0.5d
  
  // API values are pre-calculated and stored to a file, because on-the-fly calculation of diffusion is too
  // complex computationally
  val cache = Application.DOICache

  // computes API_diff and caches results, or loads cached results from file
  lazy val apidiffs = {
    val xstream = new XStream(new StaxDriver())
    if (Files.exists(cache)) {
      Logger.debug("Loading a-priori node interests from file...")
      // read serialized apidiffs
      xstream.fromXML(Files.newInputStream(cache)).asInstanceOf[mutable.HashMap[Long, Double]]
    } else {
      Logger.debug("Calculating a-priori node interests...")
      val computed = recompute()
      xstream.toXML(computed, Files.newOutputStream(cache))
      computed
    }
  }

  /**
   * (Re)computes API_diff values by a greedy algorithm as presented by van Ham and Perer.
   */
  def recompute() = {
    // delete cache if exists,
    if (Files.exists(cache)) Files.delete(cache)

    // define how API_diff calculation is done
    val diffs = new mutable.HashMap[Long, Double]()
    def apidiff(eid: Long): Double = {
      val diff = (graph.getNeighbors(eid) map {
        n => (1 / ed(eid, n)) * diffs.get(n).getOrElse(0d)
      }).reduceOption(_ max _)
      math.max(api(eid), delta * diff.getOrElse(0d))
    }

    // precompute apidiffs
    var i = 0;
    val toGo = new mutable.HashSet[Long]()
    toGo.addAll(graph.getVertices())
    while (!toGo.isEmpty) {
      // output remaining nodes (for debugging)
      if (i % 10000 == 0) println(toGo.size)
      i += 1;
      
      // recalculate API_diff for next node
      val element = toGo.head
      val prevApidiff = diffs.get(element).getOrElse(0d)
      diffs(element) = apidiff(element)

      // remove current element; add neighbours if value changed
      toGo.remove(element)
      if (diffs(element) != prevApidiff) {
        toGo.addAll(graph.getNeighbors(element))
      }
    }
    diffs
  }

  /*
   * Edge desinterest function (given as EI in the paper), using NPMI for edge
   * interestingness and its inverse to obtain desinterestingness.
   */
  private def ed(eid1: Long, eid2: Long): Double = {
    val r = graph.findEdge(eid1, eid2)
    val posnpmi = (NPMI.npmi(relationships(r)) + 1) / 2
    1f / posnpmi.toFloat
  }

  /*
   * A-priori node interestingness given by the plain node frequency.
   */
  private def api(eid: Long): Double = entities(eid).frequency.toDouble
}

/**
 * Same as <tt>DOI</tt>, but with log(frequency(node)) for a-priori interestingness.
 * 
 * @see DOI
 */
object DOILog {

  import CompleteGraph._

  val delta = 0.5d
  
  val cache = Application.DOILogCache

  lazy val apidiffs = {
    val xstream = new XStream(new StaxDriver())
    if (Files.exists(cache)) {
      Logger.debug("Loading a-priori node interests from file...")
      // read serialized apidiffs
      xstream.fromXML(Files.newInputStream(cache)).asInstanceOf[mutable.HashMap[Long, Double]]
    } else {
      Logger.debug("Calculating a-priori node interests...")
      val computed = recompute()
      xstream.toXML(computed, Files.newOutputStream(cache))
      computed
    }
  }

  def recompute() = {
    if (Files.exists(cache)) Files.delete(cache)

    var i = 0;

    // reset
    val diffs = new mutable.HashMap[Long, Double]()

    def apidiff(eid: Long): Double = {
      val diff = (graph.getNeighbors(eid) map {
        n => (1 / ed(eid, n)) * diffs.get(n).getOrElse(0d)
      }).reduceOption(_ max _)
      math.max(api(eid), delta * diff.getOrElse(0d))
    }

    // precompute apidiffs
    val toGo = new mutable.HashSet[Long]()
    toGo.addAll(graph.getVertices())
    while (!toGo.isEmpty) {
      if (i % 10000 == 0) println(toGo.size)
      i += 1;
      // recalculate apidiff
      val element = toGo.head
      val prevApidiff = diffs.get(element).getOrElse(0d)
      diffs(element) = apidiff(element)

      // remove current element; add neighbors if value changed
      toGo.remove(element)
      if (diffs(element) != prevApidiff) {
        toGo.addAll(graph.getNeighbors(element))
      }
    }
    diffs
  }

  private def ed(eid1: Long, eid2: Long): Double = {
    val r = graph.findEdge(eid1, eid2)
    val posnpmi = (NPMI.npmi(relationships(r)) + 1) / 2
    1f / posnpmi.toFloat
  }

  private def api(eid: Long): Double = math.log(entities(eid).frequency.toDouble)
}

/**
 * Implements graph expansion by diffused node DOI, i.e. nodes with higher diffused DOI are considered more interesting.
 * A-priori interestingness of nodes is the plain node frequency.
 * 
 * @see Expander
 * @see DOI
 */
class DOIExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]]) extends Expander(focus) {

  import Expander._
  import CompleteGraph._
  import DOI.apidiffs

  val cache = Application.OutputPath.resolve("doi.xml")

  implicit val ordering = new Ordering[Long] {
    override def compare(node1: Long, node2: Long) = {
      doi(node1).compare(doi(node2))
    }
  }
  implicit val expandCondition = Expander.DefaultExpandCondition

  override def initialize() = {
    DOI.apidiffs // initializes lazy variable
  }
  
  /**
   * Degree of interest defined as
   *       1
   * -------------- * doi(node)
   * d(focus, node)
   * 
   * where d is the distance of a node from the focal node and doi is the plain node frequency (as calculated
   * by <tt>DOI</tt>).
   */
  def doi(node: Long): Double = {
    apidiffs(node) * Math.pow(DefaultDistanceDiscountFactor, distanceOfNode(node))
  }

  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByNodes(selected, numNodes)
  }

  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByNodes(nodeId, numNodes, expanded, ignored)
  }
}

/**
 * Implements graph expansion by diffused node DOI, i.e. nodes with higher diffused DOI are considered more interesting.
 * A-priori interestingness of nodes is the logarithmic node frequency.
 * 
 * @see Expander
 * @see DOILog
 */
class DOILogExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]]) extends Expander(focus) {

  import Expander._
  import CompleteGraph._
  import DOILog.apidiffs

  val cache = Application.OutputPath.resolve("doi.xml")

  implicit val ordering = new Ordering[Long] {
    override def compare(node1: Long, node2: Long) = {
      doi(node1).compare(doi(node2))
    }
  }
  implicit val expandCondition = Expander.DefaultExpandCondition

  override def initialize() = {
    DOI.apidiffs // initializes lazy variable
  }

  /**
   * Degree of interest defined as
   *       1
   * -------------- * doi_log(node)
   * d(focus, node)
   * 
   * where d is the distance of a node from the focal node and doi_log is the logarithmic node frequency (as calculated
   * by <tt>DOILog</tt>).
   */
  def doi(node: Long): Double = {
    apidiffs(node) * Math.pow(DefaultDistanceDiscountFactor, distanceOfNode(node))
  }

  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByNodes(selected, numNodes)
  }

  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByNodes(nodeId, numNodes, expanded, ignored)
  }
}

/**
 * Implements graph expansion by DOI for edges, where DOI defined to be the pointwise-mutual information calculated in
 * terms of the frequencies of the edge and its endpoints.
 * 
 * @see Expander
 * @see NPMI
 */
class PMIExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]] = None) extends Expander(focus, filter) {

  import Expander._
  import CompleteGraph._
  import NPMI._

  implicit val ordering = new Ordering[Long] {
    override def compare(link1: Long, link2: Long) = {
      doi(link1).compare(doi(link2))
    }
  }

  /**
   * Degree of interest defined as
   *       1
   * -------------- * pmi(edge)
   * d(focus, edge)
   * 
   * where d is the distance of an edge from the focal node (i.e. its closest endpoint) and pmi is the pointwise-mutual information.
   */
  def doi(edge: Long) = {
    pmi(relationships(edge)) * Math.pow(DefaultDistanceDiscountFactor, distanceOfEdge(edge))
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

/**
 * Implements graph expansion by DOI for edges, where DOI defined to be the pointwise-mutual information calculated in
 * terms of the frequencies of the edge and its endpoints. This implementation favours the independence of entity occurrence,
 * i.e. relationships between entities that seem to appear together randomly are considered more interesting.
 * 
 * @see Expander
 * @see NPMI
 */
class NPMIIndependenceExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]]) extends Expander(focus, filter) {

  import Expander._
  import CompleteGraph._
  import NPMI._

  implicit val ordering = new Ordering[Long] {
    override def compare(link1: Long, link2: Long) = {
      npmii(link1).compare(npmii(link2))
    }
  }


  implicit val expandCondition = Expander.DefaultExpandCondition

  override def initialize() = {}

  /**
   * Degree of interest defined as
   *       1
   * -------------- * npmii(edge)
   * d(focus, edge)
   * 
   * where d is the distance of an edge from the focal node (i.e. its closest endpoint) and npmii is the pointwise-mutual information
   * favouring independence (calculated by 1 - |npmi(edge)|).
   */
  def doi(edge: Long) = {
	  npmii(edge) * Math.pow(DefaultDistanceDiscountFactor, distanceOfEdge(edge))
  }
  
  def npmii(edge: Long) = {
	  1 - Math.abs(npmi(relationships(edge)))
  }
  
  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByEdges(selected, numNodes)
  }

  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByEdges(nodeId, numNodes, expanded, ignored)
  }
}

/**
 * Implements graph expansion by DOI for edges, where DOI defined to be the pointwise-mutual information calculated in
 * terms of the frequencies of the edge and its endpoints. This implementation favours the dependence (of any sort)
 * of entity occurrence, i.e. relationships between entities that seem to always co-occur or tend to never occur together.
 * 
 * @see Expander
 * @see NPMI
 */
class NPMIDependenceExpander(focus: Set[Long], filter: Option[EdgePredicateFilter[Long, Long]]) extends Expander(focus, filter) {

  import Expander._
  import CompleteGraph._
  import NPMI._

  implicit val ordering = new Ordering[Long] {
    override def compare(link1: Long, link2: Long) = {
      npmid(link1).compare(npmid(link2))
    }
  }

  implicit val expandCondition = Expander.DefaultExpandCondition

  override def initialize() = {}

  /**
   * Degree of interest defined as
   *       1
   * -------------- * npmid(edge)
   * d(focus, edge)
   * 
   * where d is the distance of an edge from the focal node (i.e. its closest endpoint) and npmid is the pointwise-mutual information
   * favouring dependence (calculated by |npmi(edge)|).
   */
  def doi(edge: Long) = {
	  npmid(edge) * Math.pow(DefaultDistanceDiscountFactor, distanceOfEdge(edge))
  }
  
  def npmid(edge: Long) = {
	  Math.abs(npmi(relationships(edge)))
  }

  override def forEntities(selected: mutable.Set[Long], numNodes: Int) = {
    expandByEdges(selected, numNodes)
  }

  override def neighbors(nodeId: Long, numNodes: Int, expanded: Set[Long], ignored: Set[Long]) = {
    neighborsByEdges(nodeId, numNodes, expanded, ignored)
  }
}