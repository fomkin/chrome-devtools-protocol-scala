import Dependencies._
import org.fomkin.cdt.build.ProtocolGenerator
import xerial.sbt.Sonatype._

Global / onChangedBuildSource := ReloadOnSourceChanges

val circeVersion = "0.13.0"
val korolevVersion = "1.0.0"

val publishSettings = Seq(
  publishTo := sonatypePublishTo.value,
  Test / publishArtifact := false,
  publishMavenStyle := true,
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  sonatypeProjectHosting := Some(GitHubHosting("fomkin", "chrome-devtools-protocol-scala", "Aleksey Fomkin", "aleksey.fomkin@gmail.com"))
)

val dontPublishSettings = Seq(
  skip in publish := true,
  publish := {},
  publishArtifact := false,
)

val commonSettings = Seq(
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  scalaVersion  := "2.13.6",
  organization  := "org.fomkin",
  git.useGitDescribe := true,
)

lazy val core = project
  .enablePlugins(GitVersioning)
  .settings(commonSettings)
  .settings(publishSettings: _*)
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
  .enablePlugins(GitVersioning)
  .settings(commonSettings)
  .settings(publishSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "org.fomkin" %% "korolev-http" % korolevVersion
    ),
    name := "cdt-scala-korolev"
  )
  .dependsOn(core)

lazy val circe = project
  .in(file("interop/circe"))
  .enablePlugins(GitVersioning)
  .settings(commonSettings)
  .settings(publishSettings: _*)
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
  .settings(dontPublishSettings)
  .aggregate(core, korolev, circe)
