package util

import play.api.libs.json.JsSuccess
import play.api.libs.json.JsResult
import play.api.libs.json.JsValue
import play.api.libs.json.JsString
import play.api.libs.json.Format
import play.api.libs.json.Json
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.JsArray

/**
 * Extends JSON functionality that is commonly needed but not present in Play.
 */
object JsonExtension {
  
  /**
   * Format for Scala's <tt>Map</tt> type as in
   * http://stackoverflow.com/questions/14467689/scala-to-json-in-play-framework-2-1
   */
  implicit val mapFormat = new Format[Map[String, Object]] {
    def writes(map: Map[String, Object]): JsValue = 
      Json.obj(map.map { case (s, o) =>
        val ret: (String, JsValueWrapper) = o match {
          case _: String => s -> JsString(o.asInstanceOf[String])
          case _ => s -> JsArray(o.asInstanceOf[List[String]].map(JsString(_)))
        }
        ret
      }.toSeq: _*)
    
    def reads(jv: JsValue): JsResult[Map[String, Object]] =
      JsSuccess(jv.as[Map[String, JsValue]].map { case (k, v) =>
        k -> (v match {
          case s: JsString => s.as[String]
          case l => l.as[List[String]]
        })
      })
  }
}