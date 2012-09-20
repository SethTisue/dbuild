package distributed
package support
package scala

import project.BuildSystem
import project.model._
import _root_.java.io.File
import _root_.sbt.Path._
import logging.Logger
import sys.process._


/** Implementation of the Scala  build system. */
object ScalaBuildSystem extends BuildSystem {
  val name: String = "scala"  
  
  def extractDependencies(config: ProjectBuildConfig, dir: File, log: Logger): ExtractedBuildMeta = {
    // TODO - don't HardCode
    ExtractedBuildMeta("", 
        Seq(
          Project("jline", group, Seq(jline), Seq.empty),
          Project("scala-library", group, Seq(lib), Seq.empty),
          Project("scala-reflection", group, Seq(reflect), Seq(lib)),
          Project("scala-actors", group, Seq(actors), Seq(lib)),
          Project("scala-actors-migration", group, Seq(actorsMigration), Seq(lib, actors)),
          Project("scala-swing", group, Seq(swing), Seq(lib)),
          Project("scala-compiler", group, Seq(comp), Seq(reflect, jline)),
          Project("scalap", group, Seq(scalap), Seq(comp)),
          Project("partest", group, Seq(partest), Seq(comp, actors))
        ))
  }

  def runBuild(project: RepeatableProjectBuild, dir: File, dependencies: BuildArtifacts, log: logging.Logger): BuildArtifacts = {
    Process(Seq("ant", "distpack-maven-opt"), Some(dir)) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    // Now reading version number
    val version = readScalaVersion(dir)
    
    // Now deliver scala to the remote repo.
    // TODO - VERSIONING!!!!!!!!!!!!!!!!!!
    val localRepo = dependencies.localRepo.getAbsolutePath
    Process(Seq("ant", "deploy.local",
        "-Dlocal.snapshot.repository="+localRepo,
        "-Dlocal.release.repository="+localRepo,
        "-Dmaven.version.number="+version
    ), Some(dir / "dists" / "maven" / "latest")) ! log match {
      case 0 => ()
      case n => sys.error("Could not run scala ant build, error code: " + n)
    }
    
    // Now, return hardcoded results.
    val libDir = dir / "build" / "pack" / "lib"

    BuildArtifacts(Seq(
      ArtifactLocation(lib, libDir / "scala-library.jar", version),
      ArtifactLocation(reflect, libDir / "scala-reflect.jar", version),
      ArtifactLocation(comp, libDir / "scala-compiler.jar", version),
      ArtifactLocation(actors, libDir / "scala-actors.jar", version),
      ArtifactLocation(swing, libDir / "scala-swing.jar", version),
      ArtifactLocation(actorsMigration, libDir / "scala-actors-migration.jar", version),
      ArtifactLocation(scalap, libDir / "scalap.jar", version),
      ArtifactLocation(jline, libDir / "jline.jar", version),
      ArtifactLocation(partest, libDir / "paretst.jar", version),
      ArtifactLocation(continuations, dir / "build/pack/misc/scala-devel/plugins/continuations.jar", version)
    ) ++ dependencies.artifacts, 
    dependencies.localRepo)
  }

    
    
    
  private def readScalaVersion(baseDir: File): String = {
    val propsFile = new File(baseDir, "build/quick/classes/library/library.properties")
    import util.control.Exception.catching
    def loadProps(file: File): Option[_root_.java.util.Properties] = 
     catching(classOf[_root_.java.io.IOException]) opt {
      val props = new _root_.java.util.Properties()
      props.load(new _root_.java.io.FileReader(propsFile))
      props
    }
    val version: Option[String] = for {
      f <- if (propsFile.exists) Some(propsFile) else None
      props <- loadProps(f)
      version <- Option(props get "version.number")
    } yield version.toString
    version getOrElse sys.error("unable to load scala version number!")
  } 
    
  private[this] def group = "org.scala-lang"
  private[this] def lib = ProjectRef("scala-library", group)
  private[this] def reflect = ProjectRef("scala-reflect", group)
  private[this] def actorsMigration = ProjectRef("scala-actors-migration", group)
  private[this] def actors = ProjectRef("scala-actors", group)
  private[this] def swing= ProjectRef("scala-swing", group)
  private[this] def jline = ProjectRef("jline", group)
  private[this] def comp = ProjectRef("scala-compiler", group)
  private[this] def scalap = ProjectRef("scalap", group)
  private[this] def partest = ProjectRef("partest", group)
  private[this] def continuations = ProjectRef("continuations", group+".plugins")
}