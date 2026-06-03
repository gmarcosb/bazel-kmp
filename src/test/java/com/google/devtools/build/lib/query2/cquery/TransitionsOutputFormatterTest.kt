// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for the transitions output format.  */
@RunWith(JUnit4::class)
class TransitionsOutputFormatterTest : ConfiguredTargetQueryTest() {
    private var options: CqueryOptions? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private val events: MutableList<com.google.devtools.build.lib.events.Event> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event>()
    private var ruleClassProvider: ConfiguredRuleClassProvider? = null

    @Before
    fun setUpCqueryOptions() {
        this.options = com.google.devtools.common.options.Options.getDefaults<O>(CqueryOptions::class.java)
        options.setIncludeToolDeps(false)
        options.setIncludeImplicitDeps(false)
        options.setIncludeNoDepDeps(false)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
        this.reporter = com.google.devtools.build.lib.events.Reporter(
            EventBusEventHandler.createWithNewEventBus(),
            com.google.devtools.build.lib.events.EventHandler { e: com.google.devtools.build.lib.events.Event? ->
                events.add(
                    e
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitions_full() {
        setUpRules()

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "rule_with_patch",
            patched = [
                ":foo",
                ":foo2",
                ":trimmed_foo",
            ],
        )

        my_rule(
            name = "rule_with_split",
            split = ":bar",
        )

        simple_rule(name = "foo")

        simple_rule(name = "foo2")

        simple_rule(name = "trimmed_foo")

        simple_rule(name = "bar")
        
        """.trimIndent()
        )

        var result = getOutput("deps(//test:rule_with_patch)", Transitions.FULL)

        Truth.assertThat(result.get(0)).startsWith("FooPatchRuleTransitionFactory -> //test:rule_with_patch")
        Truth.assertThat(result.get(1)).startsWith("  patched#//test:foo#FooPatch")
        Truth.assertThat(result.get(2)).isEqualTo("    foo:SET BY RULE CLASS PATCH -> [SET BY PATCH]")
        Truth.assertThat(result.get(3)).startsWith("  patched#//test:foo2#FooPatchAttrTransitionFactory")
        Truth.assertThat(result.get(4)).isEqualTo(result.get(2))
        Truth.assertThat(result.get(5))
            .startsWith(
                "  patched#//test:trimmed_foo#(FooPatchAttrTransitionFactory +"
                        + " FooPatchTransition(trim))"
            )
        Truth.assertThat(result.get(6)).isEqualTo("    foo:SET BY RULE CLASS PATCH -> [SET BY TRIM]")

        result = getOutput("deps(//test:rule_with_split)", Transitions.FULL)
        Truth.assertThat(result.get(1)).startsWith("  split#//test:bar#FooSplitTransitionFactory")
        // TODO(shahan): the right hand side of the diff below is in split dep ordering, which is
        // dependent on checksum values. It could be brittle.
        Truth.assertThat(result.get(2))
            .isEqualTo("    foo:SET BY RULE CLASS PATCH -> [SET BY SPLIT 2, SET BY SPLIT 1]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitions_lite() {
        setUpRules()

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "rule_with_patch",
            patched = [
                ":foo",
                ":foo2",
            ],
        )

        my_rule(
            name = "rule_with_split",
            split = ":bar",
        )

        simple_rule(name = "foo")

        simple_rule(name = "foo2")

        simple_rule(name = "bar")
        
        """.trimIndent()
        )

        var result = getOutput("deps(//test:rule_with_patch)", Transitions.LITE)

        Truth.assertThat(result.get(0)).startsWith("FooPatchRuleTransitionFactory -> //test:rule_with_patch")
        Truth.assertThat(result.get(1)).startsWith("  patched#//test:foo#FooPatchAttrTransitionFactory")
        Truth.assertThat(result.get(2)).startsWith("  patched#//test:foo2#FooPatchAttrTransitionFactory")

        result = getOutput("deps(//test:rule_with_split)", Transitions.LITE)
        Truth.assertThat(result.get(0)).startsWith("FooPatchRuleTransitionFactory -> //test:rule_with_split")
        Truth.assertThat(result.get(1)).startsWith("  split#//test:bar#FooSplitTransitionFactory")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitions_getRightConfigurations() {
        setUpRules()

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "rule_with_patch",
            patched = [":foo"],
        )

        simple_rule(name = "foo")
        
        """.trimIndent()
        )

        val result = getOutput("deps(//test:rule_with_patch)", Transitions.LITE)
        val depEntry = result.get(2)
        // depEntry is "//test:rule_with_path (<config_id>)". This gets just "<config_id>".
        val postPatchConfig: String =
            depEntry.substring(depEntry.lastIndexOf("(") + 1, depEntry.length - 1)
        Truth.assertThat(result.get(1)).endsWith(postPatchConfig)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitions_noTransitions() {
        setUpRules()
        writeFile("test/BUILD", "simple_rule(name = 'foo')")

        val result = getOutput("//test:foo", Transitions.NONE)
        Truth.assertThat(result).isEmpty()
        Truth.assertThat(events).hasSize(1)
        Truth.assertThat(events.get(0).getMessage())
            .isEqualTo(
                "Instead of using --output=transitions, set the --transitions flag explicitly to 'lite'"
                        + " or 'full'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonAttributeDependencySkipped() {
        setUpRules()

        // A visibility dependency on a package_group produces a
        // DependencyKind.NonAttributeDependencyKind. This test checks that the existence of those
        // attribute types doesn't crash cquery.
        writeFile(
            "test/BUILD",
            """
        package_group(
            name = "custom_visibility",
            packages = ["//test/..."],
        )

        simple_rule(
            name = "child",
        )

        simple_rule(
            name = "parent",
            visibility = [":custom_visibility"],
            deps = [":child"],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(getOutput("deps(//test:parent)", Transitions.LITE)).isNotNull()
        Truth.assertThat(events).isEmpty()
    }

    @Throws(java.lang.Exception::class)
    private fun setUpRules() {
        val infixTrimmingTransitionFactory: TransitionFactory<RuleTransitionData?> =
            object : TransitionFactory() {
                public override fun create(ruleData: RuleTransitionData): ConfigurationTransition? {
                    if (!ruleData.rule().getName().contains("trimmed")) {
                        return NoTransition.INSTANCE
                    }
                    // rename the transition so it's distinguishable from the others in tests
                    return FooPatchTransition("SET BY TRIM", "FooPatchTransition(trim)")
                }

                public override fun transitionType(): TransitionType {
                    return TransitionType.RULE
                }
            }
        val ruleClassTransition: FooPatchRuleTransitionFactory =
            FooPatchRuleTransitionFactory("SET BY RULE CLASS PATCH")
        val attributePatchTransition: FooPatchAttrTransitionFactory =
            FooPatchAttrTransitionFactory("SET BY PATCH")
        val attributeSplitTransitions: FooSplitTransitionFactory =
            FooSplitTransitionFactory("SET BY SPLIT 1", "SET BY SPLIT 2")

        val ruleWithTransitions: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    { builder, env ->
                        builder
                            .cfg(ruleClassTransition)
                            .add(
                                attr("patched", LABEL_LIST)
                                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                                    .cfg(attributePatchTransition)
                            )
                            .add(
                                attr("split", LABEL)
                                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                                    .cfg(attributeSplitTransitions)
                            )
                    })
            }
        val simpleRule: MockRule =
            MockRule {
                MockRule.define(
                    "simple_rule",
                    { builder, env -> builder.add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)) })
            }

        this.ruleClassProvider =
            setRuleClassProviders(ruleWithTransitions, simpleRule)
                .overrideTrimmingTransitionFactoryForTesting(infixTrimmingTransitionFactory)
                .build()
        helper.useRuleClassProvider(ruleClassProvider)
    }

    @Throws(java.lang.Exception::class)
    private fun getOutput(queryExpression: String?, verbosity: CqueryOptions.Transitions?): MutableList<String> {
        val expression: QueryExpression =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse(queryExpression, getDefaultFunctions())
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val env: PostAnalysisQueryEnvironment<CqueryNode?> =
            (helper as ConfiguredTargetQueryHelper).getPostAnalysisQueryEnvironment(targetPatternSet)
        options.setTransitions(verbosity)
        // TODO(blaze-configurability): Test late-bound attributes.
        val callback: TransitionsOutputFormatterCallback =
            TransitionsOutputFormatterCallback(
                reporter,
                options,  /* out= */
                null,
                getHelper().getSkyframeExecutor(),
                env.getAccessor(),
                ruleClassProvider,
                LabelPrinter.legacy()
            )
        env.evaluateQuery(
            env.transformParsedQuery(
                com.google.devtools.build.lib.query2.engine.QueryParser.parse(
                    queryExpression,
                    env
                )
            ), callback
        )
        return callback.getResult()
    }
}
