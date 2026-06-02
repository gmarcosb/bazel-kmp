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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.AliasProvider

/**
 * Trimming transition factory which removes the test config fragment and certain options that are
 * only relevant for tests when entering a non-test rule.
 */
class TestTrimmingTransitionFactory : TransitionFactory<RuleTransitionData?> {
    /**
     * Trimming transition which removes the test config fragment if --trim_test_configuration is on.
     * 
     * 
     * At the moment, need to know the value of the testonly attribute from the underlying rule. So
     * the factory, which has access to attributes but not the configuration, attaches the appropriate
     * TestTrimmingTransition, which will have access to the configuration.
     */
    class TestTrimmingTransition private constructor(private val testonly: Boolean) : PatchTransition {
        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            return TestTrimmingLogic.REQUIRED_FRAGMENTS
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun patch(
            originalOptions: BuildOptionsView,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): BuildOptions? {
            val originalTestOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                originalOptions.get(com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java)
            if (originalTestOptions == null) {
                // nothing to do, already trimmed this fragment
                return originalOptions.underlying()
            }
            if (!originalTestOptions.getTrimTestConfiguration()
                || (originalTestOptions.getExperimentalRetainTestConfigurationAcrossTestonly()
                        && testonly)
            ) {
                // nothing to do, trimming is disabled
                return originalOptions.underlying()
            }
            // No context needed, use the constant Boolean.TRUE.
            return TestTrimmingLogic.trim(originalOptions)
        }

        companion object {
            // These are essentially a cache of the two versions of the transition depending on if
            // the associated rule is testonly = true or not.
            private val TESTONLY_TRUE = TestTrimmingTransition(true)
            private val TESTONLY_FALSE = TestTrimmingTransition(false)

            @kotlin.jvm.JvmField
            @com.google.common.annotations.VisibleForTesting
            val INSTANCE: TestTrimmingTransition = TESTONLY_FALSE
        }
    }

    public override fun create(ruleData: RuleTransitionData): PatchTransition {
        val ruleClass: RuleClass = ruleData.rule.getRuleClassObject()
        if (ruleClass
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(TestConfiguration::class.java)
            || AliasProvider.mayBeAlias(ruleData.rule)
        ) {
            // If Test rule, no need to trim here.
            // If Alias rule, might point to test rule so don't trim yet.
            return NoTransition.INSTANCE
        }

        // TODO(blaze-configurability-team): Needing special logic for config_setting implies
        //   getConfigurationFragmentPolicy is not accurate for config_setting, which is bad.
        // That said, config_setting on test options should be banned regardless of what rule type
        // consumes them.
        for (referencedOptions in ruleClass.getOptionReferenceFunction().apply(ruleData.rule)) {
            if (TEST_OPTIONS.contains(referencedOptions)) {
                // Test-option-referencing config_setting; no need to trim here.
                return NoTransition.INSTANCE
            }
        }

        // Non-test rule. Trim it!
        // Use an attribute mapper to ensure attributes are resolved to expected types
        // these attributes are defined in BaseRuleClasses
        val attrs: NonconfigurableAttributeMapper = NonconfigurableAttributeMapper.of(ruleData.rule)

        // Skip trimming when transitive_configs has magic value.
        if (attrs.has<MutableList<com.google.devtools.build.lib.cmdline.Label?>?>(
                BaseRuleClasses.TAGGED_TRIMMING_ATTR,
                BuildType.NODEP_LABEL_LIST
            )
        ) {
            for (entry in attrs.get<MutableList<com.google.devtools.build.lib.cmdline.Label>?>(
                BaseRuleClasses.TAGGED_TRIMMING_ATTR,
                BuildType.NODEP_LABEL_LIST
            )) {
                if (entry == TRANSITIVE_CONFIG_TO_TRIGGER_SKIP) {
                    return NoTransition.INSTANCE
                }
            }
        }

        // Only skip testonly = true when --experimental_retain_test_configuration_across_testonly
        //   so have to defer decision until actually have a config.
        if (attrs.has<Boolean?>("testonly", com.google.devtools.build.lib.packages.Type.BOOLEAN) && attrs.get<Boolean?>(
                "testonly",
                com.google.devtools.build.lib.packages.Type.BOOLEAN
            )
        ) {
            return TestTrimmingTransition.Companion.TESTONLY_TRUE
        }
        return TestTrimmingTransition.Companion.TESTONLY_FALSE
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.RULE
    }

    companion object {
        private val TEST_OPTIONS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java)
                    .asMap().keySet()
            )

        private val TRANSITIVE_CONFIG_TO_TRIGGER_SKIP: com.google.devtools.build.lib.cmdline.Label? =
            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("//command_line_option/fragment:test")
    }
}
