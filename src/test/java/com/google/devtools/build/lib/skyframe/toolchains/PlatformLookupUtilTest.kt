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

/** Tests for [PlatformLookupUtil].  */
@RunWith(JUnit4::class)
class PlatformLookupUtilTest : ToolchainTestCase() {
    /**
     * An [AnalysisMock] that injects [GetPlatformInfoFunction] into the Skyframe
     * executor.
     */
    private class AnalysisMockWithGetPlatformInfoFunction :
        com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(GET_PLATFORM_INFO_FUNCTION, GetPlatformInfoFunction())
                .buildOrThrow()
        }
    }

    protected val analysisMock: AnalysisMock
        get() = com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtilTest.AnalysisMockWithGetPlatformInfoFunction()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformLookup() {
        val linuxKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:linux"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val macKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:mac"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key = GetPlatformInfoKey.Companion.create(
            com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(
                linuxKey,
                macKey
            )
        )

        val result: EvaluationResult<GetPlatformInfoValue?> = getPlatformInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(key).isNotNull()

        val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?> = result.get(key).platforms()
        Truth.assertThat(platforms).containsKey(linuxKey)
        assertThat(platforms.get(linuxKey).label()).isEqualTo(linuxPlatform.label())
        Truth.assertThat(platforms).containsKey(macKey)
        assertThat(platforms.get(macKey).label()).isEqualTo(macPlatform.label())
        Truth.assertThat(platforms).hasSize(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformLookup_targetNotPlatform() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')")

        val targetKey: ConfiguredTargetKey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//invalid:not_a_platform"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key = GetPlatformInfoKey.Companion.create(
            com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(targetKey)
        )

        val result: EvaluationResult<GetPlatformInfoValue?> = getPlatformInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//invalid:not_a_platform")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformLookup_targetDoesNotExist() {
        val targetKey: ConfiguredTargetKey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//fake:missing"))
                .setConfigurationKey(targetConfigKey)
                .build()
        val key = GetPlatformInfoKey.Companion.create(
            com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(targetKey)
        )

        val result: EvaluationResult<GetPlatformInfoValue?> = getPlatformInfo(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("no such package 'fake': BUILD file not found")
    }

    @AutoCodec
    internal class GetPlatformInfoKey(platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?) :
        SkyKey {
        public override fun functionName(): SkyFunctionName? {
            return GET_PLATFORM_INFO_FUNCTION
        }

        val platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?

        init {
            this.platformKeys = platformKeys
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?>(
                platformKeys,
                "platformKeys"
            )
        }

        companion object {
            fun create(platformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?): GetPlatformInfoKey {
                return GetPlatformInfoKey(platformKeys)
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getPlatformInfo(key: GetPlatformInfoKey?): EvaluationResult<GetPlatformInfoValue?> {
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
    internal class GetPlatformInfoValue(platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>?) : SkyValue {
        val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>?

        init {
            this.platforms = platforms
            java.util.Objects.requireNonNull<MutableMap<ConfiguredTargetKey?, PlatformInfo?>?>(platforms, "platforms")
        }

        companion object {
            fun create(platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>?): GetPlatformInfoValue {
                return GetPlatformInfoValue(platforms)
            }
        }
    }

    private class GetPlatformInfoFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment): SkyValue? {
            val key = skyKey as GetPlatformInfoKey
            try {
                val platforms: MutableMap<ConfiguredTargetKey?, PlatformInfo?>? =
                    PlatformLookupUtil.getPlatformInfo(key.platformKeys, env)
                if (env.valuesMissing()) {
                    return null
                }
                return GetPlatformInfoValue.Companion.create(platforms)
            } catch (e: InvalidPlatformException) {
                throw GetPlatformInfoFunctionException(e)
            }
        }
    }

    private class GetPlatformInfoFunctionException(e: InvalidPlatformException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        // Calls PlatformLookupUtil.getPlatformInfo.
        private val GET_PLATFORM_INFO_FUNCTION: SkyFunctionName? =
            SkyFunctionName.createHermetic("GET_PLATFORM_INFO_FUNCTION")
    }
}
