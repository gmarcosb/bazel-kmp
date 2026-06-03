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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.skyframe.DependencyResolver.getDependencyContext

/**
 * Tests [ConfiguredTargetFunction]'s logic for determining each target toolchain context.
 * 
 * 
 * This is essentially an integration test for the toolchain part of [ ]. These methods form the core logic that figures out what a target's
 * toolchain dependencies are.
 * 
 * 
 * [ConfiguredTargetFunction] is a complicated class that does a lot of things. This test
 * focuses purely on the task of toolchain resolution. So instead of evaluating full [ ] instances, it evaluates a mock [SkyFunction] that just wraps the
 * [DependencyResolver.getDependencyContext] part. This keeps focus tight and integration
 * dependencies narrow.
 * 
 * 
 * We can't just call [ToolchainContextProducer] directly because that method needs a
 * [SkyFunction.Environment] and Blaze's test infrastructure doesn't support direct access to
 * environments.
 */
@RunWith(JUnit4::class)
class ToolchainsForTargetsTest : AnalysisTestCase() {
    /** Key class for [ComputeUnloadedToolchainContextsFunction].  */
    @AutoValue
    internal abstract class Key : SkyKey {
        abstract fun targetAndConfiguration(): TargetAndConfiguration?

        abstract fun configuredTargetKey(): ConfiguredTargetKey?

        public override fun functionName(): SkyFunctionName? {
            return ComputeUnloadedToolchainContextsFunction.Companion.SKYFUNCTION_NAME
        }
    }

    /**
     * Returns a [ ][<] as the result of [ ][DependencyResolver.getDependencyContext].
     */
    @AutoCodec
    internal class Value(toolchainCollection: ToolchainCollection<UnloadedToolchainContext?>?) : SkyValue {
        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?>?

        init {
            this.toolchainCollection = toolchainCollection
            java.util.Objects.requireNonNull<Any?>(toolchainCollection, "toolchainCollection")
        }

        companion object {
            fun create(toolchainCollection: ToolchainCollection<UnloadedToolchainContext?>?): Value {
                return com.google.devtools.build.lib.skyframe.toolchains.ToolchainsForTargetsTest.Value(
                    toolchainCollection
                )
            }
        }
    }

    /**
     * A mock [SkyFunction] that just calls [DependencyResolver.getDependencyContext] and
     * returns its results.
     */
    internal class ComputeUnloadedToolchainContextsFunction(private val stateProvider: LateBoundStateProvider) :
        SkyFunction {
        @Throws(ComputeUnloadedToolchainContextsException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey, env: Environment): SkyValue? {
            val key = skyKey.argument() as Key
            val state: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                env.getState(
                    { DependencyResolver.State.createForTesting(key.targetAndConfiguration()) })
            val result: DependencyContext?
            try {
                result =
                    getDependencyContext(
                        state,
                        key.configuredTargetKey(),
                        stateProvider.lateBoundRuleClassProvider(),
                        env,
                        env.getListener()
                    )
            } catch (e: ToolchainException) {
                throw ComputeUnloadedToolchainContextsException(e)
            } catch (e: ConfiguredValueCreationException) {
                throw ComputeUnloadedToolchainContextsException(e)
            } catch (e: IncompatibleTargetException) {
                throw ComputeUnloadedToolchainContextsException(e)
            } catch (e: DependencyEvaluationException) {
                throw ComputeUnloadedToolchainContextsException(e)
            } catch (e: ExecGroupCollection.InvalidExecGroupException) {
                throw ComputeUnloadedToolchainContextsException(e)
            }
            check(state.transitiveRootCauses().isEmpty()) {
                "expected empty: " + state.transitiveRootCauses().build().toList()
            }
            if (result == null) {
                return null
            }
            return com.google.devtools.build.lib.skyframe.toolchains.ToolchainsForTargetsTest.Value.Companion.create(
                result.unloadedToolchainContexts()
            )
        }

        private class ComputeUnloadedToolchainContextsException(cause: java.lang.Exception?) :
            SkyFunctionException(cause, Transience.PERSISTENT)

        companion object {
            val SKYFUNCTION_NAME: SkyFunctionName? = SkyFunctionName.createHermetic(
                "CONFIGURED_TARGET_FUNCTION_COMPUTE_UNLOADED_TOOLCHAIN_CONTEXTS"
            )
        }
    }

    /**
     * Provides build state to [ComputeUnloadedToolchainContextsFunction]. This needs to be
     * late-bound (i.e. we can't just pass the contents directly) because of the way [ ] works: the [AnalysisMock] instance that instantiates the function gets
     * created before the rest of the build state. See [AnalysisTestCase.createMocks] for
     * details.
     */
    private inner class LateBoundStateProvider {
        fun lateBoundRuleClassProvider(): RuleClassProvider? {
            return ruleClassProvider
        }
    }

    /**
     * An [AnalysisMock] that injects [ComputeUnloadedToolchainContextsFunction] into the
     * Skyframe executor.
     */
    private class AnalysisMockWithComputeDepsFunction(
        parent: AnalysisMock,
        private val stateProvider: LateBoundStateProvider
    ) : com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(parent) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(
                    ComputeUnloadedToolchainContextsFunction.Companion.SKYFUNCTION_NAME,
                    ComputeUnloadedToolchainContextsFunction(stateProvider)
                )
                .buildOrThrow()
        }
    }

    val analysisMock: AnalysisMock
        get() = AnalysisMockWithComputeDepsFunction(
            super.getAnalysisMock(), LateBoundStateProvider()
        )

    @Throws(java.lang.InterruptedException::class)
    fun getToolchainCollection(
        configuredTarget: ConfiguredTarget, configuredTargetKey: ConfiguredTargetKey?
    ): ToolchainCollection<UnloadedToolchainContext?> {
        val targetLabel: String? = configuredTarget.getOriginalLabel().toString()
        val key: SkyKey =
            key(
                TargetAndConfiguration(getTarget(targetLabel), getConfiguration(configuredTarget)),
                configuredTargetKey
            )
        // Analysis phase ended after the update() call in getToolchainCollection. We must re-enable
        // analysis so we can call ConfiguredTargetFunction again without raising an error.
        skyframeExecutor.getSkyframeBuildView().enableAnalysis(true)
        val evalResult: EvaluationResult<Value?> =
            SkyframeExecutorTestUtils.evaluate<SkyValue?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        // Test call has finished, to reset the state.
        skyframeExecutor.getSkyframeBuildView().enableAnalysis(false)
        return evalResult.get(key).toolchainCollection()
    }

    @Throws(java.lang.Exception::class)
    fun getToolchainCollection(targetLabel: String?): ToolchainCollection<UnloadedToolchainContext?> {
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update(targetLabel).getTargetsToBuild())
        return getToolchainCollection(
            target,
            ConfiguredTargetKey.builder()
                .setLabel(target.getOriginalLabel())
                .setConfigurationKey(target.getConfigurationKey())
                .build()
        )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createToolchains() {
        scratch.appendFile("MODULE.bazel", "register_toolchains('//toolchains:all')")

        scratch.file(
            "toolchain/toolchain_def.bzl",
            """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file("toolchain/BUILD", "toolchain_type(name = 'test_toolchain')")

        scratch.appendFile(
            "toolchains/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "toolchain_1",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_1_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "toolchain_1_impl",
            data = "foo",
        )

        toolchain(
            name = "toolchain_2",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_2_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "toolchain_2_impl",
            data = "bar",
        )
        
        """.trimIndent()
        )

        scratch.appendFile(
            "toolchain/rule.bzl",
            """
        def _impl(ctx):
            data = ctx.toolchains["//toolchain:test_toolchain"].data
            return [
                coverage_common.instrumented_files_info(
                    ctx,
                    source_attributes = ["srcs"],
                )
            ]

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
            },
            toolchains = ["//toolchain:test_toolchain"],
        )
        
        """.trimIndent()
        )
    }

    // actual tests
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicToolchains() {
        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        
        """.trimIndent()
        )

        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection("//a")
        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .hasResolvedToolchain("//toolchains:toolchain_1_impl")
        val toolchainType: Label? = Label.parseCanonicalUnchecked("//toolchain:test_toolchain")
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .toolchainTypes()
            .containsExactly(toolchainType, ToolchainTypeRequirement.create(toolchainType))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicToolchainsWithAliasAutoExecGroups() {
        scratch.file(
            "test/alias/BUILD",
            """
        alias(
            name = "alias_toolchain_type",
            actual = "//toolchain:test_toolchain",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            print(ctx.toolchains["//test/alias:alias_toolchain_type"])
            print(ctx.toolchains["//toolchain:test_toolchain"])
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        assertThat(update("//test:custom_rule_name").hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicToolchainsWithAliasNoAutoExecGroups() {
        scratch.file(
            "test/alias/BUILD",
            """
        alias(
            name = "alias_toolchain_type",
            actual = "//toolchain:test_toolchain",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            print(ctx.toolchains["//test/alias:alias_toolchain_type"])
            print(ctx.toolchains["//toolchain:test_toolchain"])
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
        )
        
        """.trimIndent()
        )
        useConfiguration("--noincompatible_auto_exec_groups")

        assertThat(update("//test:custom_rule_name").hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicToolchainsWithAliasNoAutoExecGroups_test() {
        scratch.appendFile(
            "toolchain/exec_group_rule.bzl",
            """
        def _impl(ctx):
            if "//toolchain:test_toolchain" in ctx.toolchains:
                fail("this is not expected, it's an exec gp toolchain")
            if ctx.exec_groups["temp"].toolchains["//toolchain:test_toolchain"] == None:
                fail("this is not expected, it's an exec gp toolchain")
            return []

        my_exec_group_rule = rule(
            implementation = _impl,
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        
        """.trimIndent()
        )

        useConfiguration("--incompatible_auto_exec_groups")

        assertThat(update("//a:a").hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execPlatform() {
        // Add some platforms and custom constraints.
        scratch.file("platforms/BUILD", "platform(name = 'local_platform_a')")

        // Test normal resolution, and with a per-target exec constraint.
        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        
        """.trimIndent()
        )

        useConfiguration("--extra_execution_platforms=//platforms:local_platform_a")

        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection("//a")
        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()
        assertThat(toolchainCollection)
            .defaultToolchainContext() // First execution platform will be used.
            .hasExecutionPlatform("//platforms:local_platform_a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execPlatform_withExecConstraint() {
        // Add some platforms and custom constraints.
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )

        // Test normal resolution, and with a per-target exec constraint.
        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(
            name = "a",
            exec_compatible_with = ["//platforms:local_value_b"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )

        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection("//a")
        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()
        assertThat(toolchainCollection)
            .defaultToolchainContext() // Exec constraint forces the use of this exec platform.
            .hasExecutionPlatform("//platforms:local_platform_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execGroups_named() {
        // Write a rule with exec groups.
        scratch.appendFile(
            "toolchain/exec_group_rule.bzl",
            """
        def _impl(ctx):
            pass

        my_exec_group_rule = rule(
            implementation = _impl,
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        
        """.trimIndent()
        )

        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection("//a")
        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()
        assertThat(toolchainCollection).defaultToolchainContext().toolchainTypes().isEmpty()
        assertThat(toolchainCollection).defaultToolchainContext().resolvedToolchainLabels().isEmpty()

        assertThat(toolchainCollection).hasExecGroup("temp")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasResolvedToolchain("//toolchains:toolchain_1_impl")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasResolvedToolchain("//toolchains:toolchain_1_impl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execGroups_defaultAndNamed() {
        // Add another toolchain type.
        scratch.appendFile(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain_type(name = "extra_toolchain")

        toolchain(
            name = "toolchain",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_impl",
            toolchain_type = ":extra_toolchain",
        )

        test_toolchain(
            name = "toolchain_impl",
            data = "foo",
        )
        
        """.trimIndent()
        )

        // Write a rule with exec groups.
        scratch.appendFile(
            "toolchain/exec_group_rule.bzl",
            """
        def _impl(ctx):
            pass

        my_exec_group_rule = rule(
            implementation = _impl,
            toolchains = ["//extra:extra_toolchain"],
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        
        """.trimIndent()
        )

        useConfiguration("--extra_toolchains=//extra:toolchain")
        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection("//a")
        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .hasToolchainType("//extra:extra_toolchain")
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .hasResolvedToolchain("//extra:toolchain_impl")

        assertThat(toolchainCollection).hasExecGroup("temp")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainCollection)
            .execGroup("temp")
            .hasResolvedToolchain("//toolchains:toolchain_1_impl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun keepParentToolchainContext() {
        // Add some platforms and custom constraints.
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )

        // Test normal resolution, and with a per-target exec constraint.
        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )

        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//a").getTargetsToBuild())
        val toolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            getToolchainCollection(
                target,
                ConfiguredTargetKey.builder()
                    .setLabel(target.getOriginalLabel())
                    .setConfigurationKey(target.getConfigurationKey())
                    .setExecutionPlatformLabel(
                        Label.parseCanonicalUnchecked("//platforms:local_platform_b")
                    )
                    .build()
            )

        assertThat(toolchainCollection).isNotNull()
        assertThat(toolchainCollection).hasDefaultExecGroup()

        // This should have the same exec platform as parentToolchainKey, which is local_platform_b.
        assertThat(toolchainCollection)
            .defaultToolchainContext()
            .hasExecutionPlatform("//platforms:local_platform_b")
    }

    /** Regression test for b/214105142, https://github.com/bazelbuild/bazel/issues/14521  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainWithDifferentExecutionPlatforms_doesNotGenerateConflictingCoverageAction() {
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(
            name = "a",
            srcs = ["a.c"],
            exec_compatible_with = ["//platforms:local_value_a"],
        )

        my_rule(
            name = "b",
            srcs = ["b.c"],
            exec_compatible_with = ["//platforms:local_value_b"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--collect_code_coverage",
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )

        update("//a:a", "//a:b")

        // Sanity check that a coverage action was generated for the rule itself.
        assertHasBaselineCoverageAction("//a:a", "Writing file a/a/baseline_coverage.dat")
        assertHasBaselineCoverageAction("//a:b", "Writing file a/b/baseline_coverage.dat")
        Truth.assertThat(getActions("//toolchains:toolchain_1_impl")).isEmpty()
        val toolchainAContext: ToolchainContext? =
            getToolchainCollection("//a:a").getDefaultToolchainContext()
        assertThat(toolchainAContext).hasExecutionPlatform("//platforms:local_platform_a")
        assertThat(toolchainAContext).hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainAContext).hasResolvedToolchain("//toolchains:toolchain_1_impl")
        val toolchainBContext: ToolchainContext? =
            getToolchainCollection("//a:b").getDefaultToolchainContext()
        assertThat(toolchainBContext).hasExecutionPlatform("//platforms:local_platform_b")
        assertThat(toolchainBContext).hasToolchainType("//toolchain:test_toolchain")
        assertThat(toolchainBContext).hasResolvedToolchain("//toolchains:toolchain_1_impl")
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun updateExplicitTarget(label: String?): AnalysisResult {
        return update(
            com.google.common.eventbus.EventBus(),
            defaultFlags().with(com.google.devtools.build.lib.analysis.util.AnalysisTestCase.Flag.KEEP_GOING),  /* explicitTargetPatterns= */
            com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonicalUnchecked(label)),  /* aspects= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* aspectsParameters= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            label
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCompatibleWith_matchesExecCompatibleWith() {
        this.analysisMock
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withToolchainTargetConstraints("@@//platforms:local_value_a")
                    .withToolchainExecConstraints()
                    .withCpu("fake")
            )
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.sh"],
            target_compatible_with = ["//platforms:local_value_b"],
        )

        genrule(
            name = "runtool",
            outs = ["b.txt"],
            cmd = "",
            exec_compatible_with = ["//platforms:local_value_b"],
            tools = [":tool"],
        )
        
        """.trimIndent()
        )

        val result: AnalysisResult = updateExplicitTarget("//foo:runtool")

        assertThat(result.hasError()).isFalse()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCompatibleWith_mismatchesExecCompatibleWith() {
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.sh"],
            target_compatible_with = ["//platforms:local_value_a"],
        )

        genrule(
            name = "runtool",
            outs = ["b.txt"],
            cmd = "",
            exec_compatible_with = ["//platforms:local_value_b"],
            tools = [":tool"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val result: AnalysisResult = updateExplicitTarget("//foo:runtool")

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Target //foo:runtool is incompatible and cannot be built, but was explicitly requested"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCompatibleWith_mismatchesExecCompatibleInDifferentPackage() {
        // Regression test for a case where incompatibility happens in an aspect tool in a different
        // package over a Starlark target with
        // --incompatible_visibility_private_attributes_at_definition
        // The tool is replaced with a fake target {@link
        // IncommpatibleTargetChecker#createIncompatibleRuleConfiguredTarget)
        // and it's verified if the tool is visible to the Starlark target.
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )
        scratch.file(
            "foo/lib.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"_my_tool": attr.label(default = "//tool")},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "tool/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_a"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":lib.bzl", "my_rule")

        my_rule(name = "target_in_different_package")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val result: AnalysisResult = updateExplicitTarget("//foo:target_in_different_package")

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Target //foo:target_in_different_package is incompatible and cannot be built, but was"
                    + " explicitly requested"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCompatibleWith_mismatchesExecCompatibleDepInDifferentPackage() {
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )
        scratch.file(
            "foo/lib.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"_my_tool": attr.label(default = "//tool")},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "tool/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_a"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":lib.bzl", "my_rule")

        my_rule(name = "dep")

        filegroup(
            name = "target_with_dep",
            srcs = [":dep"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val result: AnalysisResult = updateExplicitTarget("//foo:target_with_dep")

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Target //foo:target_with_dep is incompatible and cannot be built, but was"
                    + " explicitly requested"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetCompatibleWith_mismatchesExecCompatibleWithinAspect() {
        // Regression test for a case where incompatibility happens in an aspect tool in a different
        // package over a Starlark target with
        // --incompatible_visibility_private_attributes_at_definition
        // The tool is replaced with a fake target {@link
        // IncommpatibleTargetChecker#createIncompatibleRuleConfiguredTarget)
        // and it's verified if the tool is visible to the Starlark target the aspect is over.
        scratch.file(
            "platforms/BUILD",
            """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b"
        )
        scratch.file(
            "foo/lib.bzl",
            """
        def _impl_aspect(ctx, target):
            return []

        my_aspect = aspect(
            _impl_aspect,
            attrs = {"_my_tool": attr.label(default = "//tool")},
            exec_compatible_with = ["//platforms:local_value_a"],
        )

        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        simple_starlark_rule = rule(
            _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "tool/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_b"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":lib.bzl", "my_rule", "simple_starlark_rule")

        simple_starlark_rule(name = "simple_dep")

        my_rule(
            name = "target_with_aspect",
            deps = [":simple_dep"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val result: AnalysisResult = updateExplicitTarget("//foo:target_with_aspect")

        // TODO(bazel-team): This should report an error similarly to {@code
        // #targetCompatibleWith_mismatchesExecCompatibleDepInDifferentPackage}
        assertThat(result.hasError()).isFalse()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun assertHasBaselineCoverageAction(label: String?, progressMessage: String?) {
        val coverageAction: Action? = com.google.common.collect.Iterables.getOnlyElement<Action?>(getActions(label))
        assertThat(coverageAction).isInstanceOf(BaselineCoverageAction::class.java)
        assertThat(coverageAction.getProgressMessage()).isEqualTo(progressMessage)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getActions(label: String?): com.google.common.collect.ImmutableList<Action?> {
        return (getConfiguredTarget(label) as RuleConfiguredTarget)
            .getActions().stream().map({ obj: Any? -> Action::class.java.cast(obj) })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    companion object {
        /** Returns a [SkyKey] for a given <Target></Target>, BuildConfigurationValue> pair.  */
        private fun key(
            targetAndConfiguration: TargetAndConfiguration?, configuredTargetKey: ConfiguredTargetKey?
        ): Key {
            return AutoValue_ToolchainsForTargetsTest_Key(targetAndConfiguration, configuredTargetKey)
        }
    }
}
