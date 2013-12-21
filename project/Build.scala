import sbt._
import Keys._
import play.Project._
import com.typesafe.sbteclipse.plugin.EclipsePlugin._

object ApplicationBuild extends Build {

  val appName         = "NetworksOfNames"
  val appVersion      = "1.0"

  val appDependencies = Seq(
    // Add your project dependencies here,
    jdbc,
    anorm,
    "postgresql" % "postgresql" % "9.1-901.jdbc4",
    "net.sf.jung" % "jung-api" % "2.0.1",
    "net.sf.jung" % "jung-graph-impl" % "2.0.1",
    "net.sf.jung" % "jung-algorithms" % "2.0.1",
    "com.thoughtworks.xstream" % "xstream" % "1.4.4",
    "org.apache.lucene" % "lucene-core" % "4.3.1",
    "org.apache.lucene" % "lucene-snowball" % "3.0.3",
    "edu.stanford.nlp" % "stanford-corenlp" % "3.2.0",
    "batik" % "batik-rasterizer" % "1.6-1"
  )


  val main = play.Project(appName, appVersion, appDependencies).settings(
    // download sources with dependencies
    EclipseKeys.withSource := true,
    
    // set JVM options
    javaOptions += "-Xmx5G"
  )
}
