// Copyright 2017 The Bazel Authors. All rights reserved.
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

/**
 * Integration tests for configurable attributes.
 */
@RunWith(JUnit4::class)
class ConfigurableAttributesTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun writeConfigRules() {
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        config_setting(
            name = 'b',
            values = {'foo': 'b'})
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun writeHelloRules(includeDefaultCondition: Boolean) {
        scratch.file(
            "java/hello/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_binary', 'java_library')",
            "java_binary(",
            "    name = 'hello',",
            "    srcs = ['hello.java'],",
            "    deps = select({",
            "        '//conditions:a': [':adep'],",
            "        '//conditions:b': [':bdep'],",
            if (includeDefaultCondition)
                "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],"
            else
                "",
            "    }))",
            "",
            "java_library(",
            "    name = 'adep',",
            "    srcs = ['adep.java'])",
            "java_library(",
            "    name = 'bdep',",
            "    srcs = ['bdep.java'])",
            "java_library(",
            "    name = 'defaultdep',",
            "    srcs = ['defaultdep.java'])"
        )
    }

    /**
     * Checks that, given the specified configuration parameters, the input rule *has* the expected
     * attribute values and *doesn't have* the unexpected attribute values.
     */
    @Throws(java.lang.Exception::class)
    private fun checkRule(
        ruleLabel: String?,
        attributeName: String?,
        options: MutableCollection<String?>,
        expected: Iterable<String?>,
        notExpected: Iterable<String?>
    ) {
        useConfiguration(*options.toArray<String?>(arrayOfNulls<String>(options.size())))
        val binary: ConfiguredTarget? = getConfiguredTarget(ruleLabel)
        assertThat(binary).isNotNull()
        val actualDeps: MutableSet<String>? = artifactsToStrings(getPrerequisiteArtifacts(binary, attributeName))
        expected.forEach(java.util.function.Consumer { expectedInput: String? ->
            Truth.assertThat(actualDeps).contains(expectedInput)
        })
        notExpected.forEach(java.util.function.Consumer { unexpectedInput: String? ->
            Truth.assertThat(actualDeps).doesNotContain(unexpectedInput)
        })
    }

    @Throws(java.lang.Exception::class)
    private fun checkRule(
        ruleLabel: String?, option: String,
        expected: Iterable<String?>, notExpected: Iterable<String?>
    ) {
        checkRule(ruleLabel, com.google.common.collect.ImmutableList.of<String?>(option), expected, notExpected)
    }

    @Throws(java.lang.Exception::class)
    private fun checkRule(
        ruleLabel: String?,
        options: MutableCollection<String?>,
        expected: Iterable<String?>,
        notExpected: Iterable<String?>
    ) {
        checkRule(ruleLabel, "deps", options, expected, notExpected)
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(RULE_WITH_OUTPUT_ATTR)
                .addRuleDefinition(RULE_WITH_COMPUTED_DEFAULT)
                .addRuleDefinition(RULE_WITH_BOOLEAN_ATTR)
                .addRuleDefinition(RULE_WITH_ALLOWED_VALUES)
                .addRuleDefinition(RULE_WITH_LABEL_DEFAULT)
                .addRuleDefinition(RULE_WITH_NO_PLATFORM)
                .addRuleDefinition(RULE_WITH_STRING_LIST_DICT_ATTR)
        TestRuleClassProvider.addStandardRules(builder)
        // Allow use of --foo as a dummy flag
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setupStarlarkJavaBinary() {
        setBuildLanguageOptions("--experimental_google_legacy_api")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicConfigurability() {
        writeHelloRules( /*includeDefaultCondition=*/true)
        writeConfigRules()
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )
        checkRule(
            "//java/hello:hello",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, DEFAULTDEP_INPUT)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configurabilityDefaults() {
        writeHelloRules( /*includeDefaultCondition=*/true)
        writeConfigRules()
        checkRule(
            "//java/hello:hello",
            "--foo=something_random",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
        checkRule(
            "//java/hello:hello", "",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
    }

    /**
     * Duplicate label definitions are fine as long as they're in different selection branches.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depsWithDuplicatesInDifferentBranches() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_binary', 'java_library')",
            "java_binary(",
            "    name = 'hello',",
            "    srcs = ['hello.java'],",
            "    deps = select({",
            "        '//conditions:a': [':adep', ':cdep'],",
            "        '//conditions:b': [':bdep', ':cdep'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
            "    }))",
            "",
            "java_library(",
            "    name = 'adep',",
            "    srcs = ['adep.java'])",
            "java_library(",
            "    name = 'bdep',",
            "    srcs = ['bdep.java'])",
            "java_library(",
            "    name = 'cdep',",
            "    srcs = ['cdep.java'])"
        )
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, CDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )
    }

    /**
     * Duplicate label definitions are *not* fine within the same branch.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depsWithDuplicatesInSameBranch() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_binary', 'java_library')",
            "java_binary(",
            "    name = 'hello',",
            "    srcs = ['hello.java'],",
            "    deps = select({",
            "        '//conditions:a': [':adep', ':cdep', ':adep'],",
            "        '//conditions:b': [':bdep', ':cdep'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
            "    }))",
            "",
            "java_library(",
            "    name = 'adep',",
            "    srcs = ['adep.java'])",
            "java_library(",
            "    name = 'bdep',",
            "    srcs = ['bdep.java'])",
            "java_library(",
            "    name = 'cdep',",
            "    srcs = ['cdep.java'])"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        useConfiguration("--foo=a")
        getConfiguredTarget("//java/hello:hello")
        assertContainsEvent(
            "Label '//java/hello:adep' is duplicated in the 'deps' attribute of rule 'hello'"
        )
    }

    /**
     * When an attribute includes multiple selects, we don't allow duplicates even across
     * selects (this saves us from having to do possibly expensive value iteration since the
     * number of values can grow exponentially with respect to the number of selects).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicatesAcrossMultipleSelects() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'hello',
            srcs = select({
                '//conditions:a': ['a.java'],
                '//conditions:b': ['b.java'],
                })
                + select({
                '//conditions:a': ['a.java'],
                '//conditions:b': ['c.java'],
            }))
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        useConfiguration("--foo=a")
        getConfiguredTarget("//java/hello:hello")
        assertContainsEvent(
            "in srcs attribute of java_binary rule //java/hello:hello: Label '//java/hello:a.java' is"
                    + " duplicated"
        )
    }

    /**
     * Even with multiple selects, duplicates are allowed within a *single* select as long as
     * they're in different branches (and thus mutually exclusive).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicatesInDifferentBranchesMultipleSelects() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'hello',
            srcs = select({
                '//conditions:a': ['a.java'],
                '//conditions:b': ['a.java'],
                })
                + select({
                '//conditions:a': ['b.java'],
                '//conditions:b': ['b.java'],
            }))
        
        """.trimIndent()
        )

        useConfiguration("--foo=a")
        getConfiguredTarget("//java/hello:hello")
        assertNoEvents()
    }

    /**
     * With multiple selects, a single select still can't duplicate labels within the same branch.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicatesInSameBranchMultipleSelects() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'hello',
            srcs = select({
                '//conditions:a': ['a.java', 'a.java'],
                '//conditions:b': ['b.java'],
                })
                + select({
                '//conditions:a': ['c.java'],
                '//conditions:b': ['d.java'],
            }))
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        useConfiguration("--foo=a")
        getConfiguredTarget("//java/hello:hello")
        assertContainsEvent(
            "Label '//java/hello:a.java' is duplicated in the 'srcs' attribute of rule 'hello'"
        )
    }

    /**
     * Attributes of type [BuildType.OUTPUT] are not configurable.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputTypeNotConfigurable() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            "rule_with_output_attr(",
            "    name = 'has_an_out',",
            "    out = select({",
            "        '//conditions:a': 'a.out',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': 'default.out'})",
            ")"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo:has_an_out")
        assertContainsEvent("attribute \"out\" is not configurable")
    }

    /**
     * Attributes of type [BuildType.OUTPUT_LIST] are not configurable.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputListTypeNotConfigurable() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            "genrule(",
            "    name = 'generator',",
            "    srcs = [],",
            "    outs = select({",
            "        '//conditions:a': ['a.out'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': ['default.out']})",
            ")"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo:generator")
        assertContainsEvent("attribute \"outs\" is not configurable")
    }

    /**
     * Tests that computed defaults faithfully reflect the values of the attributes they depend on.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun computedDefaults() {
        writeConfigRules()
        scratch.file(
            "test/BUILD",
            "rule_with_computed_default(",
            "    name = 'the_rule',",
            "    string_attr = select({",
            "        '//conditions:a': 'a',",
            "        '//conditions:b': 'b',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': 'default',",
            "    }))"
        )

        // Configuration a:
        useConfiguration("--foo=a")
        var binary: ConfiguredTargetAndData = getConfiguredTargetAndData("//test:the_rule")
        var attributes: AttributeMap = BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(binary)
        assertThat(attributes.get("\$computed_attr", Type.STRING)).isEqualTo("a2")

        // configuration b:
        useConfiguration("--foo=b")
        binary = getConfiguredTargetAndData("//test:the_rule")
        attributes = BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(binary)
        assertThat(attributes.get("\$computed_attr", Type.STRING)).isEqualTo("b2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyTypeChecking_Int() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'int_key',
            srcs = select({123: ['a.java']})
        )
        
        """.trimIndent()
        )
        assertTargetError(
            "//java/foo:int_key", "select: got int for dict key, want a Label or label string"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyTypeChecking_Bool() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'bool_key',
            srcs = select({True: ['a.java']})
        )
        
        """.trimIndent()
        )
        assertTargetError(
            "//java/foo:bool_key", "select: got bool for dict key, want a Label or label string"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyTypeChecking_None() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'none_key',
            srcs = select({None: ['a.java']})
        )
        
        """.trimIndent()
        )
        assertTargetError(
            "//java/foo:none_key", "select: got NoneType for dict key, want a Label or label string"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectWithoutConditionsMakesNoSense() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'nothing',
            srcs = [],
            outs = ['notmuch'],
            cmd = select({})
        )
        
        """.trimIndent()
        )
        assertTargetError(
            "//foo:nothing",
            "select({}) with an empty dictionary can never resolve because it includes no conditions "
                    + "to match"
        )
    }

    /**
     * Tests that config keys must resolve to existent targets.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingConfigKey() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        // Only create one of two necessary configurability rules:
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        
        """.trimIndent()
        )
        writeHelloRules( /*includeDefaultCondition=*/true)
        getConfiguredTarget("//java/hello:hello")
        assertContainsEvent("no such target '//conditions:b'")
    }

    /**
     * Tests that config keys must resolve to config_setting targets.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidConfigKey() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        rule_with_output_attr(
            name = 'b',
            out = 'b.out')
        
        """.trimIndent()
        )
        writeHelloRules( /*includeDefaultCondition=*/true)
        assertThat(getConfiguredTarget("//java/hello:hello")).isNull()
        assertContainsEvent("//conditions:b is not a valid select() condition for //java/hello:hello")
        assertDoesNotContainEvent("//conditions:a") // This one is legitimate..
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyNonexistentTarget() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'g',
            outs = ['g.out'],
            cmd = select({':fake': ''})
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//foo:g")).isNull()
        assertContainsEvent("//foo:fake is not a valid select() condition for //foo:g")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyNonexistentTarget_otherPackage() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'a'})
        
        """.trimIndent()
        )
        scratch.file("bar/BUILD")
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'g',
            outs = ['g.out'],
            # With an invalid target and a real target, validate skyframe error handling.
            # See http://b/162021059 for details.
            cmd = select({'//bar:fake': '', '//conditions:a': ''})
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//foo:g")).isNull()
        assertContainsEvent("bar/BUILD: no such target '//bar:fake'")
        assertContainsEvent("foo/BUILD:1:8: errors encountered resolving select() keys for //foo:g")
    }

    /**
     * Tests config keys with multiple requirements.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multiConditionConfigKeys() {
        writeHelloRules( /*includeDefaultCondition=*/true)
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {
                'foo': 'a',
                'compilation_mode': 'dbg'
            })
        config_setting(
            name = 'b',
            values = {'foo': 'b'})
        
        """.trimIndent()
        )
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
        checkRule(
            "//java/hello:hello",
            com.google.common.collect.ImmutableList.of<String?>("--foo=a", "--compilation_mode=dbg"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )
    }

    /**
     * Tests that changing a config_setting invalidates the rule that uses it.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configKeyInvalidation() {
        writeHelloRules( /*includeDefaultCondition=*/true)
        writeConfigRules()

        // Iteration 1: --test_args=a should apply //conditions:a.
        useConfiguration("--foo=a")
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )

        // Rewrite the condition for //conditions:a.
        scratch.overwriteFile(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'foo': 'c'})
        config_setting(
            name = 'b',
            values = {'foo': 'b'})
        
        """.trimIndent()
        )

        // Iteration 2: same exact analysis should now apply the default condition.
        invalidatePackages()
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
    }

    /**
     * Tests that multiple matches are not allowed for conditions where one is not a specialization
     * of the other.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatches() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'dup1',
            values = {'compilation_mode': 'opt'})
        config_setting(
            name = 'dup2',
            values = {'define': 'foo=bar'})
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            "genrule(",
            "    name = 'gen',",
            "    cmd = '',",
            "    outs = ['gen.out'],",
            "    srcs = select({",
            "        '//conditions:dup1': ['a.in'],",
            "        '//conditions:dup2': ['b.in'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':default.in'],",
            "    }))"
        )
        useConfiguration("-c", "opt", "--define", "foo=bar")
        assertThat(getConfiguredTarget("//a:gen")).isNull()
        assertContainsEvent(
            ("Illegal ambiguous match on configurable attribute \"srcs\" in //a:gen:\n"
                    + "//conditions:dup1\n"
                    + "//conditions:dup2\n"
                    + "Multiple matches are not allowed unless one is unambiguously more specialized "
                    + "or they resolve to the same value.")
        )
    }

    /**
     * Tests that when multiple conditions match and for every matching pair, one is
     * a specialization of the other, the most specialized match is chosen.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchesConditionAndSubcondition() {
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'generic',
            values = {'compilation_mode': 'opt'})
        config_setting(
            name = 'precise',
            values = {'compilation_mode': 'opt', 'define': 'foo=bar'})
        config_setting(
            name = 'most_precise',
            values = {'compilation_mode': 'opt', 'define': 'foo=bar', 'foo': 'baz'})
        
        """.trimIndent()
        )
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary", "java_library")
        java_binary(
            name = 'binary',
            srcs = ['binary.java'],
            deps = select({
                '//conditions:generic': [':generic'],
                '//conditions:precise': [':precise'],
                '//conditions:most_precise': [':most_precise'],
            }))
        java_library(
            name = 'generic',
            srcs = ['generic.java'])
        java_library(
            name = 'precise',
            srcs = ['precise.java'])
        java_library(
            name = 'most_precise',
            srcs = ['most_precise.java'])
        
        """.trimIndent()
        )
        checkRule(
            "//java/a:binary",
            com.google.common.collect.ImmutableList.of<String?>(
                "-c",
                "opt",
                "--define",
                "foo=bar",
                "--foo",
                "baz"
            ),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/a/libmost_precise.jar"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/a/libgeneric.jar", "bin java/a/libprecise.jar"
            )
        )
    }

    /** Tests that multiple matches are allowed for conditions where the value is the same.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchesSameValue() {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'dup1',
            values = {'compilation_mode': 'opt'})
        config_setting(
            name = 'dup2',
            values = {'define': 'foo=bar'})
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            "genrule(",
            "    name = 'gen',",
            "    cmd = '',",
            "    outs = ['gen.out'],",
            "    srcs = select({",
            "        '//conditions:dup1': ['a.in'],",
            "        '//conditions:dup2': ['a.in'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':default.in'],",
            "    }))"
        )
        checkRule(
            "//a:gen",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("-c", "opt", "--define", "foo=bar"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/a.in"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/default.in")
        )
    }

    /**
     * Tests that when multiple conditions match but one condition is more specialized than the
     * others, it is chosen and there is no error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchesUnambiguous() {
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {'define': 'a=1'})
        config_setting(
            name = 'b',
            values = {'compilation_mode': 'opt'})
        config_setting(
            name = 'c',
            values = {'foo': 'baz'})
        config_setting(
            name = 'b_a_c',  # Named to come alphabetically after a and b but before c.
            values = {'define': 'a=1', 'foo': 'baz', 'compilation_mode': 'opt'})
        
        """.trimIndent()
        )
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary", "java_library")
        java_binary(
            name = 'binary',
            srcs = ['binary.java'],
            deps = select({
                '//conditions:a': [':a'],
                '//conditions:b': [':b'],
                '//conditions:c': [':c'],
                '//conditions:b_a_c': [':b_a_c'],
            }))
        java_library(
            name = 'a',
            srcs = ['a.java'])
        java_library(
            name = 'b',
            srcs = ['b.java'])
        java_library(
            name = 'c',
            srcs = ['c.java'])
        java_library(
            name = 'b_a_c',
            srcs = ['b_a_c.java'])
        
        """.trimIndent()
        )
        checkRule(
            "//java/a:binary",
            com.google.common.collect.ImmutableList.of<String?>(
                "--define",
                "a=1",
                "--compilation_mode",
                "opt",
                "--foo",
                "baz"
            ),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/a/libb_a_c.jar"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/a/liba.jar", "bin java/a/libb.jar", "bin java/a/libc.jar"
            )
        )
    }

    /** Tests that specialization checking works as expected when one user-defined flag is aliased.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchesUnambiguous_aliasedFlagValue() {
        scratch.file(
            "conditions/rules.bzl",
            "def _build_setting_impl(ctx):",
            "  return []",
            "string_flag = rule(",
            "  implementation = _build_setting_impl,",
            "  build_setting = config.string(flag=True))"
        )
        scratch.file(
            "conditions/BUILD",
            "load('//conditions:rules.bzl', 'string_flag')",
            "string_flag(name = 'foo_arg', build_setting_default = 'foo')",
            "string_flag(name = 'bar_arg', build_setting_default = 'bar')",
            "alias(",
            "    name = 'foo_alias',",
            "    actual = ':foo_arg')",
            "config_setting(",
            "    name = 'a',",
            "    flag_values = {':foo_arg': 'foo'})",
            "config_setting(",
            "    name = 'b',",
            "    flag_values = {':foo_alias': 'foo', ':bar_arg': 'bar'})"
        )
        scratch.file(
            "a/BUILD",
            "genrule(",
            "    name = 'gen',",
            "    cmd = '',",
            "    outs = ['gen.out'],",
            "    srcs = select({",
            "        '//conditions:a': ['a.in'],",
            "        '//conditions:b': ['b.in'],",
            "    }))"
        )
        checkRule(
            "//a:gen",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/b.in"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/a.in")
        )
    }

    /** Tests that specialization checking works as expected when one constraint is aliased.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchesUnambiguous_aliasedConstraintValue() {
        scratch.file(
            "conditions/BUILD",
            "constraint_setting(name = 'foo_con')",
            "constraint_setting(name = 'bar_con')",
            "constraint_value(name = 'foo', constraint_setting = 'foo_con')",
            "constraint_value(name = 'bar', constraint_setting = 'bar_con')",
            "alias(",
            "    name = 'foo_alias',",
            "    actual = ':foo')",
            "platform(",
            "    name = 'specialized_platform',",
            "    constraint_values = [':foo', ':bar'],",
            ")",
            "config_setting(",
            "    name = 'a',",
            "    constraint_values = [':foo'])",
            "config_setting(",
            "    name = 'b',",
            "    constraint_values = [':foo_alias', ':bar'])"
        )
        scratch.file(
            "a/BUILD",
            "genrule(",
            "    name = 'gen',",
            "    cmd = '',",
            "    outs = ['gen.out'],",
            "    srcs = select({",
            "        '//conditions:a': ['a.in'],",
            "        '//conditions:b': ['b.in'],",
            "    }))"
        )
        checkRule(
            "//a:gen",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("--platforms=//conditions:specialized_platform"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/b.in"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src a/a.in")
        )
    }

    /** Tests that default conditions are only required when no main condition matches.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noDefaultCondition() {
        writeHelloRules( /*includeDefaultCondition=*/false)
        writeConfigRules()

        // An explicit configuration matches: all is well.
        checkRule(
            "//java/hello:hello",
            "--foo=a",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )

        // Nothing matches: expect an error.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        useConfiguration("")
        assertThat(getConfiguredTarget("//java/hello:hello")).isNull()
        assertContainsEvent(
            "configurable attribute \"deps\" in //java/hello:hello doesn't match this configuration"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noMatchCustomErrorMessage() {
        writeConfigRules()
        scratch.file(
            "java/hello/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'hello_default_no_match_error',
            srcs = select({
                '//conditions:a': ['not_chosen.java'],
            }))
        java_binary(
            name = 'hello_custom_no_match_error',
            srcs = select({
                '//conditions:a': ['not_chosen.java'],
            },
            no_match_error = 'You always have to choose condition a!'
        ))
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        var analysisFailureRecorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(analysisFailureRecorder)
        useConfiguration("")

        assertThat(getConfiguredTarget("//java/hello:hello_default_no_match_error")).isNull()
        assertContainsEvent(
            ("configurable attribute \"srcs\" in //java/hello:hello_default_no_match_error doesn't"
                    + " match this configuration. Would a default condition help?\n"
                    + "\n"
                    + "Conditions checked:")
        )
        // Verify a Root Cause is reported when a target cannot be configured due to no matching config.
        Truth.assertThat(analysisFailureRecorder.causes).hasSize(1)
        var rootCause: AnalysisRootCauseEvent = analysisFailureRecorder.causes.get(0)
        assertThat(rootCause.getLabel())
            .isEqualTo(Label.parseCanonical("//java/hello:hello_default_no_match_error"))

        eventBus.unregister(analysisFailureRecorder)
        analysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(analysisFailureRecorder)
        eventCollector.clear()

        assertThat(getConfiguredTarget("//java/hello:hello_custom_no_match_error")).isNull()
        assertContainsEvent(
            "configurable attribute \"srcs\" in //java/hello:hello_custom_no_match_error doesn't match "
                    + "this configuration: You always have to choose condition a!"
        )
        // Verify a Root Cause is reported when a target cannot be configured due to no matching config.
        Truth.assertThat(analysisFailureRecorder.causes).hasSize(1)
        rootCause = analysisFailureRecorder.causes.get(0)
        assertThat(rootCause.getLabel())
            .isEqualTo(Label.parseCanonical("//java/hello:hello_custom_no_match_error"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nativeTypeConcatenatedWithSelect() {
        writeConfigRules()
        scratch.file(
            "java/foo/rule.bzl",
            """
        def _rule_impl(ctx):
            return []
        myrule = rule(
            implementation = _rule_impl,
            attrs = {
                'deps': attr.label_keyed_string_dict()
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary", "java_library")
        load(':rule.bzl', 'myrule')
        myrule(
            name = 'mytarget',
            deps = {':always': 'always'} | select({
                '//conditions:a': {':a': 'a'},
                '//conditions:b': {':b': 'b'},
            })
        )
        java_binary(
            name = 'binary',
            srcs = ['binary.java'],
            deps = [':always'] + select({
                '//conditions:a': [':a'],
                '//conditions:b': [':b'],
            })
        )
        java_library(
            name = 'always',
            srcs = ['always.java'])
        java_library(
            name = 'a',
            srcs = ['a.java'])
        java_library(
            name = 'b',
            srcs = ['b.java'])
        
        """.trimIndent()
        )

        checkRule(
            "//java/foo:binary",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libalways.jar",
                "bin java/foo/libb.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar")
        )

        checkRule(
            "//java/foo:mytarget",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libalways.jar",
                "bin java/foo/libb.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectConcatenatedWithNativeType() {
        writeConfigRules()
        scratch.file(
            "java/foo/rule.bzl",
            """
        def _rule_impl(ctx):
            return []
        myrule = rule(
            implementation = _rule_impl,
            attrs = {
                'deps': attr.label_keyed_string_dict()
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary", "java_library")
        load(':rule.bzl', 'myrule')
        myrule(
            name = 'mytarget',
            deps = select({
                '//conditions:a': {':a': 'a'},
                '//conditions:b': {':b': 'b'},
            }) | {':always': 'always'}
        )
        java_binary(
            name = 'binary',
            srcs = ['binary.java'],
            deps = select({
                '//conditions:a': [':a'],
                '//conditions:b': [':b'],
            }) + [':always'])
        java_library(
            name = 'always',
            srcs = ['always.java'])
        java_library(
            name = 'a',
            srcs = ['a.java'])
        java_library(
            name = 'b',
            srcs = ['b.java'])
        
        """.trimIndent()
        )

        checkRule(
            "//java/foo:binary",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libalways.jar",
                "bin java/foo/libb.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar")
        )

        checkRule(
            "//java/foo:mytarget",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libalways.jar",
                "bin java/foo/libb.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectConcatenatedWithSelect() {
        writeConfigRules()
        scratch.file(
            "java/foo/rule.bzl",
            """
        def _rule_impl(ctx):
            return []
        myrule = rule(
            implementation = _rule_impl,
            attrs = {
                'deps': attr.label_keyed_string_dict()
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary", "java_library")
        load(':rule.bzl', 'myrule')
        myrule(
            name = 'mytarget',
            deps = select({
                '//conditions:a': {':a': 'a'},
                '//conditions:b': {':b': 'b'},
            }) | select({
                '//conditions:a': {':a2': 'a2'},
                '//conditions:b': {':b2': 'b2'},
            })
        )
        java_binary(
            name = 'binary',
            srcs = ['binary.java'],
            deps = select({
                '//conditions:a': [':a'],
                '//conditions:b': [':b'],
            }) + select({
                '//conditions:a': [':a2'],
                '//conditions:b': [':b2'],
            })
        )
        java_library(
            name = 'a',
            srcs = ['a.java'])
        java_library(
            name = 'b',
            srcs = ['b.java'])
        java_library(
            name = 'a2',
            srcs = ['a2.java'])
        java_library(
            name = 'b2',
            srcs = ['b2.java'])
        
        """.trimIndent()
        )

        checkRule(
            "//java/foo:binary",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libb.jar",
                "bin java/foo/libb2.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar", "bin java/foo/liba2.jar")
        )

        checkRule(
            "//java/foo:mytarget",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libb.jar",
                "bin java/foo/libb2.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar", "bin java/foo/liba2.jar")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dictsWithSameKey() {
        writeConfigRules()
        scratch.file(
            "java/foo/rule.bzl",
            """
        def _rule_impl(ctx):
            outputs = []
            for target, value in ctx.attr.deps.items():
                output = ctx.actions.declare_file(target.label.name + value)
                ctx.actions.write(content = value, output = output)
                outputs.append(output)
            return [DefaultInfo(files=depset(outputs))]
        myrule = rule(
            implementation = _rule_impl,
            attrs = {
                'deps': attr.label_keyed_string_dict()
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load(':rule.bzl', 'myrule')
        myrule(
            name = 'mytarget',
            deps = select({
                '//conditions:a': {':a': 'a'},
            }) | select({
                '//conditions:a': {':a': 'a2'},
            })
        )
        java_library(
            name = 'a',
            srcs = ['a.java']
        )
        filegroup(
            name = 'group',
            srcs = [':mytarget'],
        )
        
        """.trimIndent()
        )

        checkRule(
            "//java/foo:group",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("--foo=a"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/aa2"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/aa")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectConcatenatedWithNonSupportingType() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = 0 + select({
                '//conditions:a': 0,
                '//conditions:b': 1,
            }))
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//foo:binary")).isNull()
        assertContainsEvent("type 'boolean' doesn't support select concatenation")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concatenationWithDifferentTypes() {
        writeConfigRules()
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'binary',
            srcs = select({
                '//conditions:a': ['a.java'],
                '//conditions:b': ['b.java'],
            }) + 'always.java'
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { getTarget("//java/foo:binary") })
        assertContainsEvent("Cannot combine incompatible types")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectsWithGlobs() {
        writeConfigRules()
        scratch.file("java/foo/globbed/ceecee.java")
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'binary',
            srcs = glob(['globbed/*.java']) + select({
                '//conditions:a': ['a.java'],
                '//conditions:b': ['b.java'],
            }))
        
        """.trimIndent()
        )

        useConfiguration("--foo=b")
        val binary: ConfiguredTarget? = getConfiguredTarget("//java/foo:binary")
        assertThat(binary).isNotNull()
        val sources: MutableSet<String>? = artifactsToStrings(getPrerequisiteArtifacts(binary, "srcs"))
        Truth.assertThat(sources).contains("src java/foo/b.java")
        Truth.assertThat(sources).contains("src java/foo/globbed/ceecee.java")
        Truth.assertThat(sources).doesNotContain("src java/foo/a.java")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectsWithGlobsWrongType() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'gen',
            srcs = [],
            outs = ['gen.out'],
            cmd = 'echo' + select({
                '//conditions:a': 'a',
                '//conditions:b': 'b',
            }) + glob(['globbed.java'], allow_empty = True))
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { getTarget("//foo:binary") })
        assertContainsEvent("Cannot combine incompatible types")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun globsInSelect() {
        writeConfigRules()
        scratch.file("java/foo/globbed/ceecee.java")
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = 'binary',
            srcs = ['binary.java'] + select({
                '//conditions:a': glob(['globbed/*.java']),
                '//conditions:b': ['b.java'],
            }))
        
        """.trimIndent()
        )

        useConfiguration("--foo=a")
        val binary: ConfiguredTarget? = getConfiguredTarget("//java/foo:binary")
        assertThat(binary).isNotNull()
        val sources: MutableSet<String>? = artifactsToStrings(getPrerequisiteArtifacts(binary, "srcs"))
        Truth.assertThat(sources).contains("src java/foo/binary.java")
        Truth.assertThat(sources).contains("src java/foo/globbed/ceecee.java")
        Truth.assertThat(sources).doesNotContain("src java/foo/b.java")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectAcceptedInAttributeWithAllowedValues() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_allowed_values(
            name = 'rule',
            one_two = select({
                '//conditions:default': 'one',
            }))
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//foo:rule")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectWithNonAllowedValueCausesError() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_allowed_values(
            name = 'rule',
            one_two = select({
                '//conditions:default': 'TOTALLY_ILLEGAL_VALUE',
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo:rule")
        assertContainsEvent(
            "invalid value in 'one_two' attribute: "
                    + "has to be one of 'one' or 'two' instead of 'TOTALLY_ILLEGAL_VALUE'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectWithMultipleNonAllowedValuesCausesMultipleErrors() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_allowed_values(
            name = 'rule',
            one_two = select({
                '//conditions:a': 'TOTALLY_ILLEGAL_VALUE',
                '//conditions:default': 'DIFFERENT_BUT_STILL_ILLEGAL',
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo:rule")
        assertContainsEvent(
            "invalid value in 'one_two' attribute: "
                    + "has to be one of 'one' or 'two' instead of 'TOTALLY_ILLEGAL_VALUE'"
        )
        assertContainsEvent(
            "invalid value in 'one_two' attribute: "
                    + "has to be one of 'one' or 'two' instead of 'DIFFERENT_BUT_STILL_ILLEGAL'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectConcatenationWithAllowedValues() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_allowed_values(
            name = 'rule',
            one_two = 'on' + select({
                '//conditions:default': 'e',
            }))
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//foo:rule")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectConcatenationWithNonAllowedValues() {
        scratch.file(
            "foo/BUILD",
            """
        rule_with_allowed_values(
            name = 'rule',
            one_two = 'on' + select({
                '//conditions:default': 'o',
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo:binary")
        assertContainsEvent(
            "invalid value in 'one_two' attribute: "
                    + "has to be one of 'one' or 'two' instead of 'ono'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun computedDefaultAttributesCanReferenceConfigurableAttributes() {
        scratch.file(
            "test/selector_rules.bzl",
            """
        def _impl(ctx):
          ctx.actions.write(
              output=ctx.outputs.out_file,
              content=ctx.attr.string_value,
          )
          return []

        def _derived_value(string_value):
          return Label("//test:%s" % string_value)

        selector_rule = rule(
          attrs = {
              "string_value": attr.string(default = ""),
              "out_file": attr.output(),
              "_derived": attr.label(default = _derived_value),
          },
        implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "genrule(name = \"foo\", srcs = [], outs = [\"foo.out\"], cmd = \"\")"
        )
        scratch.file(
            "foo/BUILD",
            """
        load('//test:selector_rules.bzl', "selector_rule")
        selector_rule(
            name = "rule",
            out_file = "rule.out",
            string_value = select({"//conditions:default": "foo"}),
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//foo:rule")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableDefaultValueWithTypeDefault() {
        writeConfigRules()
        scratch.file(
            "srctest/BUILD",
            """
        genrule(
            name = 'gen',
            cmd = '',
            outs = ['gen.out'],
            srcs = select({
                '//conditions:a': None,
            }))
        
        """.trimIndent()
        )

        useConfiguration("--foo=a")
        val ctad: ConfiguredTargetAndData = getConfiguredTargetAndData("//srctest:gen")
        val attributes: AttributeMap = BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(ctad)
        assertThat(attributes.get("srcs", LABEL_LIST)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableDefaultValueWithRuleDefault() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            """
        rule_with_label_default(
            name = 'rule',
            dep = select({
                '//conditions:a': None,
            }))
        rule_with_boolean_attr(
            name = 'default',
            boolean_attr = 1)
        
        """.trimIndent()
        )

        useConfiguration("--foo=a")
        val ctad: ConfiguredTargetAndData = getConfiguredTargetAndData("//foo:rule")
        val attributes: AttributeMap = BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(ctad)
        assertThat(attributes.get("dep", BuildType.LABEL))
            .isEqualTo(Label.parseCanonical("//foo:default"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneValuesWithMultipleSelectsMixedValues() {
        writeConfigRules()
        scratch.file(
            "a/BUILD",
            """
        genrule(
            name = 'gen',
            srcs = [],
            outs = ['out'],
            cmd = '',
            message = select({
                '//conditions:a': 'defined message 1',
                '//conditions:b': None,
            }) + select({
                '//conditions:a': None,
                '//conditions:b': 'defined message 2',
            }),
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//a:gen")).isNull()
        assertContainsEvent("Cannot combine incompatible types (select of string, select of NoneType)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptySelectCannotBeConcatenated() {
        scratch.file(
            "a/BUILD",
            """
        genrule(
            name = 'gen',
            srcs = [],
            outs = ['out'],
            cmd = select({}) + ' always include'
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:gen")).isNull()
        assertContainsEvent(
            "select({}) with an empty dictionary can never resolve because it includes no conditions "
                    + "to match"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectOnConstraints() {
        // create some useful constraints and platforms.
        scratch.file(
            "conditions/BUILD",
            """
        constraint_setting(name = 'fruit')
        constraint_value(name = 'apple', constraint_setting = 'fruit')
        constraint_value(name = 'banana', constraint_setting = 'fruit')
        platform(
            name = 'apple_platform',
            constraint_values = [':apple'],
        )
        platform(
            name = 'banana_platform',
            constraint_values = [':banana'],
        )
        config_setting(
            name = 'a',
            constraint_values = [':apple']
        )
        config_setting(
            name = 'b',
            constraint_values = [':banana']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/BUILD",
            "filegroup(name = 'adep', srcs = ['afile'])",
            "filegroup(name = 'bdep', srcs = ['bfile'])",
            "filegroup(name = 'defaultdep', srcs = ['defaultfile'])",
            "filegroup(name = 'hello',",
            "    srcs = select({",
            "        '//conditions:a': [':adep'],",
            "        '//conditions:b': [':bdep'],",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "': [':defaultdep'],",
            "    }))"
        )
        checkRule(
            "//check:hello",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("--experimental_platforms=//conditions:apple_platform"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/afile"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/bfile", "src check/defaultfile")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectDirectlyOnConstraints() {
        // Tests select()ing directly on a constraint_value (with no intermediate config_setting).
        scratch.file(
            "conditions/BUILD",
            """
        constraint_setting(name = 'fruit')
        constraint_value(name = 'apple', constraint_setting = 'fruit')
        constraint_value(name = 'banana', constraint_setting = 'fruit')
        platform(
            name = 'apple_platform',
            constraint_values = [':apple'],
        )
        platform(
            name = 'banana_platform',
            constraint_values = [':banana'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/defs.bzl",
            """
        def _impl(ctx):
          pass
        simple_rule = rule(
          implementation = _impl,
          attrs = {'srcs': attr.label_list(allow_files = True)}
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/BUILD",
            """
        load('//check:defs.bzl', 'simple_rule')
        filegroup(name = 'adep', srcs = ['afile'])
        filegroup(name = 'bdep', srcs = ['bfile'])
        simple_rule(name = 'hello',
            srcs = select({
                '//conditions:apple': [':adep'],
                '//conditions:banana': [':bdep'],
            }))
        
        """.trimIndent()
        )
        checkRule(
            "//check:hello",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("--platforms=//conditions:apple_platform"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/afile"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/bfile", "src check/defaultfile")
        )
        checkRule(
            "//check:hello",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("--platforms=//conditions:banana_platform"),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/bfile"),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("src check/afile", "src check/defaultfile")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonToolchainResolvingTargetsCantSelectDirectlyOnConstraints() {
        // Tests select()ing directly on a constraint_value (with no intermediate config_setting).
        scratch.file(
            "conditions/BUILD",
            """
        constraint_setting(name = 'fruit')
        constraint_value(name = 'apple', constraint_setting = 'fruit')
        platform(
            name = 'apple_platform',
            constraint_values = [':apple'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/BUILD",
            """
        filegroup(name = 'adep', srcs = ['afile'])
        rule_with_no_platform(name = 'hello',
            deps = select({
                '//conditions:apple': [':adep'],
            })
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        useConfiguration("--platforms=//conditions:apple_platform")
        assertThat(getConfiguredTarget("//check:hello")).isNull()
        assertContainsEvent("//conditions:apple is not a valid select() condition for //check:hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectOnlyToolchainResolvingTargetsCanSelectDirectlyOnConstraints() {
        // Tests select()ing directly on a constraint_value when the rule uses toolchain resolution
        // *only if it has a select()*. As of this test, alias() is the only rule that supports that
        // (see Alias#useToolchainResolution(ToolchainResolutionMode.ENABLED_ONLY_FOR_COMMON_LOGIC).
        scratch.file(
            "conditions/BUILD",
            """
        constraint_setting(name = 'fruit')
        constraint_value(name = 'apple', constraint_setting = 'fruit')
        constraint_value(name = 'banana', constraint_setting = 'fruit')
        platform(
            name = 'apple_platform',
            constraint_values = [':apple'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/defs.bzl",
            """
        def _impl(ctx):
          pass
        simple_rule = rule(
          implementation = _impl,
          attrs = {}
        )
        
        """.trimIndent()
        )
        scratch.file(
            "check/BUILD",
            """
        load('//check:defs.bzl', 'simple_rule')
        filegroup(name = 'bdep', srcs = ['bfile'])
        simple_rule(name = 'hello')
        simple_rule(name = 'tere')
        alias(
            name = 'selectable_alias',
            actual = select({
                '//conditions:apple': ':hello',
                '//conditions:banana': ':tere',
            }))
        
        """.trimIndent()
        )
        useConfiguration("--platforms=//conditions:apple_platform")
        assertThat(
            getConfiguredTarget("//check:selectable_alias")
                .getActual()
                .getLabel()
                .getCanonicalForm()
        )
            .isEqualTo("//check:hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMatchErrorWhenAliasResolvesToSameSetting() {
        scratch.file(
            "a/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' })
        alias(
            name = 'alias_to_foo',
            actual = ':foo')
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                ':foo': 0,
                'alias_to_foo': 1,
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNull()
        assertContainsEvent(
            ("configurable attribute \"boolean_attr\" in //a:binary doesn't match this configuration. "
                    + "Would a default condition help?\n\n"
                    + "Conditions checked:\n"
                    + " //a:foo\n"
                    + " //a:alias_to_foo")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultVisibilityConfigSetting_noVisibilityEnforcement() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=false"
        )
        scratch.file("c/BUILD", "config_setting(name = 'foo', define_values = { 'foo': '1' })")
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun privateVisibilityConfigSetting_noVisibilityEnforcement() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:private']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun publicVisibilityConfigSetting_noVisibilityEnforcement() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:public']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultVisibilityConfigSetting_defaultIsPublic() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file("c/BUILD", "config_setting(name = 'foo', define_values = { 'foo': '1' })")
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun privateVisibilityConfigSetting_defaultIsPublic() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:private']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNull()
        assertContainsEvent("'//c:foo' is not visible from\ntarget '//a:binary'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun publicVisibilityConfigSetting_defaultIsPublic() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:public']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultPublicVisibility_aliasVisibilityIgnored_aliasVisibilityIsDefault() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        alias(
            name = 'foo_alias',
            actual = ':foo')
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo_alias': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultPublicVisibility_aliasVisibilityIgnored_aliasVisibilityIsExplicit() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        alias(
            name = 'foo_alias',
            actual = ':foo',
            # Current flag combo skips this and directly checks the config_setting's visibility.
            visibility = ['//visibility:private']
        )
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo_alias': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultPublicVisibility_aliasVisibilityIgnored_configSettingVisibilityIsExplicit() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        scratch.file(
            "c/BUILD",
            """
        alias(
            name = 'foo_alias',
            actual = ':foo',
            # Current flag combo skips this and directly checks the config_setting's visibility.
            visibility = ['//visibility:public']
        )
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:private']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo_alias': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNull()
        assertContainsEvent("'//c:foo' is not visible from\ntarget '//a:binary'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultPublicVisibility_trimmedConfigsDontCrash() {
        // When enforcing config_setting visibility with
        // --incompatible_config_setting_private_default_visibility=false, the alias
        // ConfiguredTargetAndData clones itself with the ConfiguredTargetAndData of the config_setting
        // it refers to. ConfiguredTargetAndData.fromConfiguredTarget has a safety check that both
        // configs are the same. When the target with a select() is a test and --trim_test_configuration
        // is on, the alias takes the parent's config (with TestOptions) but the config_setting has it
        // stripped. This is a regression test that Blaze doesn't crash expecting those configs to be
        // equal.
        setPackageOptions(
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=false"
        )
        useConfiguration("--trim_test_configuration=true")
        scratch.file(
            "c/defs.bzl",
            """
        def _impl(ctx):
            output = ctx.outputs.out
            ctx.actions.write(output = output, content = 'hi', is_executable = True)
            return [DefaultInfo(executable = output)]

        fake_test = rule(
            attrs = {
                'msg': attr.string(),
            },
            test = True,
            outputs = {'out': 'foo.out'},
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "c/BUILD",
            """
        load(':defs.bzl', 'fake_test')
        alias(
            name = 'foo_alias',
            actual = ':foo',
        )
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
        )
        fake_test(
            name = 'foo_test',
            msg = select({
                ':foo_alias': 'hi',
                '//conditions:default': 'there'
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//c:foo_test")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultVisibilityConfigSetting_defaultIsPrivate() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=true"
        )
        scratch.file("c/BUILD", "config_setting(name = 'foo', define_values = { 'foo': '1' })")
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNull()
        assertContainsEvent("'//c:foo' is not visible from\ntarget '//a:binary'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun privateVisibilityConfigSetting_defaultIsPrivate() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=true"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:private']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNull()
        assertContainsEvent("'//c:foo' is not visible from\ntarget '//a:binary'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun publicVisibilityConfigSetting_defaultIsPrivate() {
        // Production builds default to private visibility, but BuildViewTestCase defaults to public.
        setPackageOptions(
            "--default_visibility=private",
            "--incompatible_enforce_config_setting_visibility=true",
            "--incompatible_config_setting_private_default_visibility=true"
        )
        scratch.file(
            "c/BUILD",
            """
        config_setting(
            name = 'foo',
            define_values = { 'foo': '1' },
            visibility = ['//visibility:public']
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        rule_with_boolean_attr(
            name = 'binary',
            boolean_attr = select({
                '//c:foo': 0,
                '//conditions:default': 1
            }))
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//a:binary")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectWithLabelKeysInMacro() {
        writeConfigRules()
        scratch.file("java/BUILD")
        scratch.file(
            "java/macros.bzl",
            analysisMock.javaSupport().getLoadStatementForRule("java_binary"),
            """
        def my_java_binary(name, deps = [], **kwargs):
            java_binary(
                name = name,
                deps = select({
                    Label('//conditions:a'): [Label('//java/foo:a')],
                    '//conditions:b': [Label('//java/foo:b')],
                }) + select({
                    '//conditions:a': [Label('//java/foo:a2')],
                    Label('//conditions:b'): [Label('//java/foo:b2')],
                }),
                **kwargs,
            )
        
        """.trimIndent()
        )
        scratch.file(
            "java/foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//java:macros.bzl', 'my_java_binary')
        my_java_binary(
            name = 'binary',
            srcs = ['binary.java'],
        )
        java_library(
            name = 'a',
            srcs = ['a.java'])
        java_library(
            name = 'b',
            srcs = ['b.java'])
        java_library(
            name = 'a2',
            srcs = ['a2.java'])
        java_library(
            name = 'b2',
            srcs = ['b2.java'])
        
        """.trimIndent()
        )

        checkRule(
            "//java/foo:binary",
            "--foo=b",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(
                "bin java/foo/libb.jar",
                "bin java/foo/libb2.jar"
            ),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>("bin java/foo/liba.jar", "bin java/foo/liba2.jar")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringListDictTypeConcatConfigurable() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            """
        rule_with_string_list_dict_attr(
            name = 'rule',
            string_list_dict_attr =  {'a': ['a.out']} | select({
                '//conditions:b': {'b': ['b.out']},
            }))
        
        """.trimIndent()
        )

        useConfiguration("--foo=b")
        val ctad: ConfiguredTargetAndData = getConfiguredTargetAndData("//foo:rule")
        val attributes: AttributeMap = BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(ctad)
        assertThat(attributes.get("string_list_dict_attr", Types.STRING_LIST_DICT))
            .containsExactly(
                "a",
                java.util.Arrays.< T > asList < T ? > ("a.out"),
                "b",
                java.util.Arrays.< T > asList < T ? > ("b.out")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assigningSelectToNonconfigurableAttr_fails_evenIfSelectIsSimplifiableUnconditional() {
        writeConfigRules()
        scratch.file(
            "foo/BUILD",
            """
        rule_with_output_attr(
            name = "foo",
            out = select({"//conditions:default": "default.out"}),
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors.
        getConfiguredTarget("//foo")
        assertContainsEvent("attribute \"out\" is not configurable")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompatibleSimplifyUnconditionalSelectsInRuleAttrs_doesNotAffectConfiguredAttrValue() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "foo",
            srcs = select({"//conditions:default": ["foo.cc"]}),
            link_extra_lib = select({"//conditions:default": None}),
        )
        
        """.trimIndent()
        )
        setBuildLanguageOptions("--incompatible_simplify_unconditional_selects_in_rule_attrs=false")
        val attributesFromUnsimplifiedSelects: AttributeMap =
            BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(getConfiguredTargetAndData("//foo"))

        assertThat(attributesFromUnsimplifiedSelects.get("srcs", BuildType.LABEL_LIST))
            .containsExactly(Label.parseCanonicalUnchecked("//foo:foo.cc"))
        assertThat(attributesFromUnsimplifiedSelects.get("link_extra_lib", BuildType.LABEL))
            .isEqualTo(
                attributesFromUnsimplifiedSelects
                    .getAttributeDefinition("link_extra_lib").defaultValueUnchecked
            )

        setBuildLanguageOptions("--incompatible_simplify_unconditional_selects_in_rule_attrs=true")
        val attributesFromSimplifiedSelects: AttributeMap =
            BuildViewTestCase.getMapperFromConfiguredTargetAndTarget(getConfiguredTargetAndData("//foo"))

        assertThat(attributesFromSimplifiedSelects.get("srcs", BuildType.LABEL_LIST))
            .isEqualTo(attributesFromUnsimplifiedSelects.get("srcs", BuildType.LABEL_LIST))
        assertThat(attributesFromSimplifiedSelects.get("link_extra_lib", BuildType.LABEL))
            .isEqualTo(attributesFromUnsimplifiedSelects.get("link_extra_lib", BuildType.LABEL))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectOnTestOptions_notTrimmed() {
        writeHelloRules( /* includeDefaultCondition= */true)
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {
                "test_timeout": "10",
            },
        )
        config_setting(
            name = 'b',
            values = {
                "test_timeout": "20",
            },
        )
        
        """.trimIndent()
        )
        checkRule(
            "//java/hello:hello",
            "--trim_test_configuration=false",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
        checkRule(
            "//java/hello:hello",
            com.google.common.collect.ImmutableList.of<String?>(
                "--trim_test_configuration=false",
                "--test_timeout=10"
            ),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(BDEP_INPUT, DEFAULTDEP_INPUT)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectOnTestOptions_trimmed() {
        // When --trime_test_configuration is set, all test options are considered to be unset (not
        // their default value).
        writeHelloRules( /* includeDefaultCondition= */true)
        scratch.file(
            "conditions/BUILD",
            """
        config_setting(
            name = 'a',
            values = {
                "test_timeout": "10",
            },
        )
        config_setting(
            name = 'b',
            values = {
                "test_timeout": "20",
            },
        )
        
        """.trimIndent()
        )
        checkRule(
            "//java/hello:hello",
            "--trim_test_configuration=true",  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
        checkRule(
            "//java/hello:hello",
            com.google.common.collect.ImmutableList.of<String?>(
                "--trim_test_configuration=true",
                "--test_timeout=10"
            ),  /*expected:*/
            com.google.common.collect.ImmutableList.of<String?>(DEFAULTDEP_INPUT),  /*not expected:*/
            com.google.common.collect.ImmutableList.of<String?>(ADEP_INPUT, BDEP_INPUT)
        )
    }

    companion object {
        private const val ADEP_INPUT = "bin java/hello/libadep.jar"
        private const val BDEP_INPUT = "bin java/hello/libbdep.jar"
        private const val CDEP_INPUT = "bin java/hello/libcdep.jar"
        private const val DEFAULTDEP_INPUT = "bin java/hello/libdefaultdep.jar"

        private val RULE_WITH_OUTPUT_ATTR: MockRule =
            MockRule { MockRule.define("rule_with_output_attr", attr("out", BuildType.OUTPUT)) }

        private val RULE_WITH_COMPUTED_DEFAULT: MockRule = MockRule {
            MockRule.define(
                "rule_with_computed_default",
                attr("string_attr", Type.STRING),
                attr("\$computed_attr", Type.STRING).value(
                    object : ComputedDefault("string_attr") {
                        public override fun getDefault(rule: AttributeMap): Any {
                            return@MockRule rule.get("string_attr", Type.STRING) + "2"
                        }
                    })
            )
        }

        private val RULE_WITH_BOOLEAN_ATTR: MockRule =
            MockRule { MockRule.define("rule_with_boolean_attr", attr("boolean_attr", Type.BOOLEAN)) }

        private val RULE_WITH_ALLOWED_VALUES: MockRule = MockRule {
            MockRule.define(
                "rule_with_allowed_values",
                attr("one_two", Type.STRING)
                    .allowedValues(AllowedValueSet("one", "two"))
            )
        }

        private val RULE_WITH_LABEL_DEFAULT: MockRule = MockRule {
            MockRule.define(
                "rule_with_label_default",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder.add(
                        attr("dep", BuildType.LABEL)
                            .value(Label.parseCanonicalUnchecked("//foo:default"))
                            .allowedFileTypes(FileTypeSet.ANY_FILE)
                    )
                })
        }

        private val RULE_WITH_NO_PLATFORM: MockRule = MockRule {
            MockRule.define(
                "rule_with_no_platform",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(attr("deps", LABEL_LIST).allowedFileTypes())
                        .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
                })
        }

        private val RULE_WITH_STRING_LIST_DICT_ATTR: MockRule = MockRule {
            MockRule.define(
                "rule_with_string_list_dict_attr",
                attr("string_list_dict_attr", Types.STRING_LIST_DICT)
            )
        }
    }
}
