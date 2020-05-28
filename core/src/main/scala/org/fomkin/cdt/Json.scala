package org.fomkin.cdt

trait Json[T] {

  def parse(s: String): Either[String, T]
  def unsafeParse(s: String): T

  def obj(items: (String,T)*): T
  def array(items: T*): T
  def string(s: String): T
  def int(i: Int): T
  def number(d: Double): T
  def long(l: Long): T
  def boolean(b: Boolean): T
  def nil: T

  def unsafeGet(json: T, field: String): T
  def get(json: T, field: String): Option[T]

  def unsafeToObject(json: T): T
  def unsafeToArray(json: T): Seq[T]
  def unsafeToString(json: T): String
  def unsafeToInt(json: T): Int
  def unsafeToDouble(json: T): Double
  def unsafeToLong(json: T): Long
  def unsafeToBoolean(json: T): Boolean
  def unsafeIsNull(json: T): Boolean

  def toObject(json: T): Option[T]
  def toArray(json: T): Option[Seq[T]]
  def toString(json: T): Option[String]
  def toInt(json: T): Option[Int]
  def toDouble(json: T): Option[Double]
  def toLong(json: T): Option[Long]
  def toBoolean(json: T): Option[Boolean]
  def isNull(json: T): Option[Boolean]
}

object Json {
  def apply[T: Json]: Json[T] =
    implicitly[Json[T]]
  
  implicit class JsonOps[T: Json](j: T) {
    // TODO
  }
}