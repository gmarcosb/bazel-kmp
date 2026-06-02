// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.BaseRuleClasses.ACTION_LISTENER

/**
 * Tests for aspect creation and merging with configured targets.
 * 
 * 
 * Uses the complete analysis machinery and depends on custom rules so that behaviors related to
 * aspects can be tested even if they aren't used by regular rules.
 */
@RunWith(JUnit4::class)
class AspectTest : AnalysisTestCase() {
    @Throws(java.lang.Exception::class)
    private fun pkg(name: String?, vararg contents: String?) {
        scratch.file(name + "/BUILD", *contents)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectAppliedToAliasWithSelect() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE)
        pkg(
            "a",
            "aspect(name='a', foo=[':b'])",
            "alias(name='b', actual=select({'//conditions:default': ':c'}))",
            "base(name='c')"
        )
        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:c", "rule //a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectAppliedToChainedAliases() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE)
        pkg(
            "a",
            "aspect(name='a', foo=[':b'])",
            "alias(name='b', actual=':c')",
            "alias(name='c', actual=':d')",
            "alias(name='d', actual=':e')",
            "base(name='e')"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:e", "rule //a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectAppliedToChainedAliasesAndSelect() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE)
        pkg(
            "a",
            "aspect(name='a', foo=[':b'])",
            "alias(name='b', actual=select({'//conditions:default': ':c'}))",
            "alias(name='c', actual=select({'//conditions:default': ':d'}))",
            "base(name='d')"
        )
        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:d", "rule //a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileProviderMerged() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.FILE_PROVIDER_ASPECT_REQUIRING_RULE
        )
        pkg("a", "file_provider_aspect(name='a', dep=':b')", "filegroup(name='b', srcs=['source'])")

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val filesToBuild: NestedSet<Artifact?> = a.getProvider(FileProvider::class.java).getFilesToBuild()
        Truth.assertThat(ActionsTestUtil.Companion.baseArtifactNames(filesToBuild))
            .containsExactly("file_provider_aspect_file", "source")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providersOfAspectAreMergedIntoDependency() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE)
        pkg(
            "a",
            "aspect(name='a', foo=[':b'])",
            "aspect(name='b', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:b", "rule //a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatedThroughAliasRule() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
            TestAspects.ASPECT_REQUIRING_PROVIDER_RULE
        )

        pkg(
            "a",
            "aspect_requiring_provider(name='a', foo=[':b_alias'])",
            "alias(name = 'b_alias', actual = ':b')",
            "honest(name='b', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("rule //a:a", "aspect //a:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatedThroughAliasRuleAndHonestRules() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
            TestAspects.ASPECT_REQUIRING_PROVIDER_RULE
        )

        pkg(
            "a",
            "aspect_requiring_provider(name='a', foo=[':b'])",
            "alias(name = 'b_alias', actual = ':b')",
            "honest(name='b', foo=[':c'])",
            "honest(name='c', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("rule //a:a", "aspect //a:b", "aspect //a:c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCreatedIfAdvertisedProviderIsPresent() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
            TestAspects.ASPECT_REQUIRING_PROVIDER_RULE
        )

        pkg(
            "a",
            "aspect_requiring_provider(name='a', foo=[':b'])",
            "honest(name='b', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("rule //a:a", "aspect //a:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCreatedIfAtLeastOneSetOfAdvertisedProvidersArePresent() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
            TestAspects.HONEST_RULE_2, TestAspects.ASPECT_REQUIRING_PROVIDER_SETS_RULE
        )

        pkg(
            "a",
            "aspect_requiring_provider_sets(name='a', foo=[':b', ':c'])",
            "honest(name='b', foo=[])",
            "honest2(name='c', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("rule //a:a", "aspect //a:b", "aspect //a:c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithParametrizedDefinition() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE,
            TestAspects.HONEST_RULE,
            TestAspects.PARAMETERIZED_DEFINITION_ASPECT_RULE
        )

        pkg(
            "a",
            "honest(name='q', foo=[])",
            "parametrized_definition_aspect(name='a', foo=[':b'], baz='//a:q')",
            "honest(name='c', foo=[])",
            "honest(name='b', foo=[':c'])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly(
                "rule //a:a",
                "aspect //a:b data //a:q \$dep:[ //a:q]",
                "aspect //a:c data //a:q \$dep:[ //a:q]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectInError() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.ERROR_ASPECT_RULE,
            TestAspects.SIMPLE_RULE
        )

        pkg(
            "a",
            "simple(name='a', foo=[':b'])",
            "error_aspect(name='b', foo=[':c'])",
            "simple(name='c')"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // getConfiguredTarget() uses a separate code path that does not hit
        // SkyframeBuildView#configureTargets
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:a") })
        assertContainsEvent("Aspect error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transitiveAspectInError() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.ERROR_ASPECT_RULE,
            TestAspects.SIMPLE_RULE
        )

        pkg(
            "a",
            "error_aspect(name='a', foo=[':b'])",
            "error_aspect(name='b', bar=[':c'])",
            "error_aspect(name='c', bar=[':d'])",
            "error_aspect(name='d')"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // getConfiguredTarget() uses a separate code path that does not hit
        // SkyframeBuildView#configureTargets
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:a") })
        assertContainsEvent("Aspect error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDependenciesDontShowDeprecationWarnings() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.EXTRA_ATTRIBUTE_ASPECT_RULE)

        pkg("extra", "base(name='extra', deprecation='bad aspect')")

        pkg(
            "a",
            "rule_with_extra_deps_aspect(name='a', foo=[':b'])",
            "base(name='b')"
        )

        getConfiguredTarget("//a:a")
        assertContainsEventWithFrequency("bad aspect", 0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDependsOnPackageGroup() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.PACKAGE_GROUP_ATTRIBUTE_ASPECT_RULE
        )
        pkg("extra", "package_group(name='extra')")
        pkg("a", "rule_with_package_group_deps_aspect(name='a', foo=[':b'])", "base(name='b')")

        getConfiguredTarget("//a:a")
        assertContainsEventWithFrequency("bad aspect", 0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithComputedAttribute() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.COMPUTED_ATTRIBUTE_ASPECT_RULE)

        pkg("a", "rule_with_computed_deps_aspect(name='a', foo=[':b'])", "base(name='b')")

        getConfiguredTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDependencyDeprecationWarningsAbsentDuringAspectEvaluations() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE)

        pkg("a", "aspect(name='a', foo=['//b:b'])")
        pkg("b", "aspect(name='b', bar=['//d:d'])")
        pkg("d", "base(name='d', deprecation='bad rule')")

        getConfiguredTarget("//a:a")
        assertContainsEventWithFrequency("bad rule", 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWarningsFilteredByOutputFiltersForAssociatedRules() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != TestConstants.InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.WARNING_ASPECT_RULE)
        pkg("a", "warning_aspect(name='a', foo=['//b:b', '//c:c'])")
        pkg("b", "base(name='b')")
        pkg("c", "base(name='c')")

        reporter.setOutputFilter(RegexOutputFilter.forPattern(java.util.regex.Pattern.compile("^//b:")))

        getConfiguredTarget("//a:a")
        assertContainsEventWithFrequency("Aspect warning on //b:b", 1)
        assertContainsEventWithFrequency("Aspect warning on //c:c", 0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameTargetInDifferentAttributes() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.ASPECT_REQUIRING_RULE,
            TestAspects.SIMPLE_RULE
        )
        pkg(
            "a",
            "aspect(name='a', foo=[':b'], bar=[':b'])",
            "aspect(name='b', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:b", "rule //a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameTargetInDifferentAttributesWithDifferentAspects() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.MULTI_ASPECT_RULE,
            TestAspects.SIMPLE_RULE
        )
        pkg(
            "a",
            "multi_aspect(name='a', foo=':b', bar=':b')",
            "simple(name='b')"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList()).containsExactly("foo", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun informationFromBaseRulePassedToAspect() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.HONEST_RULE,
            TestAspects.ASPECT_REQUIRING_PROVIDER_RULE
        )
        pkg(
            "a",
            "aspect_requiring_provider(name='a', foo=[':b'], baz='hello')",
            "honest(name='b', foo=[])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("rule //a:a", "aspect //a:b data hello")
    }

    /**
     * Rule definitions to be used in emptyAspectAttributesAreAvailableInRuleContext().
     */
    object EmptyAspectAttributesAreAvailableInRuleContext {
        val TEST_RULE: MockRule = MockRule {
            MockRule.ancestor(TestAspects.BASE_RULE.getClass())
                .factory(TestAspects.DummyRuleFactory::class.java)
                .define(
                    "testrule",
                    MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                        builder.add(
                            attr("foo", LABEL_LIST)
                                .legacyAllowAnyFileType()
                                .aspect(AspectWithEmptyLateBoundAttribute.Companion.INSTANCE)
                        )
                    })
        }

        class AspectWithEmptyLateBoundAttribute private constructor() : NativeAspectClass(), ConfiguredAspectFactory {
            public override fun getDefinition(params: AspectParameters?): AspectDefinition {
                return Builder(this)
                    .add(attr(":late", LABEL).value(LateBoundDefault.alwaysNull()))
                    .build()
            }

            @Throws(java.lang.InterruptedException::class, ActionConflictException::class)
            public override fun create(
                targetLabel: Label?,
                ct: ConfiguredTarget?,
                ruleContext: RuleContext,
                parameters: AspectParameters?,
                toolsRepository: RepositoryName?
            ): ConfiguredAspect {
                val lateBoundPrereq: Any? = ruleContext.getPrerequisite(":late")
                return Builder(ruleContext)
                    .addProvider(
                        AspectInfo::class.java,
                        AspectInfo(
                            NestedSetBuilder.create(
                                Order.STABLE_ORDER, if (lateBoundPrereq != null) "non-empty" else "empty"
                            )
                        )
                    )
                    .build()
            }

            companion object {
                val INSTANCE: AspectWithEmptyLateBoundAttribute = AspectWithEmptyLateBoundAttribute()
            }
        }
    }

    /**
     * An Aspect has a late-bound attribute with no value (that is, a LateBoundDefault whose
     * getDefault() returns `null`). Test that this attribute is available in the RuleContext which is
     * provided to the Aspect's `create()` method.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyAspectAttributesAreAvailableInRuleContext() {
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(
                TestAspects.SIMPLE_ASPECT,
                AspectWithEmptyLateBoundAttribute.Companion.INSTANCE
            ),
            com.google.common.collect.ImmutableList.of<E?>(
                TestAspects.BASE_RULE, EmptyAspectAttributesAreAvailableInRuleContext.TEST_RULE
            )
        )
        pkg(
            "a",
            "testrule(name='a', foo=[':b'])",
            "testrule(name='b')"
        )
        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        com.google.common.truth.Subject.contains("empty")
    }

    /**
     * Rule definitions to be used in extraActionsAreEmitted().
     */
    object ExtraActionsAreEmitted {
        val TEST_RULE: MockRule = MockRule {
            MockRule.ancestor(TestAspects.BASE_RULE.getClass())
                .factory(TestAspects.DummyRuleFactory::class.java)
                .define(
                    "testrule",
                    MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                        builder
                            .add(
                                attr("foo", LABEL_LIST)
                                    .legacyAllowAnyFileType()
                                    .aspect(AspectThatRegistersAction.Companion.INSTANCE)
                            )
                            .add(
                                attr(":action_listener", LABEL_LIST)
                                    .cfg(ExecutionTransitionFactory.createFactory())
                                    .value(ACTION_LISTENER)
                            )
                    })
        }

        class AspectThatRegistersAction private constructor() : NativeAspectClass(), ConfiguredAspectFactory {
            public override fun getDefinition(params: AspectParameters?): AspectDefinition {
                return Builder(this).build()
            }

            @Throws(java.lang.InterruptedException::class, ActionConflictException::class)
            public override fun create(
                targetLabel: Label?,
                ct: ConfiguredTarget?,
                ruleContext: RuleContext,
                parameters: AspectParameters?,
                toolsRepository: RepositoryName?
            ): ConfiguredAspect {
                ruleContext.registerAction(NullAction(ruleContext.createOutputArtifact()))
                return Builder(ruleContext).build()
            }

            companion object {
                val INSTANCE: AspectThatRegistersAction = AspectThatRegistersAction()
            }
        }
    }

    /**
     * Test that actions registered in an Aspect are reported as extra-actions on the attached rule.
     * AspectThatRegistersAction registers a NullAction, whose mnemonic is "Null". We have an
     * action_listener that targets that mnemonic, which makes sure the Aspect machinery will expose
     * an ExtraActionArtifactsProvider.
     * The rule //a:a doesn't have an aspect, so the only action we get is the one on //a:b
     * (which does have an aspect).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extraActionsAreEmitted() {
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(
                TestAspects.SIMPLE_ASPECT, AspectThatRegistersAction.Companion.INSTANCE
            ),
            com.google.common.collect.ImmutableList.of<E?>(TestAspects.BASE_RULE, ExtraActionsAreEmitted.TEST_RULE)
        )
        useConfiguration("--experimental_action_listener=//extra_actions:listener")
        scratch.file(
            "extra_actions/BUILD",
            """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Null"],
        )
        
        """.trimIndent()
        )
        pkg(
            "a",
            "testrule(name='a', foo=[':b'])",
            "testrule(name='b')"
        )
        update()

        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val extraActionArtifacts: NestedSet<Artifact.DerivedArtifact?> =
            a.getProvider(ExtraActionArtifactsProvider::class.java).getTransitiveExtraActionArtifacts()
        for (artifact in extraActionArtifacts.toList()) {
            assertThat(artifact.getOwnerLabel()).isEqualTo(Label.create("@//a", "b"))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToAllAttributes() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.ALL_ATTRIBUTES_ASPECT_RULE
        )
        pkg(
            "a",
            "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
            "simple(name='b', foo=[], txt='some text')",
            "simple(name='c', foo=[], txt='more text')",
            "all_attributes_aspect(name='x', foo=[':a'])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:x")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:a", "aspect //a:b", "aspect //a:c", "rule //a:x")
    }

    /**
     * Tests that when --experimental_extra_action_top_level_only, Blaze reports extra-actions for
     * actions registered by Aspects injected by a top-level rule. Because we can't know whether an
     * aspect was injected by a top-level target or one of its children, we approximate it by only
     * reporting extra-actions from Aspects that the top-level target could have injected.
     * 
     * 
     * Here, injector1() and injector2() inject aspects into their children. null_rule() just
     * passes the aspects to its children. The test makes sure that actions registered by aspect1
     * (injected by injector1()) are reported to the extra-action mechanism. Actions registered by
     * aspect2 (from injector2) are not reported, because the target under test (//x:a) doesn't inject
     * aspect2.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extraActionsAreEmitted_topLevel() {
        useConfiguration(
            "--experimental_action_listener=//pkg1:listener",
            "--experimental_extra_action_top_level_only"
        )

        scratch.file(
            "x/BUILD",
            """
        load(":extension.bzl", "injector1", "injector2", "null_rule")

        injector1(
            name = "a",
            deps = [":b"],
        )

        null_rule(
            name = "b",
            deps = [":c"],
        )

        null_rule(
            name = "c",
            deps = [":d"],
        )

        injector2(
            name = "d",
            extra_deps = [":e"],
        )

        null_rule(name = "e")
        
        """.trimIndent()
        )

        scratch.file(
            "x/extension.bzl",
            """
        def _aspect_impl(target, ctx):
            ctx.actions.do_nothing(mnemonic = "Mnemonic")
            return []

        aspect1 = aspect(_aspect_impl, attr_aspects = ["deps"])
        aspect2 = aspect(_aspect_impl, attr_aspects = ["extra_deps"])

        def _rule_impl(ctx):
            return []

        injector1 = rule(_rule_impl, attrs = {"deps": attr.label_list(aspects = [aspect1])})
        null_rule = rule(_rule_impl, attrs = {"deps": attr.label_list()})
        injector2 = rule(
            _rule_impl,
            attrs = {"extra_deps": attr.label_list(aspects = [aspect2])},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "pkg1/BUILD",
            """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Mnemonic"],
        )
        
        """.trimIndent()
        )

        // Check: //x:d injects an aspect which produces some extra-action.
        run {
            val analysisResult: AnalysisResult = update("//x:d")
            // Get owners of all extra-action artifacts.
            val extraArtifactOwners: MutableList<Label?> = java.util.ArrayList<Label?>()
            for (artifact in analysisResult.getArtifactsToBuild()) {
                if (artifact.getRootRelativePathString().endsWith(".xa")) {
                    extraArtifactOwners.add(artifact.getOwnerLabel())
                }
            }
            Truth.assertThat(extraArtifactOwners).containsExactly(Label.create("@//x", "e"))
        }

        // Actual test: //x:a reports actions registered by the aspect it injects.
        run {
            val analysisResult: AnalysisResult = update("//x:a")
            // Get owners of all extra-action artifacts.
            val extraArtifactOwners: MutableList<Label?> = java.util.ArrayList<Label?>()
            for (artifact in analysisResult.getArtifactsToBuild()) {
                if (artifact.getRootRelativePathString().endsWith(".xa")) {
                    extraArtifactOwners.add(artifact.getOwnerLabel())
                }
            }
            Truth.assertThat(extraArtifactOwners)
                .containsExactly(
                    Label.create("@//x", "b"), Label.create("@//x", "c"), Label.create("@//x", "d")
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun extraActionsFromDifferentAspectsDontConflict() {
        useConfiguration(
            "--experimental_action_listener=//pkg1:listener",
            "--experimental_extra_action_top_level_only"
        )

        scratch.file(
            "x/BUILD",
            """
        load(":extension.bzl", "injector1", "injector2", "null_rule")

        injector2(
            name = "i2_a",
            deps = [":i1_a"],
        )

        injector1(
            name = "i1_a",
            param = "a",
            deps = [":n"],
        )

        injector1(
            name = "i1_b",
            param = "b",
            deps = [":n"],
        )

        injector2(
            name = "i2",
            deps = [":n"],
        )

        null_rule(name = "n")
        
        """.trimIndent()
        )

        scratch.file(
            "x/extension.bzl",
            """
        def _aspect_impl(target, ctx):
            ctx.actions.do_nothing(mnemonic = "Mnemonic")
            return []

        aspect1 = aspect(
            _aspect_impl,
            attr_aspects = ["deps"],
            attrs = {"param": attr.string(values = ["a", "b"])},
        )
        aspect2 = aspect(_aspect_impl, attr_aspects = ["deps"])

        def _rule_impl(ctx):
            return []

        injector1 = rule(
            _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [aspect1]), "param": attr.string()},
        )
        injector2 = rule(_rule_impl, attrs = {"deps": attr.label_list(aspects = [aspect2])})
        null_rule = rule(_rule_impl, attrs = {"deps": attr.label_list()})
        
        """.trimIndent()
        )

        scratch.file(
            "pkg1/BUILD",
            """
        extra_action(
            name = "xa",
            cmd = "echo dont-care",
        )

        action_listener(
            name = "listener",
            extra_actions = [":xa"],
            mnemonics = ["Mnemonic"],
        )
        
        """.trimIndent()
        )

        update("//x:i1_a", "//x:i1_b", "//x:i2", "//x:i2_a")

        // Implicitly check that update() didn't throw an exception because of two actions producing
        // the same outputs.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedArtifactsInAspect() {
        scratch.file(
            "foo/shared_aspect.bzl",
            """
        MyInfo = provider()
        def _shared_aspect_impl(target, ctx):
            shared_file = ctx.actions.declare_file("shared_file")
            ctx.actions.write(output = shared_file, content = "Shared content")
            lib = ctx.rule.attr.lib
            if lib:
                result = depset([shared_file], transitive = [ctx.rule.attr.lib[MyInfo].prov])
            else:
                result = depset([shared_file])
            return MyInfo(prov = result)

        shared_aspect = aspect(
            implementation = _shared_aspect_impl,
            attr_aspects = ["lib"],
        )

        def _rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _rule_impl,
            attrs = {"lib": attr.label(
                providers = [MyInfo],
                aspects = [shared_aspect],
            )},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(":shared_aspect.bzl", "shared_aspect", "simple_rule")

        simple_rule(
            name = "top_rule",
            lib = ":first_dep",
        )

        simple_rule(
            name = "first_dep",
            lib = ":second_dep",
        )

        simple_rule(name = "second_dep")
        
        """.trimIndent()
        )
        // Confirm that load is successful and doesn't crash.
        update("//foo:top_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToAllAttributesImplicit() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.IMPLICIT_DEP_RULE, TestAspects.ALL_ATTRIBUTES_ASPECT_RULE
        )
        scratch.file(
            "extra/BUILD",
            "simple(name ='extra')"
        )
        pkg(
            "a",
            "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
            "simple(name='b', foo=[], txt='some text')",
            "implicit_dep(name='c')",
            "all_attributes_aspect(name='x', foo=[':a'])"
        )
        update()

        val a: ConfiguredTarget = getConfiguredTarget("//a:x")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly(
                "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //extra:extra", "rule //a:x"
            )
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToAllAttributesLateBound() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.LATE_BOUND_DEP_RULE, TestAspects.ALL_ATTRIBUTES_ASPECT_RULE
        )

        scratch.file(
            "extra/BUILD",
            "simple(name ='extra')"
        )
        pkg(
            "a",
            "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
            "simple(name='b', foo=[], txt='some text')",
            "late_bound_dep(name='c')",
            "all_attributes_aspect(name='x', foo=[':a'])"
        )
        useConfiguration("--plugin=//extra:extra")
        update()

        val a: ConfiguredTarget = getConfiguredTarget("//a:x")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly(
                "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //extra:extra", "rule //a:x"
            )
    }

    /**
     * Ensures an aspect with attr = '*' doesn't try to propagate to its own implicit attributes.
     * Doing so leads to a dependency cycle.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithAllAttributesDoesNotPropagateToOwnImplicitAttributes() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT_RULE
        )
        pkg(
            "a",
            "simple(name='tool')",
            "simple(name='a')",
            "all_attributes_with_tool_aspect(name='x', foo=[':a'])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:x")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly("aspect //a:a", "rule //a:x")
    }

    /**
     * Makes sure the aspect *will* propagate to its implicit attributes if there is a "regular"
     * dependency path to it (i.e. not through its own implicit attributes).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithAllAttributesPropagatesToItsToolIfThereIsPath() {
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.ALL_ATTRIBUTES_WITH_TOOL_ASPECT_RULE
        )
        pkg(
            "a",
            "simple(name='tool')",
            "simple(name='a', foo=[':b'], foo1=':c', txt='some text')",
            "simple(name='b', foo=[], txt='some text')",
            "simple(name='c', foo=[':tool'], txt='more text')",
            "all_attributes_with_tool_aspect(name='x', foo=[':a'])"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a:x")
        assertThat(a.getProvider(RuleInfo::class.java).getData().toList())
            .containsExactly(
                "aspect //a:a", "aspect //a:b", "aspect //a:c", "aspect //a:tool", "rule //a:x"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectTruthInAdvertisement() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        setRulesAvailableInTests(
            TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE,
            TestAspects.FALSE_ADVERTISEMENT_ASPECT_RULE
        )
        pkg(
            "a",
            "simple(name = 's')",
            "false_advertisement_aspect(name = 'x', deps = [':s'])"
        )
        try {
            update("//a:x")
        } catch (e: ViewCreationFailedException) {
            // expected.
        }
        assertContainsEvent(
            "Aspect 'FalseAdvertisementAspect', applied to '//a:s',"
                    + " does not provide advertised provider 'RequiredProvider'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectApplyingToFiles() {
        val aspectApplyingToFiles: AspectApplyingToFiles = AspectApplyingToFiles()
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        pkg(
            "a",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])"
        )

        val collector = AspectConfiguredCollector()
        eventBus.register(collector)

        val analysisResult: AnalysisResult =
            update(
                eventBus,
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles.getName()),
                "//a:x_deploy.jar"
            )
        val aspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val provider: AspectApplyingToFiles.Provider =
            aspect.getProvider(AspectApplyingToFiles.Provider::class.java)
        val label: Label? = Label.parseCanonicalUnchecked("//a:x_deploy.jar")
        assertThat(provider.getLabel()).isEqualTo(label)

        // Verifies that the AspectConfiguredEvent declares the corresponding AspectCompleteEvent.
        val configuredEvent: AspectConfiguredEvent? =
            com.google.common.collect.Iterables.getOnlyElement<AspectConfiguredEvent?>(collector.events)
        val targetCompletedId: BuildEventId? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(configuredEvent.getChildrenEvents())
        val key: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().keySet())
        assertThat(targetCompletedId)
            .isEqualTo(
                BuildEventIdUtil.aspectCompleted(
                    label,
                    BuildEventIdUtil.configurationId(key.getConfigurationKey()),
                    "AspectApplyingToFiles"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectApplyingToSourceFilesIgnored() {
        val aspectApplyingToFiles: AspectApplyingToFiles = AspectApplyingToFiles()
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        pkg(
            "a",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])"
        )
        scratch.file("a/x.java", "")
        val analysisResult: AnalysisResult = update(
            com.google.common.eventbus.EventBus(), defaultFlags(),
            com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles.getName()),
            "//a:x.java"
        )
        val aspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(aspect.getProvider(AspectApplyingToFiles.Provider::class.java)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectApplyingToPackageGroupIgnored() {
        val aspectApplyingToFiles: AspectApplyingToFiles = AspectApplyingToFiles()
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        pkg("b")
        pkg(
            "a",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "package_group(name = 'group', packages = ['//b'])",
            "java_binary(name = 'x', main_class = 'x.F', srcs = ['x.java'], visibility = [':group'])"
        )
        scratch.file("a/x.java", "")

        // This exercises a code path that crashes if the PackageGroup is matched as an aspect provider.
        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles.getName()),
                "//a:group"
            )
        assertThat(analysisResult.getAspectsMap()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateTopLevelAspects_allowedAndDeduplicated() {
        val aspectApplyingToFiles: AspectApplyingToFiles = AspectApplyingToFiles()
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplyingToFiles),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        pkg(
            "a",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "java_binary(name = 'x', main_class = 'x.FooBar', srcs = ['x.java'])"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<E?>(
                    aspectApplyingToFiles.getName(),
                    aspectApplyingToFiles.getName()
                ),
                "//a:x_deploy.jar"
            )
        assertThat(analysisResult.getAspectsMap()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithExtraAttribute_ignoredForOutputFile() {
        val aspect: ExtraAttributeAspect =
            ExtraAttributeAspect("//nonexistent",  /*applyToFiles=*/false)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspect),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')")

        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(aspect.getName()),
                "//a"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey())).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithExtraAttributeApplyToFiles_outputFile_hasResolvedAttribute() {
        val aspect: ExtraAttributeAspect = ExtraAttributeAspect("//extra",  /*applyToFiles=*/true)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspect),
            com.google.common.collect.ImmutableList.of<E?>(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE)
        )
        scratch.file("extra/BUILD", "simple(name='extra')")
        scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')")

        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(aspect.getName()),
                "//a"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val provider: StarlarkInfo =
            configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey()) as StarlarkInfo
        assertThat(provider.getValue("label")).isEqualTo("//extra:extra")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithExtraAttributeApplyToFiles_ignoredForSourceFile() {
        val aspect: ExtraAttributeAspect = ExtraAttributeAspect("//nonexistent",  /*applyToFiles=*/true)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspect),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        scratch.file("a/BUILD", "exports_files(['a.txt'])")
        scratch.file("a/a.txt", "hello")

        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(aspect.getName()),
                "//a:a.txt"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(configuredAspect.get(ExtraAttributeAspect.PROVIDER.getKey())).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithExtraAttributeApplyToFilesAndNot_outputFile_onlyApplyToFilesIsResolved() {
        val aspectApplies: ExtraAttributeAspect =
            ExtraAttributeAspect("//extra",  /*applyToFiles=*/true)
        val aspectDoesNotApply: ExtraAttributeAspect =
            ExtraAttributeAspect("//nonexistent",  /*applyToFiles=*/false)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplies, aspectDoesNotApply),
            com.google.common.collect.ImmutableList.of<E?>(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE)
        )
        scratch.file("extra/BUILD", "simple(name='extra')")
        scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')")

        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(
                    aspectApplies.getName(),
                    aspectDoesNotApply.getName()
                ),
                "//a"
            )

        assertThat(analysisResult.getAspectsMap()).hasSize(2)
        val provider: StarlarkInfo =
            getAspectByName(analysisResult.getAspectsMap(), aspectApplies.getName())
                .get(ExtraAttributeAspect.PROVIDER.getKey()) as StarlarkInfo
        assertThat(provider.getValue("label")).isEqualTo("//extra:extra")
        assertThat(
            getAspectByName(analysisResult.getAspectsMap(), aspectDoesNotApply.getName())
                .getProviders()
                .getProviderCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithExtraAttributeDependsOnNotApplicable_usesItsOwnAttribute() {
        val aspectApplies: ExtraAttributeAspect =
            ExtraAttributeAspect(
                "//extra",  /* applyToFiles= */
                true,
                StarlarkProviderIdentifier.forKey(ExtraAttributeAspect.PROVIDER.getKey())
            )
        val aspectDoesNotApply: ExtraAttributeAspect =
            ExtraAttributeAspect("//extra:extra2",  /*applyToFiles=*/false)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(aspectApplies, aspectDoesNotApply),
            com.google.common.collect.ImmutableList.of<E?>(TestAspects.BASE_RULE, TestAspects.SIMPLE_RULE)
        )
        scratch.file(
            "extra/BUILD",
            """
        simple(name = "extra")

        simple(name = "extra2")
        
        """.trimIndent()
        )
        scratch.file("a/BUILD", "genrule(name='gen_a', outs=['a'], cmd='touch $@')")

        val analysisResult: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(
                    aspectDoesNotApply.getName(),
                    aspectApplies.getName()
                ),
                "//a"
            )

        assertThat(analysisResult.getAspectsMap()).hasSize(2)
        val provider: StarlarkInfo =
            getAspectByName(analysisResult.getAspectsMap(), aspectApplies.getName())
                .get(ExtraAttributeAspect.PROVIDER.getKey()) as StarlarkInfo
        assertThat(provider.getValue("label")).isEqualTo("//extra:extra")
        assertThat(
            getAspectByName(analysisResult.getAspectsMap(), aspectDoesNotApply.getName())
                .getProviders()
                .getProviderCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameConfiguredAttributeOnAspectAndRule() {
        scratch.file(
            "a/a.bzl",
            """
        def _a_impl(t, ctx):
            return [DefaultInfo()]

        def _r_impl(ctx):
            return [DefaultInfo()]

        a = aspect(
            implementation = _a_impl,
            attrs = {"_f": attr.label(
                default = configuration_field(fragment = "coverage", name = "output_generator"),
            )},
        )
        r = rule(
            implementation = _r_impl,
            attrs = {
                "_f": attr.label(
                    default =
                        configuration_field(fragment = "coverage", name = "output_generator"),
                ),
                "dep": attr.label(aspects = [a]),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "r")

        r(name = "r")
        
        """.trimIndent()
        )

        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass?>(),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        getConfiguredTarget("//a:r")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelConflictDetected() {
        val bzlFileTemplate: String =
            java.lang.String.join(
                "\n",
                "def _aspect1_impl(target, ctx):",
                "  outfile = ctx.actions.declare_file('aspect.out')",
                "  ctx.actions.run_shell(",
                "    outputs = [outfile],",
                "    progress_message = 'Action for aspect 1',",
                "    command = 'echo \"1\" > ' + outfile.path,",
                "  )",
                "  return [OutputGroupInfo(files = [outfile])]",
                "def _aspect2_impl(target, ctx):",
                "  outfile = ctx.actions.declare_file('aspect.out')",
                "  ctx.actions.run_shell(",
                "    outputs = [outfile],",
                "    progress_message = 'Action for aspect 2',",
                "    command = 'echo \"%s\" > ' + outfile.path,",
                "  )",
                "  return [OutputGroupInfo(files = [outfile])]",
                "aspect1 = aspect(implementation = _aspect1_impl)",
                "aspect2 = aspect(implementation = _aspect2_impl)"
            )
        scratch.file("foo/aspect.bzl", java.lang.String.format(bzlFileTemplate, "2"))
        scratch.file("foo/BUILD", "filegroup(name = 'foo', srcs = ['foo.sh'])")
        // Expect errors.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val exception: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.eventbus.EventBus(),
                        defaultFlags(),
                        com.google.common.collect.ImmutableList.of<String?>(
                            "//foo:aspect.bzl%aspect1",
                            "//foo:aspect.bzl%aspect2"
                        ),
                        "//foo:foo"
                    )
                })
        assertThat(exception)
            .hasMessageThat()
            .containsMatch("file 'foo/aspect.out' is generated by these conflicting actions:")
        MoreAsserts.assertContainsEvent(
            eventCollector,
            java.util.regex.Pattern.compile(
                "Aspects: \\[//foo:aspect.bzl%aspect[12]], \\[//foo:aspect.bzl%aspect[12]]"
            ),
            com.google.devtools.build.lib.events.EventKind.ERROR
        )

        // Fix bzl file so actions are shared: analysis should succeed now.
        scratch.overwriteFile("foo/aspect.bzl", java.lang.String.format(bzlFileTemplate, "1"))
        reporter.addHandler(FoundationTestCase.failFastHandler)
        val result: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<String?>(
                    "//foo:aspect.bzl%aspect1",
                    "//foo:aspect.bzl%aspect2"
                ),
                "//foo:foo"
            )
        assertThat(result.getAspectsMap()).hasSize(2)

        // Break bzl file again: we should notice.
        scratch.overwriteFile("foo/aspect.bzl", java.lang.String.format(bzlFileTemplate, "2"))
        // Expect errors.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.eventbus.EventBus(),
                    defaultFlags(),
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo:aspect.bzl%aspect1",
                        "//foo:aspect.bzl%aspect2"
                    ),
                    "//foo:foo"
                )
            })
        MoreAsserts.assertContainsEvent(
            eventCollector,
            java.util.regex.Pattern.compile(
                "Aspects: \\[//foo:aspect.bzl%aspect[12]], \\[//foo:aspect.bzl%aspect[12]]"
            ),
            com.google.devtools.build.lib.events.EventKind.ERROR
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun conflictBetweenTargetAndAspect() {
        scratch.file(
            "foo/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
            outfile = ctx.actions.declare_file("conflict.out")
            ctx.actions.run_shell(
                outputs = [outfile],
                progress_message = "Action for aspect",
                command = 'echo "aspect" > ' + outfile.path,
            )
            return [OutputGroupInfo(files = [outfile])]

        def _rule_impl(ctx):
            outfile = ctx.actions.declare_file("conflict.out")
            ctx.actions.run_shell(
                outputs = [outfile],
                progress_message = "Action for target",
                command = 'echo "target" > ' + outfile.path,
            )
            return [DefaultInfo(files = depset([outfile]))]

        my_aspect = aspect(implementation = _aspect_impl)
        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//foo:aspect.bzl", "my_aspect", "my_rule")

        my_rule(
            name = "foo",
            deps = [":dep"],
        )

        filegroup(
            name = "dep",
            srcs = ["dep.sh"],
        )
        
        """.trimIndent()
        )
        // Expect errors.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val exception: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//foo:foo") })
        assertThat(exception)
            .hasMessageThat()
            .containsMatch("file 'foo/conflict.out' is generated by these conflicting actions")
        MoreAsserts.assertContainsEvent(
            eventCollector,
            java.util.regex.Pattern.compile(
                "Aspects: (\\[], \\[//foo:aspect.bzl%my_aspect]|\\[//foo:aspect.bzl%my_aspect], \\[])"
            ),
            com.google.devtools.build.lib.events.EventKind.ERROR
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDuplicatesRuleProviderError() {
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass?>(),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        scratch.file(
            "aspect/build_defs.bzl",
            """
        DuplicateInfo = provider(fields=[])
        def _aspect_impl(target, ctx):
            return [DuplicateInfo()]

        returns_duplicate_aspect = aspect(implementation = _aspect_impl)

        def _duplicate_rule_impl(ctx):
          return [DefaultInfo(), DuplicateInfo()]

        duplicate_rule = rule(implementation = _duplicate_rule_impl, attrs = {})

        def _rule_impl(ctx):
            pass

        duplicate_aspect_applying_rule = rule(
            implementation = _rule_impl,
            attrs = {"to": attr.label(aspects = [returns_duplicate_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "aspect/BUILD",
            """
        load("build_defs.bzl", "duplicate_aspect_applying_rule", "duplicate_rule")

        duplicate_rule(name = "duplicate")

        duplicate_aspect_applying_rule(
            name = "applies_aspect",
            to = ":duplicate",
        )
        
        """.trimIndent()
        )
        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//aspect:applies_aspect") })
        )
            .hasMessageThat()
            .contains("Provider DuplicateInfo provided twice")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instrumentedFilesInfoFromBaseRuleAndAspectUsesAspect() {
        scratch.file(
            "aspect/build_defs.bzl",
            """
        def _instrumented_files_info_aspect_impl(target, ctx):
            return [coverage_common.instrumented_files_info(ctx, source_attributes = ["a"])]

        instrumented_files_info_aspect = aspect(
            implementation = _instrumented_files_info_aspect_impl,
        )

        def _no_instrumented_files_info_aspect_impl(target, ctx):
            return []

        no_instrumented_files_info_aspect = aspect(
            implementation = _no_instrumented_files_info_aspect_impl,
        )

        def _applies_aspect_impl(ctx):
            return coverage_common.instrumented_files_info(ctx, dependency_attributes = ["to"])

        instrumented_files_info_aspect_rule = rule(
            implementation = _applies_aspect_impl,
            attrs = {"to": attr.label(aspects = [instrumented_files_info_aspect])},
        )

        no_instrumented_files_info_aspect_rule = rule(
            implementation = _applies_aspect_impl,
            attrs = {"to": attr.label(aspects = [no_instrumented_files_info_aspect])},
        )

        def _base_rule_impl(ctx):
            return [coverage_common.instrumented_files_info(ctx, source_attributes = ["b"])]

        base_rule = rule(
            implementation = _base_rule_impl,
            attrs = {"a": attr.label(allow_files = True), "b": attr.label(allow_files = True)},
        )

        def _base_rule_no_coverage_impl(ctx):
            return []

        base_rule_no_coverage = rule(
            implementation = _base_rule_no_coverage_impl,
            attrs = {"a": attr.label(allow_files = True), "b": attr.label(allow_files = True)},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "aspect/BUILD",
            """
        load(
            "build_defs.bzl",
            "base_rule",
            "base_rule_no_coverage",
            "instrumented_files_info_aspect_rule",
            "no_instrumented_files_info_aspect_rule",
        )

        base_rule(
            name = "rule_target",
            # Ends up in coverage sources when instrumented_files_info_aspect is applied
            a = "a",
            # Ends up in coverage sources for the base rule's InstrumentedFilesInfo is used
            b = "b",
        )

        instrumented_files_info_aspect_rule(
            name = "duplicate_instrumented_file_info",
            to = ":rule_target",
        )

        no_instrumented_files_info_aspect_rule(
            name = "instrumented_file_info_from_base_target",
            to = ":rule_target",
        )

        base_rule_no_coverage(
            name = "rule_target_no_coverage",
            # Ends up in coverage sources when instrumented_files_info_aspect is applied
            a = "a",
            # Ends up in coverage sources never
            b = "b",
        )

        instrumented_files_info_aspect_rule(
            name = "instrumented_files_info_only_from_aspect",
            to = ":rule_target_no_coverage",
        )

        no_instrumented_files_info_aspect_rule(
            name = "no_instrumented_files_info",
            to = ":rule_target_no_coverage",
        )
        
        """.trimIndent()
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=.*")
        update()
        Truth.assertThat(getInstrumentedFiles("//aspect:rule_target")).containsExactly("b")
        Truth.assertThat(getInstrumentedFiles("//aspect:duplicate_instrumented_file_info"))
            .containsExactly("a")
        Truth.assertThat(getInstrumentedFiles("//aspect:instrumented_file_info_from_base_target"))
            .containsExactly("b")
        Truth.assertThat(getInstrumentedFiles("//aspect:rule_target_no_coverage")).isEmpty()
        Truth.assertThat(getInstrumentedFiles("//aspect:instrumented_files_info_only_from_aspect"))
            .containsExactly("a")
        Truth.assertThat(getInstrumentedFiles("//aspect:no_instrumented_files_info")).isEmpty()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getInstrumentedFiles(label: String?): MutableList<String?>? {
        return baseArtifactNames(
            getConfiguredTarget(label)
                .get(InstrumentedFilesInfo.provider)
                .getInstrumentedFiles()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectSeesAspectHintsAttributeOnNativeRule() {
        setupAspectHints()
        scratch.file(
            "aspect_hints/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//aspect_hints:hints.bzl", "hint")
        load("//aspect_hints:hints_counter.bzl", "count_hints")

        hint(
            name = "my_hint",
            hints_cnt = 3,
        )

        cc_library(
            name = "lib1",
            deps = [":lib2"],
        )

        cc_library(
            name = "lib2",
            aspect_hints = [":my_hint"],
        )

        count_hints(
            name = "cnt",
            deps = [":lib1"],
        )
        
        """.trimIndent()
        )
        update()

        val a: ConfiguredTarget = getConfiguredTarget("//aspect_hints:cnt")
        val info: StarlarkInt = getHintsCntInfo(a).getValue("cnt") as StarlarkInt

        Truth.assertThat(info.truncateToInt()).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectSeesAspectHintsAttributeOnStarlarkRule() {
        setupAspectHints()
        setupStarlarkRule()
        scratch.file(
            "aspect_hints/BUILD",
            """
        load("//aspect_hints:custom_rule.bzl", "custom_rule")
        load("//aspect_hints:hints.bzl", "hint")
        load("//aspect_hints:hints_counter.bzl", "count_hints")

        hint(
            name = "my_hint",
            hints_cnt = 2,
        )

        custom_rule(
            name = "lib1",
            deps = [":lib2"],
        )

        custom_rule(
            name = "lib2",
            aspect_hints = [":my_hint"],
        )

        count_hints(
            name = "cnt",
            deps = [":lib1"],
        )
        
        """.trimIndent()
        )
        update()

        val a: ConfiguredTarget = getConfiguredTarget("//aspect_hints:cnt")
        val info: StarlarkInt = getHintsCntInfo(a).getValue("cnt") as StarlarkInt

        Truth.assertThat(info.truncateToInt()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleDepsVisibilityNotAffectNativeAspect() {
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass?>(TestAspects.ALL_ATTRIBUTES_ASPECT),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        scratch.file("defs/BUILD")
        scratch.file(
            "defs/build_defs.bzl",
            """
        def _rule_impl(ctx):
            pass

        implicit_dep_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "_tool": attr.label(default = "//tool:tool"),
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("tool/BUILD", "filegroup(name='tool', visibility = ['//defs:__pkg__'])")
        scratch.file(
            "pkg/BUILD",
            """
        load("//defs:build_defs.bzl", "implicit_dep_rule")

        implicit_dep_rule(name = "y")

        implicit_dep_rule(
            name = "x",
            deps = [":y"],
        )
        
        """.trimIndent()
        )

        val result: AnalysisResult =
            update(
                com.google.common.eventbus.EventBus(),
                defaultFlags(),
                com.google.common.collect.ImmutableList.of<E?>(TestAspects.ALL_ATTRIBUTES_ASPECT.getName()),
                "//pkg:x"
            )

        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeAspectFailIfDepsNotVisible() {
        scratch.file("tool/BUILD", "filegroup(name='tool', visibility = ['//visibility:private'])")
        val extraAttributeAspect: ExtraAttributeAspect = ExtraAttributeAspect("//tool:tool", false)
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<E?>(extraAttributeAspect),
            com.google.common.collect.ImmutableList.of<RuleDefinition?>()
        )
        scratch.file(
            "pkg/build_defs.bzl",
            """
        def _rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//pkg:build_defs.bzl", "simple_rule")

        simple_rule(name = "x")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.eventbus.EventBus(),
                    defaultFlags(),
                    com.google.common.collect.ImmutableList.of<String?>(extraAttributeAspect.getName()),
                    "//pkg:x"
                )
            })
        assertContainsEvent(
            ("ExtraAttributeAspect_//tool:tool_false aspect on simple_rule rule //pkg:x: "
                    + "Visibility error:\n"
                    + "target '//tool:tool' is not visible from\n"
                    + "target '//pkg:x'")
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupAspectHints() {
        scratch.file(
            "aspect_hints/hints.bzl",
            """
        HintInfo = provider(fields = ["hints_cnt"])

        def _hint_impl(ctx):
            return [HintInfo(hints_cnt = ctx.attr.hints_cnt)]

        hint = rule(
            implementation = _hint_impl,
            attrs = {"hints_cnt": attr.int(default = 0)},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "aspect_hints/hints_counter.bzl",
            """
        load("//aspect_hints:hints.bzl", "HintInfo")

        HintsCntInfo = provider(fields = ["cnt"])

        def _my_aspect_impl(target, ctx):
            transitive_hints = 0
            for dep in ctx.rule.attr.deps:
                transitive_hints = transitive_hints + dep[HintsCntInfo].cnt

            hints = 0
            for hint in ctx.rule.attr.aspect_hints:
                hints = hints + hint[HintInfo].hints_cnt

            return [HintsCntInfo(cnt = hints + transitive_hints)]

        my_aspect = aspect(
            implementation = _my_aspect_impl,
            attr_aspects = ["deps"],
        )

        def _count_hints_impl(ctx):
            hints = 0
            for dep in ctx.attr.deps:
                hints = hints + dep[HintsCntInfo].cnt
            return [HintsCntInfo(cnt = hints)]

        count_hints = rule(
            implementation = _count_hints_impl,
            attrs = {
                "deps": attr.label_list(aspects = [my_aspect]),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupStarlarkRule() {
        scratch.file(
            "aspect_hints/custom_rule.bzl",
            """
        def _custom_rule_impl(ctx):
            return []

        custom_rule = rule(
            implementation = _custom_rule_impl,
            attrs = {
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )
    }

    private class AspectConfiguredCollector {
        private val events: java.util.ArrayList<AspectConfiguredEvent?> = java.util.ArrayList<AspectConfiguredEvent?>()

        @com.google.common.eventbus.Subscribe
        fun configuredEvent(event: AspectConfiguredEvent?) {
            events.add(event)
        }
    }

    companion object {
        @Throws(LabelSyntaxException::class)
        private fun getHintsCntInfo(configuredTarget: ConfiguredTarget): StructImpl? {
            val key: Provider.Key =
                Key(
                    keyForBuild(Label.parseCanonical("//aspect_hints:hints_counter.bzl")), "HintsCntInfo"
                )
            return configuredTarget.get(key) as StructImpl?
        }

        private fun getAspectByName(
            aspectMap: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>, name: String?
        ): ConfiguredAspect? {
            return aspectMap.entrySet().stream()
                .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<AspectKey?, ConfiguredAspect?>? ->
                    e.getKey().getAspectName().equals(name)
                })
                .map<ConfiguredAspect?>(java.util.function.Function { java.util.Map.Entry.getValue() })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<ConfiguredAspect?>())
        }
    }
}
