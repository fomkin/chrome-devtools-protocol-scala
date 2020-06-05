package org.fomkin.cdt

final case class CdtProtocolError(code: Int, message: String)
  extends Exception(message)
