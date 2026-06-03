// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.ConfigurationMakeVariableContext

/** Tests for calculating the sysroot that require building configured targets.  */
@RunWith(JUnit4::class)
class CppSysrootTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun writeDummyLibrary() {
        scratch.file(
            "dummy/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='library')"
        )
    }

    /**
     * Supply CC_FLAGS Make variable value computed from FeatureConfiguration. Appends them to
     * original CC_FLAGS, so FeatureConfiguration can override legacy values.
     */
    class CcFlagsSupplier(ruleContext: RuleContext?) : MakeVariableSupplier {
        private val ruleContext: RuleContext

        init {
            this.ruleContext = com.google.common.base.Preconditions.checkNotNull<RuleContext>(ruleContext)
        }

        @Throws(ExpansionException::class)
        public override fun getMakeVariable(variableName: String): String? {
            if (variableName != CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME) {
                return null
            }

            try {
                val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
                return computeCcFlags(ruleContext, toolchain)
            } catch (e: RuleErrorException) {
                throw ExpansionException(e.getMessage())
            } catch (e: net.starlark.java.eval.EvalException) {
                throw ExpansionException(e.getMessage())
            }
        }

        @get:Throws(ExpansionException::class)
        val allMakeVariables: com.google.common.collect.ImmutableMap<String?, String?>
            get() = com.google.common.collect.ImmutableMap.of<String?, String?>(
                CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME,
                getMakeVariable(CppConfiguration.CC_FLAGS_MAKE_VARIABLE_NAME)
            )

        companion object {
            /**
             * Computes the appropriate value of the `$(CC_FLAGS)` Make variable based on the given
             * toolchain.
             */
            @Throws(RuleErrorException::class, net.starlark.java.eval.EvalException::class)
            fun computeCcFlags(
                ruleContext: RuleContext, toolchainProvider: CcToolchainProvider
            ): String {
                // Determine the original value of CC_FLAGS.

                val originalCcFlags: String = toolchainProvider.getLegacyCcFlagsMakeVariable()
                var sysrootCcFlags = ""
                if (toolchainProvider.getSysrootPathFragment() != null) {
                    sysrootCcFlags = SYSROOT_FLAG + toolchainProvider.getSysrootPathFragment()
                }

                // Fetch additional flags from the FeatureConfiguration.
                val featureConfigCcFlags =
                    computeCcFlagsFromFeatureConfig(ruleContext, toolchainProvider)

                // Combine the different flag sources.
                val ccFlags: com.google.common.collect.ImmutableList.Builder<String?> =
                    com.google.common.collect.ImmutableList.Builder<String?>()
                ccFlags.add(originalCcFlags)

                // Only add the sysroot flag if nothing else adds sysroot, _but_ it must appear before
                // the feature config flags.
                if (!containsSysroot(originalCcFlags, featureConfigCcFlags)) {
                    ccFlags.add(sysrootCcFlags)
                }

                ccFlags.addAll(featureConfigCcFlags)
                return com.google.common.base.Joiner.on(" ").join(ccFlags.build())
            }

            private fun containsSysroot(ccFlags: String?, moreCcFlags: MutableList<String?>): Boolean {
                return java.util.stream.Stream.concat<String?>(
                    java.util.stream.Stream.of<String?>(ccFlags),
                    moreCcFlags.stream()
                )
                    .anyMatch { str: String? -> str.contains(SYSROOT_FLAG) }
            }

            @Throws(RuleErrorException::class)
            private fun computeCcFlagsFromFeatureConfig(
                ruleContext: RuleContext, toolchainProvider: CcToolchainProvider
            ): MutableList<String?> {
                var featureConfiguration: FeatureConfiguration? = null
                val cppConfiguration: CppConfiguration?
                cppConfiguration = ruleContext.getFragment(CppConfiguration::class.java)
                try {
                    featureConfiguration =
                        CcCommon.configureFeaturesOrThrowEvalException(
                            ruleContext.getFeatures(),
                            ruleContext.getDisabledFeatures(),
                            Language.CPP,
                            toolchainProvider,
                            cppConfiguration
                        )
                } catch (e: net.starlark.java.eval.EvalException) {
                    ruleContext.ruleError(e.message)
                }
                if (featureConfiguration.actionIsConfigured(CppActionNames.CC_FLAGS_MAKE_VARIABLE)) {
                    try {
                        val buildVariables: CcToolchainVariables? = toolchainProvider.getBuildVars()
                        return com.google.common.collect.ImmutableList.copyOf(
                            featureConfiguration.getCommandLine(
                                CppActionNames.CC_FLAGS_MAKE_VARIABLE, buildVariables
                            )
                        )
                    } catch (e: net.starlark.java.eval.EvalException) {
                        throw RuleErrorException(e.message)
                    }
                }
                return com.google.common.collect.ImmutableList.of<String?>()
            }
        }
    }

    @Throws(java.lang.Exception::class)
    fun testCCFlagsContainsSysroot(
        config: BuildConfigurationValue?, sysroot: String?, shouldContain: Boolean
    ) {
        val ruleContext: RuleContext =
            getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), config))
        val context: ConfigurationMakeVariableContext =
            ConfigurationMakeVariableContext(
                ruleContext.getTarget().getPackageDeclarations(),
                config,
                ruleContext.getDefaultTemplateVariableProviders(),
                com.google.common.collect.ImmutableList.of<E?>(CcFlagsSupplier(ruleContext))
            )
        if (shouldContain) {
            com.google.common.truth.Subject.contains("--sysroot=" + sysroot)
        } else {
            assertThat(context.lookupVariable("CC_FLAGS")).doesNotContain("--sysroot=" + sysroot)
        }
    }

    @Throws(java.lang.Exception::class)
    fun getCcToolchainProvider(configuration: BuildConfigurationValue?): CcToolchainProvider {
        // use dummy library to get C++ toolchain from toolchain resolution
        val ruleContext: RuleContext =
            getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), configuration))
        return com.google.common.base.Preconditions.checkNotNull<T>(CppHelper.getToolchain(ruleContext))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostGrteTop() {
        scratch.file(
            "a/grte/top/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(name = "everything")

        cc_library(name = "library")
        
        """.trimIndent()
        )
        useConfiguration("--host_grte_top=//a/grte/top")
        val target: BuildConfigurationValue = targetConfiguration
        val targetCcProvider: CcToolchainProvider = getCcToolchainProvider(target)
        val exec: BuildConfigurationValue = execConfiguration
        val hostCcProvider: CcToolchainProvider = getCcToolchainProvider(exec)

        testCCFlagsContainsSysroot(exec, "a/grte/top", true)
        assertThat(hostCcProvider.getSysroot().equals(targetCcProvider.getSysroot())).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverrideHostGrteTop() {
        scratch.file("a/grte/top/BUILD", "filegroup(name='everything')")
        scratch.file("b/grte/top/BUILD", "filegroup(name='everything')")
        useConfiguration("--grte_top=//a/grte/top", "--host_grte_top=//b/grte/top")
        val target: BuildConfigurationValue = targetConfiguration
        val targetCcProvider: CcToolchainProvider = getCcToolchainProvider(target)
        val exec: BuildConfigurationValue = execConfiguration
        val hostCcProvider: CcToolchainProvider = getCcToolchainProvider(exec)

        assertThat(targetCcProvider.getSysroot()).isEqualTo("a/grte/top")
        assertThat(hostCcProvider.getSysroot()).isEqualTo("b/grte/top")

        testCCFlagsContainsSysroot(target, "a/grte/top", true)
        testCCFlagsContainsSysroot(target, "b/grte/top", false)
        testCCFlagsContainsSysroot(exec, "b/grte/top", true)
        testCCFlagsContainsSysroot(exec, "a/grte/top", false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGrteTopAlias() {
        scratch.file("a/grte/top/BUILD", "filegroup(name='everything')")
        scratch.file("b/grte/top/BUILD", "alias(name='everything', actual='//a/grte/top:everything')")
        useConfiguration("--grte_top=//b/grte/top")
        val target: BuildConfigurationValue = targetConfiguration
        val targetCcProvider: CcToolchainProvider = getCcToolchainProvider(target)

        assertThat(targetCcProvider.getSysroot()).isEqualTo("a/grte/top")

        testCCFlagsContainsSysroot(target, "a/grte/top", true)
        testCCFlagsContainsSysroot(target, "b/grte/top", false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSysroot() {
        // BuildConfigurationValue shouldn't provide a sysroot option by default.
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        var config: BuildConfigurationValue = targetConfiguration
        testCCFlagsContainsSysroot(config, "/usr/grte/v1", true)

        scratch.file("a/grte/top/BUILD", "filegroup(name='everything')")
        // BuildConfigurationValue should work with label grte_top options.
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL, "--grte_top=//a/grte/top:everything"
        )
        config = targetConfiguration
        testCCFlagsContainsSysroot(config, "a/grte/top", true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSysrootInFeatureConfigBlocksLegacySysroot() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withActionConfigs("sysroot_in_action_config")
            )
        scratch.overwriteFile("a/grte/top/BUILD", "filegroup(name='everything')")
        useConfiguration("--grte_top=//a/grte/top:everything")
        val ruleContext: RuleContext =
            getRuleContext(getConfiguredTarget(Label.parseCanonical("//dummy:library"), targetConfig))
        val context: ConfigurationMakeVariableContext =
            ConfigurationMakeVariableContext(
                ruleContext.getTarget().getPackageDeclarations(),
                targetConfig,
                ruleContext.getDefaultTemplateVariableProviders(),
                com.google.common.collect.ImmutableList.of<E?>(CcFlagsSupplier(ruleContext))
            )
        com.google.common.truth.Subject.contains("fc-start --sysroot=a/grte/top-from-feature fc-end")
        assertThat(context.lookupVariable("CC_FLAGS")).doesNotContain("--sysroot=a/grte/top fc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSysrootWithExecConfig() {
        // The exec BuildConfigurationValue shouldn't provide a sysroot option by default.
        for (platform in arrayOf<String>(TestConstants.PLATFORM_LABEL, TestConstants.PIII_PLATFORM_LABEL)) {
            useConfiguration("--platforms=" + platform)
            val config: BuildConfigurationValue = execConfiguration
            testCCFlagsContainsSysroot(config, "/usr/grte/v1", true)
        }
        // The exec BuildConfigurationValue should work with label grte_top options.
        scratch.file("a/grte/top/BUILD", "filegroup(name='everything')")
        for (platform in arrayOf<String>(TestConstants.PLATFORM_LABEL, TestConstants.PIII_PLATFORM_LABEL)) {
            useConfiguration("--platforms=" + platform, "--host_grte_top=//a/grte/top")
            var config: BuildConfigurationValue = execConfiguration
            testCCFlagsContainsSysroot(config, "a/grte/top", true)

            // "--grte_top" does *not* set the exec grte_top,
            // so we don't get "a/grte/top" here, but instead the default "/usr/grte/v1"
            useConfiguration("--platforms=" + platform, "--grte_top=//a/grte/top")
            config = execConfiguration
            testCCFlagsContainsSysroot(config, "/usr/grte/v1", true)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurableSysroot() {
        scratch.file(
            "test/config_setting/BUILD",
            "config_setting(name='defines', values={'define': 'override_grte_top=1'})"
        )
        scratch.file("a/grte/top/BUILD", "filegroup(name='everything')")
        scratch.file("b/grte/top/BUILD", "filegroup(name='everything')")
        scratch.file(
            "c/grte/top/BUILD",
            """
        alias(
            name = "everything",
            actual = select(
                {
                    "//test/config_setting:defines": "//a/grte/top:everything",
                    "//conditions:default": "//b/grte/top:everything",
                },
            ),
        )
        
        """.trimIndent()
        )
        useConfiguration("--grte_top=//c/grte/top:everything")
        var ccProvider: CcToolchainProvider = getCcToolchainProvider(targetConfiguration)
        assertThat(ccProvider.getSysroot()).isEqualTo("b/grte/top")

        useConfiguration("--grte_top=//c/grte/top:everything", "--define=override_grte_top=1")
        ccProvider = getCcToolchainProvider(targetConfiguration)
        assertThat(ccProvider.getSysroot()).isEqualTo("a/grte/top")
    }

    companion object {
        private const val SYSROOT_FLAG = "--sysroot="
    }
}
