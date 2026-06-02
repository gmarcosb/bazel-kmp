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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [RequiredConfigFragmentsProvider].  */
@RunWith(TestParameterInjector::class)
class RequiredConfigFragmentsTest : BuildViewTestCase() {
    @OptionsClass
    abstract class AOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "a_option",
            defaultValue = "",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN]
        )
        abstract val aOption: String?
    }

    /**
     * Public for [com.google.devtools.build.lib.analysis.config.FragmentFactory]'s
     * reflection-based construction.
     */
    @RequiresOptions(options = [AOptions::class])
    class TestFragmentA(options: BuildOptions?) : Fragment()

    /**
     * Public for [com.google.devtools.build.lib.analysis.config.FragmentFactory]'s
     * reflection-based construction.
     */
    class TestFragmentB(options: BuildOptions?) : Fragment()

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(RuleThatAttachesAspect())
                .addRuleDefinition(REQUIRES_FRAGMENT_A)
                .addRuleDefinition(REQUIRES_FRAGMENT_B)
                .addNativeAspectClass(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS)
                .addConfigurationFragment(TestFragmentA::class.java)
                .addConfigurationFragment(TestFragmentB::class.java)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun provideTransitiveRequiredFragmentsMode() {
        useConfiguration("--include_config_fragments_provider=transitive")
        scratch.file(
            "a/BUILD",
            """
        requires_fragment_b(name = "b")

        requires_fragment_a(
            name = "a",
            deps = [":b"],
        )
        
        """.trimIndent()
        )

        val aTransitiveFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider::class.java)
        assertThat(aTransitiveFragments.fragmentClasses())
            .containsAtLeast(TestFragmentA::class.java, TestFragmentB::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configSettingProvideTransitiveRequiresFragment() {
        useConfiguration("--include_config_fragments_provider=transitive")
        scratch.file(
            "a/BUILD",
            """
        config_setting(
            name = "config_on_native",
            values = {"compilation_mode": "dbg"},
        )

        config_setting(
            name = "config_on_a",
            values = {"a_option": "foo"},
        )
        
        """.trimIndent()
        )

        com.google.common.truth.Subject.contains(AOptions::class.java)
        assertThat(
            getConfiguredTarget("//a:config_on_native")
                .getProvider(RequiredConfigFragmentsProvider::class.java)
                .optionsClasses()
        )
            .doesNotContain(AOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun provideDirectRequiredFragmentsMode() {
        useConfiguration("--include_config_fragments_provider=direct")
        scratch.file(
            "a/BUILD",
            """
        requires_fragment_b(name = "b")

        requires_fragment_a(
            name = "a",
            deps = [":b"],
        )
        
        """.trimIndent()
        )

        val aDirectFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider::class.java)
        com.google.common.truth.Subject.contains(TestFragmentA::class.java)
        assertThat(aDirectFragments.fragmentClasses()).doesNotContain(TestFragmentB::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configSettingProvideDirectRequiresFragment() {
        useConfiguration("--include_config_fragments_provider=direct")
        scratch.file(
            "a/BUILD",
            """
        config_setting(
            name = "config_on_native",
            values = {"compilation_mode": "dbg"},
        )

        config_setting(
            name = "config_on_a",
            values = {"a_option": "foo"},
        )
        
        """.trimIndent()
        )

        com.google.common.truth.Subject.contains(AOptions::class.java)
        assertThat(
            getConfiguredTarget("//a:config_on_native")
                .getProvider(RequiredConfigFragmentsProvider::class.java)
                .optionsClasses()
        )
            .doesNotContain(AOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun requiresMakeVariablesSuppliedByDefine() {
        useConfiguration("--include_config_fragments_provider=direct", "--define", "myvar=myval")
        scratch.file(
            "a/BUILD",
            """
        genrule(
            name = "myrule",
            srcs = [],
            outs = ["myrule.out"],
            cmd = "echo ${'$'}(myvar) ${'$'}(COMPILATION_MODE) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:myrule").getProvider(RequiredConfigFragmentsProvider::class.java)
        assertThat(requiredFragments.defines()).containsExactly("myvar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkExpandMakeVariables() {
        useConfiguration("--include_config_fragments_provider=direct", "--define=myvar=myval")
        scratch.file(
            "a/defs.bzl",
            """
        def _impl(ctx):
            print(ctx.expand_make_variables("dummy attribute", "string with ${'$'}(myvar)!", {}))

        simple_rule = rule(
            implementation = _impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "simple_rule")

        simple_rule(name = "simple")
        
        """.trimIndent()
        )
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider::class.java)
        assertThat(requiredFragments.defines()).containsExactly("myvar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCtxVar() {
        useConfiguration(
            "--include_config_fragments_provider=direct", "--define=required_var=1,irrelevant_var=1"
        )
        scratch.file(
            "a/defs.bzl",
            """
        def _impl(ctx):
            # Defined, so reported as required.
            if "required_var" not in ctx.var:
                fail("Missing required_var")

            # Not defined, so not reported as required.
            if "prohibited_var" in ctx.var:
                fail("Not allowed to set prohibited_var")

            # Present but not a define variable, so not reported as required.
            if "COMPILATION_MODE" not in ctx.var:
                fail("Missing COMPILATION_MODE")

        simple_rule = rule(
            implementation = _impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "simple_rule")

        simple_rule(name = "simple")
        
        """.trimIndent()
        )
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:simple").getProvider(RequiredConfigFragmentsProvider::class.java)
        assertThat(requiredFragments.defines()).containsExactly("required_var")
    }

    /**
     * Aspect that requires fragments both in its definition and through [ ][.addAspectImplSpecificRequiredConfigFragments].
     */
    private class AspectWithConfigFragmentRequirements : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun getDefinition(params: AspectParameters?): AspectDefinition {
            return Builder(this)
                .requiresConfigurationFragments(REQUIRED_FRAGMENT)
                .build()
        }

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            params: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext).build()
        }

        public override fun addAspectImplSpecificRequiredConfigFragments(
            requiredFragments: RequiredConfigFragmentsProvider.Builder
        ) {
            requiredFragments.addDefine(REQUIRED_DEFINE)
        }

        companion object {
            private val REQUIRED_FRAGMENT: java.lang.Class<JavaConfiguration?> = JavaConfiguration::class.java
            private const val REQUIRED_DEFINE = "myvar"
        }
    }

    /** Rule that attaches [AspectWithConfigFragmentRequirements] to its deps.  */
    class RuleThatAttachesAspect

        : RuleDefinition, RuleConfiguredTargetFactory {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .add(
                    attr("deps", LABEL_LIST)
                        .allowedFileTypes(FileTypeSet.NO_FILE)
                        .aspect(ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS)
                )
                .build()
        }

        val metadata: Metadata
            get() = RuleDefinition.Metadata.builder()
                .name("rule_that_attaches_aspect")
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .factoryClass(RuleThatAttachesAspect::class.java)
                .build()

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        public override fun create(ruleContext: RuleContext?): ConfiguredTarget? {
            return RuleConfiguredTargetBuilder(ruleContext)
                .addProvider(RunfilesProvider.EMPTY)
                .build()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectRequiresFragments() {
        scratch.file(
            "a/BUILD",
            """
        rule_that_attaches_aspect(
            name = "parent",
            deps = [":dep"],
        )

        rule_that_attaches_aspect(name = "dep")
        
        """.trimIndent()
        )
        useConfiguration("--include_config_fragments_provider=transitive")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:parent").getProvider(RequiredConfigFragmentsProvider::class.java)
        com.google.common.truth.Subject.contains(AspectWithConfigFragmentRequirements.Companion.REQUIRED_FRAGMENT)
        assertThat(requiredFragments.defines())
            .containsExactly(AspectWithConfigFragmentRequirements.Companion.REQUIRED_DEFINE)
    }

    @Throws(java.lang.Exception::class)
    private fun writeStarlarkTransitionsAndAllowList() {
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
            "transitions/defs.bzl",
            """
        def _java_write_transition_impl(settings, attr):
            return {"//command_line_option:javacopt": ["foo"]}

        java_write_transition = transition(
            implementation = _java_write_transition_impl,
            inputs = [],
            outputs = ["//command_line_option:javacopt"],
        )

        def _cpp_read_transition_impl(settings, attr):
            return {}

        cpp_read_transition = transition(
            implementation = _cpp_read_transition_impl,
            inputs = ["//command_line_option:copt"],
            outputs = [],
        )
        
        """.trimIndent()
        )
        scratch.file("transitions/BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleTransitionReadsFragment() {
        writeStarlarkTransitionsAndAllowList()
        scratch.file(
            "a/defs.bzl",
            """
        load("//transitions:defs.bzl", "cpp_read_transition")

        def _impl(ctx):
            pass

        has_cpp_aware_rule_transition = rule(
            implementation = _impl,
            cfg = cpp_read_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "has_cpp_aware_rule_transition")

        has_cpp_aware_rule_transition(name = "cctarget")
        
        """.trimIndent()
        )
        useConfiguration("--include_config_fragments_provider=direct")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:cctarget").getProvider(RequiredConfigFragmentsProvider::class.java)
        com.google.common.truth.Subject.contains(CppOptions::class.java)
        assertThat(requiredFragments.optionsClasses()).doesNotContain(JavaOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleTransitionWritesFragment() {
        writeStarlarkTransitionsAndAllowList()
        scratch.file(
            "a/defs.bzl",
            """
        load("//transitions:defs.bzl", "java_write_transition")

        def _impl(ctx):
            pass

        has_java_aware_rule_transition = rule(
            implementation = _impl,
            cfg = java_write_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "has_java_aware_rule_transition")

        has_java_aware_rule_transition(name = "javatarget")
        
        """.trimIndent()
        )
        useConfiguration("--include_config_fragments_provider=direct")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:javatarget").getProvider(RequiredConfigFragmentsProvider::class.java)
        com.google.common.truth.Subject.contains(JavaOptions::class.java)
        assertThat(requiredFragments.optionsClasses()).doesNotContain(CppOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkAttrTransition() {
        writeStarlarkTransitionsAndAllowList()
        scratch.file(
            "a/defs.bzl",
            """
        load("//transitions:defs.bzl", "cpp_read_transition", "java_write_transition")

        def _impl(ctx):
            pass

        has_java_aware_attr_transition = rule(
            implementation = _impl,
            attrs = {
                "deps": attr.label_list(cfg = java_write_transition),
            },
        )
        has_cpp_aware_rule_transition = rule(
            implementation = _impl,
            cfg = cpp_read_transition,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("//a:defs.bzl", "has_cpp_aware_rule_transition", "has_java_aware_attr_transition")

        has_cpp_aware_rule_transition(name = "ccchild")

        has_java_aware_attr_transition(
            name = "javaparent",
            deps = [":ccchild"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--include_config_fragments_provider=direct")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:javaparent").getProvider(RequiredConfigFragmentsProvider::class.java)
        // We consider the attribute transition over the parent -> child edge a property of the parent.
        com.google.common.truth.Subject.contains(JavaOptions::class.java)
        // But not the child's rule transition.
        assertThat(requiredFragments.optionsClasses()).doesNotContain(CppOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectInheritsTransitiveFragmentsFromBaseCT(
        @TestParameter("DIRECT", "TRANSITIVE") setting: IncludeConfigFragmentsEnum?
    ) {
        writeStarlarkTransitionsAndAllowList()
        scratch.file(
            "a/defs.bzl",
            """
        A1Info = provider()

        def _a1_impl(target, ctx):
            return []

        a1 = aspect(implementation = _a1_impl)

        def _java_depender_impl(ctx):
            return []

        java_depender = rule(
            implementation = _java_depender_impl,
            fragments = ["java"],
            attrs = {},
        )

        def _r_impl(ctx):
            return []

        r = rule(
            implementation = _r_impl,
            attrs = {"dep": attr.label(aspects = [a1])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(":defs.bzl", "java_depender", "r")

        java_depender(name = "lib")

        r(
            name = "r",
            dep = ":lib",
        )
        
        """.trimIndent()
        )

        useConfiguration("--include_config_fragments_provider=" + setting)
        getConfiguredTarget("//a:r")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getAspect("//a:defs.bzl%a1").getProvider(RequiredConfigFragmentsProvider::class.java)

        if (setting === IncludeConfigFragmentsEnum.TRANSITIVE) {
            com.google.common.truth.Subject.contains(JavaConfiguration::class.java)
        } else {
            assertThat(requiredFragments.fragmentClasses()).doesNotContain(JavaConfiguration::class.java)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectInheritsTransitiveFragmentsFromRequiredAspect(
        @TestParameter("DIRECT", "TRANSITIVE") setting: IncludeConfigFragmentsEnum?
    ) {
        scratch.file(
            "a/defs.bzl",
            """
        A1Info = provider()

        def _a1_impl(target, ctx):
            return A1Info(var = ctx.var.get("my_var", "0"))

        a1 = aspect(implementation = _a1_impl, provides = [A1Info])

        A2Info = provider()

        def _a2_impl(target, ctx):
            return A2Info()

        a2 = aspect(implementation = _a2_impl, required_aspect_providers = [A1Info])

        def _simple_rule_impl(ctx):
            return []

        simple_rule = rule(
            implementation = _simple_rule_impl,
            attrs = {},
        )

        def _r_impl(ctx):
            return []

        r = rule(
            implementation = _r_impl,
            attrs = {"dep": attr.label(aspects = [a1, a2])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(":defs.bzl", "r", "simple_rule")

        simple_rule(name = "lib")

        r(
            name = "r",
            dep = ":lib",
        )
        
        """.trimIndent()
        )

        useConfiguration("--include_config_fragments_provider=" + setting, "--define", "my_var=1")
        getConfiguredTarget("//a:r")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getAspect("//a:defs.bzl%a2").getProvider(RequiredConfigFragmentsProvider::class.java)

        if (setting === IncludeConfigFragmentsEnum.TRANSITIVE) {
            com.google.common.truth.Subject.contains("my_var")
        } else {
            assertThat(requiredFragments.defines()).doesNotContain("my_var")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidStarlarkFragmentsFiltered() {
        scratch.file(
            "a/defs.bzl",
            """
        def _my_rule_impl(ctx):
            pass

        my_rule = rule(implementation = _my_rule_impl, fragments = ["java", "doesnotexist"])
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(":defs.bzl", "my_rule")

        my_rule(name = "example")
        
        """.trimIndent()
        )

        useConfiguration("--include_config_fragments_provider=direct")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:example").getProvider(RequiredConfigFragmentsProvider::class.java)

        com.google.common.truth.Subject.contains(JavaConfiguration::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectInErrorWithAllowAnalysisFailures() {
        scratch.file(
            "a/defs.bzl",
            """
        def _error_aspect_impl(target, ctx):
            fail(ctx.var["FAIL_MESSAGE"])

        error_aspect = aspect(implementation = _error_aspect_impl)

        def _my_rule_impl(ctx):
            pass

        my_rule = rule(
            implementation = _my_rule_impl,
            attrs = {"dep": attr.label(aspects = [error_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(":defs.bzl", "error_aspect", "my_rule")

        my_rule(name = "a")

        my_rule(
            name = "b",
            dep = ":a",
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--allow_analysis_failures",
            "--define=FAIL_MESSAGE=abc",
            "--include_config_fragments_provider=direct"
        )
        getConfiguredTarget("//a:b")
        val requiredFragments: RequiredConfigFragmentsProvider =
            getAspect("//a:defs.bzl%error_aspect").getProvider(RequiredConfigFragmentsProvider::class.java)

        assertThat(requiredFragments.defines()).containsExactly("FAIL_MESSAGE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configuredTargetInErrorWithAllowAnalysisFailures() {
        scratch.file(
            "a/defs.bzl",
            """
        def _error_rule_impl(ctx):
            fail(ctx.var["FAIL_MESSAGE"])

        error_rule = rule(implementation = _error_rule_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(":defs.bzl", "error_rule")

        error_rule(name = "error")
        
        """.trimIndent()
        )

        useConfiguration(
            "--allow_analysis_failures",
            "--define=FAIL_MESSAGE=abc",
            "--include_config_fragments_provider=direct"
        )
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:error").getProvider(RequiredConfigFragmentsProvider::class.java)

        assertThat(requiredFragments.defines()).containsExactly("FAIL_MESSAGE")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithSelectResolvesToConfigSetting() {
        scratch.file(
            "a/BUILD",
            String.format(
                """
            config_setting(
                name = "define_x",
                define_values = {"x": "1"},
            )

            config_setting(
                name = "k8",
                constraint_values = ["%s"]
            )

            alias(
                name = "alias_to_setting",
                actual = select({":define_x": ":k8"}),
            )

            genrule(
                name = "gen",
                outs = ["gen.out"],
                cmd = select({":alias_to_setting": "touch ${'$'}@"}),
            )
            
            """.trimIndent(),
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64"
            )
        )

        useConfiguration(
            "--define=x=1",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--include_config_fragments_provider=transitive"
        )
        val requiredFragments: RequiredConfigFragmentsProvider =
            getConfiguredTarget("//a:gen").getProvider(RequiredConfigFragmentsProvider::class.java)

        assertThat(requiredFragments.defines()).containsExactly("x")
    }

    companion object {
        private val REQUIRES_FRAGMENT_A: MockRule = MockRule {
            MockRule.Companion.define(
                "requires_fragment_a",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(
                            attr("deps", BuildType.LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                        )
                        .requiresConfigurationFragments(TestFragmentA::class.java)
                })
        }

        private val REQUIRES_FRAGMENT_B: MockRule = MockRule {
            MockRule.Companion.define(
                "requires_fragment_b",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder.requiresConfigurationFragments(
                        TestFragmentB::class.java
                    )
                })
        }

        private val ASPECT_WITH_CONFIG_FRAGMENT_REQUIREMENTS = AspectWithConfigFragmentRequirements()
    }
}
