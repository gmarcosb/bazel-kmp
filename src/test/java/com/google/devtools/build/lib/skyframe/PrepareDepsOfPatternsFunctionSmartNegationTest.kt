// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionKeyContext

/** Tests for [PrepareDepsOfPatternsFunction].  */
@RunWith(JUnit4::class)
class PrepareDepsOfPatternsFunctionSmartNegationTest : FoundationTestCase() {
    private var skyframeExecutor: SkyframeExecutor? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val analysisMock: AnalysisMock = AnalysisMock.getAnalysisMockWithoutBuiltinModules()
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(
                    getScratch().dir("/install"),
                    getScratch().dir("/output"),
                    getScratch().dir("/user_root")
                ),
                rootDirectory,
                analysisMock.productName
            )
        val ruleClassProvider: ConfiguredRuleClassProvider? = analysisMock.createRuleClassProvider()

        val pkgFactory: PackageFactory? =
            analysisMock
                .getPackageFactoryBuilderForTesting(directories)
                .build(ruleClassProvider, fileSystem)
        skyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(pkgFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(ActionKeyContext())
                .setExtraSkyFunctions(analysisMock.getSkyFunctions(directories))
                .setSyscallCache(SyscallCache.NO_CACHE)
                .build()
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        val optionsParser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        optionsParser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        skyframeExecutor.preparePackageLoading(
            PathPackageLocator(
                outputBase,
                com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            ),
            com.google.devtools.common.options.Options.getDefaults<O?>(PackageOptions::class.java),
            optionsParser.getOptions<O?>(BuildLanguageOptions::class.java),
            UUID.randomUUID(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(null)
        )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        skyframeExecutor.injectExtraPrecomputedValues(analysisMock.precomputedValues)
        scratch.file(".bazelignore")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveEvaluationFailsOnBadBuildFile() {
        // Given a well-formed package "@//foo" and a malformed package "@//foo/foo",
        createFooAndFooFoo()

        // Given a target pattern sequence consisting of a recursive pattern for "//foo/...",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo/...")

        // When PrepareDepsOfPatternsFunction completes evaluation (with no error because it was
        // recovered from),
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains package values for "@//foo" and "@//foo/foo",
        Truth.assertThat(WalkableGraphUtils.exists(PackageIdentifier.createInMainRepo("foo"), walkableGraph)).isTrue()
        Truth.assertThat(WalkableGraphUtils.exists(PackageIdentifier.createInMainRepo("foo/foo"), walkableGraph))
            .isTrue()

        // But the graph does not contain a value for the target "@//foo/foo:foofoo".
        Truth.assertThat(WalkableGraphUtils.exists(getKeyForLabel(Label.create("@//foo/foo", "foofoo")), walkableGraph))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativePatternBlocksPatternEvaluation() {
        // Given a well-formed package "//foo" and a malformed package "//foo/foo",
        createFooAndFooFoo()

        // Given a target pattern sequence consisting of a recursive pattern for "//foo/..." followed
        // by a negative pattern for the malformed package,
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo/...", "-//foo/foo/...")

        assertSkipsFoo(patternSequence)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoredPatternBlocksPatternEvaluation() {
        // Given a well-formed package "//foo" and a malformed package "//foo/foo",
        createFooAndFooFoo()

        // Given a target pattern sequence consisting of a recursive pattern for "//foo/...",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo/...")

        // and an ignored entry for the malformed package,
        scratch.overwriteFile(".bazelignore", "foo/foo")

        assertSkipsFoo(patternSequence)
    }

    @Throws(java.lang.Exception::class)
    private fun assertSkipsFoo(patternSequence: com.google.common.collect.ImmutableList<String?>?) {
        // When PrepareDepsOfPatternsFunction completes evaluation (successfully),

        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains a package value for "@//foo",
        Truth.assertThat(WalkableGraphUtils.exists(PackageIdentifier.createInMainRepo("foo"), walkableGraph)).isTrue()

        // But no package value for "@//foo/foo",
        Truth.assertThat(WalkableGraphUtils.exists(PackageIdentifier.createInMainRepo("foo/foo"), walkableGraph))
            .isFalse()

        // And the graph does not contain a value for the target "@//foo/foo:foofoo".
        val label: Label? = Label.create("@//foo/foo", "foofoo")
        Truth.assertThat(WalkableGraphUtils.exists(getKeyForLabel(label), walkableGraph)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativeNonTBDPatternsAreSkippedWithWarnings() {
        // Given a target pattern sequence with a negative non-TBD pattern,
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("-//foo/bar")

        // When PrepareDepsOfPatternsFunction completes evaluation,
        getGraphFromPatternsEvaluation(patternSequence)

        // Then a event is published that says that negative non-TBD patterns are skipped.
        assertContainsEvent(
            "Skipping '-//foo/bar, excludedSubdirs=[], filteringPolicy=[]': Negative target patterns of"
                    + " types other than \"targets below directory\" are not permitted."
        )
    }

    // Helpers:
    @Throws(java.lang.InterruptedException::class)
    private fun getGraphFromPatternsEvaluation(patternSequence: com.google.common.collect.ImmutableList<String?>?): WalkableGraph {
        val independentTarget: SkyKey =
            PrepareDepsOfPatternsValue.key(patternSequence, PathFragment.EMPTY_FRAGMENT)
        val singletonTargetPattern: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(independentTarget)

        // When PrepareDepsOfPatternsFunction completes evaluation,
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(100)
                .setEventHandler(
                    com.google.devtools.build.lib.events.Reporter(
                        EventBusEventHandler.createWithNewEventBus(),
                        eventCollector
                    )
                )
                .build()
        val evaluationResult: EvaluationResult<SkyValue?> =
            skyframeExecutor.getEvaluator().evaluate(singletonTargetPattern, evaluationContext)
        // The evaluation has no errors if success was expected.
        if (evaluationResult.hasError()) {
            org.junit.Assert.fail(evaluationResult.getError().toString())
        }
        return com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
    }

    @Throws(IOException::class)
    private fun createFooAndFooFoo() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out.txt"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/foo/BUILD", "genrule(name = 'foofoo',", "    This isn't even remotely grammatical.)"
        )
    }

    companion object {
        private fun getKeyForLabel(label: Label?): SkyKey {
            // Note that these tests used to look for TargetMarker SkyKeys before TargetMarker was
            // inlined in TransitiveTraversalFunction. Because TargetMarker is now inlined, it doesn't
            // appear in the graph. Instead, these tests now look for TransitiveTraversal keys.
            return TransitiveTraversalValue.key(label)
        }
    }
}
