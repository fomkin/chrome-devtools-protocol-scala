val circeVersion = "0.13.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-generic-extras" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion
)

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8")

addSbtPlugin("io.crashbox" % "sbt-gpg" % "0.1.6")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.1")
