// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [ToolchainTypeLookupUtil].  */
@RunWith(JUnit4::class)
class ToolchainTypeLookupUtilTest : ToolchainTestCase() {
    /**
     * An [AnalysisMock] that injects [GetToolchainTypeInfoFunction] into the Skyframe
     * executor.
     */
    private class AnalysisMockWithGetToolchainTypeInfoFunction

        : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(GET_TOOLCHAIN_TYPE_INFO_FUNCTION, GetToolchainTypeInfoFunction())
                .buildOrThrow()
        }
    }

    protected val analysisMock: AnalysisMock
        get() = AnalysisMockWithGetToolchainTypeInfoFunction()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeLookup() {
        val key: GetToolchainTypeInfoKey? =
            create(
                targetConfig, ToolchainTypeRequirement.create(testToolchainTypeLabel)
            )

        val result: EvaluationResult<GetToolchainTypeInfoValue?> = getToolchainTypeInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(key).isNotNull()

        val toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>? = result.get(key).toolchainTypes()
        Truth.assertThat(toolchainTypes)
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                    testToolchainTypeLabel,
                    testToolchainTypeInfo
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeLookup_toolchainAlias() {
        scratch.file(
            "alias/BUILD", "alias(name = 'toolchain_type', actual = '" + testToolchainTypeLabel + "')"
        )
        val aliasToolchainTypeLabel: Label = Label.parseCanonicalUnchecked("//alias:toolchain_type")
        val key: GetToolchainTypeInfoKey? =
            create(
                targetConfig, ToolchainTypeRequirement.create(aliasToolchainTypeLabel)
            )

        val result: EvaluationResult<GetToolchainTypeInfoValue?> = getToolchainTypeInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(key).isNotNull()

        val toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>? = result.get(key).toolchainTypes()
        Truth.assertThat(toolchainTypes)
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                    testToolchainTypeLabel,
                    testToolchainTypeInfo,
                    aliasToolchainTypeLabel,
                    testToolchainTypeInfo
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeLookup_targetNotToolchainType() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_toolchain_type')")

        val key: GetToolchainTypeInfoKey? =
            create(
                targetConfig,
                ToolchainTypeRequirement.create(
                    Label.parseCanonicalUnchecked("//invalid:not_a_toolchain_type")
                )
            )

        val result: EvaluationResult<GetToolchainTypeInfoValue?> = getToolchainTypeInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidToolchainTypeException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//invalid:not_a_toolchain_type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeLookup_targetNotToolchainType_ignoreInvalid() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_toolchain_type')")

        val key: GetToolchainTypeInfoKey? =
            create(
                targetConfig,
                ToolchainTypeRequirement.builder(
                    Label.parseCanonicalUnchecked("//invalid:not_a_toolchain_type")
                )
                    .ignoreIfInvalid(true)
                    .build()
            )

        val result: EvaluationResult<GetToolchainTypeInfoValue?> = getToolchainTypeInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>? = result.get(key).toolchainTypes()
        Truth.assertThat(toolchainTypes).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeLookup_targetDoesNotExist() {
        val key: GetToolchainTypeInfoKey? =
            create(
                targetConfig,
                ToolchainTypeRequirement.create(Label.parseCanonicalUnchecked("//fake:missing"))
            )

        reporter.removeHandler(failFastHandler)
        val result: EvaluationResult<GetToolchainTypeInfoValue?> = getToolchainTypeInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidToolchainTypeException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasCauseThat()
            .isInstanceOf(NoSuchPackageException::class.java)

        assertContainsEvent("no such package 'fake': BUILD file not found")
    }

    @AutoCodec
    internal class GetToolchainTypeInfoKey(
        toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?,
        configuration: BuildConfigurationValue?
    ) : SkyKey {
        public override fun functionName(): SkyFunctionName? {
            return GET_TOOLCHAIN_TYPE_INFO_FUNCTION
        }

        val toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?
        val configuration: BuildConfigurationValue?

        init {
            this.configuration = configuration
            this.toolchainTypes = toolchainTypes
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?>?>(
                toolchainTypes,
                "toolchainTypes"
            )
            java.util.Objects.requireNonNull<Any?>(configuration, "configuration")
        }

        companion object {
            fun create(
                configuration: BuildConfigurationValue?, vararg toolchainTypes: ToolchainTypeRequirement?
            ): GetToolchainTypeInfoKey {
                return GetToolchainTypeInfoKey(
                    com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(
                        toolchainTypes
                    ), configuration
                )
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getToolchainTypeInfo(
        key: GetToolchainTypeInfoKey?
    ): EvaluationResult<GetToolchainTypeInfoValue?> {
        try {
            // Must re-enable analysis for Skyframe functions that create configured targets.
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(true)
            return SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, key,  /*keepGoing=*/false, reporter
            )
        } finally {
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(false)
        }
    }

    @AutoCodec
    internal class GetToolchainTypeInfoValue(toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>?) : SkyValue {
        val toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>?

        init {
            this.toolchainTypes = toolchainTypes
            java.util.Objects.requireNonNull<MutableMap<Label?, ToolchainTypeInfo?>?>(toolchainTypes, "toolchainTypes")
        }

        companion object {
            fun create(toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>?): GetToolchainTypeInfoValue {
                return GetToolchainTypeInfoValue(toolchainTypes)
            }
        }
    }

    private class GetToolchainTypeInfoFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
            val key = skyKey as GetToolchainTypeInfoKey
            try {
                val toolchainTypes: MutableMap<Label?, ToolchainTypeInfo?>? =
                    ToolchainTypeLookupUtil.resolveToolchainTypes(
                        env, key.toolchainTypes, key.configuration
                    )
                if (env.valuesMissing()) {
                    return null
                }
                return GetToolchainTypeInfoValue.Companion.create(toolchainTypes)
            } catch (e: InvalidToolchainTypeException) {
                throw GetToolchainTypeInfoFunctionException(e)
            }
        }
    }

    private class GetToolchainTypeInfoFunctionException(e: InvalidToolchainTypeException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        // TODO: b/381396141 - Add a regression test for failure to find the second Skyframe value.
        // Calls ToolchainTypeLookupUtil.getToolchainTypeInfo.
        private val GET_TOOLCHAIN_TYPE_INFO_FUNCTION: SkyFunctionName? =
            SkyFunctionName.createHermetic("GET_TOOLCHAIN_TYPE_INFO_FUNCTION")
    }
}
