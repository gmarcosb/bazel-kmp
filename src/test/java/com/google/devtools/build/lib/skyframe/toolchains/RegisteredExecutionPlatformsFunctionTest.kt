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

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey

/**
 * Tests for [RegisteredExecutionPlatformsFunction] and [ ].
 */
@RunWith(JUnit4::class)
class RegisteredExecutionPlatformsFunctionTest : ToolchainTestCase() {
    @Throws(java.lang.InterruptedException::class)
    protected fun requestExecutionPlatformsFromSkyframe(executionPlatformsKey: SkyKey?): EvaluationResult<RegisteredExecutionPlatformsValue?> {
        try {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true)
            return SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), executionPlatformsKey,  /*keepGoing=*/false, reporter
            )
        } finally {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms() {
        // Request the executionPlatforms.
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey)
            .isNotNull()

        val value: RegisteredExecutionPlatformsValue = result.get(executionPlatformsKey)
        assertThat(value.registeredExecutionPlatformKeys()).isEmpty()
        assertThat(value.rejectedPlatforms()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_flagOverride() {
        // Add an extra execution platform.

        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:execution_platform_2")
        
        """.trimIndent()
        )
        useConfiguration("--extra_execution_platforms=//extra:execution_platform_1")

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_execution_platforms flag is first in the
        // list.
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_flagOverride_multiple() {
        // Add an extra execution platform.

        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//extra:execution_platform_1,//extra:execution_platform_2"
        )

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_execution_platforms flag is first in the
        // list.
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_targetPattern_workspace() {
        // Add an extra execution platform.

        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra/...")
        
        """.trimIndent()
        )

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_execution_platforms flag is first in the
        // list.
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_aliased() {
        // Add an extra execution platform.
        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )
        scratch.file(
            "alias/BUILD",
            """
        alias(name = "alias_platform_1", actual = "//extra:execution_platform_1");

        alias(name = "alias_platform_2", actual = "//extra:execution_platform_2");
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//alias/...")
        
        """.trimIndent()
        )

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that aliases were resolved to actual targets.
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_targetPattern_mixed() {
        // Add several targets, some of which are not actually platforms.

        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")

        filegroup(name = "not_an_execution_platform")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:all")
        
        """.trimIndent()
        )

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // There should only be two execution platforms registered from //extra.
        // Verify that the target registered with the extra_execution_platforms flag is first in the
        // list.
        assertExecutionPlatformLabels(
            result.get(executionPlatformsKey), PackageIdentifier.createInMainRepo("extra")
        )
            .containsExactly(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_targetPattern_flagOverride() {
        // Add an extra execution platform.

        scratch.file(
            "extra/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )

        useConfiguration("--extra_execution_platforms=//extra/...")

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_execution_platforms flag is first in the
        // list.
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
                Label.parseCanonicalUnchecked("//extra:execution_platform_2")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_notExecutionPlatform() {
        rewriteModuleDotBazel(
            """
        register_execution_platforms("//error:not_an_execution_platform")
        
        """.trimIndent()
        )
        // Have to use a rule that doesn't require a target platform, or else there will be a cycle.
        scratch.file(
            "error/BUILD",
            """
        toolchain_type(name = "not_an_execution_platform")
        
        """.trimIndent()
        )

        // Request the executionPlatforms.
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(executionPlatformsKey)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(executionPlatformsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//error:not_an_execution_platform")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_reload() {
        scratch.overwriteFile(
            "platform/BUILD",
            """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//platform:execution_platform_1")
        
        """.trimIndent()
        )

        var executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        var result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .contains(Label.parseCanonicalUnchecked("//platform:execution_platform_1"))

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//platform:execution_platform_2")
        
        """.trimIndent()
        )

        executionPlatformsKey =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        result = requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .contains(Label.parseCanonicalUnchecked("//platform:execution_platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_bzlmod() {
        scratch.overwriteFile(
            "MODULE.bazel",
            """
        register_execution_platforms("//:plat")
        register_execution_platforms("//:dev_plat", dev_dependency = True)
        bazel_dep(name = "bbb", version = "1.0")
        bazel_dep(name = "ccc", version = "1.1")
        
        """.trimIndent()
        )
        registry
            .addModule(
                createModuleKey("bbb", "1.0"),
                """
            module(name = "bbb", version = "1.0")
            register_execution_platforms("//:plat")
            register_execution_platforms("//:dev_plat", dev_dependency = True)
            bazel_dep(name = "ddd", version = "1.0")
            
            """.trimIndent()
            )
            .addModule(
                createModuleKey("ccc", "1.1"),
                """
            module(name = "ccc", version = "1.1")
            register_execution_platforms("//:plat")
            register_execution_platforms("//:dev_plat", dev_dependency = True)
            bazel_dep(name = "ddd", version = "1.1")
            
            """.trimIndent()
            ) // ddd@1.0 is not selected
            .addModule(
                createModuleKey("ddd", "1.0"),
                """
            module(name = "ddd", version = "1.0")
            register_execution_platforms('//:plat')
            
            """.trimIndent()
            )
            .addModule(
                createModuleKey("ddd", "1.1"),
                """
            module(name = "ddd", version = "1.1")
            register_execution_platforms("@eee//:plat", "//:plat")
            bazel_dep(name = "eee", version = "1.0")
            
            """.trimIndent()
            )
            .addModule(
                createModuleKey("eee", "1.0"),
                """
            module(name = "eee", version = "1.0")
            
            """.trimIndent()
            )
        for (repo in com.google.common.collect.ImmutableList.of<String?>(
            "bbb+1.0",
            "ccc+1.1",
            "ddd+1.0",
            "ddd+1.1",
            "eee+1.0"
        )) {
            scratch.file(moduleRoot.getRelative(repo).getRelative("REPO.bazel").getPathString())
            scratch.file(
                moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(),
                """
          platform(name = "plat")
          
          """.trimIndent()
            )
        }
        scratch.overwriteFile(
            "BUILD",
            """
        platform(name = "plat")
        platform(name = "dev_plat")
        
        """.trimIndent()
        )
        invalidatePackages()

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        if (result.hasError()) {
            throw result.getError().getException()
        }
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the execution platforms registered with bzlmod come in the BFS order
        assertExecutionPlatformLabels(result.get(executionPlatformsKey))
            .containsExactly( // Root module platforms
                Label.parseCanonical("//:plat"),
                Label.parseCanonical("//:dev_plat"),  // Other modules' toolchains
                Label.parseCanonical("@@bbb+//:plat"),
                Label.parseCanonical("@@ccc+//:plat"),
                Label.parseCanonical("@@eee+//:plat"),
                Label.parseCanonical("@@ddd+//:plat")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(ConstraintCollection.DuplicateConstraintException::class)
    fun testRegisteredExecutionPlatformsValue_equalsAndHashCode() {
        val executionPlatformKey1: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//test:executionPlatform1"))
                .setConfigurationKey(null)
                .build()
        val executionPlatformKey2: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//test:executionPlatform2"))
                .setConfigurationKey(null)
                .build()

        EqualsTester()
            .addEqualityGroup( // Two platforms registered.
                RegisteredExecutionPlatformsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        executionPlatformKey1,
                        executionPlatformKey2
                    ),  /* rejectedPlatforms= */
                    null
                ),
                RegisteredExecutionPlatformsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        executionPlatformKey1,
                        executionPlatformKey2
                    ),  /* rejectedPlatforms= */
                    null
                )
            )
            .addEqualityGroup( // A single platform registered.
                RegisteredExecutionPlatformsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(executionPlatformKey1),  /* rejectedPlatforms= */null
                )
            )
            .addEqualityGroup( // A single, different, platform registered.
                RegisteredExecutionPlatformsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(executionPlatformKey2),  /* rejectedPlatforms= */null
                )
            )
            .addEqualityGroup( // The same as the first group, but the order is different.
                RegisteredExecutionPlatformsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        executionPlatformKey2,
                        executionPlatformKey1
                    ),  /* rejectedPlatforms= */
                    null
                )
            )
            .testEquals()
    }

    /*
   * Regression test for https://github.com/bazelbuild/bazel/issues/10101.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidExecutionPlatformLabelDoesntCrash() {
        rewriteModuleDotBazel(
            """
        register_execution_platforms("//test:bad_exec_platform_label")
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "g",
            srcs = [],
            outs = ["g.out"],
            cmd = "echo hi > ${'$'}@",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            "invalid registered execution platform '//test:bad_exec_platform_label': "
                    + "no such target '//test:bad_exec_platform_label'",
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.collect.ImmutableList.of<E?>("//test:g"),  /*keepGoing=*/
                    false,  /*loadingPhaseThreads=*/
                    1,  /*doAnalysis=*/
                    true,
                    eventBus
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_requiredSettings_enabled() {
        // Add an extra platform with a required_setting
        scratch.file(
            "extra/BUILD",
            """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=opt")
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey)
            .isNotNull()

        val value: RegisteredExecutionPlatformsValue = result.get(executionPlatformsKey)

        // Both platforms should be present because the required settings match.
        assertExecutionPlatformLabels(value)
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:required_platform"),
                Label.parseCanonicalUnchecked("//extra:always_platform")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_requiredSettings_disabled() {
        // Add an extra platform with a required_setting
        scratch.file(
            "extra/BUILD",
            """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=dbg")
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey)
            .isNotNull()

        val value: RegisteredExecutionPlatformsValue = result.get(executionPlatformsKey)

        // The platform with required settings should not be present.
        assertExecutionPlatformLabels(value)
            .contains(Label.parseCanonicalUnchecked("//extra:always_platform"))
        assertExecutionPlatformLabels(value)
            .doesNotContain(Label.parseCanonicalUnchecked("//extra:required_platform"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_requiredSettings_debug() {
        // Add an extra platform with a required_setting
        scratch.file(
            "extra/BUILD",
            """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        
        """.trimIndent()
        )

        useConfiguration("--compilation_mode=dbg")
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */true)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey)
            .isNotNull()

        val value: RegisteredExecutionPlatformsValue = result.get(executionPlatformsKey)

        // Verify that the message about the unmatched config_setting is present.
        assertThat(value.rejectedPlatforms()).isNotNull()
        assertThat(value.rejectedPlatforms())
            .containsEntry(
                Label.parseCanonicalUnchecked("//extra:required_platform"),
                "mismatching required_settings: optimized"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_requiredSettings_config_error() {
        // Add an extra platform with a required_setting
        scratch.file(
            "extra/BUILD",
            """
        config_setting(
            name = "flagged",
            flag_values = {":flag": "default"},
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":flagged",
            ],
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:required_platform")
        
        """.trimIndent()
        )

        // Need this so the feature flag is actually gone from the configuration.
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(executionPlatformsKey)
            .isNotNull()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(executionPlatformsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                ("Unrecoverable errors resolving config_setting associated with"
                        + " //extra:required_platform: For config_setting flagged: Feature flag"
                        + " //extra:flag was accessed in a configuration it is not present in.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredExecutionPlatforms_requiredSettings_cantDependOnConstraintValues_error() {
        // Add an extra platform with a required_setting
        scratch.file(
            "extra/BUILD",
            """
        constraint_setting(name = "cs1")
        constraint_value(name = "cv1", constraint_setting = ":cs1")
        constraint_value(name = "cv2", constraint_setting = ":cs1")
        config_setting(
            name = "setting",
            constraint_values = [":cv1"],
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":setting",
                ":cv2",
            ],
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_execution_platforms("//extra:required_platform")
        
        """.trimIndent()
        )

        val executionPlatformsKey: SkyKey? =
            RegisteredExecutionPlatformsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredExecutionPlatformsValue?> =
            requestExecutionPlatformsFromSkyframe(executionPlatformsKey)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasErrorEntryForKeyThat(executionPlatformsKey)
            .isNotNull()
    }

    companion object {
        protected fun assertExecutionPlatformLabels(
            registeredExecutionPlatformsValue: RegisteredExecutionPlatformsValue
        ): IterableSubject {
            return assertExecutionPlatformLabels(registeredExecutionPlatformsValue, null)
        }

        protected fun assertExecutionPlatformLabels(
            registeredExecutionPlatformsValue: RegisteredExecutionPlatformsValue,
            packageRoot: PackageIdentifier?
        ): IterableSubject {
            assertThat(registeredExecutionPlatformsValue).isNotNull()
            val declaredExecutionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?> =
                registeredExecutionPlatformsValue.registeredExecutionPlatformKeys()
            val labels: MutableList<Label?> = collectExecutionPlatformLabels(declaredExecutionPlatformKeys, packageRoot)
            return Truth.assertThat(labels)
        }

        protected fun collectExecutionPlatformLabels(
            executionPlatformKeys: MutableList<ConfiguredTargetKey?>, packageRoot: PackageIdentifier?
        ): MutableList<Label?> {
            return executionPlatformKeys.stream()
                .map<Any?>(ConfiguredTargetKey::getLabel)
                .filter { label: Any? -> filterLabel(packageRoot, label) }
                .collect(Collectors.toList())
        }
    }
}
