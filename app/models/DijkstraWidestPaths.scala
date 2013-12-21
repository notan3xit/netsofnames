package models

import edu.uci.ics.jung.graph.Graph
import java.math.BigDecimal
import scala.collection.{mutable, immutable}
import edu.uci.ics.jung.algorithms.util.MapBinaryHeap
import java.util.Comparator
import scala.util.control.Breaks._
import scala.collection.JavaConversions._
import play.Logger
import scala.collection.mutable.ListBuffer

/**
 * All-pairs widest paths using a single-source widest paths implementation based on Dijkstra's algorithm.
 * The capacity is defined as the positive normalized pointwise-mutual information, rounded to two decimal
 * digits (to prevent small differences in pos. NPMI to have an impact on the path). Shorter paths are
 * preferred over longer paths of the same capacity.
 * 
 * @see SingleSourceWidestPaths
 * @see NPMI
 */
class DijkstraWidestPaths(graph: Graph[Long, Long], epsilon: Double = 0.001) {

  // caches results from one source to all other nodes
  val cache = mutable.Map[Long, SingleSourceWidestPaths]()
  
  /**
   * Returns the widest path (as a list of nodes) from a <tt>source</tt> to a <tt>target</tt>.
   * Results are calculated as needed and cached for future requests.
   */
  def widestPath(source: Long, target: Long) = {
    if (!cache.contains(source)) cache(source) = new SingleSourceWidestPaths(graph, source, epsilon)
    cache(source).pathTo(target)
  }
  
  /**
   * Returns the capacity of the widest path (as a Double value) from a <tt>source</tt> to a <tt>target</tt>.
   * Results are calculated as needed and cached for future requests.
   */
  def capacity(source: Long, target: Long) = {
    if (!cache.contains(source)) cache(source) = new SingleSourceWidestPaths(graph, source, epsilon)
    cache(source).capacityTo(target)
  }
}

/**
 * Single-source widest paths using a Dijkstra-based implementation. The capacity is defined as the positive
 * normalized pointwise-mutual information, rounded to two decimal digits (to prevent small differences in pos.
 * NPMI to have an impact on the path). Shorter paths are preferred over longer paths of the same capacity.
 */
class SingleSourceWidestPaths(graph: Graph[Long, Long], source: Long, epsilon: Double = 0.001) {
  
  // capacities that have been assigned their final values
  val knownCapacities = mutable.Map[Long, Double]()
  
  // capacities that may be subject to change as the algorithm proceeds, with the source having a capacity
  // of 1 (the maximum) to itself
  val estimatedCapacities = mutable.Map[Long, Double]().withDefault(_ => -1)
  estimatedCapacities += source -> 1
  
  // queue all vertices in a sorted heap
  val queue = new MapBinaryHeap[Long](new Comparator[Long]() {
    override def compare(some: Long, other: Long) = {
      -estimatedCapacities(some).compareTo(estimatedCapacities(other))
    }
  })
  queue.addAll(graph.getVertices())
  
  // keep track of the shortest path distances between vertices to be able to prefer shorter paths over
  // longer paths of the same capacity, whith the distance of the source node to itself being 0 (the minimum)
  val distances = mutable.Map[Long, Long]()
  distances += source -> 0
  
  // keep track of the predecessor for each node on the best paths to be able to reconstruct the paths
  val predecessors = mutable.Map[Long, Long]()

  /*
   * Implements the main Dijkstra algorithm loop that runs until the max capacity path to a given target node
   * is found. 
   */
  private def search(target: Long) = {
    breakable {
      // repeat while the distance to target is unknown or no path is found
      while(!queue.isEmpty && !knownCapacities.contains(target)) {
        // pop next node from queue and set its width to known
        val u = queue.remove()
        
        // this part of the graph is unreachable from source
        if (estimatedCapacities(u) < 0) break
        
        // move the node to known capacities
        knownCapacities += u -> estimatedCapacities(u)
        estimatedCapacities -= u
        
        // update neighbours
        graph.getNeighbors(u)
          .filter { !knownCapacities.contains(_) }
          .foreach { v =>
            // queue.add(v)
            val newCapacity = math.min(knownCapacities(u), width(graph.findEdge(u, v)))
            if (newCapacity > estimatedCapacities(v) || ~=(newCapacity, estimatedCapacities(v)) && distances(u) + 1 < distances(v)) {
              estimatedCapacities(v) = newCapacity
              predecessors(v) = u
              distances(v) = distances(u) + 1
              queue.update(v)
            }
          }
      }
    }
      
  }
  
  /**
   * Calculates and returns the maximum capacity between the source and a given target node as a Double value.
   */
  def capacityTo(target: Long) = {
    search(target)
    knownCapacities(target)
  }
  
  /**
   * Calculates and returns the maximum capacity path between the source and a given target node as a list
   * of node ids.
   */
  def pathTo(target: Long) = {
    search(target)
    val path = ListBuffer(target)
    var current = target
    while (predecessors.contains(current)) {
      val p = predecessors(current)
      p +=: path
      current = p
    }
    path
  }
  
  /**
   * Defines (approximate) equality to prevent double precision errors when checking for equality.
   */
  def ~=(d1: Double, d2: Double) = {
    (d1 - d2).abs <= epsilon
  }
  
  /**
   * Defines edge capacities (delegated to the <tt>Significance</tt> object).
   * 
   * @see Significance
   */
  def width(edge: Long) = {
    Significance.edgeStrength(edge)
  }
}