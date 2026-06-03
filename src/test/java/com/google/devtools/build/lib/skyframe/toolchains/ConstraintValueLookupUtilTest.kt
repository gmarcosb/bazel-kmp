// Copyright 2018 The Bazel Authors. All rights reserved.
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

/** Tests for [ConstraintValueLookupUtil].  */
@RunWith(JUnit4::class)
class ConstraintValueLookupUtilTest : ToolchainTestCase() {
    /**
     * An [AnalysisMock] that injects [GetConstraintValueInfoFunction] into the Skyframe
     * executor.
     */
    private class AnalysisMockWithGetPlatformInfoFunction :
        com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(GET_CONSTRAINT_VALUE_INFO_FUNCTION, GetConstraintValueInfoFunction())
                .buildOrThrow()
        }
    }

    protected val analysisMock: AnalysisMock
        get() = com.google.devtools.build.lib.skyframe.toolchains.ConstraintValueLookupUtilTest.AnalysisMockWithGetPlatformInfoFunction()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintValueLookup() {
        val linuxKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//constraints:linux"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val macKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//constraints:mac"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key =
            GetConstraintValueInfoKey.Companion.create(
                com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(
                    linuxKey,
                    macKey
                )
            )

        val result: EvaluationResult<GetConstraintValueInfoValue?> = getConstraintValueInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(key).isNotNull()

        val constraintValues: MutableList<ConstraintValueInfo?>? = result.get(key).constraintValues()
        Truth.assertThat(constraintValues).contains(linuxConstraint)
        Truth.assertThat(constraintValues).contains(macConstraint)
        Truth.assertThat(constraintValues).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintValueLookup_targetNotConstraintValue() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_constraint')")

        val targetKey: ConfiguredTargetKey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//invalid:not_a_constraint"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key = GetConstraintValueInfoKey.Companion.create(
            com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(targetKey)
        )

        val result: EvaluationResult<GetConstraintValueInfoValue?> = getConstraintValueInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidConstraintValueException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//invalid:not_a_constraint")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstraintValueLookup_targetDoesNotExist() {
        val targetKey: ConfiguredTargetKey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//fake:missing"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key = GetConstraintValueInfoKey.Companion.create(
            com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(targetKey)
        )

        reporter.removeHandler(failFastHandler)
        val result: EvaluationResult<GetConstraintValueInfoValue?> = getConstraintValueInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidConstraintValueException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasCauseThat()
            .isInstanceOf(NoSuchPackageException::class.java)

        assertContainsEvent("no such package 'fake': BUILD file not found")
    }

    @AutoCodec
    internal class GetConstraintValueInfoKey(constraintValueKeys: Iterable<ConfiguredTargetKey?>?) : SkyKey {
        public override fun functionName(): SkyFunctionName? {
            return GET_CONSTRAINT_VALUE_INFO_FUNCTION
        }

        val constraintValueKeys: Iterable<ConfiguredTargetKey?>?

        init {
            this.constraintValueKeys = constraintValueKeys
            java.util.Objects.requireNonNull<Iterable<ConfiguredTargetKey?>?>(
                constraintValueKeys,
                "constraintValueKeys"
            )
        }

        companion object {
            fun create(
                constraintValueKeys: Iterable<ConfiguredTargetKey?>?
            ): GetConstraintValueInfoKey {
                return GetConstraintValueInfoKey(constraintValueKeys)
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun getConstraintValueInfo(
        key: GetConstraintValueInfoKey?
    ): EvaluationResult<GetConstraintValueInfoValue?> {
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
    internal class GetConstraintValueInfoValue(constraintValues: MutableList<ConstraintValueInfo?>?) : SkyValue {
        val constraintValues: MutableList<ConstraintValueInfo?>?

        init {
            this.constraintValues = constraintValues
            java.util.Objects.requireNonNull<MutableList<ConstraintValueInfo?>?>(constraintValues, "constraintValues")
        }

        companion object {
            fun create(constraintValues: MutableList<ConstraintValueInfo?>?): GetConstraintValueInfoValue {
                return GetConstraintValueInfoValue(constraintValues)
            }
        }
    }

    private class GetConstraintValueInfoFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
            val key = skyKey as GetConstraintValueInfoKey
            try {
                val constraintValues: MutableList<ConstraintValueInfo?>? =
                    ConstraintValueLookupUtil.getConstraintValueInfo(key.constraintValueKeys, env)
                if (env.valuesMissing()) {
                    return null
                }
                return GetConstraintValueInfoValue.Companion.create(constraintValues)
            } catch (e: InvalidConstraintValueException) {
                throw GetConstraintValueInfoFunctionException(e)
            }
        }
    }

    private class GetConstraintValueInfoFunctionException(e: InvalidConstraintValueException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        // Calls ConstraintValueLookupUtil.getConstraintValueInfo.
        private val GET_CONSTRAINT_VALUE_INFO_FUNCTION: SkyFunctionName? =
            SkyFunctionName.createHermetic("GET_CONSTRAINT_VALUE_INFO_FUNCTION")
    }
}
