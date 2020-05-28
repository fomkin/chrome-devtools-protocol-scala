package org.fomkin.cdt

trait Json[T] {

  def parse(s: String): Either[String, T]

  def json(json: T, field: String): T
  def array(json: T, field: String): Seq[T]
  def string(json: T, field: String): String
  def int(json: T, field: String): Int
  def double(json: T, field: String): Double
  def long(json: T, field: String): Long
  def boolean(json: T, field: String): Boolean

  def maybeJson(json: T, field: String): Option[T]
  def maybeArray(json: T, field: String): Option[Seq[T]]
  def maybeString(json: T, field: String): Option[String]
  def maybeInt(json: T, field: String): Option[Int]
  def maybeDouble(json: T, field: String): Option[Double]
  def maybeLong(json: T, field: String): Option[Long]
  def maybeBoolean(json: T, field: String): Option[Boolean]
}

object Json {
  def apply[T: Json]: Json[T] =
    implicitly[Json[T]]
  
  implicit class JsonOps[T: Json](j: T) {
    def json(field: String): T = Json[T].json(j, field)
    def array(field: String): Seq[T] = Json[T].array(j, field)
    def string(field: String): String = Json[T].string(j, field)
    def int(field: String): Int = Json[T].int(j, field)
    def double(field: String): Double = Json[T].double(j, field)
    def long(field: String): Long = Json[T].long(j, field)
    def boolean(field: String): Boolean = Json[T].boolean(j, field)

    def maybeJson(field: String): Option[T] = Json[T].maybeJson(j, field)
    def maybeArray(field: String): Option[Seq[T]] = Json[T].maybeArray(j, field)
    def maybeString(field: String): Option[String] = Json[T].maybeString(j, field)
    def maybeInt(field: String): Option[Int] = Json[T].maybeInt(j, field)
    def maybeDouble(field: String): Option[Double] = Json[T].maybeDouble(j, field)
    def maybeLong(field: String): Option[Long] = Json[T].maybeLong(j, field)
    def maybeBoolean(field: String): Option[Boolean] = Json[T].maybeBoolean(j, field)
  }
}