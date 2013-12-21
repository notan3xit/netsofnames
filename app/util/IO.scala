package util

import java.nio.file.Path

/**
 * Provides I/O DSL.
 */
object IO {

  /**
   * Allows easy instantiation of a <tt>PrintWriter</tt>.
   * 
   * Usage example:
   * 
   *   printToFile(path) { p =>
   *     p.write(line)
   *     ...
   *   }
   */
  def printToFile(path: Path)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(path.toAbsolutePath().toFile())
    try { op(p) } finally { p.close() }
  }
}