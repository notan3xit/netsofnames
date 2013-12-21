package util

/**
 * Base class for <tt>Created</tt> and <tt>Existed</tt>. Designed to differentiate between operation
 * results where an element might have been either created or have already existed.
 * 
 * Usage example (where createOrGet performs some operation and returns an instance of CreateOrGet):
 * 
 *   createOrGet(parameters...) match {
 *     case Created(newElement) => ...
 *     case Existed(oldElement) => ...
 *   }
 */
sealed abstract class CreateOrGet[+A](x: A) {
  
  /**
   * Returns the wrapped element.
   */
  def get = x
  
  /**
   * Returns <b>true</b> if the wrapped element was created. Needs to be overwritten
   * by subclasses.
   */
  def created: Boolean
  
  /**
   * Returns <b>true</b> if the wrapped element existed. 
   */
  def existed = !created
}

/**
 * Wrapper class to indicate that the wrapped element was created (in the course of some operation).
 */
final case class Created[+A](x: A) extends CreateOrGet[A](x) {
  def created = true
}

/**
 * Wrapper class to indicate that the wrapped element already existed (int the course of some operation
 * where it also might have been created).
 */
final case class Existed[+A](x: A) extends CreateOrGet[A](x) {
  def created = false
}
