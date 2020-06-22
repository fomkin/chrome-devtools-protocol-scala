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
    val domainsMetaMap = domains
      .map { d =>
        d.domain -> Model.Meta(description = d.description, d.experimental, d.deprecated)
      }
      .toMap
    Model(domainsMetaMap, allTypes, commands, events)
  }

  def renderModel(model: Model, renaming: Renaming): String = {
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

    def renderStructReader(ns: String, props: Seq[Model.Property], json: String) = props
      .map { p =>
        //val n = p.name.escape(renaming.properties)
        val t = renderTypeDecl(ns, p.tpe, p.optional)
        s"""cdt.Codec[J, $t].unsafeRead(cdt.Json[J].unsafeGetNullable($json, "${p.name}"))"""
      }
      .mkString(",\n")

    def renderPropsWriter(ns: String, props: Seq[Model.Property], prefix: String) = {
      val s = props
        .map { p =>
          val n = p.name.escape(renaming.properties)
          val t = renderTypeDecl(ns, p.tpe, optional = false)
          def writer(v: String) = s"""_props.append("$n" -> cdt.Codec[J, $t].write($v))""".stripMargin
          if (p.optional) {
            s"""$prefix$n match {
               |  case Some(_v) => ${writer("_v")}
               |  case None => ()
               |}""".stripMargin
          } else {
            writer(s"$prefix$n")
          }
        }
        .mkString("\n")
      s"""{
         |  val _props = mutable.Buffer.empty[(String, J)]
         |  ${s.ident()}
         |  cdt.Json[J].obj(_props.toVector:_*)
         |}""".stripMargin
    }

    def renderTypeDecl(ns: String, td: Model.TypeDecl, optional: Boolean): String = {
      def renderRef(id: String) = {
        if (id.contains('.')) {
          val Array(d, t) = id.split('.')
          s"${d.escape(renaming.domains)}.${t.escape(renaming.types)}"
        } else {
          id.escape(renaming.types)
        }
      }
      val r = td match {
        case ref @ Model.TypeDecl.Ref(id) if usesJson(ns, ref) => s"${renderRef(id)}[J]"
        case Model.TypeDecl.Ref(id) => renderRef(id)
        case Model.TypeDecl.Json => "J"
        case Model.TypeDecl.Array(t) => s"Seq[${renderTypeDecl(ns, t, optional = false)}]"
        case Model.TypeDecl.Primitive.String => "String"
        case Model.TypeDecl.Primitive.Boolean => "Boolean"
        case Model.TypeDecl.Primitive.Number => "Double"
        case Model.TypeDecl.Primitive.Integer => "Int"
      }
      if (optional) s"Option[$r]"
      else r
    }

    def renderMeta(meta: Model.Meta, props: Seq[Model.Property], rets: Seq[Model.Property] = Nil) = {
      val params = props.map(x => x.name -> x.meta).toMap
      val exp =
        if (meta.experimental) "\n * EXPERIMENTAL"
        else ""
      val dep =
        if (meta.deprecated) "\n@deprecated "
        else "\n"
      val par = params.
        map {
          case (k, v) =>
            val exp = if (v.experimental) "EXPERIMENTAL " else ""
            val dep = if (v.experimental) "DEPRECATED " else ""
            val des = v.description.fold("")(_.ident(r = " *   "))
            s" * @param $k $dep$exp$des"
        }
        .mkString("\n")
      val par2 = if (params.nonEmpty) s"\n$par" else ""
      val ret = rets
        .map { r =>
          val v = r.meta
          val exp = if (v.experimental) "EXPERIMENTAL " else ""
          val dep = if (v.experimental) "DEPRECATED " else ""
          val des = v.description.fold("")(_.ident(r = " *  "))
          s"${r.name} -- $dep$exp$des"
        }
        .mkString("\n")

      val ret2 = rets match {
        case Seq() => ""
        case Seq(i) => s"\n * @return $ret"
        case _ => s"\n * @return (\n *   ${ret.ident(r = " *   ")}\n * )"
      }
      meta.description match {
        case Some(s) =>
          s"""/**
             | * ${s.ident(1, " * ")}$exp$par2$ret2
             | */$dep""".stripMargin
        case None if meta.experimental =>
          s"""/**$exp$par2$ret2
             | */$dep""".stripMargin
        case None =>
          ""
      }
    }

    val domains = model.commands.map(_.domain).distinct
    val typesByDomain = model.types.groupBy(_.domain)
    val eventsByDomain = model.events.groupBy(_.domain)

    val domainsSources = model
      .commands
      .groupBy(_.domain)
      .map {
        case (domain, commands) =>
          val methods = commands
            .map { commandDef =>
              val argsList = commandDef.params
                .map { p =>
                  val rawT = renderTypeDecl(domain, p.tpe, p.optional)
                  val t =
                    if (p.optional) s"$rawT = None"
                    else rawT
                  s"${p.name.escape(renaming.properties)}: $t"
                }
                .mkString(", ")
              val returnsList = commandDef.returns match {
                case Nil => "Unit"
                case Seq(item) => renderTypeDecl(domain, item.tpe, item.optional)
                case xs => s"(${xs.map(p => renderTypeDecl(domain, p.tpe, p.optional)).mkString(", ")})"
              }
              val m = renderMeta(commandDef.meta, commandDef.params, commandDef.returns)
              s"""${m}def ${commandDef.name.escape(renaming.commands)}($argsList): F[$returnsList] =
                 |  cr.runCommand(
                 |    domain = "$domain",
                 |    name = "${commandDef.name}",
                 |    params = ${renderPropsWriter(domain, commandDef.params, "").ident(2)},
                 |    mapResult = _returns => (
                 |      ${renderStructReader(domain, commandDef.returns, "_returns").ident(3)}
                 |    )
                 |  )""".stripMargin
            }
            .mkString("\n \n")
          val types = typesByDomain
            .get(domain)
            .toSeq
            .flatten
            .map {
              case Model.TypeDef.Struct(ns, id, xs, meta) =>
                val argsList = xs
                  .map(p => s"${p.name.escape(renaming.properties)}: ${renderTypeDecl(domain, p.tpe, p.optional)}")
                  .mkString(", ")
                val typeParams =
                  if (usesJson(ns, xs.map(_.tpe):_*)) "[J]"
                  else ""
                val m = renderMeta(meta, xs)
                val eid = id.escape(renaming.types)
                val eidt = s"$eid$typeParams"
                s"""${m}case class $eid$typeParams($argsList)
                   |
                   |object $eid {
                   |  implicit def ${eid}Codec[J: cdt.Json]: cdt.Codec[J, $eidt] = new cdt.Codec[J, $eidt] {
                   |    def unsafeRead(j: J): $eidt =
                   |      $eid(
                   |        ${renderStructReader(ns, xs, "j").ident(4)}
                   |      )
                   |    def write(v: $eidt): J =
                   |      ${renderPropsWriter(ns, xs, "v.").ident(3)}
                   |  }
                   |}""".stripMargin
              case Model.TypeDef.Enum(ns, id, xs, meta) =>
                val caseObjects = xs
                  .map(_.capitalize)
                  .map(x => s"case object ${x.escape(renaming.properties)} extends ${id.escape(renaming.types)}")
                  .mkString("\n")
                val readerCases = xs
                  .map { x =>
                    s"""case "$x" => ${x.capitalize.escape(renaming.properties)}"""
                  }
                  .mkString("\n")
                val writerCases = xs
                  .map { x =>
                    s"""case ${x.capitalize.escape(renaming.properties)} => "$x""""
                  }
                  .mkString("\n")
                val m = renderMeta(meta, Nil)
                val eid = id.escape(renaming.types)
                s"""${m}sealed trait ${id.escape(renaming.types)}
                   |object $eid {
                   |  ${caseObjects.ident(1)}
                   |
                   |  implicit def ${eid}Codec[J: cdt.Json]: cdt.Codec[J, $eid] = new cdt.Codec[J, $eid] {
                   |    def unsafeRead(j: J): $eid =
                   |      cdt.Json[J].unsafeToString(j) match {
                   |        ${readerCases.ident(4)}
                   |      }
                   |    def write(v: $eid): J = cdt.Json[J].string(
                   |      v match {
                   |        ${writerCases.ident(4)}
                   |      }
                   |    )
                   |  }
                   |}""".stripMargin
              case Model.TypeDef.Alias(ns, id, decl, meta) =>
                val m = renderMeta(meta, Nil)
                // TODO find out alias may be optional
                s"${m}type $id = ${renderTypeDecl(ns, decl, optional = false)}"
            }
            .mkString("\n \n")
          val events = eventsByDomain
            .get(domain)
            .toSeq
            .flatten
            .map { eventDef =>
              val argsList = eventDef.params
                .map(p => s"${p.name.escape(renaming.properties)}: ${renderTypeDecl(domain, p.tpe, p.optional)}")
                .mkString(", ")
              val m = renderMeta(eventDef.meta, eventDef.params)
              val nc = eventDef.name.escape(renaming.events).capitalize
              val (nct, ext) =
                if (usesJson(eventDef.domain, eventDef.params.map(_.tpe):_*)) {
                  (s"$nc[J]", "Event[J]")
                } else {
                  (nc, "Event[Nothing]")
                }

              s"""${m}case class $nct($argsList) extends $ext
                 |object $nc {
                 |  implicit def ${nc}Codec[J: cdt.Json]: cdt.Codec[J, $nct] = new cdt.Codec[J, $nct] {
                 |    def unsafeRead(j: J): $nct =
                 |      $nct(
                 |        ${renderStructReader(domain, eventDef.params, "j").ident(4)}
                 |      )
                 |    def write(v: $nct): J =
                 |      ${renderPropsWriter(domain, eventDef.params, "v.").ident(3)}
                 |  }
                 |}""".stripMargin
            }
            .mkString("\n\n")
          val m = renderMeta(model.domains(domain), Nil)
          s"""
             |${m}final class ${domain.escape(renaming.domains)}[F[_], J: cdt.Json](cr: cdt.CommandRunner[F, J]) {
             |
             |  import ${domain.escape(renaming.domains)}._
             |
             |  ${methods.ident()}
             |}
             |
             |object ${domain.escape(renaming.domains)} {
             |  ${types.ident()}
             |
             |  sealed trait Event[+J] extends Protocol.Event[J]
             |  object Event {
             |    ${events.ident(2)}
             |  }
             |}""".stripMargin
      }
      .mkString("\n\n")

    val domainsInit = domains
      .map { domain =>
        val n = domain.escape(renaming.domains)
        val uncapitalized = s"${n(0).toLower}${n.substring(1)}"
        s"final val $uncapitalized: $n[F, J] = new $n(this)"
      }
      .mkString("\n")

    val eventParsers = eventsByDomain
      .flatMap {
        case (domain, eventDefs) =>
          eventDefs.map { eventDef =>
            val d = domain.escape(renaming.domains)
            val n = eventDef.name.escape(renaming.commands)
            val nc = n.capitalize
            val j =
              if (usesJson(domain, eventDef.params.map(_.tpe):_*)) "[J]"
              else ""
            s"""case "$d.$n" => cdt.Codec[J, $d.Event.$nc$j].unsafeRead(params)"""
          }
      }
      .mkString("\n")

    s"""//-------------------------------------------------------
       |// GENERATED FROM devtools-protocol/json/*
       |// DO NOT EDIT!
       |//-------------------------------------------------------
       |
       |package org.fomkin.cdt.protocol
       |
       |import scala.collection.mutable
       |import org.fomkin.cdt
       |
       |$domainsSources
       |
       |abstract class Protocol[F[_], J: cdt. Json] extends cdt.CommandRunner[F, J] {
       |
       |  ${domainsInit.ident()}
       |
       |  def withSessionId(sessionId: Target.SessionID): Protocol[F, J]
       |}
       |
       |object Protocol {
       |
       |  def parseEvent[J: cdt.Json](message: J): Event[J] = {
       |    val params = cdt.Json[J].unsafeGet(message, "params")
       |    val method = cdt.Json[J].unsafeGet(message, "method")
       |    cdt.Json[J].unsafeToString(method) match {
       |      ${eventParsers.ident(3)}
       |    }
       |  }
       |  sealed trait Event[+J]
       |}
       |""".stripMargin
  }

  case class Renaming(domains: Map[String, String] = Map.empty,
                      properties: Map[String, String] = Map.empty,
                      types: Map[String, String] = Map.empty,
                      commands: Map[String, String] = Map.empty,
                      events: Map[String, String] = Map.empty)

  implicit class StringOps(val s: String) extends AnyVal {
    def ident(n: Int = 1, r: String = "  "): String = {
      val nr = r * n
      val nnr = s"\n$nr"
      s.replaceAll("\n", nnr)
    }
    def escape(mapping: Map[String, String] = Map.empty): String = s match {
      case "type" => "`type`"
      case "class" => "`class`"
      case "implicit" => "`implicit`"
      case "this" => "`this`"
      case "object" => "`object`"
      case "override" => "`override`"
      case _ if mapping.contains(s) => mapping(s)
      case _ if s.contains("-") =>
        val x :: xs = s.split("-").toList
        x + xs.map(_.capitalize).mkString
      case _ => s
    }
  }
}

