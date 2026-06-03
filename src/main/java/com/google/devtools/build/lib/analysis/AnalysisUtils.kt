// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.AnalysisUtils.Companion.isStampingEnabled
import com.google.devtools.build.lib.cmdline.Label

/**
 * Utility functions for use during analysis.
 */
class AnalysisUtils private constructor() {
    init {
        throw java.lang.IllegalStateException() // utility class
    }

    companion object {
        /**
         * Returns whether link stamping is enabled for a TriState stamp attribute.
         * 
         * 
         * This returns false for unstampable rule classes and for rules used to build tools. Otherwise
         * it returns the value of the stamp attribute, or of the stamp option if the attribute value is
         * AUTO.
         */
        fun isStampingEnabled(stamp: TriState, config: BuildConfigurationValue): Boolean {
            if (config.isToolConfiguration()) {
                return false
            }
            return stamp.equals(TriState.YES) || (stamp.equals(TriState.AUTO) && config.stampBinaries())
        }

        /**
         * Returns whether link stamping is enabled for a rule.
         * 
         * 
         * This returns false for unstampable rule classes and for rules used to build tools. Otherwise
         * it returns the value of the stamp attribute, or of the stamp option if the attribute value is
         * -1.
         */
        fun isStampingEnabled(ruleContext: RuleContext, config: BuildConfigurationValue): Boolean {
            if (ruleContext.attributes().has("stamp", BuildType.TRISTATE)) {
                val stamp: TriState = ruleContext.attributes().get("stamp", BuildType.TRISTATE)
                return Companion.isStampingEnabled(stamp, config)
            }
            if (ruleContext.attributes().has("stamp", Type.INTEGER)) {
                val stamp: Int = ruleContext.attributes().get("stamp", Type.INTEGER).toIntUnchecked()
                return isStampingEnabled(TriState.fromInt(stamp), config)
            }
            return false
        }

        fun isStampingEnabled(ruleContext: RuleContext): Boolean {
            return Companion.isStampingEnabled(ruleContext, ruleContext.getConfiguration())
        }

        // TODO(bazel-team): These need Iterable<? extends TransitiveInfoCollection> because they need to
        // be called with Iterable<ConfiguredTarget>. Once the configured target lockdown is complete, we
        // can eliminate the "extends" clauses.
        /**
         * Returns the list of providers of the specified type from a set of transitive info collections.
         */
        fun <C : TransitiveInfoProvider?> getProviders(
            prerequisites: Iterable<out TransitiveInfoCollection>, provider: java.lang.Class<C?>?
        ): MutableList<C?> {
            val result: com.google.common.collect.ImmutableList.Builder<C?> =
                com.google.common.collect.ImmutableList.builder<C?>()
            for (prerequisite in prerequisites) {
                val prerequisiteProvider: C? = prerequisite.getProvider(provider)
                if (prerequisiteProvider != null) {
                    result.add(prerequisiteProvider)
                }
            }
            return result.build()
        }

        /**
         * Returns the list of declared providers (native and Starlark) of the specified Starlark key from
         * a set of transitive info collections.
         */
        fun <T : Info?> getProviders(
            prerequisites: Iterable<out TransitiveInfoCollection>,
            starlarkKey: BuiltinProvider<T?>?
        ): MutableList<T?> {
            val result: com.google.common.collect.ImmutableList.Builder<T?> =
                com.google.common.collect.ImmutableList.builder<T?>()
            for (prerequisite in prerequisites) {
                val prerequisiteProvider: T? = prerequisite.get(starlarkKey)
                if (prerequisiteProvider != null) {
                    result.add(prerequisiteProvider)
                }
            }
            return result.build()
        }

        /**
         * Returns the list of declared providers of the specified Starlark key from a set of transitive
         * info collections.
         */
        @Throws(RuleErrorException::class)
        fun <T> getProviders(
            prerequisites: Iterable<out TransitiveInfoCollection>,
            starlarkKey: StarlarkProviderWrapper<T?>?
        ): com.google.common.collect.ImmutableList<T?> {
            val result: com.google.common.collect.ImmutableList.Builder<T?> =
                com.google.common.collect.ImmutableList.builder<T?>()
            for (prerequisite in prerequisites) {
                val prerequisiteProvider: T? = prerequisite.get(starlarkKey)
                if (prerequisiteProvider != null) {
                    result.add(prerequisiteProvider)
                }
            }
            return result.build()
        }

        /** Returns the iterable of collections that have the specified provider.  */
        fun <S : TransitiveInfoCollection?, C : TransitiveInfoProvider?>
                filterByProvider(prerequisites: Iterable<S?>, provider: java.lang.Class<C?>?): Iterable<S?> {
            return com.google.common.collect.Iterables.filter<S?>(
                prerequisites,
                com.google.common.base.Predicate { target: S? -> target.getProvider(provider) != null })
        }

        /** Returns the iterable of collections that have the specified provider.  */
        fun <S : TransitiveInfoCollection?, C : Info?> filterByProvider(
            prerequisites: Iterable<S?>, provider: BuiltinProvider<C?>?
        ): Iterable<S?> {
            return com.google.common.collect.Iterables.filter<S?>(
                prerequisites,
                com.google.common.base.Predicate { target: S? -> target.get(provider) != null })
        }

        /**
         * Returns a path fragment qualified by the rule name and unique fragment to disambiguate
         * artifacts produced from the source file appearing in multiple rules.
         * 
         * 
         * For example "//pkg:target" -> "pkg/&lt;fragment&gt;/target.
         */
        fun getUniqueDirectory(
            label: Label, fragment: PathFragment?, siblingRepositoryLayout: Boolean
        ): PathFragment {
            return label
                .getPackageIdentifier()
                .getPackagePath(siblingRepositoryLayout)
                .getRelative(fragment)
                .getRelative(label.getName())
        }

        /**
         * Checks that the given provider class either refers to an interface or to a value class.
         */
        fun <T : TransitiveInfoProvider?> checkProvider(clazz: java.lang.Class<T?>) {
            // Write this check in terms of getName() rather than getSimpleName(); the latter is expensive.
            require(
                !(!clazz.isInterface() && clazz.getName().contains(".AutoValue_"))
            ) { clazz.toString() + " is generated by @AutoValue; use " + clazz.getSuperclass() + " instead" }
        }
    }
}
