package com.wixpress.build.bazel

import com.wix.build.maven.translation.MavenToBazelTranslations._
import com.wixpress.build.bazel.LibraryRule.LibraryRuleType
import com.wixpress.build.maven.MavenMakers._
import com.wixpress.build.maven.{Coordinates, Exclusion, Packaging}
import org.specs2.matcher.{AlwaysMatcher, Matcher, MustThrownExpectations}
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope

//noinspection TypeAnnotation
class RuleResolverTest extends SpecificationWithJUnit {

  val someWorkspace = "some_workspace"

  "RuleResolver" should {

    "return import external rule in case given regular jar coordinates" in new Context {

      ruleResolver.`for`(artifact, runtimeDependencies, compileDependencies, checksum = someChecksum, neverlink = true) must containImportExternalRule(importExternalRule(
        name = artifact.workspaceRuleName,
        anArtifact = be_===(artifact.serialized),
        runtimeDeps = contain(allOf(runtimeDependencies.map(_.toLabel))),
        compileDeps = contain(allOf(compileDependencies.map(_.toLabel))),
        checksum = be_===(someChecksum),
        neverlink = beTrue
      ))
    }

    "return import external rule in case given regular jar coordinates with source attributes" in new Context {

      ruleResolver.`for`(artifact, runtimeDependencies, compileDependencies,
        checksum = someChecksum, srcChecksum = someSrcChecksum).rule mustEqual ImportExternalRule(
        name = artifact.workspaceRuleName,
        artifact = artifact.serialized,
        runtimeDeps = runtimeDependencies.map(_.toLabel),
        compileTimeDeps = compileDependencies.map(_.toLabel),
        checksum = someChecksum,
        srcChecksum = someSrcChecksum
      )
    }

    "return import external rule with pom artifact dependencies" in new Context {
      ruleResolver.`for`(artifact, pomRuntimeDependencies, pomCompileDependencies).rule mustEqual ImportExternalRule(
        name = artifact.workspaceRuleName,
        artifact = artifact.serialized,
        runtimeDeps = pomRuntimeDependencies.map(_.toLabel),
        compileTimeDeps = pomCompileDependencies.map(_.toLabel),
      )
    }

    "return scala_import rule with empty jars attribute in case of pom artifact" in new Context {
      ruleResolver.`for`(pomArtifact, runtimeDependencies, compileDependencies).rule mustEqual LibraryRule(
        name = artifact.libraryRuleName,
        jars = Set.empty,
        runtimeDeps = runtimeDependencies.map(_.toLabel),
        exports = compileDependencies.map(_.toLabel),
      )
    }

    "return scala_import rule with pom artifact dependencies" in new Context {
      ruleResolver.`for`(pomArtifact, pomRuntimeDependencies, pomCompileDependencies).rule mustEqual LibraryRule(
        name = artifact.libraryRuleName,
        jars = Set.empty,
        runtimeDeps = pomRuntimeDependencies.map(_.toLabel),
        exports = pomCompileDependencies.map(_.toLabel),
      )
    }

    "throw runtime exception rule in case of packaging that is not pom or jar" in new Context {
      val coordinates = Coordinates("g", "a", "v", Packaging("zip"), Some("proto"))
      ruleResolver.`for`(coordinates) must throwA[RuntimeException]
    }

    "return group name as target locator for jar coordiantes" in new Context {
      ruleResolver.`for`(artifact, runtimeDependencies, compileDependencies)
        .ruleTargetLocator mustEqual ImportExternalRule.ruleLocatorFrom(artifact)
    }

    "return package path as target locator for pom coordiantes" in new Context {
      ruleResolver.`for`(pomArtifact, runtimeDependencies, compileDependencies)
        .ruleTargetLocator mustEqual LibraryRule.packageNameBy(artifact)
    }

    "return import external with testonly only if TestOnlyTargetsResolver indicates artifact is testonly" in new Context {
      testOnlyTargetsResolver.isTestOnlyTarget(artifact) returns true

      ruleResolver.`for`(otherArtifact) must containImportExternalRule(importExternalRule(
        name = otherArtifact.workspaceRuleName,
        anArtifact = be_===(otherArtifact.serialized),
        testOnly = beFalse,
      ))

      ruleResolver.`for`(artifact) must containImportExternalRule(importExternalRule(
        name = artifact.workspaceRuleName,
        anArtifact = be_===(artifact.serialized),
        testOnly = beTrue,
      ))
    }

    "return library rule with testonly only if TestOnlyTargetsResolver indicates pom artifact is testonly" in new Context {
      testOnlyTargetsResolver.isTestOnlyTarget(pomArtifact) returns true

      ruleResolver.`for`(otherPomArtifact) must containLibraryRule(libraryRule(
        name = otherPomArtifact.libraryRuleName,
        testOnly = beFalse,
      ))

      ruleResolver.`for`(pomArtifact) must containLibraryRule(libraryRule(
        name = pomArtifact.libraryRuleName,
        testOnly = beTrue,
      ))
    }

    "return non jar label with @workspaceName prefix" in {
      LibraryRuleDep.nonJarLabelBy(artifact) startsWith s"@$someWorkspace"
    }.pendingUntilFixed("First @workspace_name//third_party/... should be the same as @//third_party/... to bazel and strict deps")
  }

  trait Context extends Scope with Mockito with MustThrownExpectations {
    val testOnlyTargetsResolver = mock[TestOnlyTargetsResolver]
    val ruleResolver = new RuleResolver(someWorkspace, testOnlyTargetsResolver)
  }

  val artifact = someCoordinates("some-artifact")
  val otherArtifact = someCoordinates("some-other-artifact")
  val transitiveDep = someCoordinates("some-transitiveDep")
  val transitiveDeps = Set(transitiveDep)
  val pomArtifact = someCoordinates("some-artifact").copy(packaging = Packaging("pom"))
  val otherPomArtifact = someCoordinates("some-other-artifact").copy(packaging = Packaging("pom"))
  val runtimeDependencies: Set[BazelDep] = Set(ImportExternalDep(someCoordinates("runtime-dep")))
  val compileDependencies: Set[BazelDep] = Set(ImportExternalDep(someCoordinates("compile-dep")))
  val pomRuntimeDependencies: Set[BazelDep] = Set(LibraryRuleDep(someCoordinates("runtime-dep").copy(packaging = Packaging("pom"))))
  val pomCompileDependencies: Set[BazelDep] = Set(LibraryRuleDep(someCoordinates("compile-dep").copy(packaging = Packaging("pom"))))
  val someChecksum = Some("checksum")
  val someSrcChecksum = Some("src_checksum")

  def containImportExternalRule(customMatcher: Matcher[ImportExternalRule]): Matcher[RuleToPersist] =
    customMatcher ^^ {(_: RuleToPersist).rule.asInstanceOf[ImportExternalRule]}

  def containLibraryRule(customMatcher: Matcher[LibraryRule]): Matcher[RuleToPersist] =
    customMatcher ^^ {(_: RuleToPersist).rule.asInstanceOf[LibraryRule]}

  def importExternalRule(name: String,
                         anArtifact: Matcher[String] = AlwaysMatcher[String](),
                         runtimeDeps: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                         compileDeps: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                         checksum: Matcher[Option[String]] = AlwaysMatcher[Option[String]](),
                         srcChecksum: Matcher[Option[String]] = AlwaysMatcher[Option[String]](),
                         neverlink: Matcher[Boolean] = AlwaysMatcher[Boolean](),
                         testOnly: Matcher[Boolean] = AlwaysMatcher[Boolean]()
                        ): Matcher[ImportExternalRule] =
    be_===(name) ^^ {
      (_: ImportExternalRule).name aka "rule name"
    } and anArtifact ^^ {
      (_: ImportExternalRule).artifact aka "artifact"
    } and runtimeDeps ^^ {
      (_: ImportExternalRule).runtimeDeps aka "runtimeDeps"
    } and compileDeps ^^ {
      (_: ImportExternalRule).compileTimeDeps aka "compileTimeDeps"
    } and checksum ^^ {
      (_: ImportExternalRule).checksum aka "checksum"
    } and srcChecksum ^^ {
      (_: ImportExternalRule).srcChecksum aka "srcChecksum"
    } and neverlink ^^ {
      (_: ImportExternalRule).neverlink aka "neverlink"
    } and testOnly ^^ {
      (_: ImportExternalRule).testOnly aka "testOnly"
    }

  def libraryRule(name: String,
                  sources: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  jars: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  exports: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  runtimeDeps: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  compileDeps: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  exclusions: Matcher[Set[Exclusion]] = AlwaysMatcher[Set[Exclusion]](),
                  data: Matcher[Set[String]] = AlwaysMatcher[Set[String]](),
                  testOnly: Matcher[Boolean] = AlwaysMatcher[Boolean](),
                  libraryRuleType: Matcher[LibraryRuleType] = AlwaysMatcher[LibraryRuleType]()
                 ): Matcher[LibraryRule] =
    be_===(name) ^^ {
      (_: LibraryRule).name aka "rule name"
    } and sources ^^ {
      (_: LibraryRule).sources aka "sources"
    } and jars ^^ {
      (_: LibraryRule).jars aka "jars"
    } and exports ^^ {
      (_: LibraryRule).exports aka "exports"
    } and runtimeDeps ^^ {
      (_: LibraryRule).runtimeDeps aka "runtimeDeps"
    } and compileDeps ^^ {
      (_: LibraryRule).compileTimeDeps aka "compileTimeDeps"
    } and exclusions ^^ {
      (_: LibraryRule).exclusions aka "exclusions"
    } and data ^^ {
      (_: LibraryRule).data aka "data"
    } and testOnly ^^ {
      (_: LibraryRule).testOnly aka "testOnly"
    } and libraryRuleType ^^ {
      (_: LibraryRule).libraryRuleType aka "libraryRuleType"
    }
}

