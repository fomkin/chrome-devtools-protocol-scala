package org.fomkin.cdt

trait CommandRunner[F[_], J] extends {
  def runCommand[R](domain: String, name: String, params: J, mapResult: J => R): F[R]
}
