package com.wixpress.build.bazel

import com.wixpress.build.maven.MavenMakers._
import com.wixpress.build.maven._
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope
import com.wix.build.maven.translation.MavenToBazelTranslations._

class BazelDependenciesReaderTest extends SpecificationWithJUnit {

  "BazelDependenciesReader should return" >> {

    trait emptyThirdPartyReposCtx extends Scope {
      val localWorkspace: BazelLocalWorkspace = new FakeLocalBazelWorkspace()
      val reader = new BazelDependenciesReader(localWorkspace)

      def defaultDependency(groupId: String, artifactId: String, version: String, exclusion: Set[Exclusion] = Set.empty) =
        Dependency(Coordinates(groupId, artifactId, version), MavenScope.Compile, exclusion)

      val dep = someCoordinates("some-dep")
      val ruleName = s"${dep.groupIdForBazel}_some_dep"

      localWorkspace.overwriteThirdPartyImportTargetsFile(dep.groupIdForBazel,"")

    }

    "empty set of dependencies in case given empty third party repos" in new emptyThirdPartyReposCtx {

      reader.allDependenciesAsMavenDependencies() must beEmpty
    }

    "a dependency for third party repos with 1 dependency without exclusion" in new emptyThirdPartyReposCtx {
      localWorkspace.overwriteThirdPartyImportTargetsFile(dep.groupIdForBazel,
        s"""
          |if native.existing_rule("$ruleName") == None:
          |  scala_maven_import_external(
          |      name = "$ruleName",
          |      artifact = "${dep.groupId}:some-dep:some-version",
          |  )""".stripMargin)

      reader.allDependenciesAsMavenDependencies() must contain(defaultDependency(dep.groupId, "some-dep", "some-version"))
    }

    "a dependency for third party repos with 1 proto dependency" in new emptyThirdPartyReposCtx {
      localWorkspace.overwriteThirdPartyReposFile(
        s"""
          |if native.existing_rule("$ruleName") == None:
          |   maven_proto(
          |       name = "$ruleName",
          |       artifact = "some.group:some-dep:zip:proto:some-version",
          |   )""".stripMargin)

      reader.allDependenciesAsMavenDependencies() must contain(
        Dependency(
          Coordinates("some.group", "some-dep", "some-version",Packaging("zip"),Some("proto")),
          MavenScope.Compile
        ))
    }

    "a dependency for third party repos with 1 dependency that has an exclusion" in new emptyThirdPartyReposCtx {
      localWorkspace.overwriteThirdPartyImportTargetsFile(dep.groupIdForBazel,
        s"""
          |if native.existing_rule("$ruleName") == None:
          |   scala_maven_import_external(
          |       name = "$ruleName",
          |       artifact = "${dep.groupId}:some-dep:some-version",
          |       runtime_deps = [
          |
          |       ]
          |       # EXCLUDES ${dep.groupId}:some-exclude
          |   )""".stripMargin)

      reader.allDependenciesAsMavenDependencies() must contain(defaultDependency(dep.groupId, "some-dep", "some-version", Set(Exclusion("some.group", "some-exclude"))))
    }

    "all dependencies for repository with multiple dependencies" in new emptyThirdPartyReposCtx {
      val rule1 = """some_group_some_dep1"""
      val rule2 = """some_group_some_dep2"""

      localWorkspace.overwriteThirdPartyImportTargetsFile(dep.groupIdForBazel,
        s"""
           |if native.existing_rule("$rule1") == None:
           |   scala_maven_import_external(
           |       name = "$rule1",
           |       artifact = "${dep.groupId}:some-dep1:some-version",
           |   )
           |
           |if native.existing_rule("$rule2") == None:
           |   scala_maven_import_external(
           |       name = "$rule2",
           |       artifact = "${dep.groupId}:some-dep2:some-version",
           |   )""".stripMargin)

      private val dependencies: Set[Dependency] = reader.allDependenciesAsMavenDependencies()
      dependencies must containTheSameElementsAs(
        Seq(
          defaultDependency(dep.groupId, "some-dep1", "some-version"),
          defaultDependency(dep.groupId, "some-dep2", "some-version")
        ))

    }
  }
}
