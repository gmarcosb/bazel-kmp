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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER

/**
 * The implementation of the config_feature_flag rule for defining custom flags for Android rules.
 */
class ConfigFeatureFlag : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val specifiedValues: MutableList<String?> = ruleContext.attributes().get("allowed_values", STRING_LIST)
        val values: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf<String?>(specifiedValues)
        if (values.size != specifiedValues.size) {
            val groupedValues: com.google.common.collect.ImmutableMultiset<String?> =
                com.google.common.collect.ImmutableMultiset.copyOf<String?>(specifiedValues)
            val duplicates: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            for (value in groupedValues.entrySet()) {
                if (value.getCount() > 1) {
                    duplicates.add(value.getElement())
                }
            }
            // This is a problem with attributes of config_feature_flag itself so throw error here.
            ruleContext.attributeError(
                "allowed_values",
                "cannot contain duplicates, but contained multiple of "
                        + net.starlark.java.eval.Starlark.repr(
                    duplicates.build(),
                    net.starlark.java.eval.StarlarkSemantics.DEFAULT
                )
            )
        }

        val defaultValue: java.util.Optional<String?> =
            if (ruleContext.attributes().isAttributeValueExplicitlySpecified("default_value"))
                java.util.Optional.of<T?>(ruleContext.attributes().get("default_value", STRING))
            else
                java.util.Optional.empty<String?>()
        if (defaultValue.isPresent() && !values.contains(defaultValue.get())) {
            // This is a problem with attributes of config_feature_flag itself so throw error here.
            ruleContext.attributeError(
                "default_value",
                ("must be one of "
                        + net.starlark.java.eval.Starlark.repr(
                    values.asList(),
                    net.starlark.java.eval.StarlarkSemantics.DEFAULT
                )
                        + ", but was "
                        + net.starlark.java.eval.Starlark.repr(
                    defaultValue.get(),
                    net.starlark.java.eval.StarlarkSemantics.DEFAULT
                ))
            )
        }

        if (ruleContext.hasErrors()) {
            // Don't bother validating the value if the flag was already incorrectly specified without
            // looking at the value.
            return null
        }

        var rawStarlarkValue: Any? =
            ruleContext
                .getConfiguration()
                .getOptions()
                .getStarlarkOptions()
                .get(ruleContext.getLabel())
        if (rawStarlarkValue !is FeatureFlagValue) {
            // Retain legacy behavior of treating feature flags that somehow are not FeatureFlagValue
            // as set to default
            rawStarlarkValue = com.google.devtools.build.lib.rules.config.FeatureFlagValue.DefaultValue.INSTANCE
        }

        val provider: ConfigFeatureFlagProvider? =
            constructProvider(rawStarlarkValue as FeatureFlagValue, defaultValue, values, ruleContext)
        if (provider == null) {
            return null
        }

        return RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(NestedSetBuilder.< Artifact > emptySet < Artifact ? > (STABLE_ORDER))
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addNativeDeclaredProvider(provider)
            .build()
    }

    companion object {
        /**
         * The name of the policy that is used to restrict access to the config_feature_flag rule and
         * attribute-triggered access to the feature flags setter transition.
         */
        const val ALLOWLIST_NAME: String = "config_feature_flag"

        /** The label of the policy for ALLOWLIST_NAME.  */
        private const val ALLOWLIST_LABEL = "//tools/allowlists/config_feature_flag:config_feature_flag"

        /** Constructs a definition for the attribute used to restrict access to config_feature_flag.  */
        fun getAllowlistAttribute(env: RuleDefinitionEnvironment): Attribute.Builder<Label?> {
            return Allowlist.getAttributeFromAllowlistName(ALLOWLIST_NAME)
                .value(env.getToolsLabel(ALLOWLIST_LABEL))
        }

        /**
         * Constructs a definition for the attribute used to restrict access to config_feature_flag. The
         * allowlist will only be reached if the given `attributeToInspect` has a value explicitly
         * specified.
         */
        fun getAllowlistAttribute(
            env: RuleDefinitionEnvironment, attributeToInspect: String?
        ): Attribute.Builder<Label?> {
            val label: Label? = env.getToolsLabel(ALLOWLIST_LABEL)
            return Allowlist.getAttributeFromAllowlistName(ALLOWLIST_NAME)
                .value(/**
                 * Critically, get is never actually called on attributeToInspect and thus it is not
                 * necessary to declare whether it is configurable, for this context.
                 */
                    object : ComputedDefault() {
                        public override fun getDefault(rule: AttributeMap): Label? {
                            return if (rule.isAttributeValueExplicitlySpecified(attributeToInspect)) label else null
                        }
                    })
        }

        /**
         * The name of the policy that is used to restrict access to rule definitions attaching the
         * feature flag setting transition.
         * 
         * 
         * Defined here for consistency with ALLOWLIST_NAME policy.
         */
        const val SETTER_ALLOWLIST_NAME: String = "config_feature_flag_setter"

        /** The label of the policy for SETTER_ALLOWLIST_NAME.  */
        private const val SETTER_ALLOWLIST_LABEL = "//tools/allowlists/config_feature_flag:config_feature_flag_setter"

        /** Constructs a definition for the attribute used to restrict access to config_feature_flag.  */
        fun getSetterAllowlistAttribute(
            env: RuleDefinitionEnvironment
        ): Attribute.Builder<Label?> {
            return Allowlist.getAttributeFromAllowlistName(SETTER_ALLOWLIST_NAME)
                .value(env.getToolsLabel(SETTER_ALLOWLIST_LABEL))
        }

        /**
         * Calculate and return a ConfigFeatureFlagProvider.
         * 
         * 
         * At this point any errors here are due to something being 'wrong' with the configuration. In
         * particular, this provider may be constructed BEFORE a rule transition sets the expected
         * configuration and thus want to defer errors until later in analysis when this value is actually
         * consumed as the errors would otherwise be unfixable.
         * 
         * 
         * An exception is made for if the value is explicitly set to value not in the allowed values
         * list (either on the commandline or as part of a previous transition). In that case, that is an
         * immediate error as can be fixed by ensuring those places set to allowed values. This is
         * consistent with the behavior of build_setting.
         */
        private fun constructProvider(
            featureFlagValue: FeatureFlagValue,
            defaultValue: java.util.Optional<String?>,
            values: com.google.common.collect.ImmutableSet<String?>,
            ruleContext: RuleContext
        ): ConfigFeatureFlagProvider? {
            val isValidValue: com.google.common.base.Predicate<String?> =
                com.google.common.base.Predicates.`in`<String?>(values)
            if (featureFlagValue is SetValue) {
                val setValue: String? = (featureFlagValue as SetValue).value
                if (!isValidValue.apply(setValue)) {
                    // This is consistent with build_setting, which also immediate checks that
                    // explicitly set values are valid values.
                    ruleContext.ruleError(
                        ("value must be one of "
                                + net.starlark.java.eval.Starlark.repr(
                            values.asList(),
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT
                        )
                                + ", but was "
                                + net.starlark.java.eval.Starlark.repr(
                            setValue,
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT
                        ))
                    )
                    return null
                }
                return ConfigFeatureFlagProvider.Companion.create(setValue, null, isValidValue)
            } else if (featureFlagValue == com.google.devtools.build.lib.rules.config.FeatureFlagValue.DefaultValue.INSTANCE) {
                if (!defaultValue.isPresent()) {
                    // Should defer error in case value is set by upcoming rule transition.
                    // (Although, rule authors could just add a default.)
                    // build_setting always has a default so this can't happen for them.
                    return ConfigFeatureFlagProvider.Companion.create(
                        null,
                        java.lang.String.format(
                            "Feature flag %s has no default but no value was explicitly specified.",
                            ruleContext.getLabel()
                        ),
                        isValidValue
                    )
                }
                return ConfigFeatureFlagProvider.Companion.create(defaultValue.get(), null, isValidValue)
            } else if (featureFlagValue == UnknownValue.INSTANCE) {
                // Must defer error in case value is set by upcoming rule transition.
                // build_setting doesn't have trimming logic so this can't happen for them.
                return ConfigFeatureFlagProvider.Companion.create(
                    null,
                    java.lang.String.format(
                        ("Feature flag %1\$s was accessed in a configuration it is not present in. All "
                                + "targets which depend on %1\$s directly or indirectly must name it in their "
                                + "transitive_configs attribute."),
                        ruleContext.getLabel()
                    ),
                    isValidValue
                )
            } else {
                throw java.lang.IllegalStateException("Impossible state for FeatureFlagValue: " + featureFlagValue)
            }
        }
    }
}
