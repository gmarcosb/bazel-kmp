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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Tests for [ConfiguredTargetQueryEnvironment].
 * 
 * 
 * This tests core cquery behavior (behavior that doesn't depend on `--output`).
 * Output format-specific behavior is covered in dedicated test classes.
 */
@RunWith(JUnit4::class)
class ConfiguredTargetQuerySemanticsTest : ConfiguredTargetQueryTest() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationRespected() {
        writeBuildFilesWithConfigurableAttributesUnconditionally()
        Truth.assertThat(eval("deps(//configurable:main) ^ //configurable:adep")).isEmpty()
        Truth.assertThat(eval("deps(//configurable:main) ^ //configurable:defaultdep")).hasSize(1)
    }

    @Throws(java.lang.Exception::class)
    private fun setUpLabelsFunctionTests() {
        val ruleWithTransitions: MockRule =
            MockRule {
                MockRule.define(
                    "rule_with_transitions",
                    attr("patch_dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(FooPatchAttrTransitionFactory("SET BY PATCH")),
                    attr("string_dep", STRING),
                    attr("split_dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(FooSplitTransitionFactory("SET BY SPLIT 1", "SET BY SPLIT 2")),
                    attr("patch_dep_list", LABEL_LIST)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(FooPatchAttrTransitionFactory("SET BY PATCH 2"))
                )
            }
        val noAttributeRule: MockRule = MockRule { MockRule.define("no_attribute_rule") }

        helper.useRuleClassProvider(
            setRuleClassProviders(ruleWithTransitions, noAttributeRule).build()
        )

        writeFile(
            "test/BUILD",
            """
        rule_with_transitions(
            name = "my_rule",
            patch_dep = ":dep-1",
            patch_dep_list = [
                ":dep-3",
                ":dep-4",
            ],
            split_dep = ":dep-2",
            string_dep = "some string",
        )

        no_attribute_rule(name = "dep-1")

        no_attribute_rule(name = "dep-2")

        no_attribute_rule(name = "dep-3")

        no_attribute_rule(name = "dep-4")
        
        """.trimIndent()
        )

        helper.setUniverseScope("//test:*")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelFunction_getsCorrectConfigurations() {
        setUpLabelsFunctionTests()

        // (Test that you can use the labels function without an error (b/112593112)).
        // Note - 'labels' as a command for cquery is a slight misnomer since it always prints
        // labels AND configurations. But still a helpful function so oh well.
        assertThat(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("labels('patch_dep', //test:my_rule)"))).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelFunction_getCorrectlyConfiguredDeps() {
        setUpLabelsFunctionTests()

        // Test that this retrieves the correctly configured version(s) of the dep(s).
        val patchDep: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("labels('patch_dep', //test:my_rule)"))
        val myRule: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("//test:my_rule"))
        val targetConfiguration: String? = myRule.getConfigurationChecksum()
        assertThat(patchDep.getConfigurationChecksum()).doesNotMatch(targetConfiguration)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsFunction_splitTransitionAttribute() {
        setUpLabelsFunctionTests()

        val myRule: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("//test:my_rule"))
        val targetConfiguration: String? = myRule.getConfigurationChecksum()

        val splitDeps: MutableSet<CqueryNode> = eval("labels('split_dep', //test:my_rule)")
        Truth.assertThat(splitDeps).hasSize(2)
        for (kct in splitDeps) {
            assertThat(kct.getConfigurationChecksum()).doesNotMatch(targetConfiguration)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsFunction_labelListAttribute() {
        setUpLabelsFunctionTests()

        val myRule: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("//test:my_rule"))
        val targetConfiguration: String? = myRule.getConfigurationChecksum()

        // Test that this works for label_lists as well.
        val deps: MutableSet<CqueryNode> = eval("labels('patch_dep_list', //test:my_rule)")
        Truth.assertThat(deps).hasSize(2)
        for (kct in deps) {
            assertThat(kct.getConfigurationChecksum()).doesNotMatch(targetConfiguration)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsFunction_errorsOnBadAttribute() {
        setUpLabelsFunctionTests()

        // Test that the proper error is thrown when requesting an attribute that doesn't exist.
        val evalThrowsResult: EvalThrowsResult = evalThrows("labels('fake_attr', //test:my_rule)", true)
        PostAnalysisQueryTest.Companion.assertConfigurableQueryCode(
            evalThrowsResult.getFailureDetail(), ConfigurableQuery.Code.ATTRIBUTE_MISSING
        )
        Truth.assertThat(evalThrowsResult.getMessage())
            .isEqualTo(
                "in 'fake_attr' of rule //test:my_rule: configured target of type"
                        + " rule_with_transitions does not have attribute 'fake_attr'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelsFunction_nonLabelAttribute() {
        setUpLabelsFunctionTests()
        Truth.assertThat(eval("labels('string_dep', //test:my_rule)")).isEmpty()
    }

    /**
     * Regression test for b/162431514. the `labels` query operator uses [ ][ConfiguredTargetAccessor.getPrerequisites] which is the actual logic being tested here.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetPrerequisitesFromAliasReturnsActualPrerequisites() {
        val ruleWithDep: MockRule =
            MockRule {
                MockRule.define(
                    "rule_with_dep", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }

        helper.useRuleClassProvider(setRuleClassProviders(ruleWithDep).build())
        writeFile(
            "test/BUILD",
            """
        alias(
            name = "alias",
            actual = ":actual",
        )

        rule_with_dep(
            name = "actual",
            dep = ":dep",
        )

        rule_with_dep(name = "dep")
        
        """.trimIndent()
        )

        val dep: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("labels('dep', '//test:alias')"))
        assertThat(dep.getLabel()).isEqualTo(Label.parseCanonicalUnchecked("//test:dep"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlias_filtering() {
        val ruleWithExecDep: MockRule =
            MockRule {
                MockRule.define(
                    "rule_with_exec_dep",
                    attr("exec_dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(ExecutionTransitionFactory.createFactory()),
                    attr("\$impl_dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .value(Label.parseCanonicalUnchecked("//test:other"))
                )
            }
        val simpleRule: MockRule = MockRule { MockRule.define("simple_rule") }

        helper.useRuleClassProvider(setRuleClassProviders(ruleWithExecDep, simpleRule).build())
        writeFile(
            "test/BUILD",
            """
        alias(
            name = "other_my_rule",
            actual = ":my_rule",
        )

        rule_with_exec_dep(
            name = "my_rule",
            exec_dep = ":exec_dep",
        )

        alias(
            name = "other_exec_dep",
            actual = ":exec_dep",
        )

        simple_rule(name = "exec_dep")

        alias(
            name = "other_impl_dep",
            actual = "impl_dep",
        )

        simple_rule(name = "impl_dep")
        
        """.trimIndent()
        )

        val other: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("//test:other_my_rule"))
        val myRule: CqueryNode? =
            com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("//test:my_rule"))
        // Note: {@link ConfiguredTarget#getLabel} returns the label of the "actual" value not the
        // label of the alias, so we need to check the underlying label.
        assertThat(other.getLabel()).isEqualTo(myRule.getLabel())

        // Regression test for b/73496081 in which alias-ed configured targets were skipping filtering.
        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS
        )
        Truth.assertThat(
            evalToListOfStrings(
                "deps(//test:other_my_rule)-//test:other_my_rule"
                        + getDependencyCorrectionWithGen()
            )
        )
            .isEqualTo(evalToListOfStrings("//test:my_rule"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelTransition() {
        val ruleClassTransition: MockRule =
            MockRule {
                MockRule.define(
                    "rule_class_transition",
                    { builder, env -> builder.cfg(FooPatchRuleTransitionFactory("SET BY PATCH")).build() })
            }

        helper.useRuleClassProvider(setRuleClassProviders(ruleClassTransition).build())
        helper.setUniverseScope("//test:rule_class")

        writeFile("test/BUILD", "rule_class_transition(name='rule_class')")

        val ruleClass: MutableSet<CqueryNode> = eval("//test:rule_class")
        val testOptions: com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions =
            getConfiguration(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(ruleClass))
                .getOptions()
                .get(com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions::class.java)
        Truth.assertThat(testOptions.foo).isEqualTo("SET BY PATCH")
    }

    @Throws(java.lang.Exception::class)
    private fun createConfigRulesAndBuild() {
        val ruleWithTransitions: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    attr("target", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE),
                    attr("exec", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(ExecutionTransitionFactory.createFactory()),
                    attr("deps", BuildType.LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }
        val simpleRule: MockRule =
            MockRule {
                MockRule.define(
                    "simple_rule", attr("dep", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }
        helper.useRuleClassProvider(setRuleClassProviders(ruleWithTransitions, simpleRule).build())

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "my_rule",
            exec = ":exec_dep",
            target = ":target_dep",
            deps = [":dep"],
        )

        simple_rule(
            name = "target_dep",
            dep = ":dep",
        )

        simple_rule(
            name = "exec_dep",
            dep = ":dep",
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createConfigTransitioningRuleClass() {
        overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/rules.bzl",
            """
        def _rule_impl(ctx):
            return []

        string_flag = rule(
            implementation = _rule_impl,
            build_setting = config.string(),
        )

        def _transition_impl(settings, attr):
            return {"//test:my_flag": "custom string"}

        my_transition = transition(
            implementation = _transition_impl,
            inputs = [],
            outputs = ["//test:my_flag"],
        )
        rule_with_deps_transition = rule(
            implementation = _rule_impl,
            attrs = {
                "deps": attr.label_list(cfg = my_transition),
            },
        )
        simple_rule = rule(
            implementation = _rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_target() {
        createConfigRulesAndBuild()

        Truth.assertThat(eval("config(//test:target_dep, target)")).isEqualTo(eval("//test:target_dep"))

        getHelper().setWholeTestUniverseScope("test:my_rule")

        Truth.assertThat(eval("config(//test:target_dep, target)")).isEqualTo(eval("//test:target_dep"))
        val execResult: EvalThrowsResult = evalThrows("config(//test:exec_dep, target)", true)
        Truth.assertThat(execResult.getMessage())
            .isEqualTo("No target (in) //test:exec_dep could be found in the 'target' configuration")
        PostAnalysisQueryTest.Companion.assertConfigurableQueryCode(
            execResult.getFailureDetail(), ConfigurableQuery.Code.TARGET_MISSING
        )

        val configuration: BuildConfigurationValue =
            getConfiguration(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("config(//test:dep, target)")))

        assertThat(configuration).isNotNull()
        assertThat(configuration.isExecConfiguration()).isFalse()
        assertThat(configuration.isToolConfiguration()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_nullConfig() {
        writeFile(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "my_java",
            srcs = ["foo.java"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguration(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(eval("config(//test:foo.java,null)"))))
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_configHash() {
        createConfigTransitioningRuleClass()
        writeFile(
            "test/BUILD",
            """
        load("//test:rules.bzl", "rule_with_deps_transition", "simple_rule", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )

        rule_with_deps_transition(
            name = "buildme",
            deps = [":mydep"],
        )

        simple_rule(name = "mydep")
        
        """.trimIndent()
        )

        // If we don't set --universe_scope=//test:buildme, cquery builds both //test:buildme and
        // //test:mydep as top-level targets. That means //test:mydep will have two configured targets:
        // one under the transitioned configuration and one under the top-level configuration. By
        // setting --universe_scope we ensure only the transitioned version exists.
        helper.setUniverseScope("//test:buildme")
        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS
        )
        val result: MutableSet<CqueryNode> = eval("deps(//test:buildme, 1)" + getDependencyCorrection())
        Truth.assertThat(result).hasSize(2)

        val stableOrderList: com.google.common.collect.ImmutableList<CqueryNode?> =
            com.google.common.collect.ImmutableList.copyOf<CqueryNode?>(result)
        val myDepIndex = if (stableOrderList.get(0).getLabel().toString().equals("//test:mydep")) 0 else 1
        val myDepConfig: BuildConfigurationValue = getConfiguration(stableOrderList.get(myDepIndex))
        val stringFlagConfig: BuildConfigurationValue =
            getConfiguration(stableOrderList.get(1 - myDepIndex))

        // Note: eval() resets the universe scope after each call. We have to xplicitly set it again.
        helper.setUniverseScope("//test:buildme")
        Truth.assertThat(eval("config(//test:mydep, " + myDepConfig.checksum() + ")")).hasSize(1)

        helper.setUniverseScope("//test:buildme")
        val e: com.google.devtools.build.lib.query2.engine.QueryException? =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException?>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable { eval("config(//test:mydep, " + stringFlagConfig.checksum() + ")") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("No target (in) //test:mydep could be found in the configuration with checksum")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_configHashPrefix() {
        createConfigRulesAndBuild()
        writeFile("mytest/BUILD", "simple_rule(name = 'mytarget')")

        val result: MutableSet<CqueryNode> = eval("//mytest:mytarget")
        val configHash: String =
            getConfiguration(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(result)).checksum()
        val hashPrefix: String = configHash.substring(0, configHash.length / 2)

        val resultFromPrefix: MutableSet<CqueryNode> = eval("config(//mytest:mytarget," + hashPrefix + ")")
        Truth.assertThat(resultFromPrefix).containsExactlyElementsIn(result)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_configHashUnknownPrefix() {
        createConfigRulesAndBuild()
        writeFile("mytest/BUILD", "simple_rule(name = 'mytarget')")

        val result: MutableSet<CqueryNode> = eval("//mytest:mytarget")
        val configHash: String =
            getConfiguration(com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(result)).checksum()
        val rightPrefix: String = configHash.substring(0, configHash.length / 2)
        val lastChar = rightPrefix.get(rightPrefix.length - 1)
        val wrongPrefix = rightPrefix.substring(0, rightPrefix.length - 1) + (lastChar.code + 1).toChar()

        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable { eval("config(//mytest:mytarget," + wrongPrefix + ")") })
        PostAnalysisQueryTest.Companion.assertConfigurableQueryCode(
            e.getFailureDetail(), ConfigurableQuery.Code.INCORRECT_CONFIG_ARGUMENT_ERROR
        )
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("config()'s second argument must identify a unique configuration")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_anyExec() {
        createConfigRulesAndBuild()

        // Needed to prime exec_dep with //test:my_rule which configures it in exec.
        val unused: MutableSet<CqueryNode> = eval("//test:my_rule")
        val resultFromAnyExec: MutableSet<CqueryNode> = eval("config(//test:exec_dep, anyexec)")
        Truth.assertThat(resultFromAnyExec).hasSize(1)
        val node: CqueryNode? = com.google.common.collect.Iterables.getOnlyElement<CqueryNode?>(resultFromAnyExec)
        assertThat(node.getDescription(LabelPrinter.legacy())).isEqualTo("//test:exec_dep")

        val execDepConfig: BuildConfigurationValue = getConfiguration(node)
        assertThat(execDepConfig.isExecConfiguration()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfig_exprArgumentFailure() {
        writeFile(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "my_java",
            srcs = ["foo.java"],
        )
        
        """.trimIndent()
        )

        val evalThrowsResult: EvalThrowsResult =
            evalThrows(
                "config(filter(\"??not-a-valid-regex\", //test:foo.java), null)",  /* unconditionallyThrows= */
                true
            )
        Truth.assertThat(evalThrowsResult.getMessage())
            .startsWith("illegal 'filter' pattern regexp '??not-a-valid-regex'")
        assertThat(evalThrowsResult.getFailureDetail().hasQuery()).isTrue()
        assertThat(evalThrowsResult.getFailureDetail().getQuery().getCode())
            .isEqualTo(Code.SYNTAX_ERROR)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecTransitionNotFilteredByNoToolDeps() {
        createConfigRulesAndBuild()
        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS
        )
        Truth.assertThat(evalToListOfStrings("deps(//test:my_rule)" + getDependencyCorrection()))
            .containsExactly("//test:my_rule", "//test:target_dep", "//test:dep")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveTargetPatternNeverThrowsError() {
        val parent: Path =
            getHelper()
                .getScratch()
                .file("parent/BUILD", "filegroup(name = 'parent')")
                .getParentDirectory()
        val child: Path = parent.getRelative("child")
        child.createDirectory()
        val badBuild: Path = child.getRelative("BUILD")
        badBuild.createSymbolicLink(badBuild)

        helper.setKeepGoing(true)
        Truth.assertThat(eval("//parent:all")).isEqualTo(eval("//parent:parent"))

        helper.setKeepGoing(false)
        getHelper().turnOffFailFast()
        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { eval("//parent/...") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("error loading package under directory 'parent': no such package 'parent/child':"
                        + " Symlink cycle detected while trying to find BUILD file"
                        + " /workspace/parent/child/BUILD")
            )
        assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.SYMLINK_CYCLE_OR_INFINITE_EXPANSION)
    }

    // Regression test for b/175739699
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveTargetPatternOutsideOfScopeFailsGracefully() {
        writeFile("testA/BUILD", "filegroup(name = 'testA')")
        writeFile("testB/BUILD", "filegroup(name = 'testB')")
        writeFile("testB/testC/BUILD", "filegroup(name = 'testC')")
        helper.setUniverseScope("//testA")
        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable { eval("//testB/...") })
        assertThat(e.getFailureDetail().getQuery().getCode())
            .isEqualTo(Query.Code.TARGET_NOT_IN_UNIVERSE_SCOPE)
        Truth.assertThat(e).hasMessageThat().contains("package is not in scope")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    override fun testMultipleTopLevelConfigurations_nullConfigs() {
        writeFile(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "my_java",
            srcs = ["foo.java"],
        )
        
        """.trimIndent()
        )

        val result: MutableSet<CqueryNode> = eval("//test:my_java+//test:foo.java")

        Truth.assertThat(result).hasSize(2)

        val resultIterator: MutableIterator<CqueryNode> = result.iterator()
        val first: CqueryNode = resultIterator.next()
        if (first.getLabel().toString().equals("//test:foo.java")) {
            assertThat(getConfiguration(first)).isNull()
            assertThat(getConfiguration(resultIterator.next())).isNotNull()
        } else {
            assertThat(getConfiguration(first)).isNotNull()
            assertThat(getConfiguration(resultIterator.next())).isNull()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSomePath_depInCustomConfiguration() {
        createConfigTransitioningRuleClass()
        writeFile(
            "test/BUILD",
            """
        load("//test:rules.bzl", "rule_with_deps_transition", "simple_rule", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )

        rule_with_deps_transition(
            name = "buildme",
            deps = [":mydep"],
        )

        simple_rule(name = "mydep")
        
        """.trimIndent()
        )

        // If we don't set --universe_scope=//test:buildme, then cquery builds both //test:buildme and
        // //test:mydep as top-level targets. That means //test:mydep will have two configured targets:
        // one under the transitioned configuration and one under the top-level configuration. In these
        // cases cquery prefers the top-level configured one, which won't produce a match since that's
        // not the one down this dependency path.
        helper.setUniverseScope("//test:buildme")
        val result: MutableSet<CqueryNode> = eval("somepath(//test:buildme, //test:mydep)")
        Truth.assertThat(result.stream().map<Any?> { kct: CqueryNode -> kct.getLabel().toString() }
            .collect(Collectors.toList()))
            .contains("//test:mydep")
    }

    /** Return an empty BuildOptions for testing fragment dropping. *  */
    class RemoveTestOptionsTransitionFactory

        : TransitionFactory<AttributeTransitionData?> {
        public override fun create(data: AttributeTransitionData?): ConfigurationTransition? {
            return object : PatchTransition() {
                public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                    return com.google.common.collect.ImmutableSet.of<E?>(TestOptions::class.java)
                }

                public override fun patch(
                    options: BuildOptionsView,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): BuildOptions {
                    val builder: BuildOptions.Builder = BuildOptions.builder()
                    for (option in options.underlying().getNativeOptions()) {
                        if (option !is TestOptions) {
                            builder.addFragmentOptions(option)
                        }
                    }
                    // This does not copy over Starlark options!!
                    return builder.build()
                }
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQueryHandlesDroppingFragments() {
        val ruleDropOptions: MockRule =
            MockRule {
                MockRule.define(
                    "rule_drop_options",
                    attr("dep", LABEL)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .cfg(RemoveTestOptionsTransitionFactory())
                )
            }
        val simpleRule: MockRule =
            MockRule {
                MockRule.define(
                    "simple_rule", attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                )
            }

        helper.useRuleClassProvider(setRuleClassProviders(ruleDropOptions, simpleRule).build())
        writeFile(
            "test/BUILD",
            """
        rule_drop_options(
            name = "top",
            dep = ":foo",
        )

        simple_rule(
            name = "foo",
            deps = [":bar"],
        )

        simple_rule(name = "bar")
        
        """.trimIndent()
        )

        val result: MutableSet<CqueryNode> = eval("somepath(//test:top, filter(//test:bar, deps(//test:top)))")
        Truth.assertThat(result).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelExpressionsMatchesAllConfiguredTargetsWithLabel() {
        createConfigTransitioningRuleClass()
        writeFile(
            "test/BUILD",
            """
        load("//test:rules.bzl", "rule_with_deps_transition", "simple_rule", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )

        rule_with_deps_transition(
            name = "transitioner",
            deps = [":simple"],
        )

        simple_rule(name = "simple")
        
        """.trimIndent()
        )

        helper.setUniverseScope("//test:transitioner,//test:simple")
        val result: MutableSet<CqueryNode> = eval("//test:simple")
        Truth.assertThat(result.size).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigFunctionRefinesMultipleMatches() {
        // Peer to testLabelExpressionsMatchesAllConfiguredTargetsWithLabel. The point of that test is
        // to show "cquery //foo:bar" might return multiple configured targets. The point of this test
        // is to show that config() can refine the same query to a specific one.
        createConfigTransitioningRuleClass()
        writeFile(
            "test/BUILD",
            """
        load("//test:rules.bzl", "rule_with_deps_transition", "simple_rule", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )

        rule_with_deps_transition(
            name = "transitioner",
            deps = [":simple"],
        )

        simple_rule(name = "simple")
        
        """.trimIndent()
        )

        helper.setUniverseScope("//test:transitioner,//test:simple")
        val result: MutableSet<CqueryNode> = eval("config(//test:simple, target)")
        Truth.assertThat(result.size).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectDepsAppearInCqueryDeps() {
        writeFile(
            "donut/test.bzl",
            """
        TestAspectInfo = provider("TestAspectInfo", fields = ["info"])

        def _test_aspect_impl(target, ctx):
            return [
                TestAspectInfo(
                    info = depset([target.label]),
                ),
            ]

        _test_aspect = aspect(
            implementation = _test_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_test_attr": attr.label(
                    allow_files = True,
                    default = Label("//donut:test_filegroup"),
                ),
            },
            provides = [TestAspectInfo],
        )

        def _test_impl(ctx):
            pass

        test_rule = rule(
            _test_impl,
            attrs = {
                "deps": attr.label_list(
                    aspects = [_test_aspect],
                ),
            },
        )
        
        """.trimIndent()
        )
        writeFile(
            "donut/BUILD",
            """
        load(":test.bzl", "test_rule")

        filegroup(
            name = "test_filegroup",
            srcs = ["test.bzl"],
        )

        test_rule(
            name = "test_rule_dep",
        )

        test_rule(
            name = "test_rule",
            deps = [":test_rule_dep"],
        )
        
        """.trimIndent()
        )

        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS
        )
        val result: com.google.common.collect.ImmutableList<Any> =
            eval("filter(//donut, deps(//donut:test_rule))").stream()
                .map<Any?> { cf: CqueryNode -> cf.getDescription(LabelPrinter.legacy()) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(result)
            .containsExactly(
                "//donut:test_rule",
                "//donut:test_rule_dep",
                "//donut:test.bzl%_test_aspect of //donut:test_rule_dep",
                "//donut:test.bzl",
                "//donut:test_filegroup"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainPropagatingAspectDepsAppearInCqueryDeps() {
        writeFile(
            "donut_toolchains/test_toolchain.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        test_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        writeFile(
            "donut_toolchains/BUILD",
            """
        load("//donut_toolchains:test_toolchain.bzl", "test_toolchain")

        toolchain_type(name = "toolchain_type_1")

        test_toolchain(
            name = "foo",
        )

        toolchain(
            name = "foo_toolchain",
            toolchain = ":foo",
            toolchain_type = ":toolchain_type_1",
        )
        
        """.trimIndent()
        )
        writeFile(
            "donut/test.bzl",
            """
        TestAspectInfo = provider("TestAspectInfo", fields = ["info"])

        def _test_aspect_impl(target, ctx):
            return [
                TestAspectInfo(
                    info = depset([target.label]),
                ),
            ]

        _test_aspect = aspect(
            implementation = _test_aspect_impl,
            toolchains_aspects = ["//donut_toolchains:toolchain_type_1"],
            attrs = {
                "_test_attr": attr.label(
                    allow_files = True,
                    default = Label("//donut:test_filegroup"),
                ),
            },
            provides = [TestAspectInfo],
        )

        def _test_impl(ctx):
            pass

        test_rule = rule(
            _test_impl,
            attrs = {
                "deps": attr.label_list(
                    aspects = [_test_aspect],
                ),
            },
        )

        rule_with_toolchain = rule(
            _test_impl,
            toolchains = ["//donut_toolchains:toolchain_type_1"],
        )
        
        """.trimIndent()
        )
        writeFile(
            "donut/BUILD",
            """
        load(":test.bzl", "test_rule", "rule_with_toolchain")

        filegroup(
            name = "test_filegroup",
            srcs = ["test.bzl"],
        )

        rule_with_toolchain(
            name = "test_rule_dep",
        )

        test_rule(
            name = "test_rule",
            deps = [":test_rule_dep"],
        )
        
        """.trimIndent()
        )
        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS
        )
        (helper as PostAnalysisQueryHelper<CqueryNode?>)
            .useConfiguration("--extra_toolchains=//donut_toolchains:foo_toolchain")

        val result: com.google.common.collect.ImmutableList<Any> =
            eval("filter(//donut, deps(//donut:test_rule))").stream()
                .map<Any?> { cf: CqueryNode -> cf.getDescription(LabelPrinter.legacy()) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

        Truth.assertThat(result)
            .containsExactly(
                "//donut:test_rule",
                "//donut:test_rule_dep",
                "//donut:test.bzl%_test_aspect of //donut:test_rule_dep",
                "//donut:test.bzl",
                "//donut:test_filegroup",
                "//donut_toolchains:foo",
                "//donut_toolchains:toolchain_type_1",
                "//donut:test.bzl%_test_aspect of //donut_toolchains:foo"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnAspectDepsAppearInCqueryDeps() {
        writeFile(
            "donut/test.bzl",
            """
        TestAspectInfo = provider("TestAspectInfo", fields = ["info"])
        TestAspectOnAspectInfo = provider("TestAspectOnAspectInfo", fields = ["info"])

        def _test_aspect_impl(target, ctx):
            return [
                TestAspectInfo(
                    info = depset([target.label]),
                ),
            ]

        _test_aspect = aspect(
            implementation = _test_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_test_attr": attr.label(
                    allow_files = True,
                    default = Label("//donut:test_aspect_filegroup"),
                ),
            },
            provides = [TestAspectInfo],
        )

        def _test_aspect_on_aspect_impl(target, ctx):
            return [
                TestAspectOnAspectInfo(
                    info = depset(
                        direct = [target.label],
                        transitive = [target[TestAspectInfo].info],
                    ),
                ),
            ]

        _test_aspect_on_aspect = aspect(
            implementation = _test_aspect_on_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_test_attr": attr.label(
                    allow_files = True,
                    default = Label("//donut:test_aspect_on_aspect_filegroup"),
                ),
            },
            required_aspect_providers = [TestAspectInfo],
            provides = [TestAspectOnAspectInfo],
        )

        def _test_impl(ctx):
            pass

        test_rule = rule(
            _test_impl,
            attrs = {
                "deps": attr.label_list(
                    aspects = [_test_aspect],
                ),
            },
        )

        def _test_aspect_on_aspect_rule_impl(ctx):
            pass

        test_aspect_on_aspect_rule = rule(
            _test_aspect_on_aspect_rule_impl,
            attrs = {
                "deps": attr.label_list(
                    aspects = [_test_aspect, _test_aspect_on_aspect],
                ),
            },
        )
        
        """.trimIndent()
        )
        writeFile("donut/test_aspect.file")
        writeFile("donut/test_aspect_on_aspect.file")
        writeFile(
            "donut/BUILD",
            """
        load(":test.bzl", "test_aspect_on_aspect_rule", "test_rule")

        filegroup(
            name = "test_aspect_filegroup",
            srcs = ["test_aspect.file"],
        )

        filegroup(
            name = "test_aspect_on_aspect_filegroup",
            srcs = ["test_aspect_on_aspect.file"],
        )

        test_rule(
            name = "test_rule_dep",
        )

        test_rule(
            name = "test_rule",
            deps = [":test_rule_dep"],
        )

        test_aspect_on_aspect_rule(
            name = "test_aspect_on_aspect_rule",
            deps = ["test_rule"],
        )
        
        """.trimIndent()
        )

        helper.setUniverseScope("//donut/...")
        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS
        )
        val result: com.google.common.collect.ImmutableList<Any> =
            eval("filter(//donut, deps(//donut:test_aspect_on_aspect_rule))").stream()
                .map<Any?> { cf: CqueryNode -> cf.getDescription(LabelPrinter.legacy()) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(result)
            .containsExactly(
                "//donut:test.bzl%_test_aspect_on_aspect on top of"
                        + " [//donut:test.bzl%_test_aspect of //donut:test_rule_dep]",
                "//donut:test.bzl%_test_aspect_on_aspect on top of"
                        + " [//donut:test.bzl%_test_aspect of //donut:test_rule]",
                "//donut:test_rule_dep",
                "//donut:test_rule",
                "//donut:test.bzl%_test_aspect of //donut:test_rule_dep",
                "//donut:test.bzl%_test_aspect of //donut:test_rule",
                "//donut:test_aspect_on_aspect_rule",
                "//donut:test_aspect.file",
                "//donut:test_aspect_on_aspect_filegroup",
                "//donut:test_aspect_on_aspect.file",
                "//donut:test_aspect_filegroup"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectDepsAppearInCqueryRdeps() {
        writeFile(
            "donut/test.bzl",
            """
        TestAspectInfo = provider("TestAspectInfo", fields = ["info"])

        def _test_aspect_impl(target, ctx):
            return [
                TestAspectInfo(
                    info = depset([target.label]),
                ),
            ]

        _test_aspect = aspect(
            implementation = _test_aspect_impl,
            attr_aspects = ["deps"],
            attrs = {
                "_test_attr": attr.label(
                    allow_files = True,
                    default = Label("//donut:test_filegroup"),
                ),
            },
            provides = [TestAspectInfo],
        )

        def _test_impl(ctx):
            pass

        test_rule = rule(
            _test_impl,
            attrs = {
                "deps": attr.label_list(
                    aspects = [_test_aspect],
                ),
            },
        )
        
        """.trimIndent()
        )
        writeFile(
            "donut/BUILD",
            """
        load(":test.bzl", "test_rule")

        filegroup(
            name = "test_filegroup",
            srcs = ["test.bzl"],
        )

        test_rule(
            name = "test_rule_dep",
        )

        test_rule(
            name = "test_rule",
            deps = [":test_rule_dep"],
        )
        
        """.trimIndent()
        )

        helper.setQuerySettings(
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS,
            com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.EXPLICIT_ASPECTS
        )
        val result: com.google.common.collect.ImmutableList<Any> =
            eval("rdeps(//donut/..., //donut:test_filegroup)").stream()
                .map<Any?> { cf: CqueryNode -> cf.getDescription(LabelPrinter.legacy()) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(result)
            .containsExactly(
                "//donut:test_filegroup",
                "//donut:test_rule",
                "//donut:test.bzl%_test_aspect of //donut:test_rule_dep"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrRespectsConfiguration() {
        val ruleWithList: MockRule =
            MockRule { MockRule.define("rule_with_list", attr("string_values", STRING_LIST)) }

        helper.useRuleClassProvider(setRuleClassProviders(ruleWithList).build())

        writeFile(
            "test/BUILD",
            """
        load(":flag.bzl", "bool_flag")
        bool_flag(
            name = "enable",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        writeFile(
            "configurable/BUILD",
            """
        config_setting(
            name = "enabled",
            define_values = {"test_enable": "true"})
        rule_with_list(
            name = 'target',
            string_values = select({
                ':enabled': ['foo', 'bar'],
                '//conditions:default': ['quux'],
            }),
        )
        
        """.trimIndent()
        )

        // Using default configuration, 'quux' is the only value in the attribute.
        Truth.assertThat(evalToString("attr(string_values, 'foo', '//configurable:target')")).isEmpty()
        Truth.assertThat(evalToString("attr(string_values, 'bar', '//configurable:target')")).isEmpty()
        Truth.assertThat(evalToString("attr(string_values, 'quux', '//configurable:target')"))
            .isEqualTo("//configurable:target")

        // When the flag is enabled, 'foo' and 'bar' are present, but not 'quux'
        (helper as PostAnalysisQueryHelper<CqueryNode?>).useConfiguration("--define=test_enable=true")
        Truth.assertThat(evalToString("attr(string_values, 'foo', '//configurable:target')"))
            .isEqualTo("//configurable:target")
        Truth.assertThat(evalToString("attr(string_values, 'bar', '//configurable:target')"))
            .isEqualTo("//configurable:target")
        Truth.assertThat(evalToString("attr(string_values, 'quux', '//configurable:target')")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlySelectedDormantDepsReturned() {
        writeFile(
            "a/a.bzl",
            """
        ComponentInfo = provider(fields=["dormant"])

        def _bin_impl(ctx):
          return [DefaultInfo()]

        def _materializer(ctx):
          return [d for d in ctx.attr.dep[ComponentInfo].dormant if "yes" in str(d.label)]

        bin = rule(
          implementation = _bin_impl,
          attrs = {
            "_materialized": attr.label_list(materializer=_materializer),
            "dep": attr.label(for_dependency_resolution = True) })

        def _component_impl(ctx):
          return [ComponentInfo(dormant=ctx.attr.impl)]

        component = rule(
          implementation = _component_impl,
          dependency_resolution_rule = True,
          attrs = { "impl": attr.dormant_label_list() })
        
        """.trimIndent()
        )

        writeFile(
            "a/BUILD",
            """
        load("a.bzl", "bin", "component")

        filegroup(name="a_yes")
        filegroup(name="b_no")

        bin(name="bin", dep=":c")
        component(name="c", impl=[":a_yes", "b_no"])
        
        """.trimIndent()
        )

        (helper as PostAnalysisQueryHelper<CqueryNode?>).useConfiguration("--experimental_dormant_deps")
        val deps: com.google.common.collect.ImmutableList<String>? = evalToListOfStrings("deps('//a:bin')")
        Truth.assertThat(deps).containsAtLeast("//a:bin", "//a:c", "//a:a_yes")
        Truth.assertThat(deps).doesNotContain("//a:b_no")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerRuleQuery() {
        writeFile(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
   f = ctx.actions.declare_file(ctx.label.name + ".txt")
   ctx.actions.write(f, ctx.label.name)
   return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for cd in ctx.attr.all_components_dormant:
        if "yes" in str(cd.label):
            selected.append(cd)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components_dormant": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        writeFile(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components_dormant = [":a_yes", ":b_yes", ":c_no", ":d_no"],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        (helper as PostAnalysisQueryHelper<CqueryNode?>).useConfiguration("--experimental_dormant_deps")
        val directDeps: com.google.common.collect.ImmutableList<String>? = evalToListOfStrings("deps('//:bin', 1)")
        // The direct deps should not contain the unanalyzed (i.e. non-selected) dormant deps.
        // cquery should also include the materializer rule in the direct deps, even though it
        // "disappears" from the perspective of the depending target, since changing the materializer
        // rule (e.g. its implementation function) could change the depending target.
        Truth.assertThat(directDeps).containsAtLeast("//:a_yes", "//:b_yes", "//:component_selector")
        Truth.assertThat(directDeps).containsNoneOf("//:c_no", "//:d_no")

        val allDeps: com.google.common.collect.ImmutableList<String>? = evalToListOfStrings("deps('//:bin')")
        // All deps should still not contain the non-selected dormant deps.
        Truth.assertThat(allDeps).containsAtLeast("//:a_yes", "//:b_yes", "//:component_selector")
        Truth.assertThat(allDeps).containsNoneOf("//:c_no", "//:d_no")

        // component_selector shouldn't have deps because they're all through a dormant_label_list.
        val componentSelectorDeps: com.google.common.collect.ImmutableList<String>? =
            evalToListOfStrings("deps('//:component_selector', 1)")
        Truth.assertThat(componentSelectorDeps).containsNoneOf("//:a_yes", "//:b_yes", "//:c_no", "//:d_no")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerRuleRealDepsQuery() {
        writeFile(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output", "info"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f, info = ctx.attr.info)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
    attrs = {
        "info": attr.string(),
    }
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in c[ComponentInfo].info:
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    allow_real_deps = True,
    attrs = {
        "all_components": attr.label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        writeFile(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [":a", ":b", ":c", ":d"],
)

component(name = "aaa")
component(name = "a", info = "yes")
component(name = "b", info = "yes")
component(name = "c", info = "no")
component(name = "d", info = "no")
component(name = "zzz")

""".trimIndent()
        )

        (helper as PostAnalysisQueryHelper<CqueryNode?>).useConfiguration("--experimental_dormant_deps")
        val directDeps: com.google.common.collect.ImmutableList<String>? = evalToListOfStrings("deps('//:bin', 1)")
        // The direct deps of bin should not contain the non-selected deps.
        // cquery should also include the materializer rule in the direct deps, even though it
        // "disappears" from the perspective of the depending target, since changing the materializer
        // rule (e.g. its implementation function) could change the depending target.
        Truth.assertThat(directDeps).containsAtLeast("//:a", "//:b", "//:component_selector")
        Truth.assertThat(directDeps).containsNoneOf("//:c", "//:d")

        // All deps will contain the non-selected deps because they're non-dormant ("real") deps of the
        // materializer rule.
        val allDeps: com.google.common.collect.ImmutableList<String>? = evalToListOfStrings("deps('//:bin')")
        Truth.assertThat(allDeps).containsAtLeast("//:a", "//:b", "//:c", "//:d", "//:component_selector")

        // component_selector all the deps because they're all through a label_list.
        val componentSelectorDeps: com.google.common.collect.ImmutableList<String>? =
            evalToListOfStrings("deps('//:component_selector', 1)")
        Truth.assertThat(componentSelectorDeps).containsAtLeast("//:a", "//:b", "//:c", "//:d")
    }
}
