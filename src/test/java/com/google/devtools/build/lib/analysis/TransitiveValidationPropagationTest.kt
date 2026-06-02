// Copyright 2021 The Bazel Authors. All rights reserved.
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

/** Tests for [OutputGroupInfo.VALIDATION_TRANSITIVE] output group  */
@RunWith(JUnit4::class)
class TransitiveValidationPropagationTest : BuildViewTestCase() {
    /** Fake native rule that outputs a single validation artifact  */
    class ValidationOutputRule

        : RuleDefinition, RuleConfiguredTargetFactory {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .addAttribute(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.NO_FILE).build())
                .build()
        }

        val metadata: Metadata
            get() = RuleDefinition.Metadata.builder()
                .name("validation_rule")
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .factoryClass(ValidationOutputRule::class.java)
                .build()

        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            val valid: Artifact = ruleContext.createOutputArtifact()
            ruleContext.registerAction(NullAction(valid))
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER))
                .addProvider(RunfilesProvider.EMPTY)
                .addOutputGroup(OutputGroupInfo.VALIDATION, valid)
                .build()
        }
    }

    /**
     * Fake native rule that disables transitive validation artifact propagation returning only a
     * single validation artifact
     */
    class TransitiveValidationOverrideRule

        : RuleDefinition, RuleConfiguredTargetFactory {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder.build()
        }

        val metadata: Metadata
            get() = RuleDefinition.Metadata.builder()
                .name("transitive_validation_rule")
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java, ValidationOutputRule::class.java)
                .factoryClass(TransitiveValidationOverrideRule::class.java)
                .build()

        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget {
            val valid: Artifact = ruleContext.createOutputArtifact()
            ruleContext.registerAction(NullAction(valid))
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER))
                .addProvider(RunfilesProvider.EMPTY)
                .addOutputGroup(OutputGroupInfo.VALIDATION_TRANSITIVE, valid)
                .build()
        }
    }

    /** Make the test rule class provider understand our rules in addition to the standard ones.  */
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(ValidationOutputRule())
                .addRuleDefinition(TransitiveValidationOverrideRule())
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidationOutputPropagation() {
        scratch.file(
            "valid/BUILD",
            """
        validation_rule(name = "foo")

        validation_rule(
            name = "bar",
            deps = [":foo"],
        )

        validation_rule(name = "baz")

        validation_rule(
            name = "top",
            deps = [
                "bar",
                "baz",
            ],
        )

        transitive_validation_rule(
            name = "top_transitive",
            deps = [
                "bar",
                "baz",
            ],
        )
        
        """.trimIndent()
        )

        val topValid: MutableList<String?>? =
            prettyArtifactNames(
                OutputGroupInfo.get(getConfiguredTarget("//valid:top"))
                    .getOutputGroup(OutputGroupInfo.VALIDATION)
            )
        val topTransitiveValid: MutableList<String?>? =
            prettyArtifactNames(
                OutputGroupInfo.get(getConfiguredTarget("//valid:top_transitive"))
                    .getOutputGroup(OutputGroupInfo.VALIDATION)
            )

        Truth.assertThat(topValid).containsExactly("valid/foo", "valid/bar", "valid/baz", "valid/top")
        Truth.assertThat(topTransitiveValid).containsExactly("valid/top_transitive")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveValidationOutputGroupNotAllowedForStarlarkRules() {
        scratch.file(
            "foobar/foo_rule.bzl",
            """
        def _impl(ctx):
            return [OutputGroupInfo(_validation_transitive = depset())]

        foo_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "foobar/BUILD",
            """
        load("//foobar:foo_rule.bzl", "foo_rule")

        foo_rule(name = "foo")
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foobar:foo") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("//foobar:foo_rule.bzl cannot access the _transitive_validation private API")
    }
}
