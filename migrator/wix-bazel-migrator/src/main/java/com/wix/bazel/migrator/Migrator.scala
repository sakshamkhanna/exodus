package com.wix.bazel.migrator

import better.files.FileOps
import com.wix.bazel.migrator.model.{Package, SourceModule}
import com.wix.bazel.migrator.transform._
import com.wix.bazel.migrator.workspace.WorkspaceWriter
import com.wix.build.maven.analysis.ThirdPartyConflicts
import com.wixpress.build.bazel.NoPersistenceBazelRepository
import com.wixpress.build.maven._
import com.wixpress.build.sync.{DiffSynchronizer, HighestVersionConflictResolution}

/*
  should probably allow to customize:
  1. if DiskBasedClasspathResolver.reset or .init (save running time if there haven't been maven changes)
  2. If to persist the transformation results (if we want to later only quickly iterate over the writer)
  3. If to proceed to writing
  later maybe also to add ability to reset the git repository, maybe also to clone the repo
  the first 3 will enable deleting the "Generator" object and just build a run configuration which does it
 */
object Migrator extends MigratorApp {
  println(s"starting migration with configuration [$configuration]")

  val thirdPartyConflicts = checkConflictsInThirdPartyDependencies(aetherResolver)
  if (configuration.failOnSevereConflicts) failIfFoundSevereConflictsIn(thirdPartyConflicts)


  def bazelPackages = {
    val rawPackages = if (configuration.performTransformation) transform() else Persister.readTransformationResults()
    val withProtoPackages = new ExternalProtoTransformer(codeModules).transform(rawPackages)
    val withModuleDepsPackages = new ModuleDepsTransformer(codeModules).transform(withProtoPackages)
    withModuleDepsPackages
  }


  writeBazelRc()
  writePrelude()
  writeBazelRemoteRc()
  writeWorkspace()
  writeInternal()
  writeExternal()
  writeBazelCustomRunnerScript()

  private def transform() = {
    val exceptionFormattingDependencyAnalyzer = new ExceptionFormattingDependencyAnalyzer(codotaDependencyAnalyzer)
    val cachingCodotaDependencyAnalyzer = new CachingEagerEvaluatingCodotaDependencyAnalyzer(codeModules, exceptionFormattingDependencyAnalyzer)
    val mutuallyExclusiveCompositeDependencyAnalyzer = if (wixFrameworkMigration)
      new CompositeDependencyAnalyzer(
        cachingCodotaDependencyAnalyzer,
        new ManualInfoDependencyAnalyzer(sourceModules),
        new InternalFileDepsOverridesDependencyAnalyzer(sourceModules, repoRoot.toPath))
    else
      new CompositeDependencyAnalyzer(
      cachingCodotaDependencyAnalyzer,
      new InternalFileDepsOverridesDependencyAnalyzer(sourceModules, repoRoot.toPath))

    val transformer = new BazelTransformer(mutuallyExclusiveCompositeDependencyAnalyzer)
    val bazelPackages: Set[Package] = transformer.transform(codeModules)
    Persister.persistTransformationResults(bazelPackages)
    bazelPackages
  }

  private def wixFrameworkMigration = sys.env.get("repo_url").exists(_.contains("wix-framework"))


  private def writeBazelRc(): Unit =
    new BazelRcWriter(repoRoot).write()

  private def writeBazelRemoteRc(): Unit =
    new BazelRcRemoteWriter(repoRoot).write()

  private def writeWorkspace(): Unit =
    new WorkspaceWriter(repoRoot.toPath).write()

  private def writePrelude(): Unit =
    new PreludeWriter(repoRoot.toPath).write()

  private def writeInternal(): Unit = new Writer(repoRoot, codeModules).write(bazelPackages)



  private def writeExternal(): Unit = {
    new TemplateOfThirdPartyDepsSkylarkFileWriter(repoRoot).write()

    val bazelRepo = new NoPersistenceBazelRepository(repoRoot.toScala)
    val internalCoordinates = codeModules.map(_.coordinates)
    val filteringResolver = new FilteringGlobalExclusionDependencyResolver(
      resolver = aetherResolver,
      globalExcludes = internalCoordinates
    )

    val repoCoordinates = codeModules.map(_.coordinates)

    val directDependencies = codeModules.flatMap(_.dependencies.directDependencies).filterExternalDeps(repoCoordinates)

    val externalDependencies = new DependencyCollector(aetherResolver)
      .addOrOverrideDependencies(constantDependencies)
      .addOrOverrideDependencies(new HighestVersionConflictResolution().resolve(directDependencies))
      .mergeExclusionsOfSameCoordinates()
      .dependencySet()

    val managedDependenciesFromMaven = aetherResolver
      .managedDependenciesOf(managedDependenciesArtifact)
      .forceCompileScope

    val localNodes = filteringResolver.dependencyClosureOf(externalDependencies.forceCompileScope, managedDependenciesFromMaven)

    val bazelRepoWithManagedDependencies = new NoPersistenceBazelRepository(managedDepsRepoRoot.toScala)
    val diffSynchronizer = new DiffSynchronizer(bazelRepoWithManagedDependencies, bazelRepo, aetherResolver)
    diffSynchronizer.sync(localNodes)

    new DependencyCollectionCollisionsReport(codeModules).printDiff(externalDependencies)
  }

  private def writeBazelCustomRunnerScript(): Unit = {
    new BazelCustomRunnerWriter(repoRoot.toPath).write()
  }

  private def failIfFoundSevereConflictsIn(conflicts: ThirdPartyConflicts): Unit = {
    if (conflicts.fail.nonEmpty) {
      throw new RuntimeException("Found failing third party conflicts (look for \"Found conflicts\" in log)")
    }
  }

  implicit class DependencySetExtensions(dependencies: Set[Dependency]) {
    def filterExternalDeps(repoCoordinates: Set[Coordinates]) = {
      dependencies.filterNot(dep => repoCoordinates.exists(_.equalsOnGroupIdAndArtifactId(dep.coordinates)))
    }
  }
}
