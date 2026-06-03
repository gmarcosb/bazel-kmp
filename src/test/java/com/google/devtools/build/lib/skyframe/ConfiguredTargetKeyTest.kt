// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions.MapBackedChecksumCache

@RunWith(TestParameterInjector::class)
class ConfiguredTargetKeyTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec(@TestParameter useSharedValues: Boolean) {
        val nullConfigKey: ConfiguredTargetKey =
            createKey( /* useNullConfig= */
                true,  /* isToolchainKey= */
                false,  /* shouldApplyRuleTransition= */
                true
            )
        val keyWithConfig: ConfiguredTargetKey =
            createKey( /* useNullConfig= */
                false,  /* isToolchainKey= */
                false,  /* shouldApplyRuleTransition= */
                true
            )
        val keyWithFinalConfig: ConfiguredTargetKey =
            createKey( /* useNullConfig= */
                false,  /* isToolchainKey= */
                false,  /* shouldApplyRuleTransition= */
                false
            )
        val toolchainKey: ConfiguredTargetKey =
            createKey( /* useNullConfig= */
                false,  /* isToolchainKey= */
                true,  /* shouldApplyRuleTransition= */
                true
            )
        val toolchainKeyWithFinalConfig: ConfiguredTargetKey =
            createKey( /* useNullConfig= */
                false,  /* isToolchainKey= */
                true,  /* shouldApplyRuleTransition= */
                false
            )

        val tester: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SerializationTester(
                nullConfigKey,
                keyWithConfig,
                keyWithFinalConfig,
                toolchainKey,
                toolchainKeyWithFinalConfig
            )
                .addDependency(OptionsChecksumCache::class.java, MapBackedChecksumCache())

        if (useSharedValues) {
            tester
                .addCodec(ConfiguredTargetKey.valueSharingCodec())
                .makeMemoizingAndAllowFutureBlocking(true)
        }

        tester.runTests()
    }

    private fun createKey(
        useNullConfig: Boolean, isToolchainKey: Boolean, shouldApplyRuleTransition: Boolean
    ): ConfiguredTargetKey {
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ConfiguredTargetKey.builder().setLabel(Label.parseCanonicalUnchecked("//p:key"))
        if (!useNullConfig) {
            key.setConfigurationKey(targetConfigKey)
        }
        if (isToolchainKey) {
            key.setExecutionPlatformLabel(Label.parseCanonicalUnchecked("//platforms:b"))
        }
        key.setShouldApplyRuleTransition(shouldApplyRuleTransition)
        return key.build()
    }
}
