import Dependencies._
import org.fomkin.cdt.build.ProtocolGenerator

val Http4sVersion = "0.21.4"

ThisBuild / scalaVersion     := "2.13.2"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "org.fomkin"

lazy val core = project
  .settings(
    name := "chromedevtools-scala",
    Compile / sourceGenerators += Def.task {
      import org.fomkin.cdt.build.ProtocolGenerator._
      val outDir = (Compile / sourceManaged).value / "org" / "fomkin" / "cdt" / "protocol"
      val protocolDir = file("devtools-protocol") / "json"
      val jsProtocolFile = protocolDir / "js_protocol.json"
      val browserProtocolFile = protocolDir / "browser_protocol.json"
      val jsProtocol = readData(jsProtocolFile)
      val browserProtocol = readData(browserProtocolFile)
      val errorOrModel = for (jsp <- jsProtocol; bp <- browserProtocol) yield
        processData(jsp.domains ++ bp.domains)
      val renaming = ProtocolGenerator.Renaming(
        types = Map("ApplicationCache" -> "ApplicationCacheInfo") // To avoid name conflict with ApplicationCache domain
      )
      val model = errorOrModel match {
        case Left(error) => throw error
        case Right(model) => renderModel(model, renaming)
      }
      outDir.mkdirs()
      model.toSeq.map {
        case (k, v) =>
          val out = outDir / s"$k.scala"
          IO.write(out, v)
          out
      }
    }.taskValue
  )

lazy val http4s = project
  .in(file("interop/http4s"))
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s" %% "http4s-dsl"          % Http4sVersion,
    ),
    name := "chromedevtools-scala-http4s"
  )

lazy val circe = project
  .in(file("interop/circe"))
  .settings(name := "chromedevtools-scala-circe")

lazy val chromedevtools = (project in file("."))
  .aggregate(core, http4s, circe)

// Uncomment the following for publishing to Sonatype.
// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for more detail.

// ThisBuild / description := "Some descripiton about your project."
// ThisBuild / licenses    := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
// ThisBuild / homepage    := Some(url("https://github.com/example/project"))
// ThisBuild / scmInfo := Some(
//   ScmInfo(
//     url("https://github.com/your-account/your-project"),
//     "scm:git@github.com:your-account/your-project.git"
//   )
// )
// ThisBuild / developers := List(
//   Developer(
//     id    = "Your identifier",
//     name  = "Your Name",
//     email = "your@email",
//     url   = url("http://your.url")
//   )
// )
// ThisBuild / pomIncludeRepository := { _ => false }
// ThisBuild / publishTo := {
//   val nexus = "https://oss.sonatype.org/"
//   if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
//   else Some("releases" at nexus + "service/local/staging/deploy/maven2")
// }
// ThisBuild / publishMavenStyle := true
