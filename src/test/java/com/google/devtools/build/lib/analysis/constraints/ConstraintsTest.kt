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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.BaseRuleClasses

/** Tests for the constraint enforcement system.  */
@RunWith(JUnit4::class)
class ConstraintsTest : AbstractConstraintsTest() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createBuildFile() {
        // Support files for RuleClassWithImplicitAndLateBoundDefaults:
        scratch.file(
            "helpers/BUILD",
            """
        filegroup(name = 'implicit', srcs = ['implicit.sh'])
        filegroup(name = 'latebound', srcs = ['latebound.sh'])
        filegroup(name = 'default', srcs = ['default.sh'])
        
        """.trimIndent()
        )
        scratch.file(
            "config/BUILD",
            """
        config_setting(name = 'a', values = {'define': 'mode=a'})
        config_setting(name = 'b', values = {'define': 'mode=b'})
        
        """.trimIndent()
        )
    }

    /**
     * Dummy rule class for testing rule class defaults. This class applies valid defaults. Note
     * that the specified environments must be independently created.
     */
    private class RuleClassDefaultRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .setUndocumented()
                .compatibleWith(Label.parseCanonicalUnchecked("//buildenv/rule_class_compat:b"))
                .restrictedTo(Label.parseCanonicalUnchecked("//buildenv/rule_class_restrict:d"))
                .build()
        }

        public override fun getMetadata(): Metadata {
            return RuleDefinition.Metadata.builder()
                .name("rule_class_default")
                .ancestors(BaseRuleClasses.NativeActionCreatingRule::class.java)
                .factoryClass(UnknownRuleConfiguredTarget::class.java)
                .build()
        }
    }

    /** Injects the rule class default rules into the default test rule class provider.  */
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addRuleDefinition(RuleClassDefaultRule())
        builder.addRuleDefinition(BAD_RULE_CLASS_DEFAULT_RULE)
        builder.addRuleDefinition(RULE_WITH_IMPLICIT_AND_LATEBOUND_DEFAULTS)
        builder.addRuleDefinition(RULE_WITH_ENFORCED_IMPLICIT_ATTRIBUTE)
        builder.addRuleDefinition(RULE_WITH_SKIPPED_ATTRIBUTE)
        builder.addRuleDefinition(CONSTRAINT_EXEMPT_RULE_CLASS)
        return builder.build()
    }

    /**
     * Writes the environments and environment groups referred to by the rule class defaults.
     */
    @Throws(java.lang.Exception::class)
    private fun writeRuleClassDefaultEnvironments() {
        EnvironmentGroupMaker("buildenv/rule_class_compat").setEnvironments("a", "b")
            .setDefaults("a").make()
        EnvironmentGroupMaker("buildenv/rule_class_restrict").setEnvironments("c", "d")
            .setDefaults("c").make()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageErrorOnEnvironmentGroupWithMissingEnvironments() {
        scratch.file(
            "buildenv/envs/BUILD",
            """
        environment(name = 'env1')
        environment(name = 'env2')
        environment_group(
            name = 'envs',
            environments = [':env1', ':en2'],
            defaults = [':env1'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(
            scratchConfiguredTarget(
                "foo", "g",
                ("genrule("
                        + "    name = 'g',"
                        + "    srcs = [],"
                        + "    outs = ['g.out'],"
                        + "    cmd = '',"
                        + "    restricted_to = ['//buildenv/envs:env1'])")
            )
        )
            .isNull()
        assertContainsEvent("environment //buildenv/envs:en2 does not exist")
    }

    /**
     * By default, a rule *implicitly* supports all defaults, meaning the explicitly known
     * environment set is empty.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultSupportedEnvironments() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        val ruleDef: String = AbstractConstraintsTest.Companion.getDependencyRule()
        Truth.assertThat(supportedEnvironments("dep", ruleDef)).isEmpty()
    }

    /**
     * "Constraining" a rule's environments explicitly sets them.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constrainedSupportedEnvironments() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        val ruleDef: String =
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"))
        Truth.assertThat(supportedEnvironments("dep", ruleDef))
            .containsExactlyElementsIn(BuildViewTestCase.asLabelSet("//buildenv/foo:c"))
    }

    /**
     * Specifying compatibility adds the specified environments to the defaults.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compatibleSupportedEnvironments() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        val ruleDef: String =
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:c"))
        Truth.assertThat(supportedEnvironments("dep", ruleDef))
            .containsExactlyElementsIn(BuildViewTestCase.asLabelSet("//buildenv/foo:a", "//buildenv/foo:c"))
    }

    /**
     * A rule can't support *no* environments.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun supportedEnvironmentsConstrainedtoNothing() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val ruleDef: String =
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo())
        assertThat(scratchConfiguredTarget("hello", "dep", ruleDef)).isNull()
        assertContainsEvent("attribute cannot be empty")
    }

    /**
     * Restrict the environments within one group, declare compatibility for another.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun supportedEnvironmentsInMultipleGroups() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults("c").make()
        val ruleDef: String = AbstractConstraintsTest.Companion.getDependencyRule(
            AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"),
            AbstractConstraintsTest.Companion.compatibleWith("//buildenv/bar:d")
        )
        Truth.assertThat(supportedEnvironments("dep", ruleDef))
            .containsExactlyElementsIn(
                BuildViewTestCase.asLabelSet("//buildenv/foo:b", "//buildenv/bar:c", "//buildenv/bar:d")
            )
    }

    /**
     * The same label can't appear in both a constraint and a compatibility declaration.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameEnvironmentCompatibleAndRestricted() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val ruleDef: String = AbstractConstraintsTest.Companion.getDependencyRule(
            AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"),
            AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:b")
        )
        assertThat(scratchConfiguredTarget("hello", "dep", ruleDef)).isNull()
        assertContainsEvent("//buildenv/foo:b cannot appear both here and in restricted_to")
    }

    /**
     * Two labels from the same group can't appear in different attributes.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameGroupCompatibleAndRestricted() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val ruleDef: String = AbstractConstraintsTest.Companion.getDependencyRule(
            AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a"),
            AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:b")
        )
        assertThat(scratchConfiguredTarget("hello", "dep", ruleDef)).isNull()
        assertContainsEvent(
            "//buildenv/foo:b and //buildenv/foo:a belong to the same environment group"
        )
    }

    /**
     * Tests that rule class defaults change a rule's default set of environments.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun supportedEnvironmentsRuleClassDefaults() {
        writeRuleClassDefaultEnvironments()
        val ruleDef = "rule_class_default(name = 'a')"
        val expectedEnvironments: MutableSet<Label?> = BuildViewTestCase.asLabelSet(
            "//buildenv/rule_class_compat:a",
            "//buildenv/rule_class_compat:b", "//buildenv/rule_class_restrict:d"
        )
        Truth.assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments)
    }

    /**
     * Tests that explicit declarations override rule class defaults.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitAttributesOverrideRuleClassDefaults() {
        writeRuleClassDefaultEnvironments()
        val ruleDef = ("rule_class_default("
                + "    name = 'a',"
                + "    compatible_with = ['//buildenv/rule_class_restrict:c'],"
                + "    restricted_to = ['//buildenv/rule_class_compat:a'],"
                + ")")
        val expectedEnvironments: MutableSet<Label?> = BuildViewTestCase.asLabelSet(
            "//buildenv/rule_class_compat:a",
            "//buildenv/rule_class_restrict:c", "//buildenv/rule_class_restrict:d"
        )
        Truth.assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments)
    }

    /**
     * Tests that a rule's "known" supported environments includes those from groups referenced
     * in rule class defaults but not in explicit rule attributes.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun knownEnvironmentsIncludesThoseFromRuleClassDefaults() {
        writeRuleClassDefaultEnvironments()
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        val ruleDef = ("rule_class_default("
                + "    name = 'a',"
                + "    restricted_to = ['//buildenv/foo:b'],"
                + ")")
        val expectedEnvironments: MutableSet<Label?> = BuildViewTestCase.asLabelSet(
            "//buildenv/rule_class_compat:a",
            "//buildenv/rule_class_compat:b", "//buildenv/rule_class_restrict:d",
            "//buildenv/foo:b"
        )
        Truth.assertThat(supportedEnvironments("a", ruleDef)).containsExactlyElementsIn(expectedEnvironments)
    }

    /**
     * Tests that environments from the same group can't appear in both restriction and
     * compatibility rule class defaults.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameEnvironmentRuleClassCompatibleAndRestricted() {
        writeRuleClassDefaultEnvironments()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val ruleDef = "bad_rule_class_default(name = 'a')"
        assertThat(scratchConfiguredTarget("hello", "a", ruleDef)).isNull()
        assertContainsEvent(
            "//buildenv/rule_class_compat:a and //buildenv/rule_class_compat:b "
                    + "belong to the same environment group"
        )
    }

    /**
     * Tests that a dependency is valid if both rules implicitly inherit all default environments.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allDefaults() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule()
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a dependency is valid when both rules explicitly declare the same constraints.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameConstraintsDeclaredExplicitly() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a dependency is valid when both the depender and dependency explicitly declare
     * their constraints and the depender supports a subset of the dependency's environments
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validConstraintsDeclaredExplicitly() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.constrainedTo(
                    "//buildenv/foo:a",
                    "//buildenv/foo:b"
                )
            ),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a dependency is invalid when both the depender and dependency explicitly declare
     * their constraints and the depender supports an environment the dependency doesn't.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidConstraintsDeclaredExplicitly() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.constrainedTo(
                    "//buildenv/foo:a",
                    "//buildenv/foo:b"
                )
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a"
        )
    }

    /**
     * Tests that a dependency is valid when both rules add the same set of environments to their
     * defaults.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameCompatibilityConstraints() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            ),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            )
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a dependency is valid when both rules add environments to their defaults and
     * the depender only adds environments also added by the dependency.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validCompatibilityConstraints() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            ),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:c"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a dependency is invalid when both rules add environments to their defaults and
     * the depender adds environments not added by the dependency.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidCompatibilityConstraints() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:c")),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b"
        )
    }

    /**
     * Tests the error message when the dependency is missing multiple expected environments.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMissingEnvironments() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environments: "
                    + "//buildenv/foo:b, //buildenv/foo:c"
        )
    }

    /**
     * Tests a valid dependency including environments from different groups.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validMultigroupConstraints() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        EnvironmentGroupMaker("buildenv/bar").setEnvironments("d", "e", "f").setDefaults("d")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b", "//buildenv/foo:c"),
                AbstractConstraintsTest.Companion.compatibleWith("//buildenv/bar:e")
            ),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"),
                AbstractConstraintsTest.Companion.compatibleWith("//buildenv/bar:e")
            )
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests an invalid dependency including environments from different groups.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidMultigroupConstraints() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        EnvironmentGroupMaker("buildenv/bar").setEnvironments("d", "e", "f").setDefaults("d")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"),
                AbstractConstraintsTest.Companion.compatibleWith("//buildenv/bar:e")
            ),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b", "//buildenv/foo:c"),
                AbstractConstraintsTest.Companion.compatibleWith("//buildenv/bar:e")
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b"
        )
    }

    /**
     * Tests a valid dependency where the dependency doesn't "know" about the expected environment's
     * group, but implicitly supports it because that environment is a default.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validConstraintsUnknownEnvironmentToDependency() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a", "b")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests an invalid dependency where the dependency doesn't "know" about the expected
     * environment's group and doesn't support it because it isn't a default.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidConstraintsUnknownEnvironmentToDependency() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a", "b")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"))
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:c"
        )
    }

    /**
     * Tests a valid dependency where the depender doesn't "know" about one of the dependency's
     * groups, the depender implicitly supports that group's defaults, and all of those defaults
     * are accounted for in the dependency.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validConstraintsUnknownEnvironmentToDependender() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(
                AbstractConstraintsTest.Companion.constrainedTo(
                    "//buildenv/foo:a",
                    "//buildenv/foo:b"
                )
            ),
            AbstractConstraintsTest.Companion.getDependingRule()
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests an invalid dependency where the depender doesn't "know" about one of the dependency's
     * groups, the depender implicitly supports that group's defaults, and one of those defaults
     * isn't accounted for in the dependency.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidConstraintsUnknownEnvironmentToDependender() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")),
            AbstractConstraintsTest.Companion.getDependingRule()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a"
        )
    }

    /**
     * Tests the case where one dependency is valid and another one isn't.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun oneDependencyIsInvalid() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getRuleDef(
                "filegroup",
                "bad_dep",
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")
            ),
            AbstractConstraintsTest.Companion.getRuleDef(
                "filegroup",
                "good_dep",
                AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:b")
            ),
            AbstractConstraintsTest.Companion.getRuleDef(
                "filegroup",
                "depender",
                AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a", "//buildenv/foo:b"),
                AbstractConstraintsTest.Companion.getAttrDef("srcs", "good_dep", "bad_dep")
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:depender")).isNull()
        assertContainsEvent("//hello:bad_dep doesn't support expected environment: //buildenv/foo:a")
        assertDoesNotContainEvent("//hello:good_dep")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraintEnforcementDisabled() {
        useConfiguration("--enforce_constraints=0")
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:b",
                    "//buildenv/foo:c"
                )
            )
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraintEnforcementDisabledExecConfig() {
        useConfiguration("--enforce_constraints=0")
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults().make()
        scratch.file(
            "hello/BUILD",
            "genrule(",
            "    name = 'gen',",
            "    srcs = [],",
            "    outs = ['gen.out'],",
            "    cmd = '',",
            "    tools = [':main'])",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:a"))
        )
        assertThat(getConfiguredTarget("//hello:gen")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that package defaults compatibility produces a valid dependency that would otherwise
     * be invalid.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compatibilityPackageDefaults() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            "package(default_compatible_with = ['//buildenv/foo:b'])",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a rule's compatibility declaration overrides its package defaults compatibility.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageDefaultsCompatibilityOverride() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        // We intentionally create an invalid dependency structure vs. a valid one. If we tested on
        // a valid one, this test wouldn't be able to distinguish between rule declarations overriding
        // package defaults and package defaults overriding rule declarations.
        scratch.file(
            "hello/BUILD",
            "package(default_compatible_with = ['//buildenv/foo:b'])",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:a")),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.compatibleWith(
                    "//buildenv/foo:a",
                    "//buildenv/foo:b"
                )
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b"
        )
    }

    /**
     * Tests that package defaults restriction produces a valid dependency that would otherwise be
     * invalid.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun restrictionPackageDefaults() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a", "b")
            .make()
        scratch.file(
            "hello/BUILD",
            "package(default_restricted_to = ['//buildenv/foo:b'])",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")),
            AbstractConstraintsTest.Companion.getDependingRule()
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    /**
     * Tests that a rule's restriction declaration overrides its package defaults restriction.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageDefaultsRestrictionOverride() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        // We intentionally create an invalid dependency structure vs. a valid one. If we tested on
        // a valid one, this test wouldn't be able to distinguish between rule declarations overriding
        // package defaults and package defaults overriding rule declarations.
        scratch.file(
            "hello/BUILD",
            "package(default_restricted_to = ['//buildenv/foo:b'])",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a")),
            AbstractConstraintsTest.Companion.getDependingRule(
                AbstractConstraintsTest.Companion.constrainedTo(
                    "//buildenv/foo:a",
                    "//buildenv/foo:b"
                )
            )
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:b"
        )
    }

    /**
     * Tests that "default_compatible_with" fills in a rule's "compatible_with" when not specified
     * by the rule. This is different than, e.g., the rule declaration / rule class defaults model,
     * where the "compatible_with" / "restricted_to" values of rule class defaults are merged together
     * before being supplied to the rule. See comments in DependencyResolver for more discussion.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageDefaultsDirectlyFillRuleAttributes() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        scratch.file(
            "hello/BUILD",
            "package(default_restricted_to = ['//buildenv/foo:b'])",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.compatibleWith("//buildenv/foo:a"))
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:dep")).isNull()
        assertContainsEvent(
            "//buildenv/foo:a and //buildenv/foo:b belong to the same "
                    + "environment group. They should be declared together either here or in restricted_to"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hostDependenciesAreNotChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(name = 'host_tool',
            srcs = ['host_tool.sh'],
            restricted_to = ['//buildenv/foo:b'])
        genrule(
            name = 'hello',
            srcs = [],
            outs = ['hello.out'],
            cmd = '',
            tools = [':host_tool'],
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hello")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hostDependenciesAreNotChecked_customRule() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/rule.bzl",
            """
        def _impl(ctx):
            pass
        my_rule = rule(
            implementation = _impl,
            attrs = {
                'tool': attr.label(cfg = 'exec',),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "hello/BUILD",
            """
        load(':rule.bzl', 'my_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(name = 'host_tool',
            srcs = ['host_tool.sh'],
            restricted_to = ['//buildenv/foo:b'])
        my_rule(
            name = 'hello',
            tool = ':host_tool',
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hello")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execDependenciesAreNotChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(name = 'tool',
            srcs = ['tool.sh'],
            restricted_to = ['//buildenv/foo:b'])
        genrule(
            name = 'hello',
            srcs = [],
            outs = ['hello.out'],
            cmd = '',
            tools = [':tool'],
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hello")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execDependenciesAreNotChecked_customRule() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/rule.bzl",
            """
        def _impl(ctx):
            pass
        my_rule = rule(
            implementation = _impl,
            attrs = {
                'tool': attr.label(cfg = 'exec',),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "hello/BUILD",
            """
        load(':rule.bzl', 'my_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(name = 'exec_tool',
            srcs = ['exec_tool.sh'],
            restricted_to = ['//buildenv/foo:b'])
        my_rule(
            name = 'hello',
            tool = ':exec_tool',
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hello")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitAndLateBoundDependenciesAreNotChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        rule_with_implicit_and_latebound_deps(
            name = 'hi',
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hi")).isNotNull()
        // Note that the event "cannot build rule_with_implicit_and_latebound_deps" *does* occur
        // because of the implementation of UnknownRuleConfiguredTarget.
        assertDoesNotContainEvent(":implicit doesn't support expected environment")
        assertDoesNotContainEvent(":latebound doesn't support expected environment")
        assertDoesNotContainEvent("normal doesn't support expected environment")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun implicitDepsWithWhiteListedAttributeAreChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        rule_with_enforced_implicit_deps(
            name = 'hi',
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:hi")).isNull()
        assertContainsEvent(
            "dependency //helpers:implicit doesn't support expected environment: //buildenv/foo:b"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitDepWithEnforcementSkipOverride() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        rule_with_skipped_attr(
            name = 'hi',
            some_attr = '//helpers:default',
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hi")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaDataAndResourcesAttributesSkipped() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'hi',
            data = ['//helpers:default'],
            resources = ['//helpers:default'],
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hi")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filegroupDataAttributesSkipped() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("a").make()
        scratch.file(
            "hello/BUILD",
            """
        filegroup(
            name = 'hi',
            data = ['//helpers:default'],
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//hello:hi")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFilesAreChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        scratch.file(
            "hello/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        genrule(name = 'gen', srcs = [], outs = ['shlib.sh'], cmd = '')
        foo_binary(
            name = 'shlib',
            srcs = ['shlib.sh'],
            data = ['whatever.txt'],
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:shlib")).isNull()
        assertContainsEvent(
            "dependency //hello:gen doesn't support expected environment: //buildenv/foo:a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configSettingRulesAreNotChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        scratch.file(
            "hello/BUILD",
            """
        filegroup(
            name = 'shlib',
            srcs = select({
                '//config:a': ['shlib.sh'],
            }),
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//hello:shlib")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fulfills() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setFulfills("a", "b")
            .setDefaults()
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a")),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fulfillsIsNotSymmetric() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setFulfills("a", "b")
            .setDefaults()
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b")),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a"))
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:main")).isNull()
        assertContainsEvent(
            "dependency //hello:dep doesn't support expected environment: //buildenv/foo:a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fulfillsIsTransitive() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b", "c")
            .setFulfills("a", "b")
            .setFulfills("b", "c")
            .setDefaults()
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a")),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultEnvironmentDirectlyFulfills() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setFulfills("a", "b")
            .setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:b"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultEnvironmentIndirectlyFulfills() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b", "c")
            .setFulfills("a", "b")
            .setFulfills("b", "c")
            .setDefaults("a")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(),
            AbstractConstraintsTest.Companion.getDependingRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:c"))
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun environmentFulfillsExpectedDefault() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setFulfills("a", "b")
            .setDefaults("b")
            .make()
        scratch.file(
            "hello/BUILD",
            AbstractConstraintsTest.Companion.getDependencyRule(AbstractConstraintsTest.Companion.constrainedTo("//buildenv/foo:a")),
            AbstractConstraintsTest.Companion.getDependingRule()
        )
        assertThat(getConfiguredTarget("//hello:main")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constraintExemptRulesDontHaveConstraintAttributes() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setDefaults("a")
            .make()
        scratch.file(
            "ihave/BUILD",
            """
        totally_free_rule(
            name = 'nolimits',
            restricted_to = ['//buildenv/foo:b']
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//ihave:nolimits")).isNull()
        assertContainsEvent("no such attribute 'restricted_to' in 'totally_free_rule'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingEnvironmentGroupDirectlyDoesntCrash() {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b")
            .setDefaults("a")
            .make()
        assertThat(getConfiguredTarget("//buildenv/foo:foo")).isNotNull()
    }

    @Throws(java.lang.Exception::class)
    private fun writeDepsForSelectTests() {
        scratch.file(
            "deps/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'dep_a',
            srcs = [],
            restricted_to = ['//buildenv/foo:a'])
        cc_library(
            name = 'dep_b',
            srcs = [],
            restricted_to = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableDepsCanMissEnvironments() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableAliasDepsTreatedLikeOtherDeps() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        alias(
            name = 'deps_a_alias',
            actual = '//deps:dep_a')
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': [':deps_a_alias'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun selectableOutputFileDepsTreatedLikeOtherDeps() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        genrule(
            name = 'src_a',
            outs = ['src_a.c'],
            cmd = 'touch ${'$'}@',
            restricted_to = ['//buildenv/foo:a'])
        cc_library(
            name = 'lib',
            srcs = select({
                '//config:a': [':src_a.c'],
                '//config:b': [],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun staticCheckingOnSelectsTemporarilyDisabled() {
        // TODO(bazel-team): update this test once static checking on selects is implemented. When
        // that happens, the union of all deps in the select must support the environments in the
        // depending rule. So the logic here is constraint-invalid because //buildenv/foo:c isn't
        // fulfilled by any of the deps.
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b", "c").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b', '//buildenv/foo:c'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depInBothSelectAndUnconditionalListIsAlwaysChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            hdrs = ['//deps:dep_a'],
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            "dependency //deps:dep_a doesn't support expected environment: //buildenv/foo:b"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unconditionalSelectsAlwaysChecked() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//conditions:default': ['//deps:dep_a'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            "dependency //deps:dep_a doesn't support expected environment: //buildenv/foo:b"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingValidCaseDirect() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        // Valid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingBadCaseDirect() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Invalid because "--define mode=a" refines :lib to "compatible_with = []" (empty).
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            (""
                    + "//hello:lib: the current command line flags disqualify all supported environments "
                    + "because of incompatible select() paths:\n"
                    + " \n"
                    + "  environment: //buildenv/foo:b\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:2:11)\n"
                    + "    because of a select() that chooses dep: //deps:dep_a\n"
                    + "    which lacks: //buildenv/foo:b")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingValidCaseTransitive() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        cc_library(
            name = 'depender',
            srcs = [],
            deps = [':lib'],
            compatible_with = ['//buildenv/foo:a'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        // Valid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
        assertThat(getConfiguredTarget("//hello:depender")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingBadCaseTransitive() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        cc_library(
            name = 'depender',
            srcs = [],
            deps = [':lib'],
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Invalid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
        assertThat(getConfiguredTarget("//hello:depender")).isNull()
        assertContainsEvent(
            ("//hello:depender: the current command line flags disqualify all supported environments"
                    + " because of incompatible select() paths:\n"
                    + " \n"
                    + "  environment: //buildenv/foo:b\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:2:11)\n"
                    + "    because of a select() that chooses dep: //deps:dep_a\n"
                    + "    which lacks: //buildenv/foo:b")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingBadCaseChooseLowestLevelCulprit() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib2',  # Even though both lib1 and lib2 refine away b, lib2 is the culprit.
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        cc_library(
            name = 'lib1',
            srcs = [],
            deps = select({
                '//config:a': [':lib2'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/foo:b'])
        cc_library(
            name = 'depender',
            srcs = [],
            deps = [':lib1'],
            compatible_with = ['//buildenv/foo:b'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Invalid because "--define mode=a" refines :lib to "compatible_with = ['//buildenv/foo:a']".
        assertThat(getConfiguredTarget("//hello:depender")).isNull()
        assertContainsEvent(
            ("//hello:depender: the current command line flags disqualify all supported environments"
                    + " because of incompatible select() paths:\n"
                    + " \n"
                    + "  environment: //buildenv/foo:b\n"
                    + "    removed by: //hello:lib2 (/workspace/hello/BUILD:2:11)\n"
                    + "    because of a select() that chooses dep: //deps:dep_a\n"
                    + "    which lacks: //buildenv/foo:b")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun environmentRefiningAccountsForImplicitDefaults() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults("b").make()
        writeDepsForSelectTests()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }))
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Invalid because :lib has an implicit default of ['//buildenv/foo:b'] and "--define mode=a"
        // refines it to "compatible_with = []" (empty).
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            (""
                    + "//hello:lib: the current command line flags disqualify all supported environments "
                    + "because of incompatible select() paths:\n"
                    + " \n"
                    + "  environment: //buildenv/foo:b\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:2:11)\n"
                    + "    because of a select() that chooses dep: //deps:dep_a\n"
                    + "    which lacks: //buildenv/foo:b")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun environmentRefiningChecksAllEnvironmentGroups() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults().make()
        scratch.file(
            "deps/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'dep_a',
            srcs = [],
            restricted_to = ['//buildenv/foo:a', '//buildenv/bar:d'])
        cc_library(
            name = 'dep_b',
            srcs = [],
            restricted_to = ['//buildenv/foo:b', '//buildenv/bar:c'])
        
        """.trimIndent()
        )
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': ['//deps:dep_a'],
                '//config:b': ['//deps:dep_b'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/bar:c'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Invalid because while the //buildenv/foo refinement successfully refines :lib to
        // ['//buildenv/foo:a'], the bar refinement refines it to [].
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            (""
                    + "//hello:lib: the current command line flags disqualify all supported environments "
                    + "because of incompatible select() paths:\n"
                    + " \n"
                    + "  environment: //buildenv/bar:c\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:2:11)\n"
                    + "    because of a select() that chooses dep: //deps:dep_a\n"
                    + "    which lacks: //buildenv/bar:c")
        )
    }

    /**
     * When multiple environment groups get cleared out by refinement, batch the missing environments
     * by group membership.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refinedEnvironmentCheckingPartitionsErrorsbyEnvironmentGroup() {
        EnvironmentGroupMaker("buildenv/foo").setEnvironments("a", "b").setDefaults().make()
        EnvironmentGroupMaker("buildenv/bar").setEnvironments("c", "d").setDefaults().make()
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'all_groups_gone',
            srcs = [],
            restricted_to = ['//buildenv/foo:b', '//buildenv/bar:d'])
        cc_library(
            name = 'all_groups_there',
            srcs = [],
            restricted_to = ['//buildenv/foo:a', '//buildenv/bar:c'])
        cc_library(
            name = 'lib',
            srcs = [],
            deps = select({
                '//config:a': [':all_groups_gone'],
                '//config:b': [':all_groups_there'],
            }),
            compatible_with = ['//buildenv/foo:a', '//buildenv/bar:c'])
        
        """.trimIndent()
        )
        useConfiguration("--define", "mode=a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            (""
                    + "//hello:lib: the current command line flags disqualify all supported environments "
                    + "because of incompatible select() paths:\n"
                    + " \n"
                    + "environment group: //buildenv/foo:foo:\n"
                    + " \n"
                    + "  environment: //buildenv/foo:a\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:10:11)\n"
                    + "    because of a select() that chooses dep: //hello:all_groups_gone\n"
                    + "    which lacks: //buildenv/foo:a\n")
        )

        assertContainsEvent(
            ("environment group: //buildenv/bar:bar:\n"
                    + " \n"
                    + "  environment: //buildenv/bar:c\n"
                    + "    removed by: //hello:lib (/workspace/hello/BUILD:10:11)\n"
                    + "    because of a select() that chooses dep: //hello:all_groups_gone\n"
                    + "    which lacks: //buildenv/bar:c")
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeRulesForRefiningSubsetTests(topLevelRestrictedTo: String?) {
        EnvironmentGroupMaker("buildenv/foo")
            .setEnvironments("a", "b", "all")
            .setFulfills("all", "a")
            .setFulfills("all", "b")
            .setDefaults()
            .make()
        scratch.file(
            "hello/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(",
            "    name = 'lib',",
            "    srcs = [],",
            "    deps = [':dep1'],",
            "    restricted_to = ['//buildenv/foo:" + topLevelRestrictedTo + "'])",
            "cc_library(",
            "    name = 'dep1',",
            "    srcs = [],",  // This is technically illegal because "dep1" declares support for both "a" and "b" but
            // no dependency under the select can provide "b". This is known as "static select
            // constraint checking" and is currently an unimplemented Bazel TODO.
            "    deps = select({",
            "        '//config:a': [':dep2'],",
            "        '//conditions:default': [':dep2'],",
            "    }),",
            "    restricted_to = ['//buildenv/foo:all'])",
            "cc_library(",
            "    name = 'dep2',",
            "    srcs = [],",
            "    compatible_with = ['//buildenv/foo:a'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refiningReplacesRemovedEnvironmentWithValidFulfillingSubset() {
        writeRulesForRefiningSubsetTests("a")
        assertThat(getConfiguredTarget("//hello:lib")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refiningReplacesRemovedEnvironmentWithInvalidFulfillingSubset() {
        writeRulesForRefiningSubsetTests("b")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello:lib")).isNull()
        assertContainsEvent(
            "//hello:lib: the current command line flags disqualify all supported "
                    + "environments because of incompatible select() paths"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidSelectKeyError() {
        scratch.file(
            "hello/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'a',
            runtime_deps = ['//hello/b'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "hello/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'b',
            runtime_deps = select({'//hello/c': []}),
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertThat(getConfiguredTarget("//hello/a")).isNull()
        assertContainsEvent(
            "no such package 'hello/c': BUILD file not found in any of the following directories. Add"
                    + " a BUILD file to a directory to mark it as a package"
        )
        assertContainsEvent("errors encountered resolving select() keys for //hello/b:b")
    }

    companion object {
        /**
         * Dummy rule class for testing rule class defaults. This class applies invalid defaults. Note
         * that the specified environments must be independently created.
         */
        private val BAD_RULE_CLASS_DEFAULT_RULE: MockRule = MockRule {
            MockRule.define(
                "bad_rule_class_default",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .setUndocumented() // These defaults are invalid since compatibleWith and restrictedTo can't mix
                        // environments from the same group.
                        .compatibleWith(
                            Label.parseCanonicalUnchecked("//buildenv/rule_class_compat:a")
                        )
                        .restrictedTo(
                            Label.parseCanonicalUnchecked("//buildenv/rule_class_compat:b")
                        )
                })
        }

        private val RULE_WITH_IMPLICIT_AND_LATEBOUND_DEFAULTS: MockRule = MockRule {
            MockRule.define(
                "rule_with_implicit_and_latebound_deps",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .setUndocumented()
                        .add(
                            Attribute.attr("\$implicit", BuildType.LABEL)
                                .value(Label.parseCanonicalUnchecked("//helpers:implicit"))
                        )
                        .add(
                            Attribute.attr(":latebound", BuildType.LABEL)
                                .value(
                                    Attribute.LateBoundDefault.fromConstantForTesting(
                                        Label.parseCanonicalUnchecked("//helpers:latebound")
                                    )
                                )
                        )
                        .add(
                            Attribute.attr("normal", BuildType.LABEL)
                                .allowedFileTypes(FileTypeSet.NO_FILE)
                                .value(Label.parseCanonicalUnchecked("//helpers:default"))
                        )
                })
        }

        private val RULE_WITH_ENFORCED_IMPLICIT_ATTRIBUTE: MockRule = MockRule {
            MockRule.define(
                "rule_with_enforced_implicit_deps",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .setUndocumented()
                        .add(
                            Attribute.attr("\$implicit", BuildType.LABEL)
                                .value(Label.parseCanonicalUnchecked("//helpers:implicit"))
                                .checkConstraints()
                        )
                })
        }

        private val RULE_WITH_SKIPPED_ATTRIBUTE: MockRule = MockRule {
            MockRule.define(
                "rule_with_skipped_attr",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .setUndocumented()
                        .add(
                            Attribute.attr("some_attr", BuildType.LABEL)
                                .allowedFileTypes(FileTypeSet.NO_FILE)
                                .dontCheckConstraints()
                        )
                })
        }


        private val CONSTRAINT_EXEMPT_RULE_CLASS: MockRule = MockRule {
            MockRule.define(
                "totally_free_rule",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .setUndocumented()
                        .exemptFromConstraintChecking(
                            "for testing removal of restricted_to / compatible_with"
                        )
                })
        }
    }
}
