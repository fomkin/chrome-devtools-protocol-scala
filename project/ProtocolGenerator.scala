package org.fomkin.cdt.build

import java.io.File

import io.circe._
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._

import scala.annotation.tailrec

object ProtocolGenerator {

  import org.fomkin.cdt.build.{ProtocolGeneratorData => Data}
  import org.fomkin.cdt.build.{ProtocolGeneratorModel => Model}

  def readData(jsonFile: File): Either[Error, Data.Protocol] = {
    implicit val customConfig: Configuration = Configuration.default.withDefaults
    val source = scala.io.Source.fromFile(jsonFile)
    val json = source.mkString
    source.close()
    decode[Data.Protocol](json)
  }

  def processData(domains: Seq[Data.Domain]): Model = {

    def processPrimitiveType(s: String): Model.TypeDecl = s match {
      case "string" => Model.TypeDecl.Primitive.String
      case "integer" => Model.TypeDecl.Primitive.Integer
      case "number" => Model.TypeDecl.Primitive.Number
      case "boolean" => Model.TypeDecl.Primitive.Boolean
      case "object" => Model.TypeDecl.Json
      case "any" => Model.TypeDecl.Json
    }

    val processTypeDelc: (Option[String], Option[String], Option[Seq[String]], Option[Data.Items]) => Either[Seq[String], Model.TypeDecl] = {
      case (Some("string"), None, Some(enum), None) => Left(enum)
      case (Some("array"), None, None, Some(Data.Items(Some(seqType), None))) =>
        Right(Model.TypeDecl.Array(processPrimitiveType(seqType)))
      case (Some("array"), None, None, Some(Data.Items(None, Some(ref)))) =>
        val refType = Model.TypeDecl.Ref(ref)
        Right(Model.TypeDecl.Array(refType))
      case (Some("object"), None, None, None) => Right(Model.TypeDecl.Json)
      case (Some(primitive), None, None, None) => Right(processPrimitiveType(primitive))
      case (None, Some(ref), None, None) => Right(Model.TypeDecl.Ref(ref))
      case tpl => throw new Exception(s"Unhandled property declaration: $tpl")
    }

    def processProperty(p: Data.Property, ns: String, prefix: String): (Seq[Model.TypeDef], Model.Property) = {
      val meta = Model.Meta(p.description, p.experimental, p.deprecated)
      processTypeDelc(p.`type`, p.$ref, p.`enum`, p.items) match {
        case Left(xs) =>
          val typeId = prefix.capitalize + p.name.capitalize
          val `def` = Model.TypeDef.Enum(ns, typeId, xs, meta)
          val decl = Model.TypeDecl.Ref(typeId)
          (Seq(`def`), Model.Property(p.name, decl, p.optional, meta))
        case Right(decl) =>
          (Nil, Model.Property(p.name, decl, p.optional, meta))
      }
    }

    def processCommand(ns: String, command: Data.Command) = {
      val (argsEnums, args) = command.parameters.map(processProperty(_, ns, command.name)).unzip
      val (retEnums, rets) = command.returns.map(processProperty(_, ns, command.name)).unzip
      val meta = Model.Meta(command.description, command.experimental, command.deprecated)
      val enums = argsEnums.flatten ++ retEnums.flatten
      val `def` = Model.CommandDef(ns, command.name, args, rets, meta)
      (enums, `def`)
    }

    val types = domains.flatMap { domain =>
      val ns = domain.domain
      domain.types.flatMap {
        case Data.Type(id, desc, "string", Some(enum), None, _, depr, exp) =>
          val meta = Model.Meta(desc, depr, exp)
          Seq(Model.TypeDef.Enum(ns, id, enum, meta))
        case Data.Type(id, desc, "array", None, Some(Data.Items(Some(tpe), None)), _, depr, exp) =>
          val meta = Model.Meta(desc, depr, exp)
          val decl = Model.TypeDecl.Array(processPrimitiveType(tpe))
          Seq(Model.TypeDef.Alias(ns, id, decl, meta))
        case Data.Type(id, desc, "array", None, Some(Data.Items(None, Some(ref))), _, depr, exp) =>
          val meta = Model.Meta(desc, depr, exp)
          val decl = Model.TypeDecl.Array(Model.TypeDecl.Ref(ref))
          Seq(Model.TypeDef.Alias(ns, id, decl, meta))
        case Data.Type(id, desc, "object", None, None, properties, depr, exp) =>
          val meta = Model.Meta(desc, depr, exp)
          val (enums, props) = properties.map(processProperty(_, ns, id)).unzip
          val `def` = Model.TypeDef.Struct(ns, id, props, meta)
          `def` +: enums.flatten
        case Data.Type(id, desc, tpe, None, None, Nil, depr, exp) =>
          val meta = Model.Meta(desc, depr, exp)
          Seq(Model.TypeDef.Alias(ns, id, processPrimitiveType(tpe), meta))
      }
    }

    val (enumsInCommands, commands) = domains
      .flatMap(domain => domain.commands.map(processCommand(domain.domain, _)))
      .unzip

    val (enumsInEvents, events) = domains
      .flatMap(domain => domain.events.map(processCommand(domain.domain, _)))
      .unzip

    val allTypes = types ++ enumsInCommands.flatten ++ enumsInEvents.flatten
    Model(domains.map(_.domain), allTypes, commands, events)
  }

  def renderModel(model: Model): Map[String, String] = {

    val typeIndex = model
      .types
      .map(t => (t.qname, t))
      .toMap

    def usesJson(ns: String, decls: Model.TypeDecl*): Boolean = {
      @tailrec def aux(stack: List[String], ns: String, decls: Vector[Model.TypeDecl]): Boolean = {
        def checkStruct(id: String): Option[Model.TypeDef.Struct] =
          typeIndex.get(s"$ns.$id").orElse(typeIndex.get(id)) match {
            case Some(struct: Model.TypeDef.Struct) => Some(struct)
            case _ => None
          }
        decls match {
          case x +: xs => x match {
            case Model.TypeDecl.Json => true
            case Model.TypeDecl.Array(Model.TypeDecl.Json) => true
            case Model.TypeDecl.Array(Model.TypeDecl.Ref(id)) if stack.contains(id) => aux(stack, ns, xs)
            case Model.TypeDecl.Array(Model.TypeDecl.Ref(id)) =>
              checkStruct(id) match {
                case None => aux(stack, ns, xs)
                case Some(struct) =>
                  aux(id :: stack, struct.domain, struct.properties.map(_.tpe).toVector ++ xs)
              }
            case Model.TypeDecl.Ref(id) if stack.contains(id) => aux(stack, ns, xs)
            case Model.TypeDecl.Ref(id) =>
              checkStruct(id) match {
                case None => aux(stack, ns, xs)
                case Some(struct) =>
                  aux(id :: stack, struct.domain, struct.properties.map(_.tpe).toVector ++ xs)
              }
            case _ => aux(stack, ns, xs)
          }
          case _ => false
        }
      }
      aux(Nil, ns, decls.toVector)
    }

    def renderTypeDecl(ns: String, td: Model.TypeDecl): String = td match {
      case ref @ Model.TypeDecl.Ref(id) if usesJson(ns, ref) => s"${id.escape}[Json]"
      case Model.TypeDecl.Ref(id) => id.escape
      case Model.TypeDecl.Json => "Json"
      case Model.TypeDecl.Array(t) => s"Seq[${renderTypeDecl(ns, t)}]"
      case Model.TypeDecl.Primitive.String => "String"
      case Model.TypeDecl.Primitive.Boolean => "Boolean"
      case Model.TypeDecl.Primitive.Number => "Double"
      case Model.TypeDecl.Primitive.Integer => "Int"
    }
    def renderMeta(meta: Model.Meta) = {
      val exp =
        if (meta.experimental) "\n * EXPERIMENTAL"
        else ""
      val dep =
        if (meta.deprecated) "\n@deprecated "
        else "\n"
      meta.description match {
        case Some(s) =>
          s"""/**
             | * ${s.ident(1, " * ")}$exp
             | */$dep""".stripMargin
        case None if meta.experimental =>
          s"""/**$exp
             | */$dep""".stripMargin
        case None =>
          ""
      }
    }

    val typesByDomain = model.types.groupBy(_.domain)
    val eventsByDomain = model.events.groupBy(_.domain)

    model
      .commands
      .groupBy(_.domain)
      .map {
        case (domain, commands) =>
          val methods = commands
            .map { commandDef =>
              val argsList = commandDef.params
                .map(p => s"${p.name.escape}: ${renderTypeDecl(domain, p.tpe)}")
                .mkString(", ")
              val returnsList = commandDef.returns match {
                case Nil => "Unit"
                case Seq(item) => renderTypeDecl(domain, item.tpe)
                case xs => s"(${xs.map(_.tpe).map(renderTypeDecl(domain, _)).mkString(", ")})"
              }
              val m = renderMeta(commandDef.meta)
              s"${m}def ${commandDef.name.escape}($argsList): F[$returnsList]"
            }
            .mkString("\n")
          val types = typesByDomain
            .get(domain)
            .toSeq
            .flatten
            .map {
              case Model.TypeDef.Struct(ns, id, xs, meta) =>
                val argsList = xs
                  .map(p => s"${p.name.escape}: ${renderTypeDecl(domain, p.tpe)}")
                  .mkString(", ")
                val typeParams =
                  if (usesJson(ns, xs.map(_.tpe):_*)) "[Json]"
                  else ""
                s"case class $id$typeParams($argsList)"
              case Model.TypeDef.Enum(ns, id, xs, meta) =>
                val caseObjects = xs
                  .map(_.capitalize)
                  .map(x => s"case object ${x.escape} extends ${id.escape}")
                  .mkString("\n")
                s"""sealed trait ${id.escape}
                   |object ${id.escape} {
                   |  ${caseObjects.ident(1, "  ")}
                   |}
                   |""".stripMargin
              case Model.TypeDef.Alias(ns, id, decl, meta) =>
                s"type $id = ${renderTypeDecl(ns, decl)}"
            }
            .mkString("\n")
          val events = eventsByDomain
            .get(domain)
            .toSeq
            .flatten
            .map { eventDef =>
              val argsList = eventDef.params
                .map(p => s"${p.name.escape}: ${renderTypeDecl(domain, p.tpe)}")
                .mkString(", ")
              val typeParams =
                if (usesJson(eventDef.domain, eventDef.params.map(_.tpe):_*)) "[Json]"
                else ""
              s"case class ${eventDef.name.escape.capitalize}$typeParams($argsList) extends Event"
            }
            .mkString("\n")
          domain -> s"""trait ${domain.escape}[F[_], Json] {
                       |  import ${domain.escape}._
                       |
                       |  ${methods.ident()}
                       |}
                       |
                       |object ${domain.escape} {
                       |  ${types.ident()}
                       |
                       |  sealed trait Event
                       |  object Event {
                       |    ${events.ident(2)}
                       |  }
                       |}""".stripMargin
      }
  }

  implicit class StringOps(val s: String) extends AnyVal {
    def ident(n: Int = 1, r: String = "  "): String = {
      val nr = r * n
      val nnr = s"\n$nr"
      s.replaceAll("\n", nnr)
    }
    def escape: String = s match {
      case "type" => "`type`"
      case "class" => "`class`"
      case "implicit" => "`implicit`"
      case "this" => "`this`"
      case "object" => "`object`"
      case "override" => "`override`"
      case _ if s.contains("-") =>
        val x :: xs = s.split("-").toList
        x + xs.map(_.capitalize).mkString
      case _ => s
    }
  }
}

