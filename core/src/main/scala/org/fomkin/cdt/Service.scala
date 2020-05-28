package org.fomkin.cdt

trait Service[F[_], J, E] {
  type Unsubscribe = () => F[Unit]
  def runCommand(id: Long, domain: String, name: String, params: J): F[J]
  def subscribeEvents(f: Unsubscribe => E => F[Unit]): F[Unsubscribe]
}
