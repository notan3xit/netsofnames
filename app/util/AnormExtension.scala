package util

import org.joda.time._
import org.joda.time.format._
import anorm._

/**
 * Extends Anorm by functionality that is not present, but frequently required.
 * 
 * DateTime format, conversion to statements, and from database results as in
 * http://stackoverflow.com/questions/11388301/joda-datetime-field-on-play-framework-2-0s-anorm
 */
object AnormExtension {

  val dateFormatGeneration: DateTimeFormatter = DateTimeFormat.forPattern("yyyyMMddHHmmssSS");

  /**
   * Conversion of timestamp columns to DateTime.
   */
  implicit def rowToDateTime: Column[DateTime] = Column.nonNull { (value, meta) =>
    val MetaDataItem(qualified, nullable, clazz) = meta
    value match {
      case ts: java.sql.Timestamp => Right(new DateTime(ts.getTime))
      case d: java.sql.Date => Right(new DateTime(d.getTime))
      case str: java.lang.String => Right(dateFormatGeneration.parseDateTime(str))
      case _ => Left(TypeDoesNotMatch("Cannot convert " + value + ":" + value.asInstanceOf[AnyRef].getClass))
    }
  }

  /**
   * Conversion of DateTimes to timestamp values in statements.
   */
  implicit val dateTimeToStatement = new ToStatement[DateTime] {
    def set(s: java.sql.PreparedStatement, index: Int, aValue: DateTime): Unit = {
      s.setTimestamp(index, new java.sql.Timestamp(aValue.withMillisOfSecond(0).getMillis()))
    }
  }

}