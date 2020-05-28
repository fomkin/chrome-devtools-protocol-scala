package org.fomkin.cdt.build

object ProtocolGeneratorData {
  case class Version(major: String, minor: String)

  case class Protocol(version: Version, domains: Seq[Domain])

  case class Domain(domain: String,
                    description: Option[String] = None,
                    types: Seq[Type] = Nil,
                    commands: Seq[Command] = Nil,
                    events: Seq[Command] = Nil,
                    deprecated: Boolean = false,
                    dependencies: Seq[String] = Nil)

  case class Type(id: String,
                  description: Option[String],
                  `type`: String,
                  enum: Option[Seq[String]],
                  items: Option[Items],
                  properties: Seq[Property] = Nil,
                  deprecated: Boolean = false,
                  experimental: Boolean = false)

  case class Property(name: String,
                      description: Option[String],
                      `type`: Option[String],
                      enum: Option[Seq[String]],
                      items: Option[Items],
                      $ref: Option[String],
                      experimental: Boolean = false,
                      deprecated: Boolean = false,
                      optional: Boolean = false)

  case class Items(`type`: Option[String],
                   $ref: Option[String])

  case class Command(name: String,
                     description: Option[String],
                     deprecated: Boolean = false,
                     experimental: Boolean = false,
                     parameters: Seq[Property] = Nil,
                     returns: Seq[Property] = Nil)
}
