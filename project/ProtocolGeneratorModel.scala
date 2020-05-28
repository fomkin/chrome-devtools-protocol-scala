package org.fomkin.cdt.build

case class ProtocolGeneratorModel(domains: Seq[String],
                                  types: Seq[ProtocolGeneratorModel.TypeDef],
                                  commands: Seq[ProtocolGeneratorModel.CommandDef],
                                  events: Seq[ProtocolGeneratorModel.CommandDef])

object ProtocolGeneratorModel {

  case class Meta(description: Option[String],
                  experimental: Boolean,
                  deprecated: Boolean)

  sealed trait TypeDecl

  object TypeDecl {
    case object Json extends TypeDecl
    case class Array(t: TypeDecl) extends TypeDecl
    case class Ref(id: String) extends TypeDecl
    sealed trait Primitive extends TypeDecl
    object Primitive {
      case object Integer extends Primitive
      case object Number extends Primitive
      case object String extends Primitive
      case object Boolean extends Primitive
    }
  }

  sealed trait TypeDef {
    def domain: String
    def id: String
    lazy val qname: String = s"$domain.$id"
  }

  object TypeDef {

    case class Struct(domain: String,
                      id: String,
                      properties: Seq[Property],
                      meta: Meta) extends TypeDef

    case class Enum(domain: String,
                    id: String,
                    xs: Seq[String], meta: Meta) extends TypeDef

    case class Alias(domain: String,
                     id: String,
                     decl: TypeDecl,
                     meta: Meta) extends TypeDef
  }

  case class CommandDef(domain: String,
                        name: String,
                        params: Seq[Property],
                        returns: Seq[Property],
                        meta: Meta)

  case class Property(name: String,
                      tpe: TypeDecl,
                      optional: Boolean,
                      meta: Meta)
}
