scalaVersion := "3.10.0"

scalacOptions += "-deprecation"

lazy val root = rootProject
  .settings(
    name := "graphdb-embedded-mode",
    libraryDependencies ++= Seq(

      "com.ontotext.graphdb" % "graphdb-runtime" % "10.8.13",
      "dev.zio" %% "zio" % "2.1.26",
      "dev.zio" %% "zio-logging-slf4j" % "2.5.3",
      //You can add library dependencies here, for example,
      //"org.scalatest" %% "scalatest" % "3.2.19" % Test,
      //"org.scalameta" %% "munit" % "1.2.3" % Test
    )
  )
