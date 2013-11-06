package distributed
package support
package assemble

import project.model._
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.FileUtils
import _root_.java.io.File
import _root_.sbt.Path._
import _root_.sbt.IO
import _root_.sbt.IO.relativize
import logging.Logger
import sys.process._
import distributed.repo.core.LocalRepoHelper
import distributed.project.model.Utils.{ writeValue, readValue }
import distributed.project.dependencies.Extractor
import distributed.project.build.LocalBuildRunner
import collection.JavaConverters._
import org.apache.maven.model.{ Model, Dependency }
import org.apache.maven.model.io.xpp3.{ MavenXpp3Reader, MavenXpp3Writer }
import org.apache.maven.model.Dependency
import org.apache.ivy.util.ChecksumHelper
import distributed.support.NameFixer.fixName
import _root_.sbt.NameFilter
import org.apache.ivy

/**
 * The "assemble" build system accepts a list of nested projects, with the same format
 * as the "build" section of a normal dbuild configuration file.
 * All of those nested projects will be built *independently*, meaning that they
 * will not use one the artifacts of the others. At the end, when all of the
 * projects are built, the "group" build system will collect all of the artifacts
 * generated by the nested projects, and patch their pom/ivy files by modifying their
 * dependencies, so that they refer to one another. The net result is that they
 * will all appear to have been originating from a single project.
 */
object AssembleBuildSystem extends BuildSystemCore {
  val name: String = "assemble"

  private def assembleExpandConfig(config: ProjectBuildConfig) = config.extra match {
    case None => AssembleExtraConfig(None) // pick default values
    case Some(ec: AssembleExtraConfig) => ec
    case _ => throw new Exception("Internal error: Assemble build config options are the wrong type in project \"" + config.name + "\". Please report")
  }

  private def projectsDir(base: File, config: ProjectBuildConfig) = {
    // don't use the entire nested project config, as it changes after resolution (for the components)
    // also, avoid using the name as-is as the last path component (it might confuse the dbuild's heuristic
    // used to determine sbt's default project names, see dbuild's issue #66)
    val uuid = hashing sha1 config.name
    base / "projects" / uuid
  }

  // overriding resolve, as we need to resolve its nested projects as well
  override def resolve(config: ProjectBuildConfig, dir: File, extractor: Extractor, log: Logger): ProjectBuildConfig = {
    if (config.uri != "nil" && !config.uri.startsWith("nil:"))
      sys.error("Fatal: the uri in Assemble " + config.name + " must start with the string \"nil:\"")
    // resolve the main URI (which will do nothing since it is "nil", but we may have
    // some debugging diagnostic, so let's call it anyway)
    val rootResolved = super.resolve(config, dir, extractor, log)
    // and then the nested projects (if any)
    val newExtra = rootResolved.extra match {
      case None => None
      case Some(extra: AssembleExtraConfig) =>
        val newParts = extra.parts map { buildConfig =>
          val nestedResolvedProjects =
            buildConfig.projects.foldLeft(Seq[ProjectBuildConfig]()) { (s, p) =>
              log.info("----------")
              log.info("Resolving module: " + p.name)
              val nestedExtractionConfig = ExtractionConfig(p, buildConfig.options getOrElse BuildOptions())
              val moduleConfig = extractor.dependencyExtractor.resolve(nestedExtractionConfig.buildConfig, projectsDir(dir, p), extractor, log)
              s :+ moduleConfig
            }
          DistributedBuildConfig(nestedResolvedProjects, buildConfig.options)
        }
        Some(extra.copy(parts = newParts))
      case _ => throw new Exception("Internal error: Assemble build config options are the wrong type in project \"" + config.name + "\". Please report")
    }
    rootResolved.copy(extra = newExtra)
  }

  def extractDependencies(config: ExtractionConfig, dir: File, extractor: Extractor, log: Logger): ExtractedBuildMeta = {
    val ec = assembleExpandConfig(config.buildConfig)

    // we consider the names of parts in the same way as subprojects, allowing for a
    // partial deploy, etc.
    val subProjects = ec.parts.toSeq.flatMap(_.projects).map(_.name)
    if (subProjects.size != subProjects.distinct.size) {
      sys.error(subProjects.diff(subProjects.distinct).distinct.mkString("These subproject names appear twice: ", ", ", ""))
    }
    val partOutcomes = ec.parts.toSeq flatMap { buildConfig =>
      buildConfig.projects map { p =>
        log.info("----------")
        val nestedExtractionConfig = ExtractionConfig(p, buildConfig.options getOrElse BuildOptions())
        extractor.extractedResolvedWithCache(nestedExtractionConfig, projectsDir(dir, p), log)
      }
    }
    if (partOutcomes.exists(_.isInstanceOf[ExtractionFailed])) {
      sys.error(partOutcomes.filter { _.isInstanceOf[ExtractionFailed] }.map { _.project }.mkString("failed: ", ", ", ""))
    }
    val partsOK = partOutcomes.collect({ case e: ExtractionOK => e })
    val allConfigAndExtracted = (partsOK flatMap { _.pces })

    // time to do some more checking:
    // - do we have a duplication in provided artifacts?
    // let's start building a sequence of all modules, with the name of the subproject they come from
    val artiSeq = allConfigAndExtracted.flatMap { pce => pce.extracted.projects.map(art => ((art.organization + "#" + art.name), pce.config.name)) }
    log.info(artiSeq.toString)
    // group by module ID, and check for duplications
    val artiMap = artiSeq.groupBy(_._1)
    log.info(artiMap.toString)
    val duplicates = artiMap.filter(_._2.size > 1)
    if (duplicates.nonEmpty) {
      duplicates.foreach { z =>
        log.error(z._2.map(_._2).mkString(z._1 + " is provided by: ", ", ", ""))
      }
      sys.error("Duplicate artifacts found in project")
    }

    // ok, now we just have to merge everything together. There is no version number in the assemble
    // per se, since the versions are decided by the components.
    val newMeta = ExtractedBuildMeta("0.0.0", allConfigAndExtracted.flatMap(_.extracted.projects),
      partOutcomes.map { _.project })
    log.info(newMeta.subproj.mkString("These subprojects will be built: ", ", ", ""))
    newMeta
  }

  // runBuild() is called with the (empty) root source resolved, but the parts have not been checked out yet.
  // Therefore, we will call localBuildRunner.checkCacheThenBuild() on each part,
  // which will in turn resolve it and then build it (if not already in cache).
  def runBuild(project: RepeatableProjectBuild, dir: File, input: BuildInput, localBuildRunner: LocalBuildRunner, log: logging.Logger): BuildArtifactsOut = {
    val ec = assembleExpandConfig(project.config)
    val version = input.version

    log.info(ec.parts.toSeq.flatMap(_.projects).map(_.name).mkString("These subprojects will be built: ", ", ", ""))

    val localRepo = input.outRepo
    // We do a bunch of in-place file operations in the localRepo, before returning.
    // To avoid problems due to stale files, delete all contents before proceeding.
    IO.delete(localRepo.*("*").get)

    def mavenArtifactDir(repoDir: File, ref: ProjectRef, crossSuffix: String) =
      ref.organization.split('.').foldLeft(repoDir)(_ / _) / (ref.name + crossSuffix)

    def ivyArtifactDir(repoDir: File, ref: ProjectRef, crossSuffix: String) =
      repoDir / ref.organization / (ref.name + crossSuffix)

    // In order to detect the artifacts that belong to the scala core (non cross-versioned)
    // we cannot rely on the cross suffix, as the non-scala nested projects might also be published
    // with cross versioning disabled (it's the default in dbuild). Our only option is going after
    // the organization id "org.scala-lang".
    def isScalaCore(name: String, org: String) = {
      val fixedName = fixName(name)
      (org == "org.scala-lang" && fixedName.startsWith("scala")) ||
        (org == "org.scala-lang.plugins" && fixedName == "continuations")
    }

    def isScalaCoreRef(p: ProjectRef) =
      isScalaCore(p.name, p.organization)

    def isScalaCoreArt(l: ArtifactLocation) =
      isScalaCoreRef(l.info)

    // Since we know the repository format, and the list of "subprojects", we grab
    // the files corresponding to each one of them right from the relevant subdirectory.
    // We then calculate the sha, and package each subproj's results as a BuildSubArtifactsOut.
    def scanFiles[Out](artifacts: Seq[ProjectRef], crossSuffix: String)(f: File => Out) = {
      // use the list of artifacts as a hint as to which directories should be looked up,
      // but actually scan the dirs rather than using the list of artifacts (there may be
      // additional files like checksums, for instance).
      artifacts.flatMap { art =>
        val artCross = if (isScalaCoreRef(art)) "" else crossSuffix
        Seq(mavenArtifactDir(localRepo, art, artCross),
          ivyArtifactDir(localRepo, art, artCross))
      }.distinct.flatMap { _.***.get }.
        // Since this may be a real local maven repo, it also contains
        // the "maven-metadata-local.xml" files, which should /not/ end up in the repository.
        filterNot(file => file.isDirectory || file.getName == "maven-metadata-local.xml").map(f)
    }

    def projSHAs(artifacts: Seq[ProjectRef], crossSuffix: String): Seq[ArtifactSha] = scanFiles(artifacts, crossSuffix) {
      LocalRepoHelper.makeArtifactSha(_, localRepo)
    }

    // OK, now build the parts
    val (preCrossPreDupsArtifactsMap, repeatableProjectBuilds) = (ec.parts.toSeq flatMap { build =>
      build.projects map { p =>
        // the parts are build built independently from one another. Their list
        // of dependencies is cleared before building, so that they do not rely on one another
        log.info("----------")
        log.info("Building part: " + p.name)
        val nestedExtractionConfig = ExtractionConfig(p, build.options getOrElse BuildOptions())
        val partConfigAndExtracted = localBuildRunner.extractor.cachedExtractOr(nestedExtractionConfig, log) {
          // if it's not cached, something wrong happened.
          sys.error("Internal error: extraction metadata not found for part " + p.name)
        } match {
          case outcome: ExtractionOK => outcome.pces.headOption getOrElse
            sys.error("Internal error: PCES empty after cachedExtractOr(); please report")
          case _ => sys.error("Internal error: cachedExtractOr() returned incorrect outcome; please report.")
        }
        val repeatableProjectBuild = RepeatableProjectBuild(partConfigAndExtracted.config, partConfigAndExtracted.extracted.version,
          Seq.empty, // remove all dependencies, and pretend that this project stands alone
          partConfigAndExtracted.extracted.subproj, build.options getOrElse BuildOptions())
        val outcome = localBuildRunner.checkCacheThenBuild(projectsDir(dir, p), repeatableProjectBuild, Seq.empty, Seq.empty, log)
        val artifactsOut = outcome match {
          case o: BuildGood => o.artsOut
          case o: BuildBad => sys.error("Part " + p.name + ": " + o.status)
        }
        val artifactsSafe = BuildArtifactsOut(artifactsOut.results.map { sub =>
          if (sub.subName == "default-sbt-project")
            sub.copy(subName = p.name + "-default-sbt-project") else sub
        })
        val q = (p.name, artifactsSafe)
        log.debug("---> " + q)
        (q, repeatableProjectBuild)
      }
    }).unzip

    // We might have duplicated subprojects. Make them unique if that is the case
    val subProjects = preCrossPreDupsArtifactsMap.map { _._2 }.flatMap { _.results }.map { _.subName }
    val nonUniqueSubProjs = subProjects.diff(subProjects.distinct).distinct
    val preCrossArtifactsMap = preCrossPreDupsArtifactsMap.map {
      case (proj, arts) =>
        (proj, BuildArtifactsOut(arts.results.map { sub =>
          if (nonUniqueSubProjs.contains(sub)) sub.copy(subName = proj + sub.subName) else sub
        }))
    }

    // Excellent, we now have in preCrossArtifactsMap a sequence of BuildArtifactsOut from the parts

    // we also need the new scala version, which we take from the scala-library artifact, among
    // our subprojects. If we cannot find it, then we have none.
    val scalaVersion = {
      val allArts = preCrossArtifactsMap.map(_._2).flatMap(_.results).flatMap(_.artifacts)
      allArts.find(l => l.info.organization == "org.scala-lang" && l.info.name == "scala-library").map(_.version)
    }
    def getScalaVersion(crossLevel: String) = scalaVersion getOrElse
      sys.error("In Assemble, the requested cross-version level is " + crossLevel + ", but no scala-library was found among the artifacts.")

    // ------
    //
    // now, let's retrieve the parts' artifacts again (they have already been published)
    val uuids = repeatableProjectBuilds map { _.uuid }
    log.info("Retrieving module artifacts")
    log.debug("into " + localRepo)
    val artifactLocations = LocalRepoHelper.getArtifactsFromUUIDs(log.info, localBuildRunner.repository, localRepo, uuids)

    // ------
    // ok. At this point, we have:
    // preCrossArtifactsMap: map name -> artifacts, from the nested parts. Each mapping corresponds to one nested project,
    //   and the list of artifacts may contain multiple subprojects, each with their own BuildSubArtifactsOut
    //
    // ------
    //
    // Before rearranging the poms, we may need to adapt the cross-version strings in the part
    // names. That depends on the value of cross-version in our main build.options.cross-version.
    // If it is "disabled" (default), the parts should already have a version without a cross-version
    // string; we might have to remove the cross suffix, if present, from the modules compiled by the
    // scala ant task, as that is not affected by the cross-version selector. In any case, we just need
    // to remove all cross suffixes. If it is anything else, we need to adjust the cross-version suffix
    // of all artifacts (except those of the scala core) according to the value of the new scala
    // version, according to the "scala-library" artifact we have in our "Assemble". If we have
    // no scala-library, however, we can't change the suffixes at all, so we stop.
    // If cross-version is "full", the parts will have a cross suffix like
    // "_2.11.0-M5"; we should replace that with the new full Scala version.
    // For "standard" it may be either "_2.11.0-M5" or "_2.11", depending on what each part
    // decides. For binaryFull, it will be "_2.11" even for milestones.
    // The cross suffix for the parts depends on their own build.options.
    // 
    // We change that in conformance to project.crossVersion, so that:
    // - disabled => no suffix
    // - full => full version string
    // - binaryFull => binaryScalaVersion
    // - standard => binary if stable, full otherwise
    // For "standard" we rely on the simple 0.12 algorithm (contains "-"), as opposed to the
    // algorithms detailed in sbt's pull request #600.
    //
    // We have to patch both the list of BuildSubArtifactsOut, as well as the actual filenames
    // (including checksums, if any)

    val Part = """(\d+\.\d+)(?:\..+)?""".r
    def binary(s: String) = s match {
      case Part(z) => z
      case _ => sys.error("Fatal: cannot extract Scala binary version from string \"" + s + "\"")
    }
    val crossSuff = project.buildOptions.crossVersion match {
      case "disabled" => ""
      case l @ "full" => "_" + getScalaVersion(l)
      case l @ "binary" => "_" + binary(getScalaVersion(l))
      case l @ "standard" =>
        val version = getScalaVersion(l)
        "_" + (if (version.contains('-')) version else binary(version))
      case cv => sys.error("Fatal: unrecognized cross-version option \"" + cv + "\"")
    }
    def patchName(s: String) = fixName(s) + crossSuff

    // this is the renaming section: the artifacts are renamed according
    // to the crossSuffix selection
    val artifactsMap = preCrossArtifactsMap map {
      case (projName, BuildArtifactsOut(subs)) => (projName, BuildArtifactsOut(
        subs map {
          case BuildSubArtifactsOut(subProjName, artifacts, shas) =>
            val renamedArtifacts = artifacts map { l =>
              if (isScalaCoreArt(l)) l else l.copy(crossSuffix = crossSuff)
            }
            val newSHAs = shas map { sha =>
              val oldLocation = sha.location
              object OrgNameVerFilenamesuffix {
                val MavenMatchPattern = """(.*)/([^/]*)/([^/]*)/\2(-[^/]*)""".r
                val IvyXmlMatchPattern = """([^/]*)/([^/]*)/([^/]*)/(ivys)/([^/]*)""".r
                val IvyMatchPattern = """([^/]*)/([^/]*)/([^/]*)/([^/]*)/\2([^/]*)""".r

                // Returns: org, name, ver, suffix1, suffix2, isMaven, isIvyXml
                // where:
                // - for Maven:
                //   isMaven is true
                //   suffix1 is the part after the "name", for example in:
                //     org/scala-lang/modules/scala-xml_2.11.0-M5/1.0-RC4/scala-xml_2.11.0-M5-1.0-RC4-sources.jar
                //   suffix1 is "-1.0-RC4-sources.jar"
                //   suffix2 is ignored
                // - for Ivy, for things that are in an "ivys" directory:
                //   isIvyXml is true
                //   suffix1 is "ivys"
                //   suffix2 is the filename. For example:
                //     org.scala-lang/scala-compiler/2.10.2-dbuildx83bbe18c0407e30bbcf72be0eb1cfc9934528099/ivys/ivy.xml.sha1
                //     -> suffix2 is "ivy.xml.sha1"
                // - for Ivy, for things that are not in an "ivys" directory:
                //   isIvyXml is false
                //   suffix1 is "docs", "jars", etc.
                //   suffix2 is the portion of the filename after "name". For example:
                //     org.scala-lang/scala-compiler/2.10.2-dbuildx83bbe18c0407e30bbcf72be0eb1cfc9934528099/docs/scala-compiler-javadoc.jar
                //     -> suffix1 is "docs"
                //     -> suffix2 is "-javadoc.jar"
                def unapply(s: String): Option[(String, String, String, String, String, Boolean, Boolean)] = {
                  try {
                    val MavenMatchPattern(org, name, ver, suffix) = s
                    Some((org.replace('/', '.'), name, ver, suffix, "", true, false))
                  } catch {
                    case e: _root_.scala.MatchError => try {
                      val IvyXmlMatchPattern(org, name, ver, suffix1, suffix2) = s
                      Some((org, name, ver, suffix1, suffix2, false, true))
                    } catch {
                      case e: _root_.scala.MatchError => try {
                        val IvyMatchPattern(org, name, ver, suffix1, suffix2) = s
                        Some((org, name, ver, suffix1, suffix2, false, false))
                      } catch {
                        case e: _root_.scala.MatchError => None
                      }
                    }
                  }
                }
              }
              try {
                val OrgNameVerFilenamesuffix(org, oldName, ver, suffix1, suffix2, isMaven, isIvyXml) = oldLocation
                if (isScalaCore(oldName, org)) sha else {
                  val newName = patchName(oldName)
                  if (newName == oldName) sha else {

                    def fileDir(name: String) = if (isMaven)
                      org.split('.').foldLeft(localRepo)(_ / _) / name / ver
                    else
                      localRepo / org / name / ver / suffix1

                    def fileLoc(name: String) = fileDir(name) / (if (isMaven)
                      (name + suffix1)
                    else if (isIvyXml)
                      suffix2
                    else
                      (name + suffix2))

                    val oldFile = fileLoc(oldName)
                    val newFile = fileLoc(newName)
                    val newLocation = IO.relativize(localRepo, newFile) getOrElse
                      sys.error("Internal error while relativizing " + newFile.getCanonicalPath() + " against " + localRepo.getCanonicalPath())
                    fileDir(newName).mkdirs() // ignore if already present
                    if (!oldFile.renameTo(newFile))
                      sys.error("cannot rename " + oldLocation + " to " + newLocation + ".")
                    sha.copy(location = newLocation)
                  }
                }
              } catch {
                case e: _root_.scala.MatchError =>
                  log.error("Path cannot be parsed: " + oldLocation + ". Continuing...")
                  sha
              }
            }
            BuildSubArtifactsOut(subProjName, renamedArtifacts, newSHAs)
        }))
    }

    //
    // we have all our artifacts ready. Time to rewrite the POMs and the ivy.xmls!
    // Note that we will also have to recalculate the shas
    //
    // Let's collect the list of available artifacts:
    //
    val allArtifactsOut = artifactsMap.map { _._2 }
    val available = allArtifactsOut.flatMap { _.results }.flatMap { _.artifacts }

    (localRepo.***.get).filter(_.getName.endsWith(".pom")).foreach {
      pom =>
        val reader = new MavenXpp3Reader()
        val model = reader.read(new _root_.java.io.FileReader(pom))
        // transform dependencies
        val deps: Seq[Dependency] = model.getDependencies.asScala
        val newDeps: _root_.java.util.List[Dependency] = (deps map { m =>
          available.find { artifact =>
            artifact.info.organization == m.getGroupId &&
              artifact.info.name == fixName(m.getArtifactId)
          } map { art =>
            val m2 = m.clone
            m2.setArtifactId(fixName(m.getArtifactId) + art.crossSuffix)
            m2.setVersion(art.version)
            m2
          } getOrElse m
        }).asJava
        val newModel = model.clone
        // has the artifactId (aka the name) changed? If so, patch that as well.
        val NameExtractor = """.*/([^/]*)/([^/]*)/\1-[^/]*.pom""".r
        val NameExtractor(newArtifactId, _) = pom.getCanonicalPath()
        newModel.setArtifactId(newArtifactId)
        newModel.setDependencies(newDeps)
        // we overwrite in place, there should be no adverse effect at this point
        val writer = new MavenXpp3Writer
        writer.write(new _root_.java.io.FileWriter(pom), newModel)
        updateChecksumFiles(pom)
    }

    def updateChecksumFiles(base: File) = {
      // We will also have to change the .sha1 and .md5 files
      // corresponding to this pom, if they exist, otherwise artifactory and ivy
      // will refuse to use the pom in question.
      Seq("md5", "sha1") foreach { algorithm =>
        val checksumFile = new File(base.getCanonicalPath + "." + algorithm)
        if (checksumFile.exists) {
          FileUtils.writeStringToFile(checksumFile, ChecksumHelper.computeAsString(base, algorithm))
        }
      }
    }

    (localRepo.***.get).filter(_.getName == "ivy.xml").foreach { file =>
      import _root_.scala.collection.JavaConversions._
      // ok, let's see what we can do with the ivy.xml
      val settings = new ivy.core.settings.IvySettings()
      val ivyHome = dir / ".ivy2" / "cache"
      settings.setDefaultIvyUserDir(ivyHome)
      val parser = ivy.plugins.parser.xml.XmlModuleDescriptorParser.getInstance()
      val ivyFileRepo = new ivy.plugins.repository.file.FileRepository(localRepo.getAbsoluteFile())
      val rel = IO.relativize(localRepo, file) getOrElse sys.error("Internal error while relativizing")
      val ivyFileResource = ivyFileRepo.getResource(rel)
      val model = parser.parseDescriptor(settings, file.toURL(), ivyFileResource, true) match {
        case m: ivy.core.module.descriptor.DefaultModuleDescriptor => m
        case m => sys.error("Unknown Module Descriptor: " + m)
      }

      val myRevID = model.getModuleRevisionId()
      val NameExtractor = """[^/]*/([^/]*)/[^/]*/ivys/ivy.xml""".r
      val NameExtractor(newArtifactId) = rel
      val newRevID = org.apache.ivy.core.module.id.ModuleRevisionId.newInstance(
        myRevID.getOrganisation(),
        newArtifactId,
        myRevID.getBranch(),
        myRevID.getRevision(),
        myRevID.getExtraAttributes())
      val newModel = new org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor(newRevID,
        model.getStatus(), model.getPublicationDate(), model.isDefault())
      newModel.setDescription(model.getDescription())
      model.getConfigurations() foreach { c =>
        newModel.addConfiguration(c)
        val conf = c.getName
        model.getArtifacts(conf) foreach { mArt =>
          val newArt =
            if (fixName(newArtifactId) != fixName(mArt.getName())) mArt else
              ivy.core.module.descriptor.DefaultArtifact.cloneWithAnotherName(mArt, newArtifactId)
          newModel.addArtifact(conf, newArt)
        }
      }
      model.getAllExcludeRules() foreach { newModel.addExcludeRule }
      model.getExtraAttributesNamespaces() foreach { case ns: (String, String) => newModel.addExtraAttributeNamespace(ns._1, ns._2) }
      model.getExtraInfo() foreach { case ns: (String, String) => newModel.addExtraInfo(ns._1, ns._2) }
      model.getLicenses() foreach { case l => newModel.addLicense(l) }
      newModel.setHomePage(model.getHomePage())
      newModel.setLastModified(model.getLastModified())
      model.getDependencies() foreach { d =>
        val dep = d match {
          case t: ivy.core.module.descriptor.DefaultDependencyDescriptor => t
          case t => sys.error("Unknown Dependency Descriptor: " + t)
        }
        val rid = dep.getDependencyRevisionId()
        val newDep = available.find { artifact =>
          artifact.info.organization == rid.getOrganisation() &&
            artifact.info.name == fixName(rid.getName())
        } map { art =>
          val transformer = new ivy.plugins.namespace.NamespaceTransformer {
            def transform(revID: ivy.core.module.id.ModuleRevisionId) = {
              ivy.core.module.id.ModuleRevisionId.newInstance(
                revID.getOrganisation(),
                art.info.name + art.crossSuffix,
                revID.getBranch(),
                art.version,
                revID.getExtraAttributes())
            }
            def isIdentity() = false
          }
          val transformMrid = transformer.transform(dep.getDependencyRevisionId())
          val transformDynamicMrid = transformer.transform(dep.getDynamicConstraintDependencyRevisionId())
          val newdd = new ivy.core.module.descriptor.DefaultDependencyDescriptor(
            null, transformMrid, transformDynamicMrid,
            dep.isForce(), dep.isChanging(), dep.isTransitive())
          val moduleConfs = dep.getModuleConfigurations()
          moduleConfs foreach { conf =>
            dep.getDependencyConfigurations(conf).foreach { newdd.addDependencyConfiguration(conf, _) }
            dep.getExcludeRules(conf).foreach { newdd.addExcludeRule(conf, _) }
            dep.getIncludeRules(conf).foreach { newdd.addIncludeRule(conf, _) }
            dep.getDependencyArtifacts(conf).foreach { depArt =>
              val newDepArt = if (art.info.name != fixName(depArt.getName())) depArt else {
                val n = new ivy.core.module.descriptor.DefaultDependencyArtifactDescriptor(depArt.getDependencyDescriptor(),
                  art.info.name + art.crossSuffix, depArt.getType(), depArt.getExt(), depArt.getUrl(), depArt.getExtraAttributes())
                depArt.getConfigurations().foreach(n.addConfiguration)
                n
              }
              newdd.addDependencyArtifact(conf, newDepArt)
            }
          }
          newdd
        } getOrElse dep

        newModel.addDependency(newDep)
      }
      ivy.plugins.parser.xml.XmlModuleDescriptorWriter.write(newModel, file)
      updateChecksumFiles(file)
    }

    // dbuild SHAs must be re-computed (since the POM/Ivy files changed)
    //
    val out = BuildArtifactsOut(artifactsMap.map {
      case (project, arts) =>
        val modArtLocs = arts.results.flatMap { _.artifacts }
        BuildSubArtifactsOut(project, modArtLocs, projSHAs(modArtLocs.map { _.info }, crossSuff))
    })
    log.debug("out: " + writeValue(out))
    out
  }

}
