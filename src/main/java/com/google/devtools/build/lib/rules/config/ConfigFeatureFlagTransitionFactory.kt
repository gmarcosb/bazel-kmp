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

import com.google.devtools.build.lib.packages.BuildType.LABEL_KEYED_STRING_DICT

/**
 * Transition factory which allows for setting the values of config_feature_flags below the rule it
 * is attached to based on one of that rule's attributes.
 * 
 * 
 * Currently, this is only intended for use by android_binary and other Android top-level rules.
 */
class ConfigFeatureFlagTransitionFactory

/**
 * Creates a transition factory which will generate a transition over a given rule which sets
 * exactly the flag values in the attribute with the given `attributeName` of that rule,
 * unsetting any flag values not listed there.
 * 
 * 
 * This attribute must not be a configured `LABEL_KEYED_STRING_DICT`. (No selects)
 */(
    /**
     * Returns the attribute examined by this transition factory.
     */
    val attributeName: String
) : StarlarkExposedRuleTransitionFactory, ConfigurationTransitionApi {
    public override fun addToRuleFromStarlark(ctx: RuleDefinitionEnvironment, builder: RuleClass.Builder) {
        builder.add(ConfigFeatureFlag.Companion.getAllowlistAttribute(ctx, attributeName))
        builder.addAllowlistChecker(
            AllowlistChecker.builder()
                .setAllowlistAttr(ConfigFeatureFlag.Companion.ALLOWLIST_NAME)
                .setErrorMessage("the attribute " + attributeName + " is not available in this package")
                .setLocationCheck(AllowlistChecker.LocationCheck.INSTANCE)
                .setAttributeSetTrigger(attributeName)
                .build()
        )
        builder.add(ConfigFeatureFlag.Companion.getSetterAllowlistAttribute(ctx))
        builder.addAllowlistChecker(
            AllowlistChecker.builder()
                .setAllowlistAttr(ConfigFeatureFlag.Companion.SETTER_ALLOWLIST_NAME)
                .setErrorMessage(
                    "the rule class is not allowed access to feature flags setter transition"
                )
                .setLocationCheck(AllowlistChecker.LocationCheck.DEFINITION)
                .setAttributeSetTrigger(attributeName)
                .build()
        )
    }

    /** Transition which resets the set of flag-value pairs to the map it was constructed with.  */
    private class ConfigFeatureFlagValuesTransition(
        flagValues: com.google.common.collect.ImmutableSortedMap<Label?, String?>,
        private val cachedHashCode: Int
    ) : PatchTransition {
        private val flagValues: com.google.common.collect.ImmutableSortedMap<Label?, String?>

        constructor(flagValues: MutableMap<Label?, String?>) : this(
            com.google.common.collect.ImmutableSortedMap.copyOf<Label?, String?>(
                flagValues
            ), flagValues.hashCode()
        )

        init {
            this.flagValues = com.google.common.collect.ImmutableSortedMap.copyOf<Label?, String?>(flagValues)
        }

        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            return com.google.common.collect.ImmutableSet.of<E?>(ConfigFeatureFlagOptions::class.java)
        }

        public override fun patch(
            options: BuildOptionsView,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): BuildOptions? {
            if (!options.contains(ConfigFeatureFlagOptions::class.java)) {
                return options.underlying()
            }
            return FeatureFlagValue.Companion.replaceFlagValues(options.underlying(), flagValues)
        }

        override fun equals(other: Any?): Boolean {
            return other is ConfigFeatureFlagValuesTransition
                    && this.flagValues == other.flagValues
        }

        override fun hashCode(): Int {
            return cachedHashCode
        }

        override fun toString(): String {
            return String.format("ConfigFeatureFlagValuesTransition{flagValues=%s}", flagValues)
        }
    }

    public override fun create(ruleData: RuleTransitionData): PatchTransition? {
        val attrs: NonconfiguredAttributeMapper = NonconfiguredAttributeMapper.of(ruleData.rule())
        if (attrs.isAttributeValueExplicitlySpecified(attributeName)) {
            return ConfigFeatureFlagValuesTransition(
                attrs.get(attributeName, LABEL_KEYED_STRING_DICT)
            )
        } else {
            return NoTransition.INSTANCE
        }
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.RULE
    }

    override fun equals(other: Any?): Boolean {
        return other is ConfigFeatureFlagTransitionFactory
                && this.attributeName == other.attributeName
    }

    override fun hashCode(): Int {
        return attributeName.hashCode()
    }

    override fun toString(): String {
        return String.format("ConfigFeatureFlagTransitionFactory{attributeName=%s}", attributeName)
    }
}
