import Dependencies._
import org.fomkin.cdt.build.ProtocolGenerator

val circeVersion = "0.13.0"
val korolevVersion = "0.16.0-RC1-22-g5ac6981-SNAPSHOT"

Global    / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / scalaVersion         := "2.13.1"
ThisBuild / version              := "0.1.0-SNAPSHOT"
ThisBuild / organization         := "org.fomkin"

val commonSettings = Seq(
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
)

lazy val core = project
  .settings(commonSettings)
  .settings(
    name := "cdt-scala-core",
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
        domains = Map(
          "DOM" -> "Dom",
          "DOMSnapshot" -> "DomSnapshot",
          "DOMDebugger" -> "DomDebugger",
          "DOMStorage" -> "DomStorage",
          "CSS" -> "Css"
        ),
        types = Map("ApplicationCache" -> "ApplicationCacheInfo") // To avoid name conflict with ApplicationCache domain
      )
      val model = errorOrModel match {
        case Left(error) => throw error
        case Right(model) => renderModel(model, renaming)
      }
      outDir.mkdirs()
      val f = outDir / "Protocol.scala"
      IO.write(f, model)
      Seq(f)
    }.taskValue,
    mappings in (Compile,packageSrc) := (managedSources in Compile).value map (s => (s,s.getName)),
  )

lazy val korolev = project
  .in(file("interop/korolev"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.fomkin" %% "korolev-http" % korolevVersion
    ),
    name := "cdt-scala-korolev"
  )
  .dependsOn(core)

lazy val circe = project
  .in(file("interop/circe"))
  .settings(commonSettings)
  .settings(
    name := "cdt-scala-circe",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
  )
  .dependsOn(core)

lazy val chromedevtools = (project in file("."))
  .settings(skip in publish := true)
  .aggregate(core, korolev, circe)

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
