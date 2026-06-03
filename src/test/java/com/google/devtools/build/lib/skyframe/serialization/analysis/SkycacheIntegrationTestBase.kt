// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked

abstract class SkycacheIntegrationTestBase : BuildIntegrationTestCase(), SkycacheIntegrationTestHelpers {
    @org.junit.Rule
    var testName: TestName = TestName()

    private val syscallCache = ClearCountingSyscallCache()

    @Before
    fun setup() {
        // TODO: b/367284400 - replace this with a barebones diffawareness check that works in Bazel
        // integration tests (e.g. making LocalDiffAwareness supported and not return
        // EVERYTHING_MODIFIED) for baseline diffs.
        addOptions("--experimental_frontier_violation_check=disabled_for_testing")
    }

    protected fun addDownloadOptions() {
        addOptions(DOWNLOAD_MODE_OPTION)
    }

    protected fun addUploadOptions() {
        addOptions(UPLOAD_MODE_OPTION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expectCheckedInvalidConfiguration_withDuplicateActiveDirectories() {
        write("foo/BUILD", "filegroup(name='A', srcs = [])")
        addUploadOptions()
        addOptions("--experimental_active_directories=foo,foo")
        val e: InvalidConfigurationException? =
            org.junit.Assert.assertThrows<T?>(
                InvalidConfigurationException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:A") })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "Active directories configuration error: foo has already been explicitly marked as"
                        + " included. Current state: [included: [foo], excluded: []]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noActiveDirectoriesOrProjectScl_fallsBackToFullSerialization() {
        write(
            "foo/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        assertUploadSuccess("//foo:empty")
        assertContainsEvent("No active directories were found. Falling back on full serialization.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitEmptyActiveDirectoriesFlag_fallsBackToFullSerialization() {
        write("foo/BUILD", "filegroup(name='A', srcs = [])")
        addOptions("--experimental_active_directories=")
        assertUploadSuccess("//foo:A")
        assertContainsEvent("No active directories were found. Falling back on full serialization.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noActiveDirectoriesInProjectScl_fallsBackToFullSerialization() {
        write("foo/BUILD", "filegroup(name='A', srcs = [])")
        writeProjectSclDefinition("test/project_proto.scl",  /* alsoWriteBuildFile= */true)
        write(
            "foo/PROJECT.scl",
            """
load("//test:project_proto.scl", "project_pb2")
project = project_pb2.Project.create(project_directories = []) # empty

""".trimIndent()
        )
        assertUploadSuccess("//foo:A")
        assertContainsEvent("No active directories were found. Falling back on full serialization.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun onlyExcludedDirectories_withActiveDirectoriesFlag_fallsBackToFullSerialization() {
        write("foo/BUILD", "filegroup(name='A', srcs = [])")
        addOptions("--experimental_active_directories=-foo")
        assertUploadSuccess("//foo:A")
        assertContainsEvent("No active directories were found. Falling back on full serialization.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun activeDirectoriesMatcher_activeDirectoriesFlag_takesPrecedenceOverProjectFile() {
        // Tests that the result of the active directories matcher is the same regardless of whether
        // the matcher is obtained from the active directories flag or the PROJECT.scl file.
        setupScenarioWithConfiguredTargets()

        writeProjectSclWithActiveDirs("foo")
        addUploadOptions()
        buildTarget("//foo:A")
        val serializedKeysWithProjectScl: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys()
        assertThat(serializedKeysWithProjectScl).isNotEmpty()

        skyframeExecutor.resetEvaluator()

        addOptions("--experimental_active_directories=bar") // overrides the PROJECT.scl file.
        buildTarget("//foo:A")
        val serializedKeysWithActiveDirectories: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys()
        assertThat(serializedKeysWithActiveDirectories).isNotEmpty()

        assertThat(serializedKeysWithActiveDirectories).isNotEqualTo(serializedKeysWithProjectScl)
        assertContainsEvent(
            "Specifying --experimental_active_directories will override the active directories"
                    + " specified in the PROJECT.scl file"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun activeDirectoriesMatcher_withProjectSclOrActiveDirectories_areEquivalent() {
        // Tests that the result of the active directories matcher is the same regardless of whether
        // the matcher is obtained from the active directories flag or the PROJECT.scl file.
        setupScenarioWithConfiguredTargets()

        writeProjectSclWithActiveDirs( /* path= */
            "foo",  /* activeDirs...= */
            "foo"
        )
        addUploadOptions()
        buildTarget("//foo:A")
        val serializedNodesWithProjectScl: Int =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeysCount()
        Truth.assertThat(serializedNodesWithProjectScl).isAtLeast(1)

        skyframeExecutor.resetEvaluator()

        addOptions("--experimental_active_directories=foo")
        buildTarget("//foo:A")
        val serializedNodesWithActiveDirectories: Int =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeysCount()
        Truth.assertThat(serializedNodesWithActiveDirectories).isAtLeast(1)
        Truth.assertThat(serializedNodesWithActiveDirectories).isEqualTo(serializedNodesWithProjectScl)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializingFrontierWithProjectFile_hasNoError() {
        write(
            "foo/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        writeProjectSclWithActiveDirs("foo")

        addUploadOptions()
        buildTarget("//foo:empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializingWithMultipleTopLevelProjectFiles_hasError() {
        write(
            "foo/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        writeProjectSclWithActiveDirs("foo")

        write(
            "bar/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        writeProjectSclWithActiveDirs("bar")

        addUploadOptions()
        val exception: LoadingFailedException? =
            org.junit.Assert.assertThrows<T?>(
                LoadingFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//foo:empty", "//bar:empty") })
        assertThat(exception).hasMessageThat().contains("This is a multi-project build")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializingWithMultipleTargetsResolvingToSameProjectFile_hasNoError() {
        write(
            "foo/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        write(
            "foo/bar/BUILD",
            """
        package_group(name = "empty")
        
        """.trimIndent()
        )
        writeProjectSclWithActiveDirs("foo")

        addUploadOptions()
        buildTarget("//foo:empty", "//foo/bar:empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildCommandWithSkymeld_uploadsFrontierBytesWithUploadMode() {
        runSkymeldScenario()
        // Validate that Skymeld did run.
        assertThat(commandEnvironment.withMergedAnalysisAndExecutionSourceOfTruth()).isTrue()

        val listener: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener()
        assertThat(listener.getSerializedKeysCount()).isAtLeast(1)
        assertThat(listener.getSkyfunctionCounts().count(SkyFunctions.CONFIGURED_TARGET)).isAtLeast(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildCommandWithSkymeld_doesNotClearCacheMidBuild() {
        runSkymeldScenario()

        Truth.assertThat(this.syscallCacheClearCount).isEqualTo(2)
    }

    @Throws(java.lang.Exception::class)
    private fun runSkymeldScenario() {
        writeProjectSclWithActiveDirs("foo")
        write(
            "foo/BUILD",
            """
        genrule(name = "g", srcs = ["//bar"], outs = ["g.out"], cmd = "cp ${'$'}< ${'$'}@")
        genrule(name = "h", srcs = ["//bar"], outs = ["h.out"], cmd = "cp ${'$'}< ${'$'}@")
        
        """.trimIndent()
        )
        write(
            "bar/BUILD",
            """
        genrule(name = "bar", outs = ["out"], cmd = "touch ${'$'}@")
        
        """.trimIndent()
        )
        addUploadOptions()
        addOptions(
            "--build",  // overrides --nobuild in setup step.
            "--experimental_merged_skyframe_analysis_execution" // forces Skymeld.
        )
        assertThat(buildTarget("//foo:all").getSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildCommand_serializedFrontierProfileContainsExpectedClasses() {
        val profilePath:  // to avoid confusion with vfs Paths
                Path = java.nio.file.Files.createTempFile(null, "profile")

        addOptions("--serialized_frontier_profile=" + profilePath)
        setupScenarioWithAspects()
        assertUploadSuccess("//bar:one")

        // The proto parses successfully from the file.
        val proto: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Profile.parseFrom(java.nio.file.Files.readAllBytes(profilePath), ExtensionRegistry.getEmptyRegistry())

        // The exact contents of the proto could change easily based on the underlying implementation.
        // Constructs a rather coarse assertion on the top-level entries of the proto that is hoped to
        // be relatively robust.

        // Constructs a table of location ID to class name.
        val classNames: HashMap<Int?, String?> = HashMap<Int?, String?>()
        proto.getFunctionList().stream()
            .forEach(
                { function ->
                    // All the names are formatted <class name>(<codec name>). Keeps just the class name
                    // because the codec name could easily change.
                    val fullName: String = proto.getStringTableList().get(function.getName() as Int)
                    val classNameEnd: Int = fullName.indexOf("(")
                    if (classNameEnd == -1) {
                        return@forEach  // not a class name
                    }
                    classNames.put(function.getId() as Int, fullName.substring(0, classNameEnd))
                })

        val topLevelClassNames: com.google.common.collect.ImmutableList<String?>? =
            proto.getSampleList().stream()
                .filter({ sample -> sample.getLocationIdCount() === 1 })
                .map({ sample -> classNames.get(sample.getLocationId(0) as Int) }) // Skips entries that do not have class names (invalidation data).
                .filter({ name -> name != null })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        // These top-level class names should be relatively stable.
        Truth.assertThat(topLevelClassNames)
            .containsAtLeast(
                "com.google.devtools.build.lib.actions.Artifact.DerivedArtifact",
                "com.google.devtools.build.lib.actions.Artifact.SourceArtifact",
                "com.google.devtools.build.lib.analysis.ConfiguredTargetValue",
                "com.google.devtools.build.lib.cmdline.Label",
                "java.lang.Object[]"
            )

        Truth.assertWithMessage(
            "ConfiguredTargetValue subtypes should be represented in the profile as"
                    + " ConfiguredTargetValue"
        )
            .that(topLevelClassNames)
            .doesNotContain("com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue")

        Truth.assertWithMessage(
            "ConfiguredTargetValue subtypes should be represented in the profile as"
                    + " ConfiguredTargetValue"
        )
            .that(topLevelClassNames)
            .doesNotContain("com.google.devtools.build.lib.skyframe.NonRuleConfiguredTargetValue")
    }

    override fun createTestRoot(fileSystem: FileSystem?): Path {
        try {
            return com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(fileSystem)
        } catch (e: IOException) {
            throw java.lang.AssertionError(e)
        }
    }

    @Throws(java.lang.Exception::class)
    protected fun setupScenarioWithAspects() {
        write(
            "foo/provider.bzl",
            """
FileCountInfo = provider(
    fields = {
        'count' : 'number of files'
    }
)

""".trimIndent()
        )

        write(
            "foo/file_count.bzl",
            """
load("//foo:provider.bzl", "FileCountInfo")
def _file_count_aspect_impl(target, ctx):
    count = 0
    # Make sure the rule has a srcs attribute.
    if hasattr(ctx.rule.attr, 'srcs'):
        # Iterate through the sources counting files.
        for src in ctx.rule.attr.srcs:
            for f in src.files.to_list():
                count = count + 1
    # Get the counts from our dependencies.
    for dep in ctx.rule.attr.deps:
        count = count + dep[FileCountInfo].count
    return [FileCountInfo(count = count)]

file_count_aspect = aspect(
    implementation = _file_count_aspect_impl,
    attr_aspects = ['deps'],
    attrs = {
      "_y" : attr.label(default="//foo:generate_y"),
    },
)

""".trimIndent()
        )

        writeProjectSclWithActiveDirs("bar", "foo")

        write(
            "foo/BUILD",
            """
genrule(
    name = "generate_y",
    srcs = ["x.txt"],
    outs = ["y.txt"],
    cmd = "cat ${'$'}< > ${'$'}@",
)

""".trimIndent()
        )

        write(
            "bar/BUILD",
            """
load("@rules_java//java:defs.bzl", "java_library")
java_library(
    name = "one",
    srcs = ["One.java"],
    deps = [":two"],
)

java_library(
    name = "two",
    srcs = ["Two.java", "TwoA.java", "TwoB.java"],
)

# This genrule creates a DerivedArtifact and NestedSet dep to be serialized in the frontier.
#
# Without this, the test fails in the Bazel source tree with missing expected java.lang.Object[]
# and com.google.devtools.build.lib.actions.Artifact.DerivedArtifact from the actual
# topLevelClassNames. In Blaze-land, these classes were contributed by implicit frontier
# dependencies not found in Bazel-land, so this genrule ensures that the test do not rely on
# Blaze-land side-effects.
genrule(
    name = "two_gen",
    outs = ["TwoA.java", "TwoB.java"],
    cmd = "touch ${'$'}(OUTS)",
)

""".trimIndent()
        )

        addOptions("--nobuild", "--aspects=//foo:file_count.bzl%file_count_aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorOnWarmSkyframeUploadBuilds() {
        setupScenarioWithConfiguredTargets()

        writeProjectSclWithActiveDirs("foo")

        assertUploadSuccess("//foo:A")
        val exception: T? = org.junit.Assert.assertThrows<T?>(
            AbruptExitException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:A") })
        assertThat(exception).hasMessageThat().contains(BuildView.UPLOAD_BUILDS_MUST_BE_COLD)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorOnWarmSkyframeNoBuildUploadBuilds() {
        setupScenarioWithConfiguredTargets()

        writeProjectSclWithActiveDirs("foo")

        addOptions("--nobuild")
        assertUploadSuccess("//foo:A")
        val exception: T? = org.junit.Assert.assertThrows<T?>(
            AbruptExitException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:A") })
        assertThat(exception).hasMessageThat().contains(BuildView.UPLOAD_BUILDS_MUST_BE_COLD)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cquery_succeedsAndDoesNotTriggerUpload() {
        setupScenarioWithConfiguredTargets()
        addUploadOptions()
        runtimeWrapper.newCommand(CqueryCommand::class.java)
        buildTarget("//foo:A") // succeeds, even though there's no PROJECT.scl
        assertThat(
            commandEnvironment
                .getRemoteAnalysisCachingEventListener()
                .getSerializedKeysCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cquery_succeedsAndDoesNotTriggerUploadWithProjectScl() {
        setupScenarioWithConfiguredTargets()
        writeProjectSclWithActiveDirs("foo")
        addUploadOptions()
        runtimeWrapper.newCommand(CqueryCommand::class.java)
        buildTarget("//foo:A")
        assertThat(
            commandEnvironment
                .getRemoteAnalysisCachingEventListener()
                .getSerializedKeysCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfiguration_doesNotAffectSkyValueVersion() {
        setupScenarioWithConfiguredTargets()
        writeProjectSclWithActiveDirs("mytest")
        write("mytest/mytest.sh", "exit 0").setExecutable(true)

        write(
            "mytest/BUILD",
            """
        load("//test_defs:foo_test.bzl", "foo_test")
        foo_test(
            name = "mytest",
            srcs = ["mytest.sh"],
            data = ["//foo:A"],
        )
        
        """.trimIndent()
        )

        addUploadOptions()
        addOptions("--nobuild")

        buildTarget("//mytest")
        assertThat(
            commandEnvironment
                .getRemoteAnalysisCachingEventListener()
                .getSerializedKeysCount()
        )
            .isAtLeast(1)

        val versionFromBuild: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSkyValueVersion()

        skyframeExecutor.resetEvaluator()

        runtimeWrapper.newCommand(TestCommand::class.java)
        buildTarget("//mytest")
        assertThat(
            commandEnvironment
                .getRemoteAnalysisCachingEventListener()
                .getSerializedKeysCount()
        )
            .isAtLeast(1)

        val versionFromTest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSkyValueVersion()

        // Assert that the top level config checksum subcomponent is equal.
        assertThat(versionFromBuild.getTopLevelConfigFingerprint())
            .isEqualTo(versionFromTest.getTopLevelConfigFingerprint())
        // Then assert that the whole thing is equal.
        assertThat(versionFromBuild).isEqualTo(versionFromTest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dumpUploadManifestOnlyMode_writesManifestToStdOut() {
        setupScenarioWithConfiguredTargets()
        addOptions(DUMP_MANIFEST_MODE_OPTION)
        writeProjectSclWithActiveDirs("foo")

        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        buildTarget("//foo:A")

        // BuildConfigurationKey is omitted to avoid too much specificity.
        val expected: com.google.common.collect.ImmutableList<String?> =
            """
FRONTIER_CANDIDATE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//bar:C,
FRONTIER_CANDIDATE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//bar:E,
FRONTIER_CANDIDATE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//bar:H,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//bar:F,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//foo:A,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//foo:A,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//foo:B,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//foo:D,
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//foo:G,

"""
                .trimIndent()
                .lines()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

        expected.forEach(java.util.function.Consumer { line: String? -> com.google.common.truth.Subject.contains(line) })

        // The additional line is from the additional --host_platforms analysis node, which has a
        // different label internally and externally.
        assertThat(outErr.outAsLatin1().lines()).hasSize(expected.size() + 1)

        // Nothing serialized
        assertThat(
            commandEnvironment
                .getRemoteAnalysisCachingEventListener()
                .getSerializedKeysCount()
        )
            .isEqualTo(0)
    }

    @Throws(java.lang.Exception::class)
    protected fun setupScenarioWithConfiguredTargets() {
        // ┌───────┐     ┌───────┐
        // │ bar:C │ ◀── │ foo:A │
        // └───────┘     └───────┘
        //                 │
        //                 │
        //                 ▼
        // ┌───────┐     ┌───────┐     ┌───────┐
        // │ bar:E │ ◀── │ foo:B │ ──▶ │ bar:F │
        // └───────┘     └───────┘     └───────┘
        //   │             │             │
        //   │             │             │
        //   ▼             ▼             ▼
        // ┌───────┐     ┌───────┐     ┌───────┐
        // │ bar:I │     │ foo:D │     │ foo:G │
        // └───────┘     └───────┘     └───────┘
        //                 │
        //                 │
        //                 ▼
        //               ┌───────┐
        //               │ bar:H │
        //               └───────┘
        write(
            "foo/BUILD",
            """
filegroup(name = "A", srcs = [":B", "//bar:C"])
filegroup(name = "B", srcs = [":D", "//bar:E", "//bar:F"])
filegroup(name = "D", srcs = ["//bar:H"])
filegroup(name = "G")

""".trimIndent()
        )
        write(
            "bar/BUILD",
            """
filegroup(name = "C")
filegroup(name = "E", srcs = [":I"])
filegroup(name = "F", srcs = ["//foo:G"])
filegroup(name = "H")
filegroup(name = "I")

""".trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionLookupKey_underTheFrontier_areNotUploaded() {
        setupGenruleGraph()
        assertUploadSuccess("//A")
        val serializedKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys()
        val labels: com.google.common.collect.ImmutableSet<Label?> =
            getLabels(Companion.filterKeys<ActionLookupKey?>(serializedKeys, ActionLookupKey::class.java))

        // Active set
        Truth.assertThat(labels).contains(parseCanonicalUnchecked("//A"))

        // Frontier
        Truth.assertThat(labels)
            .containsAtLeast(
                parseCanonicalUnchecked("//C:C.txt"),  // output file CT
                parseCanonicalUnchecked("//E")
            )

        // Under the frontier
        Truth.assertThat(labels).doesNotContain(parseCanonicalUnchecked("//C"))
        Truth.assertThat(
            labels.stream().map<Any?>(Label::toString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        )
            .doesNotContain("//D:D")

        // Different top level target
        Truth.assertThat(labels).doesNotContain(parseCanonicalUnchecked("//B"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun frontierSelectionSucceeds_forTopLevelGenruleConfiguredTargetWithUniqueName() {
        setupGenruleGraph()
        write(
            "A/BUILD",
            """
        genrule(
            name = "copy_of_A", # renamed
            srcs = ["in.txt", "//C:C.txt", "//E"],
            outs = ["A"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        assertUploadSuccess("//A")
        val serializedKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys()
        val labels: com.google.common.collect.ImmutableSet<Label?> =
            getLabels(Companion.filterKeys<ActionLookupKey?>(serializedKeys, ActionLookupKey::class.java))

        // Active set
        Truth.assertThat(labels)
            .containsAtLeast(
                parseCanonicalUnchecked("//A"),
                parseCanonicalUnchecked("//A:copy_of_A"),
                parseCanonicalUnchecked("//A:in.txt")
            )

        // Frontier
        Truth.assertThat(labels)
            .containsAtLeast(parseCanonicalUnchecked("//C:C.txt"), parseCanonicalUnchecked("//E"))

        // Under the frontier
        Truth.assertThat(labels).doesNotContain(parseCanonicalUnchecked("//C"))
        Truth.assertThat(
            labels.stream().map<Any?>(Label::toString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        )
            .doesNotContain("//D:D")

        // Different top level target
        Truth.assertThat(labels).doesNotContain(parseCanonicalUnchecked("//B"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dumpUploadManifestOnlyMode_forTopLevelGenruleConfiguredTarget() {
        setupGenruleGraph()
        write(
            "A/BUILD",
            """
        genrule(
            name = "copy_of_A",
            srcs = ["in.txt", "//C:C.txt", "//E"],
            outs = ["A"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )

        addOptions(DUMP_MANIFEST_MODE_OPTION)

        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr

        buildTarget("//A")

        // Note that there are two //A:A - one each for target and exec configuration. The
        // BuildConfigurationKey is omitted because it's too specific, but we test for the
        // exact number of entries in the manifest later, so the two //A:A configured targets will be
        // counted correctly.
        val expectedActiveSet: com.google.common.collect.ImmutableList<String?> =
            """
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//A:copy_of_A, config=
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//A:A, config=
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//A:A, config=
ACTIVE: CONFIGURED_TARGET:ConfiguredTargetKey{label=//A:in.txt, config=null}

"""
                .trimIndent()
                .lines()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

        val actualActiveSet: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            outErr.outAsLatin1().lines().filter({ l -> l.startsWith("ACTIVE:") }).collect(Collectors.joining("\n"))

        expectedActiveSet.forEach(java.util.function.Consumer { line: String? ->
            com.google.common.truth.Subject.contains(
                line
            )
        })

        assertThat(actualActiveSet.lines()).hasSize(expectedActiveSet.size())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionLookupData_ownedByActiveSet_areUploaded() {
        setupGenruleGraph()
        assertUploadSuccess("//A")
        val serializedKeys: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys()
        val actionLookupDatas: com.google.common.collect.ImmutableSet<ActionLookupData?> =
            Companion.filterKeys<ActionLookupData?>(serializedKeys, ActionLookupData::class.java)
        val owningLabels: com.google.common.collect.ImmutableSet<Label?> = getOwningLabels(actionLookupDatas)

        // Active set
        Truth.assertThat(owningLabels).contains(parseCanonicalUnchecked("//A"))

        // Frontier
        Truth.assertThat(owningLabels)
            .containsAtLeast(parseCanonicalUnchecked("//C"), parseCanonicalUnchecked("//E"))

        // Under the frontier
        Truth.assertThat(
            owningLabels.stream().map<Any?>(Label::toString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        )
            .contains("//D:D")

        // Different top level target
        Truth.assertThat(owningLabels).doesNotContain(parseCanonicalUnchecked("//B"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun disjointDirectoriesWithCanonicalProject_uploadsSuccessfully() {
        setupGenruleGraph()
        write("B/PROJECT.scl", "project = { \"actual\": \"//A:PROJECT.scl\" }")
        assertUploadSuccess("//A", "//B")
    }

    @Throws(IOException::class)
    protected fun setupGenruleGraph() {
        // /--> E
        // A -> C -> D
        // B ---^
        write("A/in.txt", "A")
        write(
            "A/BUILD",
            """
        genrule(
            name = "A",
            srcs = ["in.txt", "//C:C.txt", "//E"],
            outs = ["A"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("B/in.txt", "B")
        write(
            "B/BUILD",
            """
        genrule(
            name = "B",
            srcs = ["in.txt", "//C:C.txt"],
            outs = ["B"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("C/in.txt", "C")
        write(
            "C/BUILD",
            """
        genrule(
            name = "C",
            srcs = ["in.txt", "//D:D.txt"],
            outs = ["C.txt"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("D/in.txt", "D")
        write(
            "D/BUILD",
            """
        genrule(
            name = "D",
            srcs = ["in.txt"],
            outs = ["D.txt"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("E/in.txt", "E")
        write(
            "E/BUILD",
            """
        genrule(
            name = "E",
            srcs = ["in.txt"],
            outs = ["E.txt"],
            cmd = "cat ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        writeProjectSclWithActiveDirs("A")
    }

    @Throws(java.lang.Exception::class)
    protected fun uploadManifest(vararg targets: String?): com.google.common.collect.ImmutableList<String?> {
        skyframeExecutor.resetEvaluator()
        addOptions(DUMP_MANIFEST_MODE_OPTION)
        val outErr: RecordingOutErr = RecordingOutErr()
        this.outErr = outErr
        buildTarget(*targets)
        return outErr
            .outAsLatin1()
            .lines()
            .filter({ s -> s.startsWith("ACTIVE: ") || s.startsWith("FRONTIER_CANDIDATE:") })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    @Throws(java.lang.Exception::class)
    protected fun roundtrip(vararg targets: String?) {
        skyframeExecutor.resetEvaluator()
        assertUploadSuccess(*targets)
        skyframeExecutor.resetEvaluator()
        assertDownloadSuccess(*targets)
    }

    val commandEnvironment: CommandEnvironment
        get() = super.commandEnvironment

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() {
            val builder: @NotNull BlazeRuntime.Builder = super.runtimeBuilder
            if (testUsesSyscallCacheClearCount()) {
                // There isn't really a good way to apply this conditionally during @Before in Junit.
                builder.addBlazeModule(SyscallCacheInjectingModule())
            }
            return builder
        }

    fun testUsesSyscallCacheClearCount(): Boolean {
        return testName.getMethodName() == "buildCommandWithSkymeld_doesNotClearCacheMidBuild"
    }

    val syscallCacheClearCount: Int
        get() = syscallCache.clearCount.get()

    internal class ClearCountingSyscallCache : SyscallCache {
        private val clearCount: AtomicInteger = AtomicInteger(0)

        @Throws(IOException::class)
        public override fun readdir(path: Path): MutableCollection<Dirent?> {
            return path.readdir(Symlinks.NOFOLLOW)
        }

        @Throws(IOException::class)
        public override fun statIfFound(path: Path, symlinks: Symlinks?): FileStatus? {
            return path.statIfFound(symlinks)
        }

        public override fun getType(path: Path?, symlinks: Symlinks?): DirentTypeWithSkip {
            return DirentTypeWithSkip.FILESYSTEM_OP_SKIPPED
        }

        public override fun clear() {
            clearCount.incrementAndGet()
        }
    }

    internal inner class SyscallCacheInjectingModule : BlazeModule() {
        public override fun workspaceInit(
            runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
        ) {
            builder.setSyscallCache(syscallCache)
        }
    }

    companion object {
        protected const val UPLOAD_MODE_OPTION: String = "--experimental_remote_analysis_cache_mode=upload"
        protected const val DOWNLOAD_MODE_OPTION: String = "--experimental_remote_analysis_cache_mode=download"
        protected const val DUMP_MANIFEST_MODE_OPTION: String =
            "--experimental_remote_analysis_cache_mode=dump_upload_manifest_only"

        protected fun assertContainsExactlyPrefixes(
            strings: com.google.common.collect.ImmutableList<String?>?, vararg prefixes: String?
        ) {
            val prefixCorrespondence: Correspondence<String?, String?> =
                Correspondence.from<String?, String?>(BinaryPredicate { obj: String?, prefix: String? ->
                    obj.startsWith(
                        prefix
                    )
                }, "starts with")
            for (prefix in prefixes) {
                Truth.assertThat(strings).comparingElementsUsing<String?, String?>(prefixCorrespondence)
                    .contains(prefix)
            }
        }

        protected fun assertContainsPrefixes(
            strings: com.google.common.collect.ImmutableList<String?>?, vararg prefixes: String?
        ) {
            val prefixCorrespondence: Correspondence<String?, String?> =
                Correspondence.from<String?, String?>(BinaryPredicate { obj: String?, prefix: String? ->
                    obj.startsWith(
                        prefix
                    )
                }, "starts with")
            for (prefix in prefixes) {
                Truth.assertThat(strings).comparingElementsUsing<String?, String?>(prefixCorrespondence)
                    .contains(prefix)
            }
        }

        protected fun assertDoesNotContainPrefixes(
            strings: com.google.common.collect.ImmutableList<String?>?, vararg prefixes: String?
        ) {
            val prefixCorrespondence: Correspondence<String?, String?> =
                Correspondence.from<String?, String?>(BinaryPredicate { obj: String?, prefix: String? ->
                    obj.startsWith(
                        prefix
                    )
                }, "starts with")
            Truth.assertThat(strings).comparingElementsUsing<String?, String?>(prefixCorrespondence)
                .containsNoneIn(prefixes)
        }

        protected fun <T> filterKeys(
            from: MutableSet<SkyKey?>,
            klass: java.lang.Class<out T?>
        ): com.google.common.collect.ImmutableSet<T?> {
            return from.stream().filter(java.util.function.Predicate { obj: SkyKey? -> klass.isInstance(obj) })
                .map { obj: Any? -> klass.cast(obj) }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<T?>())
        }

        protected fun getLabels(from: MutableSet<ActionLookupKey?>): com.google.common.collect.ImmutableSet<Label?> {
            return from.stream().map<Any?>(ActionLookupKey::getLabel)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }

        protected fun getLabelStrings(from: MutableSet<ActionLookupKey?>): com.google.common.collect.ImmutableSet<String?> {
            return getLabels(from).stream().map<Any?>(Label::toString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }

        protected fun getOwningLabels(from: MutableSet<ActionLookupData?>): com.google.common.collect.ImmutableSet<Label?> {
            return from.stream()
                .map<Any?>(java.util.function.Function { data: ActionLookupData? ->
                    data.getActionLookupKey().getLabel()
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }
    }
}
