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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.config.Fragment

/**
 * Policy used to express the set of configuration fragments which are legal for a rule or aspect to
 * access.
 */
class ConfigurationFragmentPolicy private constructor(
    requiredConfigurationFragments: FragmentClassSet,
    starlarkRequiredConfigurationFragments: com.google.common.collect.ImmutableSet<String?>,
    missingFragmentPolicy: com.google.common.collect.ImmutableMap<java.lang.Class<*>?, MissingFragmentPolicy?>
) {
    /**
     * How to handle the case if the configuration is missing fragments that are required according
     * to the rule class.
     */
    enum class MissingFragmentPolicy {
        /**
         * Some rules are monolithic across languages, and we want them to continue to work even when
         * individual languages are disabled. Use this policy if the rule implementation is handling
         * missing fragments.
         */
        IGNORE,

        /**
         * Use this policy to generate fail actions for the target rather than failing the analysis
         * outright. Again, this is used when rules are monolithic across languages, but we still need
         * to analyze the dependent libraries. (Instead of this mechanism, consider annotating
         * attributes as unused if certain fragments are unavailable.)
         */
        CREATE_FAIL_ACTIONS,

        /**
         * Use this policy to fail the analysis of that target with an error message; this is the
         * default.
         */
        FAIL_ANALYSIS
    }

    /**
     * Builder to construct a new ConfigurationFragmentPolicy.
     */
    class Builder {
        /** Configuration fragment classes required by this rule.  */
        private val requiredConfigurationFragments: MutableSet<java.lang.Class<out Fragment?>?> =
            HashSet<java.lang.Class<out Fragment?>?>()

        /**
         * Sets of configuration fragments required by this rule, as defined by their Starlark names
         * (see [StarlarkBuiltin].
         * 
         * 
         * Duplicate entries will automatically be ignored by the SetMultimap.
         */
        private val starlarkRequiredConfigurationFragments: MutableSet<String?> = LinkedHashSet<String?>()

        private val missingFragmentPolicy: MutableMap<java.lang.Class<*>?, MissingFragmentPolicy?> =
            LinkedHashMap<java.lang.Class<*>?, MissingFragmentPolicy?>()

        /**
         * Declares that the implementation of the associated rule class requires the given fragments to
         * be present.
         * 
         * 
         * The value is inherited by subclasses.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requiresConfigurationFragments(
            configurationFragments: MutableCollection<java.lang.Class<out Fragment?>?>?
        ): Builder {
            requiredConfigurationFragments.addAll(configurationFragments)
            return this
        }

        /**
         * Declares that the implementation of the associated rule class requires the given fragments to
         * be present for this rule.
         * 
         * 
         * In contrast to [.requiresConfigurationFragments], this method takes the
         * names of fragments (as determined by [StarlarkBuiltin]) instead of their classes.
         * 
         * 
         * The value is inherited by subclasses.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun requiresConfigurationFragmentsByStarlarkBuiltinName(
            configurationFragmentNames: MutableCollection<String?>?
        ): Builder {
            starlarkRequiredConfigurationFragments.addAll(configurationFragmentNames!!)
            return this
        }

        /**
         * Adds the configuration fragments from the `other` policy to this Builder.
         * 
         * 
         * Missing fragment policy is also copied over, overriding previously set values.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun includeConfigurationFragmentsFrom(other: ConfigurationFragmentPolicy): Builder {
            requiredConfigurationFragments.addAll(other.requiredConfigurationFragments)
            starlarkRequiredConfigurationFragments.addAll(other.starlarkRequiredConfigurationFragments)
            missingFragmentPolicy.putAll(other.missingFragmentPolicy)
            return this
        }

        /**
         * Sets the policy for the case where the configuration is missing specified required fragment
         * class (see [.requiresConfigurationFragments]).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMissingFragmentPolicy(
            fragmentClass: java.lang.Class<*>?, missingFragmentPolicy: MissingFragmentPolicy?
        ): Builder {
            this.missingFragmentPolicy.put(fragmentClass, missingFragmentPolicy)
            return this
        }

        fun build(): ConfigurationFragmentPolicy {
            return ConfigurationFragmentPolicy(
                FragmentClassSet.of(requiredConfigurationFragments),
                com.google.common.collect.ImmutableSet.copyOf<String?>(starlarkRequiredConfigurationFragments),
                com.google.common.collect.ImmutableMap.copyOf<java.lang.Class<*>?, MissingFragmentPolicy?>(
                    missingFragmentPolicy
                )
            )
        }
    }

    private val requiredConfigurationFragments: FragmentClassSet

    /** A set of Starlark module names of required configuration fragments.  */
    private val starlarkRequiredConfigurationFragments: com.google.common.collect.ImmutableSet<String?>

    /** What to do during analysis if a configuration fragment is missing.  */
    private val missingFragmentPolicy: com.google.common.collect.ImmutableMap<java.lang.Class<*>?, MissingFragmentPolicy?>

    init {
        this.requiredConfigurationFragments = requiredConfigurationFragments
        this.starlarkRequiredConfigurationFragments = starlarkRequiredConfigurationFragments
        this.missingFragmentPolicy = missingFragmentPolicy
    }

    /**
     * The set of required configuration fragments; this contains all fragments that can be accessed
     * by the rule implementation under any configuration.
     */
    fun getRequiredConfigurationFragments(): FragmentClassSet {
        return requiredConfigurationFragments
    }

    /**
     * Returns the fragments required by Starlark definitions (e.g. `fragments = ["cpp"]`
     * with the naming form seen in the Starlark API.
     * 
     * 
     * [ ][com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.getStarlarkFragmentByName]
     * can be used to convert this to Java fragment instances.
     */
    fun getRequiredStarlarkFragments(): com.google.common.collect.ImmutableCollection<String?> {
        return starlarkRequiredConfigurationFragments
    }

    /**
     * Checks if the configuration fragment may be accessed (i.e., if it's declared) in any
     * configuration.
     */
    fun isLegalConfigurationFragment(configurationFragment: java.lang.Class<*>?): Boolean {
        return requiredConfigurationFragments.contains(configurationFragment)
                || hasLegalFragmentName(configurationFragment)
    }

    /**
     * Checks whether the name of the given fragment class was declared as required in any
     * configuration.
     */
    private fun hasLegalFragmentName(configurationFragment: java.lang.Class<*>?): Boolean {
        val fragmentModule: net.starlark.java.annot.StarlarkBuiltin? =
            net.starlark.java.annot.StarlarkAnnotations.getStarlarkBuiltin(configurationFragment)

        return fragmentModule != null
                && starlarkRequiredConfigurationFragments.contains(fragmentModule.name)
    }

    /**
     * Whether to fail analysis if any of the specified configuration fragment class is missing.
     * 
     * 
     * If unset for the specific fragment class, defaults to FAIL_ANALYSIS
     */
    fun getMissingFragmentPolicy(fragmentClass: java.lang.Class<*>?): MissingFragmentPolicy? {
        return missingFragmentPolicy.getOrDefault(fragmentClass, MissingFragmentPolicy.FAIL_ANALYSIS)
    }
}
