
name := "balancer"

version := "0.1"

scalaVersion := "2.12.5"

libraryDependencies ++= Seq(
  // akka
  "com.typesafe.akka" %% "akka-actor" % "2.5.11",
  "com.typesafe.akka" %% "akka-stream" % "2.5.11",
  "com.typesafe.akka" %% "akka-http" % "10.1.1",
  // logging
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.11",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
  // config
  "com.typesafe" % "config" % "1.2.1",
  // akka persistence
  "com.typesafe.akka" %% "akka-persistence" % "2.5.11",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
)