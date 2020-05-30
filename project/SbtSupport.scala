import sbt._
import Keys._
import scala.concurrent.ExecutionContext.Implicits.global

object SbtSupport {
  val sbtLaunchJarUrl = SettingKey[String]("sbt-launch-jar-url")
  val sbtLaunchJarLocation = SettingKey[File]("sbt-launch-jar-location")  
  val sbtLaunchJar = TaskKey[Seq[java.io.File]]("sbt-launch-jar", "Resolves SBT launch jar")

  def downloadFile(log: Logger, dbuildVersion:String, uri: String, file: File): Seq[File] = {
    log.info("Downloading "+uri+" to "+ file.getAbsolutePath() +"...")
    val ht = new com.typesafe.dbuild.http.HttpTransfer(dbuildVersion)
    try {
      ht.download(uri, file)
    } finally {
      ht.close()
    }
    Seq(file)
  }

  val buildSettings: Seq[Setting[_]] = Seq(
    sbtLaunchJarUrl := "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.3.11/sbt-launch.jar",
    sbtLaunchJarLocation := baseDirectory.value / "target" / "sbt" / "sbt-launch.jar",
    sbtLaunchJar := downloadFile(streams.value.log, version.value, sbtLaunchJarUrl.value, sbtLaunchJarLocation.value)
  )
  val settings: Seq[Setting[_]] = buildSettings ++ Seq(
    // The jar is added as a resource, so that the running dbuild can find it and use it to spawn new instances of sbt
    resourceGenerators in Compile += sbtLaunchJar.taskValue
  )
}
