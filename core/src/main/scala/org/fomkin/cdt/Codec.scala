package org.fomkin.cdt

trait Codec[J, T] {
  def unsafeRead(j: J): T
  def write(v: T): J
}

object Codec {

  def apply[J: Json, T](implicit codec: Codec[J, T]): Codec[J, T] =
    implicitly[Codec[J, T]]

  implicit def optionCodec[J: Json, T](implicit codec: Codec[J, T]): Codec[J, Option[T]] = new Codec[J, Option[T]] {
    def unsafeRead(j: J): Option[T] =
      if (Json[J].unsafeIsNull(j)) None
      else Some(Codec[J, T].unsafeRead(j))
    def write(v: Option[T]): J = v match {
      case Some(x) => Codec[J, T].write(x)
      case None => Json[J].nil
    }
  }

  implicit def jsonCodec[J: Json]: Codec[J, J] = new Codec[J, J] {
    def unsafeRead(j: J): J = j
    def write(v: J): J = v
  }

  implicit def seqCodec[J: Json, T](implicit codec: Codec[J, T]): Codec[J, Seq[T]] = new Codec[J, Seq[T]] {
    def unsafeRead(j: J): Seq[T] =
      Json[J].unsafeToArray(j).map(x => Codec[J, T].unsafeRead(x))
    def write(v: Seq[T]): J =
      Json[J].array(v.map(x => Codec[J, T].write(x)):_*)
  }
  
  implicit def stringCodec[J: Json]: Codec[J, String] = new Codec[J, String] {
    def unsafeRead(j: J): String = Json[J].unsafeToString(j)
    def write(v: String): J = Json[J].string(v)
  }

  implicit def intCodec[J: Json]: Codec[J, Int] = new Codec[J, Int] {
    def unsafeRead(j: J): Int = Json[J].unsafeToInt(j)
    def write(v: Int): J = Json[J].int(v)
  }

  implicit def doubleCodec[J: Json]: Codec[J, Double] = new Codec[J, Double] {
    def unsafeRead(j: J): Double = Json[J].unsafeToDouble(j)
    def write(v: Double): J = Json[J].number(v)
  }

  implicit def booleanCodec[J: Json]: Codec[J, Boolean] = new Codec[J, Boolean] {
    def unsafeRead(j: J): Boolean = Json[J].unsafeToBoolean(j)
    def write(v: Boolean): J = Json[J].boolean(v)
  }

}