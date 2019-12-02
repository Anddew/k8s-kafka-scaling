name := "kafka-scaling"

lazy val commonSettings = Seq(
  organization := "com.anddew",
  version := "0.1",
  scalaVersion := "2.12.8"
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*)
  .enablePlugins(AssemblyPlugin)

libraryDependencies ++= Seq(
  "org.apache.kafka" %% "kafka-streams-scala" % "2.3.0",
  "ch.hsr" % "geohash" % "1.3.0",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.1.3"
)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
//  case PathList("org", "slf4j", xs@_*) => MergeStrategy.first
  case x => MergeStrategy.first
}

assemblyJarName in assembly := "kafka-streams-app.jar"
mainClass in assembly := Some("com.anddew.kafkastreamscaling.Runner")