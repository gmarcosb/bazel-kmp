// Copyright 2025 The Bazel Authors. All rights reserved.
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
// limitations under the License.package com.google.devtools.build.lib.skyframe;
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [TargetPatternUtil].  */
@RunWith(TestParameterInjector::class)
class TargetPatternUtilTest : BuildViewTestCase() {
    @org.junit.Test
    @TestParameters(valuesProvider = ExpansionPatternProvider::class)
    @Throws(java.lang.Exception::class)
    fun expansion(
        rawPatterns: com.google.common.collect.ImmutableList<String?>?,
        expectedLabels: com.google.common.collect.ImmutableList<Label?>?
    ) {
        ExpansionPatternProvider.Companion.createBuildFiles(scratch)

        val result: com.google.common.collect.ImmutableSet<Label?> =
            expandTargetPattern(rawPatterns, FilteringPolicies.NO_FILTER)
        Truth.assertThat(result).containsExactlyElementsIn(expectedLabels)
    }

    // TODO: blaze-configurability-team - Test errors
    // TODO: blaze-configurability-team - Test relative labels
    // TODO: blaze-configurability-team - Test filtering policies
    private class ExpansionPatternProvider :
        com.google.testing.junit.testparameterinjector.TestParametersValuesProvider() {
        override fun provideValues(context: com.google.testing.junit.testparameterinjector.TestParametersValuesProvider.Context?): com.google.common.collect.ImmutableList<TestParametersValues?> {
            return com.google.common.collect.ImmutableList.of<TestParametersValues?>( // Single patterns.
                Companion.create("//foo/bar:baz", "//foo/bar:baz"),
                Companion.create(
                    "//wildcard/single/...",
                    "//wildcard/single:a",
                    "//wildcard/single:b",
                    "//wildcard/single:c"
                ),
                Companion.create(
                    "//wildcard/single:all",
                    "//wildcard/single:a",
                    "//wildcard/single:b",
                    "//wildcard/single:c"
                ),
                Companion.create(
                    "//wildcard/single:*",
                    "//wildcard/single:BUILD",
                    "//wildcard/single:a",
                    "//wildcard/single:b",
                    "//wildcard/single:c"
                ),
                Companion.create(
                    "//wildcard/deep/...",
                    "//wildcard/deep/a",
                    "//wildcard/deep/b:b_1",
                    "//wildcard/deep/b:b_2",
                    "//wildcard/deep/c"
                ),  // Combinations of patterns

                Companion.create(
                    com.google.common.collect.ImmutableList.of<String?>("//foo/bar:baz", "//foo/bar:quux"),
                    "//foo/bar:baz",
                    "//foo/bar:quux"
                ),
                Companion.create(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//wildcard/deep/a/...",
                        "//wildcard/deep/c/..."
                    ),
                    "//wildcard/deep/a",
                    "//wildcard/deep/c"
                ),  // Negative patterns.
                // TODO: blaze-configurability-team - fix handling of negative patterns and re-enable

                Companion.create(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "-//foo/bar:baz",
                        "//foo/bar:quux"
                    ), "//foo/bar:quux"
                ),
                Companion.create(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//wildcard/deep/...",
                        "-//wildcard/deep/b/..."
                    ),
                    "//wildcard/deep/a",
                    "//wildcard/deep/c"
                )
            )
        }

        companion object {
            private fun create(rawPattern: String, vararg rawLabels: String?): TestParametersValues {
                return Companion.create(com.google.common.collect.ImmutableList.of<String?>(rawPattern), *rawLabels)
            }

            private fun create(
                rawPatterns: com.google.common.collect.ImmutableList<String?>?, vararg rawLabels: String?
            ): TestParametersValues {
                val labels: com.google.common.collect.ImmutableList<Label?> =
                    java.util.Arrays.stream<String?>(rawLabels).map<Any?>(Label::parseCanonicalUnchecked)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

                val name: String = java.lang.String.format("%s-%s", rawPatterns, labels)
                return TestParametersValues.builder()
                    .name(name)
                    .addParameter("rawPatterns", rawPatterns)
                    .addParameter("expectedLabels", labels)
                    .build()
            }

            @Throws(IOException::class)
            private fun createBuildFiles(scratch: Scratch) {
                scratch.file(
                    "foo/bar/BUILD",
                    """
          filegroup(name = "baz")
          filegroup(name = "quux")
          
          """.trimIndent()
                )
                scratch.file(
                    "wildcard/single/BUILD",
                    """
          filegroup(name = "a")
          filegroup(name = "b")
          filegroup(name = "c")
          
          """.trimIndent()
                )
                scratch.file(
                    "wildcard/deep/a/BUILD",
                    """
          filegroup(name = "a")
          
          """.trimIndent()
                )
                scratch.file(
                    "wildcard/deep/b/BUILD",
                    """
          filegroup(name = "b_1")
          filegroup(name = "b_2")
          
          """.trimIndent()
                )
                scratch.file(
                    "wildcard/deep/c/BUILD",
                    """
          filegroup(name = "c")
          
          """.trimIndent()
                )
            }
        }
    }

    // Test setup and methods.
    @Throws(java.lang.InterruptedException::class)
    private fun expandTargetPattern(
        rawPatterns: com.google.common.collect.ImmutableList<String?>?, filteringPolicy: FilteringPolicy?
    ): com.google.common.collect.ImmutableSet<Label?> {
        val key = ExpandTargetPatternKey(rawPatterns, filteringPolicy)
        val result: EvaluationResult<ExpandTargetPatternValue?> = expandTargetPattern(key)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(key).isNotNull()

        return result.get(key).result()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun expandTargetPattern(key: ExpandTargetPatternKey?): EvaluationResult<ExpandTargetPatternValue?> {
        try {
            // Must re-enable analysis for Skyframe functions that create configured targets.
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(true)
            return SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, key,  /* keepGoing= */false, reporter
            )
        } finally {
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(false)
        }
    }

    val analysisMock: AnalysisMock
        get() = AnalysisMockWithExpandTargetPatternFunction()

    /**
     * An [AnalysisMock] that injects [ExpandTargetPatternFunction] into the Skyframe
     * executor.
     */
    private class AnalysisMockWithExpandTargetPatternFunction

        : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(EXPAND_TARGET_PATTERNS_FUNCTION, ExpandTargetPatternFunction())
                .buildOrThrow()
        }
    }

    @AutoCodec
    internal class ExpandTargetPatternKey(
        rawPatterns: com.google.common.collect.ImmutableList<String?>?,
        filteringPolicy: FilteringPolicy?
    ) : SkyKey {
        public override fun functionName(): SkyFunctionName? {
            return EXPAND_TARGET_PATTERNS_FUNCTION
        }

        val rawPatterns: com.google.common.collect.ImmutableList<String?>?
        val filteringPolicy: FilteringPolicy?

        init {
            this.filteringPolicy = filteringPolicy
            this.rawPatterns = rawPatterns
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(rawPatterns)
            java.util.Objects.requireNonNull<Any?>(filteringPolicy)
        }
    }

    @AutoCodec
    internal class ExpandTargetPatternValue(result: com.google.common.collect.ImmutableSet<Label?>?) : SkyValue {
        val result: com.google.common.collect.ImmutableSet<Label?>?

        init {
            this.result = result
        }
    }

    private class ExpandTargetPatternFunction : SkyFunction {
        @Throws(java.lang.InterruptedException::class, ExpandTargetPatternFunctionException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
            val key = skyKey as ExpandTargetPatternKey

            val mainRepoMapping: RepositoryMappingValue =
                env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue
            if (env.valuesMissing()) {
                return null
            }
            val targetPatternParser: TargetPattern.Parser =
                Parser(
                    PathFragment.EMPTY_FRAGMENT,
                    RepositoryName.MAIN,
                    mainRepoMapping.repositoryMapping()
                )

            try {
                val signedTargetPatterns: com.google.common.collect.ImmutableList<SignedTargetPattern?>? =
                    TargetPatternUtil.parseAllSigned(key.rawPatterns, targetPatternParser)
                val labels: com.google.common.collect.ImmutableSet<Label?>? =
                    TargetPatternUtil.expandTargetPatterns(
                        env, signedTargetPatterns, key.filteringPolicy
                    )
                if (env.valuesMissing()) {
                    return null
                }

                return ExpandTargetPatternValue(labels)
            } catch (e: InvalidTargetPatternException) {
                throw ExpandTargetPatternFunctionException(e)
            }
        }
    }

    private class ExpandTargetPatternFunctionException(e: InvalidTargetPatternException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        private val EXPAND_TARGET_PATTERNS_FUNCTION: SkyFunctionName? =
            SkyFunctionName.createHermetic("EXPAND_TARGET_PATTERNS_FUNCTION")
    }
}
