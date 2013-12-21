package models

import edu.uci.ics.jung.graph.UndirectedSparseMultigraph
import scala.collection.JavaConversions._
import edu.uci.ics.jung.algorithms.metrics.Metrics
import play.Logger
import edu.uci.ics.jung.algorithms.shortestpath.DijkstraShortestPath

/**
 * Calculations for several graph metrics.
 */
object GraphMetrics {
  
  import CompleteGraph._
  
  lazy val all = Map(
    "Number of vertices" -> numberOfVertices(),
    "Number of edges" -> numberOfEdges(),
    "Average degree" -> averageDegree(),
    "Average shortes path length" -> averageShortestPathLength(),
    "Diametre" -> diameter(),
    "Network clustering coefficient" -> clusterinCoefficient()
  )
  
  def numberOfVertices() = {
    Logger.debug("Calculating number of vertices...")
    graph.getVertexCount()
  }
  
  def numberOfEdges() = {
    Logger.debug("Calculating number of edges...")
    graph.getEdgeCount()
  }
  
  def averageDegree() =  {
    Logger.debug("Calculating average degree...")
    var sum = 0l
    for (u <- graph.getVertices()) {
      sum += graph.getNeighborCount(u)
      if (sum < 0) throw new RuntimeException("Long overflow while calculating average degree.")
    }
    sum.toDouble / graph.getVertexCount()
  }
  
  def averageShortestPathLength() = {
    Logger.debug("Calculating average shortes path length...")
    var sum = 0l
    var num = 0l
    for (u <- graph.getVertices()) {
      val distances = new DijkstraShortestPath(graph, true) // created cached distances for this source
      for (v <- graph.getVertices() if v > u) {
        val distance = distances.getDistance(u, v)
        if (distance != null) {
          sum += distance.intValue()
          num += 1
          if (sum < 0) throw new RuntimeException("Long overflow while calculating average shortest path length.")
        }
      }
    }
    Logger.debug("Average shortest path calculations: sum = %d, num = %d".format(sum, num))
    sum.toDouble / num
  }
  
  def diameter() = {
    Logger.debug("Calculating diametre...")
    var max = 0
    for (u <- graph.getVertices()) {
      val distances = new DijkstraShortestPath(graph, true)
      for (v <- graph.getVertices() if v > u) {
        val distance = distances.getDistance(u, v)
        if (distance != null)
          max = math.max(max, distance.intValue())
      }
    }
    max
  }
  
  def clusterinCoefficient() = {
    Logger.debug("Calculating clustering coefficient...")
    val coefficients = Metrics.clusteringCoefficients(graph)
    var sum = 0d
    var num = 0l
    coefficients.values foreach { c =>
      sum += c
      num += 1
      if (sum < 0) throw new RuntimeException("Double overflow while calculating clustering coefficient.")
      
    }
    sum / num
  }
}