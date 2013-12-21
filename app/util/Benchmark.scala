package util

import java.util.concurrent.TimeUnit
import play.Logger

/**
 * Provides a benchmark DSL.
 */
object Benchmark {
  implicit def toBenchmarkable[B](op: => B) = {
    new Benchmarkable(op)
  }
}

/**
 * Adds <tt>withBenchmark</tt> method to operations that records and logs execution time.
 */
class Benchmarkable[B](op: => B) {
  
  /**
   * Given a message, logs it at the beginning of the operation, performs the operation, logs
   * the message again with the time (in seconds) it took to execute the operation, and returns
   * the operation's result.
   * 
   * Use like this:
   * 
   *   ([block of code]).withBenchmark("Performing operation")
   *   
   * This will result in the output:
   *   
   *   """
   *   Performing operation...
   *   Performing operation... [n seconds]
   *   """
   */
  def withBenchmark(message: String): B = {
    Logger.debug(message + "...")
    val start = System.nanoTime
    val result = op
    val end = System.nanoTime
    val seconds = TimeUnit.NANOSECONDS.toSeconds(end - start)
    Logger.debug("%s... Done. [%d seconds]".format(message, seconds))
    result
  }
}