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

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A [ConfiguredTarget] is conceptually a [TransitiveInfoCollection] coupled with the
 * [com.google.devtools.build.lib.packages.Target] and [ ] objects it was created
 * from.
 * 
 * 
 * This interface is supposed to only be used in [BuildView] and above. In particular, rule
 * implementations should not be able to access the [ConfiguredTarget] objects associated with
 * their direct dependencies, only the corresponding [TransitiveInfoCollection]s. Also, [ ] objects should not be accessible from the action graph.
 */
interface ConfiguredTarget : TransitiveInfoCollection, net.starlark.java.eval.Structure, CqueryNode {
    /** Returns a key that may be used to lookup this [ConfiguredTarget].  */
    override fun getLookupKey(): ActionLookupKey?

    override fun getLabel(): com.google.devtools.build.lib.cmdline.Label {
        return getLookupKey().getLabel()
    }

    override fun getConfigurationChecksum(): String? {
        return if (getConfigurationKey() == null) null else getConfigurationKey().getOptions().checksum()
    }

    /**
     * Returns the [BuildConfigurationKey] naming the [ ] for which this
     * configured target is defined. Configuration is defined for all configured targets with
     * exception of [ ] and [ ] for
     * which it is always **null**.
     * 
     * 
     * If this changes, [AspectResolver.aspectMatchesConfiguredTarget] should be updated.
     */
    override fun getConfigurationKey(): BuildConfigurationKey? {
        return getLookupKey().getConfigurationKey()
    }

    /** Returns keys for a legacy Starlark provider.  */
    override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?>?

    /**
     * Returns a legacy Starlark provider.
     * 
     * 
     * Overrides [Structure.getValue], but does not allow EvalException to be thrown.
     */
    override fun getValue(name: String?): Any?

    /**
     * If the configured target is an alias, return the actual target in the end of the alias chain,
     * otherwise return the current target. This follows alias chains.
     */
    override fun getActual(): ConfiguredTarget? {
        return this
    }

    /**
     * If the configured target is an alias, return the actual target directly pointed to by the
     * alias, otherwise return the current target. This does not follow alias chains.
     */
    fun getActualNoFollow(): ConfiguredTarget? {
        return this
    }

    /**
     * If the configured target is an alias, return the original label, otherwise return the current
     * label. This is not the same as `getActual().getLabel()`, because it does not follow alias
     * chains.
     */
    override fun getOriginalLabel(): com.google.devtools.build.lib.cmdline.Label {
        return getLabel()
    }

    /**
     * The configuration conditions that trigger this configured target's configurable attributes. For
     * targets that do not support configurable attributes, this will be an empty map.
     */
    override fun getConfigConditions(): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, ConfigMatchingProvider?>? {
        return com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, ConfigMatchingProvider?>()
    }

    override fun isRuleConfiguredTarget(): Boolean {
        return false
    }

    /**
     * The base configured target if it has been merged with aspects otherwise the current value.
     * 
     * 
     * Unwrapping is recursive if there are multiple layers.
     */
    override fun unwrapIfMerged(): ConfiguredTarget {
        return this
    }

    /**
     * This is only intended to be called from the query dialects of Starlark.
     * 
     * @return a map of provider names to their values, or null if there are no providers
     */
    override fun getProvidersDictForQuery(): net.starlark.java.eval.Dict<String?, Any?>? {
        return null
    }

    companion object {
        /** All `ConfiguredTarget`s have a "label" field.  */
        const val LABEL_FIELD: String = "label"

        /** All `ConfiguredTarget`s have a "files" field.  */
        const val FILES_FIELD: String = "files"
    }
}
