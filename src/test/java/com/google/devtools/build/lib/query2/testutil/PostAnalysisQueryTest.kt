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
package com.google.devtools.build.lib.query2.testutil

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.analysis.util.DummyTestFragment
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.packages.Attribute.attr
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import com.google.devtools.build.lib.query2.engine.QueryException
import com.google.devtools.build.lib.query2.engine.QueryParser
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests for [PostAnalysisQueryEnvironment].  */
abstract class PostAnalysisQueryTest<T> : AbstractQueryTest<T?>() {
    // Also filter out platform dependencies.
    override fun getDependencyCorrection(): String {
        return " - deps(" + TestConstants.PLATFORM_LABEL + ")"
    }

    @Before
    fun disableOrderedResults() {
        helper!!.setOrderedResults(false)
    }

    @Before
    fun setMockToolsConfig() {
        this.mockToolsConfig = this.helper!!.getMockToolsConfig()
    }

    /**
     * In production, cquery constructs the universe by parsing targets from the query expression and
     * building them at the top level. If this is not viable (e.g. component functions) or not desired
     * (e.g. somepath(//foo-built-in-target, //bar-built-in-host), the user must specify the
     * --universe_scope flag. Enforce the same behavior in this test by initializing universe scope to
     * an invalid target expression.
     */
    override fun getDefaultUniverseScope(): String {
        return DEFAULT_UNIVERSE
    }

    protected val helper: PostAnalysisQueryHelper<T?>?
        get() = field

    /**
     * At the end of each eval, reset the universe scope to the default if the test doesn't use a
     * single universe scope.
     */
    @Throws(Exception::class)
    override fun eval(query: String?): MutableSet<T?>? {
        maybeParseUniverseScope(query)
        val queryResult = super.eval(query)
        if (!this.helper!!.isWholeTestUniverse()) {
            helper!!.setUniverseScope(getDefaultUniverseScope())
        }
        return queryResult
    }

    @Throws(Exception::class)
    override fun evalThrows(query: String?, unconditionallyThrows: Boolean): EvalThrowsResult {
        maybeParseUniverseScope(query)
        val queryResult = super.evalThrows(query, unconditionallyThrows)
        if (!this.helper!!.isWholeTestUniverse()) {
            helper!!.setUniverseScope(getDefaultUniverseScope())
        }
        return queryResult
    }

    // Parse the universe if the universe has not been set manually through the helper.
    @Throws(Exception::class)
    private fun maybeParseUniverseScope(query: String?) {
        if (this.helper!!
                .getUniverseScopeAsStringList()
            != mutableListOf<String?>(getDefaultUniverseScope())
        ) {
            return
        }
        val expression: QueryExpression = QueryParser.parse(
            query,
            this.defaultFunctions
        )
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        if (!targetPatternSet.isEmpty()) {
            val universeScope = StringBuilder()
            for (target in targetPatternSet) {
                universeScope.append(target).append(",")
            }
            helper!!.setUniverseScope(universeScope.toString())
        }
    }

    protected abstract val defaultFunctions: HashMap<String?, QueryFunction?>?

    protected abstract fun getConfiguration(target: T?): BuildConfigurationValue?

    override fun testConfigurableAttributes(): Boolean {
        // ConfiguredTargetQuery knows the actual configuration, so it doesn't falsely overapproximate.
        return false
    }

    @Test
    override fun testTargetLiteralWithMissingTargets() {
        this.helper!!.turnOffFailFast()
        val e: TargetParsingException =
            Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                ThrowingRunnable { super.testTargetLiteralWithMissingTargets() })
        assertThat(e)
            .hasMessageThat()
            .matches(
                TestUtils.createMissingTargetAssertionString( /* target= */
                    "b",  /* packageStr= */
                    "a",
                    helper!!.getRootDirectory().getPathString(),
                    ""
                )
            )
        assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.TARGET_MISSING)
    }

    @Test
    @Throws(Exception::class)
    override fun testBadTargetLiterals() {
        this.helper!!.turnOffFailFast()
        val e: TargetParsingException =
            Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                ThrowingRunnable { super.testBadTargetLiterals() })
        checkResultofBadTargetLiterals(e.getMessage(), e.getDetailedExitCode().getFailureDetail())
    }

    @Test
    @Throws(Exception::class)
    override fun testNoImplicitDeps() {
        val ruleWithImplicitDeps: MockRule =
            MockRule {
                MockRule.define(
                    "implicit_deps_rule",
                    attr("explicit", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE),
                    attr("explicit_with_default", LABEL)
                        .value(Label.parseCanonicalUnchecked("//test:explicit_with_default"))
                        .allowedFileTypes(FileTypeSet.ANY_FILE),
                    attr("\$implicit", LABEL).value(Label.parseCanonicalUnchecked("//test:implicit")),
                    attr(":latebound", LABEL)
                        .value(
                            Attribute.LateBoundDefault.fromConstantForTesting(
                                Label.parseCanonicalUnchecked("//test:latebound")
                            )
                        )
                )
            }
        helper!!.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build())

        writeFile(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        implicit_deps_rule(
            name = "my_rule",
            explicit = ":explicit",
            explicit_with_default = ":explicit_with_default",
        )

        cc_library(name = "explicit")

        cc_library(name = "explicit_with_default")

        cc_library(name = "implicit")

        cc_library(name = "latebound")
        
        """.trimIndent()
        )

        val implicits = "//test:implicit + //test:latebound"
        val explicits = "//test:my_rule + //test:explicit + //test:explicit_with_default"

        // Check for implicit dependencies (late bound attributes, implicit attributes, platforms)
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsAtLeastElementsIn(
                unique(evalToListOfStrings(explicits + " + " + implicits + " + " + TestConstants.PLATFORM_LABEL))
            )

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsAtLeastElementsIn(evalToListOfStrings(explicits))
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsNoneIn(evalToListOfStrings(implicits))
    }

    @Test
    @Throws(Exception::class)
    fun testNoImplicitDepsOnOutputFile() {
        writeFile(
            "test/BUILD",
            """
load(":defs.bzl", "my_dep", "my_rule")
my_rule(
    name = "buildme",
    explicit_deps = [":explicit_dep"],
    output = "foo.out",
)
my_dep(name = "explicit_dep")
my_dep(name = "implicit_dep")

""".trimIndent()
        )

        writeFile(
            "test/defs.bzl",
            """
my_dep = rule(
    implementation = lambda ctx: [],
    attrs = {},
)

def _impl(ctx):
    ctx.actions.write(ctx.outputs.output, "hello!")

my_rule = rule(
    implementation = _impl,
    attrs = {
        "output": attr.output(),
        "explicit_deps": attr.label_list(),
        "_implicit_deps": attr.label_list(default = ["//test:implicit_dep"]),
    },
)

""".trimIndent()
        )

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        Truth.assertThat(evalToListOfStrings("deps(//test:foo.out)"))
            .containsAtLeast("//test:foo.out", "//test:buildme", "//test:explicit_dep")
        Truth.assertThat(evalToListOfStrings("deps(//test:foo.out)")).doesNotContain("//test:implicit_dep")
    }

    @Test
    @Throws(Exception::class)
    fun testImplicitDepsOnOutputFile() {
        writeFile(
            "test/BUILD",
            """
load(":defs.bzl", "my_dep", "my_rule")
my_rule(
    name = "buildme",
    explicit_deps = [":explicit_dep"],
    output = "foo.out",
)
my_dep(name = "explicit_dep")
my_dep(name = "implicit_dep")

""".trimIndent()
        )

        writeFile(
            "test/defs.bzl",
            """
my_dep = rule(
    implementation = lambda ctx: [],
    attrs = {},
)

def _impl(ctx):
    ctx.actions.write(ctx.outputs.output, "hello!")

my_rule = rule(
    implementation = _impl,
    attrs = {
        "output": attr.output(),
        "explicit_deps": attr.label_list(),
        "_implicit_deps": attr.label_list(default = ["//test:implicit_dep"]),
    },
)

""".trimIndent()
        )

        helper!!.setQuerySettings()
        Truth.assertThat(evalToListOfStrings("deps(//test:foo.out)"))
            .containsAtLeast(
                "//test:foo.out", "//test:buildme", "//test:explicit_dep", "//test:implicit_dep"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testNoImplicitDeps_toolchains() {
        val ruleWithImplicitDeps: MockRule =
            MockRule {
                MockRule.define(
                    "implicit_toolchain_deps_rule",
                    { builder, env ->
                        builder.addToolchainTypes(
                            ToolchainTypeRequirement.create(
                                Label.parseCanonicalUnchecked("//test:toolchain_type")
                            )
                        )
                    })
            }
        helper!!.useRuleClassProvider(setRuleClassProviders(ruleWithImplicitDeps).build())

        writeFile(
            "test/toolchain.bzl",
            """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo()
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load(":toolchain.bzl", "test_toolchain")

        implicit_toolchain_deps_rule(
            name = "my_rule",
        )

        toolchain_type(name = "toolchain_type")

        toolchain(
            name = "toolchain",
            toolchain = ":toolchain_impl",
            toolchain_type = ":toolchain_type",
        )

        test_toolchain(name = "toolchain_impl")
        
        """.trimIndent()
        )
        (helper as PostAnalysisQueryHelper<T?>).useConfiguration("--extra_toolchains=//test:toolchain")

        val implicits = "//test:toolchain_impl"
        val explicits = "//test:my_rule"

        // Check for implicit toolchain dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsAtLeastElementsIn(
                unique(evalToListOfStrings(explicits + "+" + implicits + "+" + TestConstants.PLATFORM_LABEL))
            )

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val filteredDeps = evalToListOfStrings("deps(//test:my_rule)")
        Truth.assertThat(filteredDeps).contains(explicits)
        Truth.assertThat(filteredDeps).doesNotContain(implicits)
    }

    @Throws(Exception::class)
    private fun writeSimpleToolchain() {
        writeFile(
            "test/toolchain_def.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        test_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load("//test:toolchain_def.bzl", "test_toolchain")

        toolchain_type(name = "toolchain_type")

        toolchain(
            name = "toolchain",
            toolchain = ":toolchain_impl",
            toolchain_type = "//test:toolchain_type",
        )

        test_toolchain(name = "toolchain_impl")
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testNoImplicitDeps_starlark_toolchains() {
        writeSimpleToolchain()
        writeFile(
            "test/rule/rule.bzl",
            """
        def _impl(ctx):
            return []

        implicit_toolchain_deps_rule = rule(
            implementation = _impl,
            toolchains = ["//test:toolchain_type"],
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/rule/BUILD",
            """
        load(":rule.bzl", "implicit_toolchain_deps_rule")

        implicit_toolchain_deps_rule(
            name = "my_rule",
        )
        
        """.trimIndent()
        )
        (helper as PostAnalysisQueryHelper<T?>).useConfiguration("--extra_toolchains=//test:toolchain")

        val implicits = "//test:toolchain_impl"
        val explicits = "//test/rule:my_rule"

        // Check for implicit toolchain dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test/rule:my_rule)"))
            .containsAtLeast(explicits, implicits)

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val filteredDeps = evalToListOfStrings("deps(//test/rule:my_rule)")
        Truth.assertThat(filteredDeps).contains(explicits)
        Truth.assertThat(filteredDeps).doesNotContain(implicits)
    }

    @Test
    @Throws(Exception::class)
    fun testNoImplicitDeps_cc_toolchains() {
        writeFile(
            "test/toolchain/toolchain_config.bzl",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl", "CcToolchainConfigInfo")
        load("@rules_cc//cc/common:cc_common.bzl", "cc_common")
        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                toolchain_identifier = "mock-llvm-toolchain-k8",
                host_system_name = "mock-system-name-for-k8",
                target_system_name = "mock-target-system-name-for-k8",
                target_cpu = "k8",
                target_libc = "mock-libc-for-k8",
                compiler = "mock-compiler-for-k8",
                abi_libc_version = "mock-abi-libc-for-k8",
                abi_version = "mock-abi-version-for-k8",
            )

        cc_toolchain_config = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/toolchain/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':toolchain_config.bzl', 'cc_toolchain_config')",
            "cc_toolchain_config(name = 'some-cc-toolchain-config')",
            "filegroup(name = 'nothing', srcs = [])",
            "cc_toolchain(",
            "    name = 'some_cc_toolchain_impl',",
            "    all_files = ':nothing',",
            "    as_files = ':nothing',",
            "    compiler_files = ':nothing',",
            "    dwp_files = ':nothing',",
            "    linker_files = ':nothing',",
            "    objcopy_files = ':nothing',",
            "    strip_files = ':nothing',",
            "    toolchain_config = ':some-cc-toolchain-config',",
            ")",
            "toolchain(",
            "    name = 'some_cc_toolchain',",
            "    toolchain = ':some_cc_toolchain_impl',",
            "    toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
            ")"
        )
        writeFile(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "my_rule",
            srcs = ["whatever.cpp"],
        )
        
        """.trimIndent()
        )
        (helper as PostAnalysisQueryHelper<T?>)
            .useConfiguration("--extra_toolchains=//test/toolchain:some_cc_toolchain")

        val implicits = "//test/toolchain:some_cc_toolchain_impl"
        val explicits = "//test:my_rule"

        // Check for implicit toolchain dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsAtLeastElementsIn(
                unique(evalToListOfStrings(explicits + "+" + implicits + "+" + TestConstants.PLATFORM_LABEL))
            )

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val filteredDeps = evalToListOfStrings("deps(//test:my_rule)")
        Truth.assertThat(filteredDeps).contains(explicits)
        Truth.assertThat(filteredDeps).doesNotContain(implicits)
    }

    // Regression test for b/148550864
    @Test
    @Throws(Exception::class)
    fun testNoImplicitDeps_platformDeps() {
        val simpleRule: MockRule = MockRule { MockRule.define("simple_rule") }
        helper!!.useRuleClassProvider(setRuleClassProviders(simpleRule).build())

        writeFile(
            "test/BUILD",
            """
        simple_rule(name = "my_rule")

        platform(name = "host_platform")

        platform(name = "execution_platform")
        
        """.trimIndent()
        )

        (helper as PostAnalysisQueryHelper<T?>)
            .useConfiguration(
                "--host_platform=//test:host_platform",
                "--extra_execution_platforms=//test:execution_platform"
            )

        // Check for platform dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)"))
            .containsAtLeastElementsIn(
                unique(evalToListOfStrings("//test:execution_platform + //test:host_platform"))
            )
        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)")).containsExactly("//test:my_rule")
    }

    //  Regression test for b/275502129.
    @Test
    @Throws(Exception::class)
    fun testNoImplicitDepsFromAutoExecGroups_autoExecGroupsEnabled() {
        writeSimpleToolchain()
        writeFile(
            "test/aeg/defs.bzl",
            """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test:toolchain_type"],
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/aeg/BUILD",
            """
        load("//test/aeg:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        (helper as PostAnalysisQueryHelper<T?>)
            .useConfiguration("--incompatible_auto_exec_groups", "--extra_toolchains=//test:all")

        val implicits = "//test:toolchain_impl"
        val explicits = "//test/aeg:custom_rule_name"

        // Check for implicit toolchain dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test/aeg:custom_rule_name)"))
            .containsAtLeast(explicits, implicits)

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val filteredDeps = evalToListOfStrings("deps(//test/aeg:custom_rule_name)")
        Truth.assertThat(filteredDeps).contains(explicits)
        Truth.assertThat(filteredDeps).doesNotContain(implicits)
    }

    //  Regression test for b/275502129.
    @Test
    @Throws(Exception::class)
    fun testNoImplicitDepsFromCustomExecGroups_autoExecGroupsEnabled() {
        writeSimpleToolchain()
        writeFile(
            "test/aeg/defs.bzl",
            """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            exec_groups = {
                "custom_exec_group": exec_group(
                    toolchains = ["//test:toolchain_type"],
                ),
            },
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/aeg/BUILD",
            """
        load("//test/aeg:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        (helper as PostAnalysisQueryHelper<T?>)
            .useConfiguration("--incompatible_auto_exec_groups", "--extra_toolchains=//test:all")

        val implicits = "//test:toolchain_impl"
        val explicits = "//test/aeg:custom_rule_name"

        // Check for implicit toolchain dependencies
        Truth.assertThat(evalToListOfStrings("deps(//test/aeg:custom_rule_name)"))
            .containsAtLeast(explicits, implicits)

        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val filteredDeps = evalToListOfStrings("deps(//test/aeg:custom_rule_name)")
        Truth.assertThat(filteredDeps).contains(explicits)
        Truth.assertThat(filteredDeps).doesNotContain(implicits)
    }

    @Test
    @Throws(Exception::class)
    override fun testNoImplicitDeps_computedDefault() {
        val computedDefaultRule: MockRule =
            MockRule {
                MockRule.define(
                    "computed_default_rule",
                    attr("conspiracy", Type.STRING).value("space jam was a documentary"),
                    attr("dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .value(
                            object : ComputedDefault("conspiracy") {
                                public override fun getDefault(rule: AttributeMap): Any? {
                                    return@MockRule if (rule.get("conspiracy", Type.STRING)
                                            .equals("space jam was a documentary")
                                    )
                                        Label.parseCanonicalUnchecked("//test:foo")
                                    else
                                        null
                                }
                            })
                )
            }

        helper!!.useRuleClassProvider(setRuleClassProviders(computedDefaultRule).build())

        writeFile(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = "foo")

        computed_default_rule(name = "my_rule")
        
        """.trimIndent()
        )

        val target = "//test:my_rule"

        Truth.assertThat(evalToListOfStrings("deps(" + target + ")")).contains("//test:foo")
        helper!!.setQuerySettings(QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        Truth.assertThat(eval("deps(" + target + ")")).isEqualTo(eval(target))
    }

    @Test
    @Throws(Exception::class)
    override fun testLet() {
        this.helper!!.setWholeTestUniverseScope("//a,//b,//c,//d")
        super.testLet()
    }

    @Test
    @Throws(Exception::class)
    override fun testSet() {
        this.helper!!.setWholeTestUniverseScope("//a:*,//b:*,//c:*,//d:*")
        super.testSet()
    }

    /** Attribute transition factory on --foo  */
    protected class FooPatchAttrTransitionFactory
    @kotlin.jvm.JvmOverloads constructor(var toOption: String?, var name: String? = "FooPatchAttrTransitionFactory") :
        TransitionFactory<AttributeTransitionData?> {
        public override fun create(unused: AttributeTransitionData?): ConfigurationTransition? {
            return FooPatchTransition(this.toOption, this.name)
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }
    }

    /** Rule transition factory on --foo  */
    protected class FooPatchRuleTransitionFactory
    @kotlin.jvm.JvmOverloads constructor(var toOption: String?, var name: String? = "FooPatchRuleTransitionFactory") :
        TransitionFactory<RuleTransitionData?> {
        public override fun create(unused: RuleTransitionData?): ConfigurationTransition? {
            return FooPatchTransition(this.toOption, this.name)
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.RULE
        }
    }

    /** PatchTransition on --foo  */
    protected class FooPatchTransition(var toOption: String?, var name: String?) : PatchTransition {
        public override fun requiresOptionFragments(): ImmutableSet<Class<out FragmentOptions?>?> {
            return ImmutableSet.of<E?>(DummyTestFragment.DummyTestOptions::class.java)
        }

        public override fun patch(options: BuildOptionsView, eventHandler: EventHandler?): BuildOptions {
            val result: BuildOptionsView = options.clone()
            result.get(DummyTestFragment.DummyTestOptions::class.java).setFoo(toOption)
            return result.underlying()
        }
    }

    /** Split transition factory on --foo  */
    protected class FooSplitTransitionFactory
        (var toOption1: String?, var toOption2: String?) : TransitionFactory<AttributeTransitionData?> {
        public override fun create(data: AttributeTransitionData?): ConfigurationTransition? {
            return object : SplitTransition() {
                val name: String
                    get() = "FooSplitTransitionFactory"

                public override fun requiresOptionFragments(): ImmutableSet<Class<out FragmentOptions?>?> {
                    return ImmutableSet.of<E?>(DummyTestFragment.DummyTestOptions::class.java)
                }

                public override fun split(
                    options: BuildOptionsView, eventHandler: EventHandler?
                ): ImmutableMap<String?, BuildOptions?> {
                    val result1: BuildOptionsView = options.clone()
                    val result2: BuildOptionsView = options.clone()
                    result1.get(DummyTestFragment.DummyTestOptions::class.java).setFoo(toOption1)
                    result2.get(DummyTestFragment.DummyTestOptions::class.java).setFoo(toOption2)
                    return ImmutableMap.of<K?, V?>("result1", result1.underlying(), "result2", result2.underlying())
                }
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }

        val isSplit: Boolean
            get() = true
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleTopLevelConfigurations() {
        val transitionedRule: MockRule =
            MockRule {
                MockRule.define(
                    "transitioned_rule",
                    { builder, env -> builder.cfg(FooPatchRuleTransitionFactory("SET BY PATCH")).build() })
            }

        val untransitionedRule: MockRule = MockRule { MockRule.define("untransitioned_rule") }

        helper!!.useRuleClassProvider(
            setRuleClassProviders(transitionedRule, untransitionedRule).build()
        )

        writeFile(
            "test/BUILD",
            """
        transitioned_rule(name = "transitioned_rule")

        untransitioned_rule(name = "untransitioned_rule")
        
        """.trimIndent()
        )

        val result = eval("//test:transitioned_rule+//test:untransitioned_rule")

        Truth.assertThat(result).hasSize(2)

        val resultIterator = result!!.iterator()
        assertThat(getConfiguration(resultIterator.next()))
            .isNotEqualTo(getConfiguration(resultIterator.next()))
    }

    @Test
    @Throws(Exception::class)
    abstract fun testMultipleTopLevelConfigurations_nullConfigs()

    @Test
    @Throws(Exception::class)
    fun testMultipleTopLevelConfigurations_multipleConfigsPrefersTopLevel() {
        val ruleWithTransitionAndDep: MockRule =
            MockRule {
                MockRule.define(
                    "rule_with_transition_and_dep",
                    { builder, env ->
                        builder
                            .cfg(FooPatchRuleTransitionFactory("SET BY PATCH"))
                            .addAttribute(
                                attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE).build()
                            )
                            .build()
                    })
            }

        val simpleRule: MockRule = MockRule { MockRule.define("simple_rule") }

        helper!!.useRuleClassProvider(
            setRuleClassProviders(ruleWithTransitionAndDep, simpleRule).build()
        )

        writeFile(
            "test/BUILD",
            """
        rule_with_transition_and_dep(
            name = "top-level",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        helper!!.setUniverseScope("//test:*")

        // `//test:dep` has two configurations.
        Truth.assertThat(eval("//test:dep")).hasSize(2)
    }

    @Test
    @Throws(Exception::class)
    fun testNonIdempotentRuleTransition() {
        writeFile(
            "test/defs.bzl",
            """
        StringSettingInfo = provider(fields = ["value"])

        def _string_impl(ctx):
            return StringSettingInfo(value = ctx.build_setting_value)

        string_setting = rule(
            implementation = _string_impl,
            build_setting = config.string(),
        )

        def _transition_impl(settings, attr):
            return {
                k: v + "-transitioned"
                for k, v in settings.items()
            }

        _transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:string_setting"],
            outputs = ["//test:string_setting"],
        )

        def _custom_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.attr.name)
            ctx.actions.write(out, ctx.attr._string_setting[StringSettingInfo].value)
            return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(
            cfg = _transition,
            implementation = _custom_rule_impl,
            attrs = {
                "_string_setting": attr.label(default = "//test:string_setting"),
            },
         )
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "custom_rule", "string_setting")

        string_setting(
            name = "string_setting",
            build_setting_default = "default",
        )

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )

        val output = evalToListOfStrings("//test:custom_rule_name")
        Truth.assertThat(output).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun testNonIdempotentRuleTransition_transitionedConfigIsAlsoToplevel() {
        writeFile(
            "test/defs.bzl",
            """
        StringSettingInfo = provider(fields = ["value"])

        def _string_impl(ctx):
            return StringSettingInfo(value = ctx.build_setting_value)

        string_setting = rule(
            implementation = _string_impl,
            build_setting = config.string(),
        )

        def _transition_impl(settings, attr):
            return {
                k: v + "-transitioned"
                for k, v in settings.items()
            }

        _transition = transition(
            implementation = _transition_impl,
            inputs = ["//test:string_setting"],
            outputs = ["//test:string_setting"],
        )

        def _custom_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.attr.name)
            ctx.actions.write(out, ctx.attr._string_setting[StringSettingInfo].value)
            return [DefaultInfo(files = depset([out]))]

        custom_rule = rule(
            cfg = _transition,
            implementation = _custom_rule_impl,
            attrs = {
                "_string_setting": attr.label(default = "//test:string_setting"),
            },
         )

        def _wrapper_impl(_):
            pass

        wrapper = rule(
            implementation = _wrapper_impl,
            attrs = {
                "dep": attr.label(
                    cfg = _transition,
                ),
            },
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "custom_rule", "string_setting", "wrapper")

        string_setting(
            name = "string_setting",
            build_setting_default = "default",
        )

        custom_rule(name = "custom_rule_name")

        wrapper(
            name = "wrapper_name",
            dep = ":custom_rule_name",
        )
        
        """.trimIndent()
        )

        val output =
            evalToListOfStrings("//test:all intersect //test:custom_rule_name")
        Truth.assertThat(output).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun inconsistentSkyQueryIncremental() {
        this.helper!!.setSyscallCache(TestUtils.makeDisappearingFileCache("bar/BUILD"))
        this.helper!!.turnOffFailFast()
        writeFile("foo/BUILD")
        writeFile("bar/BUILD")
        this.helper!!.setUniverseScope("//bar/...")
        val targetParsingException: TargetParsingException =
            Assert.assertThrows<T?>(TargetParsingException::class.java, ThrowingRunnable { eval("set()") })
        assertThat(
            targetParsingException
                .getDetailedExitCode()
                .getFailureDetail()
                .getPackageLoading()
                .getCode()
        )
            .isEqualTo(FailureDetails.PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR)
        this.helper!!.setUniverseScope("//foo/...")
        val queryException =
            Assert.assertThrows<QueryException>(QueryException::class.java, ThrowingRunnable { eval("bar") })
        assertThat(queryException.getFailureDetail().getTargetPatterns().getCode())
            .isEqualTo(FailureDetails.TargetPatterns.Code.CANNOT_DETERMINE_TARGET_FROM_FILENAME)
    }

    @Test
    @Throws(Exception::class)
    fun labelPointsToMultipleConfiguredTargets() {
    }

    @Throws(Exception::class)
    private fun writeSimpleTarget() {
        val simpleRule: MockRule =
            MockRule {
                MockRule.define(
                    "simple_rule", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }
        helper!!.useRuleClassProvider(setRuleClassProviders(simpleRule).build())

        writeFile("test/BUILD", "simple_rule(name = 'target')")
    }

    @Test
    @Throws(Exception::class)
    fun aliasMinus() {
        val simpleRule: MockRule =
            MockRule {
                MockRule.define(
                    "simple_rule", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }
        helper!!.useRuleClassProvider(setRuleClassProviders(simpleRule).build())

        writeFile(
            "p/BUILD",
            "simple_rule(name = 'dep')",
            "alias(name = 'alias', actual = 'dep')",
            "simple_rule(name = 'user', dep = ':alias')"
        )
        Truth.assertThat(evalToString("deps(//p:alias) - deps(//p:dep)")).isEqualTo("//p:alias")
        // The following assertion fails if the expression `//p:alias` doesn't represent two configured
        // targets -- one configured without TestOptions (trimmed) and the other configured with one
        // (untrimmed). The untrimmed configured target is from the top-level expression, whereas the
        // trimmed one is from `//p:user`'s dependency.
        Truth.assertThat(evalToString("deps(//p:user) - deps(//p:alias)")).isEqualTo("//p:user")
    }

    @Test
    @Throws(Exception::class)
    fun testVisibleFunctionDoesNotWork() {
        writeSimpleTarget()
        val result = evalThrows("visible(//test:target, //test:*)", true)
        Truth.assertThat(result.getMessage()).isEqualTo("visible() is not supported on configured targets")
        assertConfigurableQueryCode(result.getFailureDetail(), Code.VISIBLE_FUNCTION_NOT_SUPPORTED)
    }

    @Test
    @Throws(Exception::class)
    fun testSiblingsFunctionDoesNotWork() {
        writeSimpleTarget()
        val result = evalThrows("siblings(//test:target)", true)
        Truth.assertThat(result.getMessage()).isEqualTo("siblings() not supported for post analysis queries")
        assertConfigurableQueryCode(result.getFailureDetail(), Code.SIBLINGS_FUNCTION_NOT_SUPPORTED)
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfilesFunctionDoesNotWork() {
        writeSimpleTarget()
        val result = evalThrows("buildfiles(//test:target)", true)
        Truth.assertThat(result.getMessage())
            .isEqualTo("buildfiles() doesn't make sense for the configured target graph")
        assertConfigurableQueryCode(result.getFailureDetail(), Code.BUILDFILES_FUNCTION_NOT_SUPPORTED)
    }

    @Test
    @Throws(Exception::class)
    override fun testGenqueryScope() {
        runGenqueryScopeTest(true)
    }

    // LabelListAttr not currently supported.
    override fun testLabelsOperator() {}

    // Wants to get the query environment without evaluation -- not worth it.
    @Test
    override fun testEqualityOfOrderedThreadSafeImmutableSet() {
    }

    // The actual crosstool-related targets depended on are not the nominal crosstool label the test
    // expects.
    // "Extended rules" don't play nicely with actual analysis.
    override fun testNoDepsOnAspectAttributeWhenAspectMissing() {}

    override fun testNoDepsOnAspectAttributeWithNoImpicitDeps() {}

    override fun testHaveDepsOnAspectsAttributes() {}

    // Can't handle loading-phase errors.
    override fun testStrictTestSuiteWithFile() {}

    override fun testTestsOperatorReportsMissingTargets() {}

    override fun testCycleInStarlark() {}

    override fun testCycleInStarlarkParentDir() {}

    override fun testCycleInSubpackage() {}

    override fun testRegression1309697() {}

    override fun badRuleInDeps() {}

    override fun boundedRdepsWithError() {}

    // Can't handle cycles.
    override fun testDotDotDotWithCycle() {}

    override fun testDotDotDotWithUnrelatedCycle() {}

    // ...
    override fun testQueryTimeLoadingTargetsBelowNonPackageDirectory() {}

    override fun testQueryTimeLoadingOfTargetsBelowPackageHappyPath() {}

    override fun testQueryTimeLoadingTargetsBelowMissingPackage() {}

    // These tests clear the universe, getting rid of mock tools that are needed for analysis. Disable
    // at least for now. Other than testSlashSlashDotDotDot, they're only testing visibility anyway.
    override fun testSlashSlashDotDotDot() {}

    override fun testVisible_default_private() {}

    override fun testVisible_default_public() {}

    override fun testPackageGroupAllBeneath() {}

    override fun testVisible_java_javatests() {}

    override fun testVisible_java_javatests_different_package() {}

    override fun testVisible_javatests_java() {}

    override fun testVisible_package_group() {}

    override fun testVisible_package_group_include() {}

    override fun testVisible_package_group_invisible() {}

    override fun testVisible_private_same_package() {}

    override fun testVisible_simple_different_subpackages() {}

    override fun testVisible_simple_package() {}

    override fun testVisible_simple_private() {}

    override fun testVisible_simple_public() {}

    override fun testVisible_simple_subpackages() {}

    // test_suite rules aren't supported, since they're not configured targets.
    override fun testTestsOperatorFiltersByNegativeTag() {}

    override fun testTestsOperatorCrossesPackages() {}

    override fun testTestsOperatorHandlesCyclesGracefully() {}

    override fun testTestSuiteInTestsAttributeAndViceVersa() {}

    override fun testAmbiguousAllResolvesToTestSuiteNamedAll() {}

    override fun testTestSuiteWithFile() {}

    override fun testTestsOperatorFiltersByTagSizeAndEnv() {}

    override fun testTestsOperatorExpandsTestsAndExcludesNonTests() {}

    // buildfiles() operator.
    override fun testBuildFiles() {}

    override fun testBuildFilesDoesNotReturnVisibilityOfBUILD() {}

    override fun testBuildFilesDoesNotReturnVisibilityOfRule() {}

    override fun testBuildfilesOfBuildfiles() {}

    override fun testBuildfilesWithDuplicates() {}

    override fun bzlPackageBadDueToBrokenLoad() {}

    override fun bzlPackageBadDueToBrokenSyntax() {}

    override fun testBuildfilesContainingScl() {}

    override fun buildfilesBazel() {}

    override fun testTargetsFromBuildfilesAndRealTargets() {}

    // siblings() operator.
    override fun testSiblings_duplicatePackages() {}

    override fun testSiblings_samePackageRdeps() {}

    override fun testSiblings_matchesTargetNamedAll() {}

    override fun testSiblings_simple() {}

    override fun testSiblings_withBuildfiles() {}

    // same_pkg_direct_rdeps() operator.
    @Throws(Exception::class)
    override fun testSamePackageRdeps_simple() {
    }

    @Throws(Exception::class)
    override fun testSamePackageRdeps_duplicate() {
    }

    @Throws(Exception::class)
    override fun testSamePackageRdeps_two() {
    }

    @Throws(Exception::class)
    override fun testSamePackageRdeps_twoPackages() {
    }

    @Throws(Exception::class)
    override fun testSamePackageRdeps_crissCross() {
    }

    // We eagerly load all packages, so can't test that we don't load one.
    @Test
    override fun testWildcardsDontLoadUnnecessaryPackages() {
    }

    @Test
    override fun boundedDepsWithError() {
    }

    // Query needs a graph.
    @Test
    override fun testGraphOrderOfWildcards() {
    }

    // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
    // would never occur because the visibility error would occur first.
    @Test
    @Throws(Exception::class)
    override fun testVisibleWithNonPackageGroupVisibility() {
    }

    // Visibility is checked in the analysis phase, so the post-analysis query done in this unit test
    // would never occur because the visibility error would occur first.
    @Test
    @Throws(Exception::class)
    override fun testVisibleWithPackageGroupWithNonPackageGroupIncludes() {
    }

    // We don't support --nodep_deps=false.
    @Test
    @Throws(Exception::class)
    override fun testNodepDeps_false() {
    }

    // package_group instances have a null configuration and are filtered out by --host_deps=false.
    @Test
    @Throws(Exception::class)
    override fun testDefaultVisibilityReturnedInDeps_nonEmptyDependencyFilter() {
    }

    companion object {
        const val DEFAULT_UNIVERSE: String = "DEFAULT_UNIVERSE"

        protected fun assertConfigurableQueryCode(failureDetail: FailureDetail, code: Code?) {
            assertThat(failureDetail.getConfigurableQuery().getCode()).isEqualTo(code)
        }

        protected fun unique(list: MutableList<String?>): MutableList<String?> {
            return list.stream().distinct().toList()
        }
    }
}
