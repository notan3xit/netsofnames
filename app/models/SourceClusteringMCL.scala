package models

import scala.collection.JavaConversions._
import net.sf.javaml.distance.CosineDistance
import net.sf.javaml.core.SparseInstance
import net.sf.javaml.clustering.mcl.MarkovClustering
import net.sf.javaml.clustering.mcl.SparseMatrix
import net.sf.javaml.distance.{CosineSimilarity => JavaMLCosineSimilarity}
import scala.math.Ordering
import org.joda.time.DateTimeComparator
import java.util.Comparator
import org.joda.time.DateTime
import java.util.Arrays
import net.sf.javaml.clustering.mcl.MCL
import net.sf.javaml.core.DefaultDataset
import scala.collection.JavaConversions._
import org.tartarus.snowball.ext.GermanStemmer
import play.Logger

/**
 * Implementation of source sentence clustering by text similarity. This implementation is based on JavaML's implementation of MCL clustering.
 * 
 * @param pGamma Configurable parameter to JavaML' MCL implementation. A default value is provided by the companion object.
 * @param loopGain Configurable parameter to JavaML' MCL implementation. A default value is provided by the companion object.
 */
class SourceClusteringMCL(sources: List[Source], pGamma: Double = SourceClusteringMCL.DefaultPGamma, loopGain: Double = SourceClusteringMCL.DefaultLoopGain) {

  import SourceClusteringMCL._
  import util.UpString._
  
  // documents are lists of stemmed tokens, with stopwords removed
  val documents = sources.map(_.sentence.text.stemsWithoutStopwords)
  
  // all words is a set of stemmed words in all documents
  val allWords = documents.flatten.sorted.distinct
  
  // inverse document frequencies
  val idf = inverseDocumentFrequencies(documents)
  
  // vector representation
  // this is based on sources (not documents) to link results back to the sources (which are needed for subsequent processing)
  val vectors = sources map { source =>
    source2vector(source)
  }
  
  // the data representation used by JavaML
  val dataset = new DefaultDataset(vectors.toList)
  
  /**
   * Calculation of clusters.
   */
  def clusters() = {
    val maxResidual = 0.001d // default: 0.001d, this determines what is idempotent in double representation and should be left, probably
    val maxZero = 0.001d // default: 0.001d, this determines what means "zero" in double representation and should be left, probably
    
    // instantiate and execute MCL algorithm
    val mcl = new MCL(CosineSimilarity, maxResidual, pGamma, loopGain, maxZero)
    val clusters = mcl.cluster(dataset)
    
    // map results back to Sources, cast to correct type and sort by earliest cluster member
    clusters
      .map(_.classes().toList.asInstanceOf[List[Source]].sortBy(source => source.date)(DateTimeOrdering))
      .sortBy(cluster => cluster(0).date)(DateTimeOrdering)
  }
  
  private def inverseDocumentFrequencies(documents: List[List[String]]) = {
    allWords map { t =>
      t -> Math.log(documents.size.toFloat / documents.filter(d => d.contains(t)).size)
    } toMap
  }
  
  private def termFrequencies(document: String) = {
    document.stemsWithoutStopwords.groupBy(identity).mapValues(_.length)
  }
  
  private def source2vector(source: Source) = {
    val tf = termFrequencies(source.sentence.text)
    val vector = allWords map { t =>
      if (tf.contains(t)) { tf(t) * idf(t) } else 0.0
    }
    new SparseInstance(vector.toArray, source)
  }
}

/**
 * Companion object for SourceClusteringMCL.
 */
object SourceClusteringMCL {
  
  // use JavaML's implementation of CosineSimilarity
  val CosineSimilarity = new JavaMLCosineSimilarity()
  
  // default values for pGamma and loopGain, as defined in JavaML (these can be changed by passing custom values during
  // instantiation of SourceClusteringMCL)
  val DefaultPGamma = 2.0d
  val DefaultLoopGain = 0.0d
  
  implicit val DateTimeOrdering = Ordering.comparatorToOrdering(DateTimeComparator.getInstance.asInstanceOf[Comparator[DateTime]])
  
  /**
   * Creates and runs a SourceClusteringMCL instance. 
   */
  def cluster(sources: List[Source], pGamma: Double = DefaultPGamma, loopGain: Double = DefaultLoopGain) = {
    new SourceClusteringMCL(sources, pGamma, loopGain).clusters()
  }
}

