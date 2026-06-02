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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.packages.BuildType.LABEL_KEYED_STRING_DICT

/**
 * A transition factory for trimming feature flags manually via an attribute which specifies the
 * feature flags used by transitive dependencies.
 */
class ConfigFeatureFlagTaggedTrimmingTransitionFactory
    (private val attributeName: String?) : TransitionFactory<RuleTransitionData?> {
    /** Applies manual trimming to the given set of flags.  */
    class ConfigFeatureFlagTaggedTrimmingTransition internal constructor(flags: com.google.common.collect.ImmutableSortedSet<Label?>) :
        PatchTransition {
        private val flags: com.google.common.collect.ImmutableSortedSet<Label?>
        private val cachedHashCode: Int

        init {
            this.flags = flags
            this.cachedHashCode = this.flags.hashCode()
        }

        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            return com.google.common.collect.ImmutableSet.of<E?>(
                ConfigFeatureFlagOptions::class.java,
                CoreOptions::class.java
            )
        }

        public override fun patch(
            options: BuildOptionsView,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): BuildOptions? {
            val configFeatureFlagOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                options.get(ConfigFeatureFlagOptions::class.java)
            if (configFeatureFlagOptions == null
                || !configFeatureFlagOptions.getEnforceTransitiveConfigsForConfigFeatureFlag()
            ) {
                return options.underlying()
            }
            return FeatureFlagValue.Companion.trimFlagValues(options.underlying(), flags)
        }

        override fun equals(other: Any?): Boolean {
            return other
            is ConfigFeatureFlagTaggedTrimmingTransition
                    && this.flags == other.flags
        }

        override fun hashCode(): Int {
            return cachedHashCode
        }

        override fun toString(): String {
            return String.format("ConfigFeatureFlagTaggedTrimmingTransition{flags=%s}", flags)
        }

        companion object {
            val EMPTY: ConfigFeatureFlagTaggedTrimmingTransition =
                ConfigFeatureFlagTaggedTrimmingTransition(com.google.common.collect.ImmutableSortedSet.of<Label?>())
        }
    }

    public override fun create(ruleData: RuleTransitionData): PatchTransition? {
        val attrs: NonconfiguredAttributeMapper = NonconfiguredAttributeMapper.of(ruleData.rule())
        val ruleClass: RuleClass = ruleData.rule().getRuleClassObject()

        if (AliasProvider.mayBeAlias(ruleData.rule())) {
            // As a convenience, do not require transitive_config to be set for alias rule.
            return NoTransition.INSTANCE
        }

        if (ruleClass.getName().equals(ConfigFeatureFlagRule.Companion.RULE_NAME)) {
            return ConfigFeatureFlagTaggedTrimmingTransition(
                com.google.common.collect.ImmutableSortedSet.of(ruleData.rule().getLabel())
            )
        }

        val requiredLabelsBuilder: com.google.common.collect.ImmutableSortedSet.Builder<Label?> =
            com.google.common.collect.ImmutableSortedSet.Builder<Label?>(com.google.common.collect.Ordering.natural<Comparable<*>?>())
        if (attrs.isAttributeValueExplicitlySpecified(attributeName)
            && !attrs.get(attributeName, NODEP_LABEL_LIST).isEmpty()
        ) {
            // Entries starting with //command_line_option[:/] represent native options and are not
            // relevant for this transition. Non-existent flags already do not error so this skipping
            // is done out of an abundance of caution and as a statement of intent for the future.
            for (entry in attrs.get(attributeName, NODEP_LABEL_LIST)) {
                val packageName: String = entry.getPackageName()
                if (packageName == "command_line_option"
                    || packageName.startsWith("command_line_option/")
                ) {
                    continue
                }
                requiredLabelsBuilder.add(entry)
            }
        }
        if (ruleClass.getTransitionFactory() is ConfigFeatureFlagTransitionFactory) {
            val settingAttribute: String? = cfft.getAttributeName()
            // Because the process of setting a flag also creates a dependency on that flag, we need to
            // include all the set flags, even if they aren't actually declared as used by this rule.
            requiredLabelsBuilder.addAll(attrs.get(settingAttribute, LABEL_KEYED_STRING_DICT).keySet())
        }

        val requiredLabels: com.google.common.collect.ImmutableSortedSet<Label?> = requiredLabelsBuilder.build()
        if (requiredLabels.isEmpty()) {
            return ConfigFeatureFlagTaggedTrimmingTransition.Companion.EMPTY
        }

        return ConfigFeatureFlagTaggedTrimmingTransition(requiredLabels)
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.RULE
    }
}
