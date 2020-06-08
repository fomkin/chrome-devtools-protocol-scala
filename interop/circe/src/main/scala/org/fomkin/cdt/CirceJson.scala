package org.fomkin.cdt

import org.fomkin.cdt.{Json => JsonT}

import io.circe.Json
import io.circe.parser
import io.circe._

final class CirceJson extends JsonT[Json] {

  // Creation

  def obj(items: (String, Json)*): Json =
    Json.obj(items:_*)

  def add(obj: Json, k: String, v: Json): Json =
    obj.asObject.fold(obj)(obj => Json.fromJsonObject(obj.add(k, v)))

  def array(items: Json*): Json =
    Json.arr(items:_*)

  def string(s: String): Json =
    Json.fromString(s)

  def int(i: Int): Json =
    Json.fromInt(i)

  def number(d: Double): Json =
    Json.fromDoubleOrNull(d)

  def long(l: Long): Json =
    Json.fromLong(l)

  def boolean(b: Boolean): Json =
    Json.fromBoolean(b)

  def nil: Json =
    Json.Null

  // Read properties

  def unsafeGetNullable(json: Json, field: String): Json = {
    val obj = json.asObject.get
    if (obj.contains(field)) obj(field).get
    else nil
  }

  def unsafeGet(json: Json, field: String): Json =
    get(json, field).get

  def get(json: Json, field: String): Option[Json] =
    json.asObject.flatMap(_(field))

  // Unsafe converters

  def unsafeToArray(json: Json): Seq[Json] =
    toArray(json).get

  def unsafeToString(json: Json): String =
    toString(json).get

  def unsafeToInt(json: Json): Int =
    toInt(json).get

  def unsafeToDouble(json: Json): Double =
    toDouble(json).get

  def unsafeToLong(json: Json): Long =
    toLong(json).get

  def unsafeToBoolean(json: Json): Boolean =
    toBoolean(json).get

  // Safe converters

  def toArray(json: Json): Option[Seq[Json]] =
    json.asArray

  def toString(json: Json): Option[String] =
    json.asString

  def toInt(json: Json): Option[Int] =
    json.asNumber.flatMap(_.toInt)

  def toDouble(json: Json): Option[Double] =
    json.asNumber.map(_.toDouble)

  def toLong(json: Json): Option[Long] =
    json.asNumber.flatMap(_.toLong)

  def toBoolean(json: Json): Option[Boolean] =
    json.asBoolean

  // Misc

  def parse(s: String): Either[String, Json] =
    parser.parse(s).left.map(_.getMessage)

  def unsafeParse(s: String): Json =
    parser.parse(s) match {
      case Left(error) => throw error
      case Right(value) => value
    }

  def stringify(j: Json): String =
    j.printWith(Printer.noSpaces)

  def isNull(json: Json): Boolean =
    json.isNull
}

object CirceJson {
  implicit val circeJson: JsonT[Json] =
    new CirceJson()
}