// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Tests that late bound label declarations obey the invariant that the computed label is in the
 * transitive closure of the default label.
 */
@RunWith(JUnit4::class)
class LateBoundAttributeTest : BuildViewTestCase() {
    @org.junit.Rule
    val mocks: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val attributes: AttributeMap? = null

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvariant() {
        // This configuration makes java_proto_library and java_lite_proto_library's toolchain
        // attributes to have defaultValue == actualValue, which makes the test below skip them.
        // Otherwise, the test chokes because it tries to traverse the non-existent
        // tools/proto/toolchains/BUILD file.
        useConfiguration(
            "--proto_toolchain_for_java=//tools/proto/toolchains:java",
            "--proto_toolchain_for_javalite=//tools/proto/toolchains:javalite"
        )

        LabelChecker(getTargetConfiguration())
            .checkRuleClasses(ruleClassProvider.getRuleClassMap().values())

        LabelChecker(getExecConfiguration())
            .checkRuleClasses(ruleClassProvider.getRuleClassMap().values())
    }

    private inner class LabelChecker(configuration: BuildConfigurationValue?) {
        private val configuration: BuildConfigurationValue?
        private var failed = false

        init {
            this.configuration = configuration
        }

        @Throws(java.lang.Exception::class)
        fun checkRuleClasses(ruleClasses: Iterable<RuleClass>) {
            for (ruleClass in ruleClasses) {
                checkRuleClass(ruleClass)
            }
            // If this fails you need to check your rule class declarations.
            Truth.assertThat(failed).isFalse()
        }

        @Throws(java.lang.Exception::class)
        fun checkRuleClass(ruleClass: RuleClass) {
            if (ruleClass.getName().startsWith("$")) {
                // Ignore abstract rule classes.
                return
            }

            for (attribute in ruleClass.getAttributeProvider().getAttributes()) {
                checkAttribute(ruleClass, attribute)
            }
        }

        @Throws(java.lang.Exception::class)
        fun checkAttribute(ruleClass: RuleClass, attribute: Attribute) {
            val attributeName: String? = attribute.name
            if (!Attribute.isAnalysisDependent(attributeName)) {
                return
            }

            if (ATTRIBUTE_EXCEPTIONS.contains(attributeName)) {
                return
            }

            if (attribute.getType() === BuildType.LABEL) {
                val label: Label?
                label =
                    BuildType.LABEL.cast(
                        DependencyResolutionHelpers.resolveLateBoundDefault(
                            null, attributes, attribute, configuration
                        )
                    )
                if (label != null) {
                    checkLabel(ruleClass, attribute, label)
                }
            } else if (attribute.getType() === BuildType.LABEL_LIST) {
                val labels: MutableList<Label?>
                labels =
                    BuildType.LABEL_LIST.cast(
                        DependencyResolutionHelpers.resolveLateBoundDefault(
                            null, attributes, attribute, configuration
                        )
                    )
                for (label in labels) {
                    checkLabelList(ruleClass, attribute, label)
                }
            } else {
                throw java.lang.AssertionError("Unknown attribute: '" + attributeName + "'")
            }
        }

        /**
         * We check that the label set by the [DependencyResolutionHelpers] with the default
         * configuration is in the transitive closure of the default value set in the rule class.
         * 
         * 
         * Branches created using the result of `"blaze query deps(//target)"` only work if all
         * labels loaded by blaze during the loading phase are also returned by this query. The check
         * here is a bit stricter than that, and disallows omitting the label if another attribute
         * already sets the same label.
         */
        @Throws(java.lang.Exception::class)
        fun checkLabel(ruleClass: RuleClass, attribute: Attribute, label: Label?) {
            val defaultValue: Label?
            if (attribute.defaultValueUnchecked is LateBoundDefault<*, *>) {
                defaultValue =
                    BuildType.LABEL.cast(
                        (attribute.defaultValueUnchecked as LateBoundDefault<*, *>).getDefault(null)
                    )
            } else {
                defaultValue = attribute.defaultValueUnchecked as Label?
            }
            if ((defaultValue == null) || !existsPath(defaultValue, label)) {
                java.lang.System.err.println("in " + ruleClass.getName() + " attribute " + attribute.name + ":")
                java.lang.System.err.println("  " + label + " is not in the transitive closure of " + defaultValue)
                failed = true
            }
        }

        /**
         * Similar to [.checkLabel] except for we check that the label is reachable by *any*
         * value in the default value (doesn't need to be reachable by all values in the default).
         */
        @Throws(java.lang.Exception::class)
        fun checkLabelList(ruleClass: RuleClass, attribute: Attribute, label: Label?) {
            val defaultValues: MutableList<Label>?
            if (attribute.defaultValueUnchecked is LateBoundDefault<*, *>) {
                defaultValues =
                    BuildType.LABEL_LIST.cast(
                        (attribute.defaultValueUnchecked as LateBoundDefault<*, *>).getDefault(null)
                    )
            } else {
                defaultValues = attribute.defaultValueUnchecked as MutableList<Label>?
            }
            failed = true
            if (defaultValues == null) {
                java.lang.System.err.println("in " + ruleClass.getName() + " attribute " + attribute.name + ":")
                java.lang.System.err.println(" no available default for this attribute")
            } else {
                for (defaultLabel in defaultValues) {
                    if (existsPath(defaultLabel, label)) {
                        failed = false
                    }
                }
                // label was not reachable from any label in the defaultValue
                if (failed) {
                    java.lang.System.out.println(
                        "in " + ruleClass.getName() + " attribute " + attribute.name + ":"
                    )
                    java.lang.System.out.println(
                        ("  " + label + " is not in the transitive closure of "
                                + java.util.Arrays.toString(defaultValues.toArray()))
                    )
                }
            }
        }

        /**
         * Returns whether a path exists from the first given label to the second.
         */
        @Throws(java.lang.Exception::class)
        fun existsPath(from: Label, to: Label?): Boolean {
            return from.equals(to) || visitTransitively(from).toList().contains(to)
        }

        @Throws(java.lang.InterruptedException::class)
        fun visitTransitively(label: Label?): NestedSet<Label?> {
            val key: SkyKey = TransitiveTargetKey.of(label)
            val evaluationContext: EvaluationContext? =
                EvaluationContext.newBuilder().setParallelism(5).setEventHandler(reporter).build()
            val result: EvaluationResult<SkyValue?> =
                getSkyframeExecutor().prepareAndGet(
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    evaluationContext
                )
            val value: TransitiveTargetValue? = result.get(key) as TransitiveTargetValue?
            val hasTransitiveError = (value == null) || value.encounteredLoadingError()
            if (result.hasError() || hasTransitiveError) {
                throw java.lang.RuntimeException(result.getError().getException())
            }
            return value.getTransitiveTargets()
        }
    }

    companion object {
        /**
         * These attributes are only an exception because we can't easily test them: their default value
         * as determined by the [DependencyResolutionHelpers] depends on the rule, not just on the
         * rule class and configuration. The problem with the test below is that we don't instantiate
         * rules and the [.attributes] collection is just an empty mock.
         */
        private val ATTRIBUTE_EXCEPTIONS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(":computed_cc_rpc_libs")
    }
}
