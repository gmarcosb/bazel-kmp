// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData.SPLIT_DEP_ORDERING

/**
 * Tests [ConfiguredTargetFunction]'s logic for determining each target's [ ].
 * 
 * 
 * This is essentially an integration test for [DependencyResolver.computeDependencies] and
 * [DependencyResolutionHelpers]. These methods form the core logic that figures out what a
 * target's deps are, how their configurations should differ from their parent, and how to
 * instantiate those configurations as tangible [BuildConfigurationValue] objects.
 * 
 * 
 * [ConfiguredTargetFunction] is a complicated class that does a lot of things. This test
 * focuses purely on the task of determining configurations for deps. So instead of evaluating full
 * [ConfiguredTargetFunction] instances, it evaluates a mock [SkyFunction] that just
 * wraps the [DependencyResolver.computeDependencies] part. This keeps focus tight and
 * integration dependencies narrow.
 * 
 * 
 * We can't just call [DependencyResolver.computeDependencies] directly because that method
 * needs a [SkyFunction.Environment] and Blaze's test infrastructure doesn't support direct
 * access to environments.
 */
@RunWith(JUnit4::class)
class ConfigurationsForTargetsTest : AnalysisTestCase() {
    /**
     * A mock [SkyFunction] that just calls [DependencyResolver.computeDependencies] and
     * returns its results.
     */
    private class ComputeDependenciesFunction(private val stateProvider: LateBoundStateProvider) : SkyFunction {
        // This is an ActionLookupKey to identify it as a "analysis object" from the point of view of
        // serialization testing frameworks. See b/355401678 for more information.
        private class Key(arg: TargetAndConfiguration?) : AbstractSkyKey<TargetAndConfiguration?>(arg),
            ActionLookupKey {
            public override fun functionName(): SkyFunctionName? {
                return SKYFUNCTION_NAME
            }

            val configurationKey: BuildConfigurationKey?
                get() =// Technically unused, but needed to mark this key as an ActionLookupKey.
                    arg.getConfiguration().getKey()

            val label: Label?
                get() =// Technically unused, but needed to mark this key as an ActionLookupKey.
                    arg.getLabel()

            /**
             * A serialization-only codec to support test infrastructure.
             * 
             * 
             * Certain tests require the byte representation of keys without requiring those bytes to
             * be deserialized.
             */
            @com.google.errorprone.annotations.Keep
            private class Codec : DeferredObjectCodec<Key?>() {
                val encodedClass: java.lang.Class<Key?>
                    get() = com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.ComputeDependenciesFunction.Key::class.java

                @Throws(SerializationException::class, IOException::class)
                public override fun serialize(context: SerializationContext, key: Key, codedOut: CodedOutputStream?) {
                    context.serialize(key.label, codedOut)
                    context.serialize(key.configurationKey, codedOut)
                }

                public override fun deserializeDeferred(
                    context: AsyncDeserializationContext?, codedIn: CodedInputStream?
                ): DeferredValue<Key?>? {
                    throw java.lang.IllegalStateException("not expected to be called")
                }
            }
        }

        /** Returns an [OrderedSetMultimap] representing the deps of given target.  */
        internal class Value(depMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?) : SkyValue {
            var depMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?

            init {
                this.depMap = depMap
            }
        }

        @Throws(
            com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.ComputeDependenciesFunction.EvalException::class,
            java.lang.InterruptedException::class
        )
        public override fun compute(skyKey: SkyKey, env: Environment): SkyValue? {
            try {
                val targetAndConfiguration: TargetAndConfiguration = skyKey.argument() as TargetAndConfiguration
                // Set up the toolchain context so that exec transitions resolve properly.
                val state: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    DependencyResolver.State.createForTesting(targetAndConfiguration)
                val starlarkExecTransition: java.util.Optional<StarlarkAttributeTransitionProvider?>? =
                    StarlarkExecTransitionLoader.loadStarlarkExecTransition(
                        if (targetAndConfiguration.getTarget() == null)
                            null
                        else
                            targetAndConfiguration.getConfiguration().getOptions(),
                        { bzlKey -> env.getValueOrThrow(bzlKey, BzlLoadFailedException::class.java) as BzlLoadValue? })
                if (starlarkExecTransition == null) {
                    return null
                }
                state.dependencyContext =
                    DependencyContext.create(
                        ToolchainCollection.< UnloadedToolchainContext > builder < UnloadedToolchainContext ? > ()
                            .addDefaultContext(
                                UnloadedToolchainContextImpl.builder(
                                    ToolchainContextKey.key()
                                        .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                                        .configurationKey(
                                            targetAndConfiguration.getConfiguration().getKey()
                                        )
                                        .build()
                                )
                                    .setTargetPlatform(
                                        PlatformInfo.builder().setLabel(TARGET_PLATFORM_LABEL).build()
                                    )
                                    .setExecutionPlatform(
                                        PlatformInfo.builder().setLabel(EXEC_PLATFORM_LABEL).build()
                                    )
                                    .build()
                            )
                            .build(),
                        ConfigConditions.EMPTY
                    )
                val depMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? =
                    DependencyResolver.computeDependencies(
                        state,
                        ConfiguredTargetKey.builder()
                            .setLabel(targetAndConfiguration.getLabel())
                            .setConfiguration(targetAndConfiguration.getConfiguration())
                            .build(),  /* aspects= */
                        com.google.common.collect.ImmutableList.of<E?>(),  /* loadExecAspectsKey= */
                        null,
                        stateProvider.lateBoundSkyframeBuildView().getStarlarkTransitionCache(),
                        starlarkExecTransition.orElse(null),
                        env,
                        env.getListener(),  /* baseTargetPrerequisitesSupplier= */
                        null,  /* baseTargetUnloadedToolchainContexts= */
                        null
                    )
                return if (env.valuesMissing()) null else com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.ComputeDependenciesFunction.Value(
                    depMap
                )
            } catch (e: java.lang.RuntimeException) {
                throw e
            } catch (e: java.lang.Exception) {
                throw com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.ComputeDependenciesFunction.EvalException(
                    e
                )
            }
        }

        private class EvalException(cause: java.lang.Exception?) : SkyFunctionException(cause, Transience.PERSISTENT)

        public override fun extractTag(skyKey: SkyKey): String {
            return (skyKey.argument() as TargetAndConfiguration).getLabel().getName()
        }

        companion object {
            val SKYFUNCTION_NAME: SkyFunctionName? =
                SkyFunctionName.createHermetic("CONFIGURED_TARGET_FUNCTION_COMPUTE_DEPENDENCIES")

            /** Returns a [SkyKey] for a given <Target></Target>, BuildConfigurationValue> pair.  */
            private fun key(target: Target?, config: BuildConfigurationValue?): Key {
                return com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.ComputeDependenciesFunction.Key(
                    TargetAndConfiguration(target, config)
                )
            }
        }
    }

    /**
     * Provides build state to [ComputeDependenciesFunction]. This needs to be late-bound (i.e.
     * we can't just pass the contents directly) because of the way [AnalysisTestCase] works:
     * the [AnalysisMock] instance that instantiates the function gets created before the rest
     * of the build state. See [AnalysisTestCase.createMocks] for details.
     */
    private inner class LateBoundStateProvider {
        fun lateBoundSkyframeBuildView(): SkyframeBuildView {
            return skyframeExecutor.getSkyframeBuildView()
        }
    }

    /**
     * An [AnalysisMock] that injects [ComputeDependenciesFunction] into the Skyframe
     * executor.
     */
    private class AnalysisMockWithComputeDepsFunction(private val stateProvider: LateBoundStateProvider) :
        com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(
                    ComputeDependenciesFunction.Companion.SKYFUNCTION_NAME,
                    ComputeDependenciesFunction(stateProvider)
                )
                .buildOrThrow()
        }
    }

    val analysisMock: AnalysisMock
        get() = com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.AnalysisMockWithComputeDepsFunction(
            com.google.devtools.build.lib.skyframe.ConfigurationsForTargetsTest.LateBoundStateProvider()
        )

    /** Returns the configured deps for a given target.  */
    @Throws(java.lang.Exception::class)
    private fun getConfiguredDeps(
        target: ConfiguredTarget
    ): com.google.common.collect.SetMultimap<DependencyKind, ConfiguredTargetAndData?> {
        val targetLabel: String? = AliasProvider.getDependencyLabel(target).toString()
        val key: SkyKey = ComputeDependenciesFunction.Companion.key(getTarget(targetLabel), getConfiguration(target))
        // Must re-enable analysis for Skyframe functions that create configured targets.
        skyframeExecutor.getSkyframeBuildView().enableAnalysis(true)
        val evalResult: EvaluationResult<ComputeDependenciesFunction.Value?> =
            SkyframeExecutorTestUtils.evaluate<SkyValue?>(skyframeExecutor, key,  /*keepGoing=*/false, reporter)
        skyframeExecutor.getSkyframeBuildView().enableAnalysis(false)
        return evalResult.get(key).depMap
    }

    /**
     * Returns the configured deps for a given target under the given attribute. Assumes the target
     * uses the target configuration.
     * 
     * 
     * Throws an exception if the attribute can't be found.
     */
    @Throws(java.lang.Exception::class)
    private fun getConfiguredDeps(
        targetLabel: String?,
        attrName: String?
    ): com.google.common.collect.ImmutableList<ConfiguredTarget> {
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update(targetLabel).getTargetsToBuild())
        val maybeConfiguredDeps: com.google.common.collect.ImmutableList<ConfiguredTarget>? =
            getConfiguredDeps(target, attrName)
        Truth.assertThat(maybeConfiguredDeps).isNotNull()
        return maybeConfiguredDeps
    }

    /**
     * Returns the configured deps for a given configured target under the given attribute.
     * 
     * 
     * Returns null if the attribute can't be found.
     */
    @Throws(java.lang.Exception::class)
    private fun getConfiguredDeps(
        target: ConfiguredTarget, attrName: String?
    ): com.google.common.collect.ImmutableList<ConfiguredTarget>? {
        val allDeps: com.google.common.collect.Multimap<DependencyKind, ConfiguredTargetAndData?> =
            getConfiguredDeps(target)
        for (kind in allDeps.keySet()) {
            val attribute: Attribute = kind.getAttribute()
            if (attribute.name.equals(attrName)) {
                return com.google.common.collect.ImmutableList.< ConfiguredTarget > copyOf < ConfiguredTarget >(
                    com.google.common.collect.Collections2.transform<ConfiguredTargetAndData?, Any?>(
                        allDeps.get(kind), ConfiguredTargetAndData::getConfiguredTarget
                    )
                )
            }
        }
        return null
    }

    @Throws(java.lang.Exception::class)
    private fun getConfiguredDepsWithData(
        targetLabel: String?, attrName: String?
    ): com.google.common.collect.ImmutableList<ConfiguredTargetAndData> {
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update(targetLabel).getTargetsToBuild())
        val maybeConfiguredDeps: com.google.common.collect.ImmutableList<ConfiguredTargetAndData>? =
            getConfiguredDepsWithData(target, attrName)
        Truth.assertThat(maybeConfiguredDeps).isNotNull()
        return maybeConfiguredDeps
    }

    @Throws(java.lang.Exception::class)
    private fun getConfiguredDepsWithData(
        target: ConfiguredTarget, attrName: String?
    ): com.google.common.collect.ImmutableList<ConfiguredTargetAndData>? {
        val allDeps: com.google.common.collect.Multimap<DependencyKind, ConfiguredTargetAndData?> =
            getConfiguredDeps(target)
        for (kind in allDeps.keySet()) {
            val attribute: Attribute = kind.getAttribute()
            if (attribute.name.equals(attrName)) {
                return com.google.common.collect.ImmutableList.copyOf<ConfiguredTargetAndData?>(allDeps.get(kind))
            }
        }
        return null
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        scratch.file(
            "platform/BUILD",
            """
        # Add basic target and exec platforms for testing.
        platform(name = "target")

        platform(name = "exec")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullConfiguredDepsHaveExpectedConfigs() {
        scratch.file(
            "a/BUILD", "genrule(name = 'gen', srcs = ['gen.in'], cmd = '', outs = ['gen.out'])"
        )
        val genIn: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(getConfiguredDeps("//a:gen", "srcs"))
        assertThat(getConfiguration(genIn)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun genQueryScopeHasExpectedConfigs() {
        scratch.file(
            "p/BUILD",
            """
        filegroup(name = "a")

        genquery(
            name = "q",
            expression = "deps(//p:a)",
            scope = [":a"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//p:q").getTargetsToBuild())
        // There are no configured targets for the "scope" attribute.
        val configuredScopeDeps: com.google.common.collect.ImmutableList<ConfiguredTarget>? =
            getConfiguredDeps(target, "scope")
        Truth.assertThat(configuredScopeDeps).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetDeps() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
        )

        cc_binary(
            name = "binary",
            srcs = ["main.cc"],
            deps = [
                ":dep1",
                ":dep2",
            ],
        )
        
        """.trimIndent()
        )
        val deps: MutableList<ConfiguredTarget> = getConfiguredDeps("//a:binary", "deps")
        Truth.assertThat(deps).hasSize(2)
        val topLevelConfiguration: BuildConfigurationValue =
            getConfiguration(com.google.common.collect.Iterables.getOnlyElement<T?>(update("//a:binary").getTargetsToBuild()))
        for (dep in deps) {
            assertThat(topLevelConfiguration).isEqualTo(getConfiguration(dep))
        }
    }

    /** Tests dependencies in attribute with exec transition.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execDeps() {
        scratch.file(
            "a/exec_rule.bzl",
            """
        exec_rule = rule(
            implementation = lambda ctx: [],
            attrs = {"tools": attr.label_list(cfg = "exec")},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:exec_rule.bzl", "exec_rule")
        load('//test_defs:foo_binary.bzl', 'foo_binary')

        foo_binary(
            name = "exec_tool",
            srcs = ["exec_tool.sh"],
        )

        exec_rule(
            name = "gen",
            tools = [":exec_tool"],
        )
        
        """.trimIndent()
        )

        val toolDep: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(getConfiguredDeps("//a:gen", "tools"))
        val toolConfiguration: BuildConfigurationValue = getConfiguration(toolDep)
        assertThat(toolConfiguration.isToolConfiguration()).isTrue()
        assertThat(toolConfiguration.getOptions().get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(EXEC_PLATFORM_LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun splitDeps() {
        // Write a simple rule with split dependencies.
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//a/...",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/defs.bzl",
            """
        def _transition_impl(settings, attr):
            return {
                "opt": {"//command_line_option:compilation_mode": "opt"},
                "dbg": {"//command_line_option:compilation_mode": "dbg"},
            }

        split_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        def _split_deps_rule_impl(ctx):
            pass

        split_deps_rule = rule(
            implementation = _split_deps_rule_impl,
            attrs = {
                "dep": attr.label(cfg = split_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//a:defs.bzl", "split_deps_rule")

        cc_library(
            name = "lib",
            srcs = ["lib.cc"],
        )

        split_deps_rule(
            name = "a",
            dep = ":lib",
        )
        
        """.trimIndent()
        )

        // Verify that the dependencies have different configurations.
        val deps: com.google.common.collect.ImmutableList<ConfiguredTargetAndData> =
            getConfiguredDepsWithData("//a:a", "dep")
        Truth.assertThat(deps).hasSize(2)
        val dep1: ConfiguredTargetAndData = deps.get(0)
        val dep2: ConfiguredTargetAndData = deps.get(1)
        assertThat(dep1.getConfiguration().checksum()).isNotEqualTo(dep2.getConfiguration().checksum())

        // We don't care what order split deps are listed, but it must be deterministic.
        assertThat(SPLIT_DEP_ORDERING.compare(dep1, dep2)).isLessThan(0)
    }

    /**
     * Ensures that <bold>different</bold> transitions don't trigger false cache hits.
     * 
     * 
     * This test checks a subtle version of that: if the same Starlark transition applies to two
     * deps, but that transition reads their attributes and their attribute values are different, we
     * need to make sure they're distinctly computed.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameTransitionDifferentParameters() {
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//a/...",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/defs.bzl",
            """
        def _transition_impl(settings, attr):
            return {"//command_line_option:compilation_mode": attr.myattr}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        def _parent_rule_impl(ctx):
            pass

        parent_rule = rule(
            implementation = _parent_rule_impl,
            attrs = {
                "dep1": attr.label(),
                "dep2": attr.label(),
            },
        )

        def _child_rule_impl(ctx):
            pass

        child_rule = rule(
            implementation = _child_rule_impl,
            cfg = my_transition,
            attrs = {
                "myattr": attr.string(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "child_rule", "parent_rule")

        child_rule(
            name = "child1",
            # For this dep, my_transition reads myattr="dbg".
            myattr = "dbg",
        )

        child_rule(
            name = "child2",
            # For this dep, my_transition reads myattr="opt".
            myattr = "opt",
        )

        parent_rule(
            name = "buildme",
            dep1 = ":child1",
            dep2 = ":child2",
        )
        
        """.trimIndent()
        )

        val child1: ConfiguredTarget? = com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
            getConfiguredDeps(
                "//a:buildme",
                "dep1"
            )
        )
        val child2: ConfiguredTarget? = com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(
            getConfiguredDeps(
                "//a:buildme",
                "dep2"
            )
        )
        // Check that each dep ends up with a distinct compilation_mode value.
        assertThat(getConfiguration(child1).getCompilationMode()).isEqualTo(CompilationMode.DBG)
        assertThat(getConfiguration(child2).getCompilationMode()).isEqualTo(CompilationMode.OPT)
    }

    companion object {
        private val TARGET_PLATFORM_LABEL: Label? = Label.parseCanonicalUnchecked("//platform:target")
        private val EXEC_PLATFORM_LABEL: Label? = Label.parseCanonicalUnchecked("//platform:exec")
    }
}
