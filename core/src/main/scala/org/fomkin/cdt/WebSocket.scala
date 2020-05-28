package org.fomkin.cdt

trait WebSocket[F[_]] {
  def receive[T: Json](f: T => F[Unit]): F[Unit]
  def send[T: Json](json: T): F[Unit]
}

object WebSocket {
  def apply[F[_]: WebSocket]: WebSocket[F] =
    implicitly[WebSocket[F]]
}