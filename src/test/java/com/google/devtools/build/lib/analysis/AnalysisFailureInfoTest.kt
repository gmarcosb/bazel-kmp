// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.test.AnalysisFailure

/**
 * Tests verifying analysis failure propagation via [AnalysisFailureInfo] when `--allow_analysis_failures=true`.
 */
@RunWith(TestParameterInjector::class)
class AnalysisFailureInfoTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        useConfiguration("--allow_analysis_failures=true")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisFailureInfoStarlarkApi() {
        val label: Label? = Label.create("test", "test")
        val failure: AnalysisFailure = AnalysisFailure.create(label, "ErrorMessage")
        Truth.assertThat(getattr(failure, "label")).isSameInstanceAs(label)
        Truth.assertThat(getattr(failure, "message")).isEqualTo("ErrorMessage")

        val info: AnalysisFailureInfo? =
            AnalysisFailureInfo.forAnalysisFailures(com.google.common.collect.ImmutableList.of<E?>(failure))
        // info.causes.to_list()[0] == failure
        val causes: NestedSet<AnalysisFailure?> =
            Depset.cast(getattr(info, "causes"), AnalysisFailure::class.java, "causes")
        assertThat(causes.toList().get(0)).isSameInstanceAs(failure)
    }

    /** Regression test for b/154007057 (rule name) and b/186685477 (output file).  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRuleExpanderFailure(
        @TestParameter("//test:bad_variable", "//test:bad_variable.out") targetToRequest: String?
    ) {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "bad_variable",
            outs = ["bad_variable.out"],
            cmd = "cp ${'$'}< ${'$'}@",  # Error to use ${'$'}< with no srcs
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget(targetToRequest)
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains("variable '$<' : no input file")
        assertThat(failure.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:bad_variable"))
    }

    /** Regression test for b/154007057.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeRuleConfiguredTargetFactoryCreateReturningNull() {
        scratch.file(
            "test/BUILD",
            """
        native_rule_with_failing_configured_target_factory(
            name = "bad_factory",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:bad_factory")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains("FailingRuleConfiguredTargetFactory.create() fails")
        assertThat(failure.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:bad_factory"))
    }

    /** Dummy factory whose `create()` method always returns `null`.  */
    class FailingRuleConfiguredTargetFactory

        : RuleConfiguredTargetFactory {
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            ruleContext.ruleError("FailingRuleConfiguredTargetFactory.create() fails")
            return null
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisTestNotReturningAnalysisTestResultInfo_cannotPropagate() {
        scratch.file(
            "test/BUILD",  //
            "providerless_analysis_lib(name = 'providerless')"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:providerless")
        assertContainsEvent(
            "Error while collecting analysis-phase failure information for '//test:providerless': rules"
                    + " with analysis_test=true must return an instance of AnalysisTestResultInfo"
        )
    }

    /** Regression test for b/233890545  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analysisTestExpectingFailureDependedOnByAnalysisTest_cannotPropagate() {
        useConfiguration("--allow_analysis_failures=false")
        scratch.file(
            "test/extension.bzl",
            """
        def bad_rule_impl(ctx):
            fail("Bad rule fails")

        bad_rule = rule(
            implementation = bad_rule_impl,
            attrs = {"dep": attr.label()},
        )

        def analysis_test_impl(ctx):
            return [AnalysisTestResultInfo(success = False, message = "Expect failure")]

        _transition = analysis_test_transition(
            settings = {"//command_line_option:allow_analysis_failures": "True"},
        )

        analysis_test = rule(
            implementation = analysis_test_impl,
            analysis_test = True,
            attrs = {"dep": attr.label(cfg = _transition)},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "analysis_test", "bad_rule")

        analysis_test(
            name = "outer",
            dep = ":inner",
        )

        analysis_test(
            name = "inner",
            dep = ":tested_by_inner",
        )

        bad_rule(name = "tested_by_inner")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:outer")
        assertContainsEvent(
            ("Error while collecting analysis-phase failure information for '//test:inner':"
                    + " analysis_test rule '//test:inner' cannot be transitively depended on by another"
                    + " analysis test rule")
        )
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(
                    (MockRule {
                        MockRule.factory(FailingRuleConfiguredTargetFactory::class.java)
                            .define("native_rule_with_failing_configured_target_factory")
                    } as MockRule))
                .addRuleDefinition(
                    MockRule {
                        MockRule.ancestor(BaseRuleClasses.NativeBuildRule::class.java)
                            .type(RuleClassType.NORMAL)
                            .define(
                                "providerless_analysis_lib",
                                MockRuleCustomBehavior { ruleClassBuilder: RuleClass.Builder?, env: RuleDefinitionEnvironment? -> ruleClassBuilder.setIsAnalysisTest() })
                    } as MockRule)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleFailure() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_rule_impl(ctx):
            fail("This Is My Failure Message")

        custom_rule = rule(implementation = custom_rule_impl)
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(name = "r")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:r")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains("This Is My Failure Message")
        assertThat(failure.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:r"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleFailure_forTest() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_rule_impl(ctx):
            fail("This Is My Failure Message")

        custom_test = rule(
            implementation = custom_rule_impl,
            test = True,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_test")

        custom_test(name = "r")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:r")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains("This Is My Failure Message")
        assertThat(failure.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:r"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkRuleFailure_withOutput() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_rule_impl(ctx):
            fail("This Is My Failure Message")

        custom_rule = rule(
            implementation = custom_rule_impl,
            outputs = {"my_output": "%{name}.txt"},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(name = "r")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:r")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val failure: AnalysisFailure = info.getCauses().getSet(AnalysisFailure::class.java).toList().get(0)
        com.google.common.truth.Subject.contains("This Is My Failure Message")
        assertThat(failure.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:r"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transitiveStarlarkRuleFailure() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_rule_impl(ctx):
            fail("This Is My Failure Message")

        custom_rule = rule(implementation = custom_rule_impl)

        def depending_rule_impl(ctx):
            return []

        depending_rule = rule(
            implementation = depending_rule_impl,
            attrs = {"deps": attr.label_list()},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule", "depending_rule")

        custom_rule(name = "one")

        custom_rule(name = "two")

        depending_rule(
            name = "failures_are_direct_deps",
            deps = [
                ":one",
                ":two",
            ],
        )

        depending_rule(
            name = "failures_are_indirect_deps",
            deps = [":failures_are_direct_deps"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:failures_are_indirect_deps")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo

        val expectedOne: AnalysisFailure? =
            AnalysisFailure.create(
                Label.parseCanonicalUnchecked("//test:one"), "This Is My Failure Message"
            )
        val expectedTwo: AnalysisFailure? =
            AnalysisFailure.create(
                Label.parseCanonicalUnchecked("//test:two"), "This Is My Failure Message"
            )

        assertThat(info.getCausesNestedSet().toList())
            .comparingElementsUsing(analysisFailureCorrespondence)
            .containsExactly(expectedOne, expectedTwo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkAspectFailure() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_aspect_impl(target, ctx):
            fail("This Is My Aspect Failure Message")

        custom_aspect = aspect(implementation = custom_aspect_impl, attr_aspects = ["deps"])

        def custom_rule_impl(ctx):
            return []

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {"deps": attr.label_list(aspects = [custom_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(name = "one")

        custom_rule(
            name = "two",
            deps = [":one"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:two")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val expectedOne: AnalysisFailure? =
            AnalysisFailure.create(
                Label.parseCanonicalUnchecked("//test:one"), "This Is My Aspect Failure Message"
            )

        assertThat(info.getCausesNestedSet().toList())
            .comparingElementsUsing(analysisFailureCorrespondence)
            .containsExactly(expectedOne)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transitiveStarlarkAspectFailure() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_aspect_impl(target, ctx):
            if hasattr(ctx.rule.attr, "kaboom") and ctx.rule.attr.kaboom:
                fail("This Is My Aspect Failure Message")
            return []

        custom_aspect = aspect(implementation = custom_aspect_impl, attr_aspects = ["deps"])

        def custom_rule_impl(ctx):
            return []

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {
                "deps": attr.label_list(aspects = [custom_aspect]),
                "kaboom": attr.bool(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(
            name = "one",
            kaboom = True,
        )

        custom_rule(
            name = "two",
            deps = [":one"],
        )

        custom_rule(
            name = "three",
            deps = [":two"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:three")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val expectedOne: AnalysisFailure? =
            AnalysisFailure.create(
                Label.parseCanonicalUnchecked("//test:one"), "This Is My Aspect Failure Message"
            )

        assertThat(info.getCausesNestedSet().toList())
            .comparingElementsUsing(analysisFailureCorrespondence)
            .containsExactly(expectedOne)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkAspectAndRuleFailure_analysisFailureInfoPropagatesOnlyFromRuleFailure() {
        scratch.file(
            "test/extension.bzl",
            """
        def custom_aspect_impl(target, ctx):
            fail("This Is My Aspect Failure Message")

        custom_aspect = aspect(implementation = custom_aspect_impl, attr_aspects = ["deps"])

        def custom_rule_impl(ctx):
            fail("This Is My Rule Failure Message")

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {"deps": attr.label_list(aspects = [custom_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(name = "one")

        custom_rule(
            name = "two",
            deps = [":one"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:two")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val expectedRuleFailure: AnalysisFailure? =
            AnalysisFailure.create(
                Label.parseCanonicalUnchecked("//test:one"), "This Is My Rule Failure Message"
            )

        assertThat(info.getCausesNestedSet().toList())
            .comparingElementsUsing(analysisFailureCorrespondence)
            .containsExactly(expectedRuleFailure)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkAspectWithAdvertisedProvidersFailure_analysisFailurePropagates() {
        scratch.file(
            "test/extension.bzl",
            """
        MyInfo = provider()

        def custom_aspect_impl(target, ctx):
            fail("Aspect Failure")

        custom_aspect = aspect(implementation = custom_aspect_impl, provides = [MyInfo])

        def custom_rule_impl(ctx):
            pass

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {"deps": attr.label_list(aspects = [custom_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:extension.bzl", "custom_rule")

        custom_rule(name = "one")

        custom_rule(
            name = "two",
            deps = [":one"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:two")
        val info: AnalysisFailureInfo =
            target.get(AnalysisFailureInfo.provider.getKey()) as AnalysisFailureInfo
        val expectedRuleFailure: AnalysisFailure? =
            AnalysisFailure.create(Label.parseCanonicalUnchecked("//test:one"), "Aspect Failure")

        assertThat(info.getCausesNestedSet().toList())
            .comparingElementsUsing(analysisFailureCorrespondence)
            .containsExactly(expectedRuleFailure)
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun getattr(x: Any?, name: String?): Any? {
            return Starlark.getattr( /*mu=*/null, StarlarkSemantics.DEFAULT, x, name, null)
        }

        private val analysisFailureCorrespondence: Correspondence<AnalysisFailure?, AnalysisFailure?> =
            Correspondence.from<AnalysisFailure?, AnalysisFailure?>(
                BinaryPredicate { actual: AnalysisFailure?, expected: AnalysisFailure? ->
                    actual.getLabel().equals(expected.getLabel())
                            && actual.getMessage().contains(expected.getMessage())
                },
                "is equivalent to"
            )
    }
}
