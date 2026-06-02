// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A [CqueryNode] provides information necessary to traverse different types of nodes that can
 * be visited during a [CqueryCommand] call. This may include [ConfiguredTarget], [ ], and transition nodes.
 */
interface CqueryNode {
    /** Returns a key that may be used to lookup this [CqueryNode].  */
    @kotlin.jvm.JvmField
    val lookupKey: ActionLookupKey?

    val label: Label
        get() = this.lookupKey.getLabel()

    fun getDescription(labelPrinter: LabelPrinter): String {
        return labelPrinter.toString(this.originalLabel)
    }

    val configurationChecksum: String?
        get() = if (this.configurationKey == null) null else this.configurationKey.getOptions().checksum()

    val configurationKey: BuildConfigurationKey?
        /**
         * Returns the [BuildConfigurationKey] naming the [ ] for which this cquery
         * node is defined. Configuration is defined for all configured targets with exception of [ ] and [ ] for
         * which it is always **null**.
         * 
         * 
         * If this changes, [AspectResolver.aspectMatchesConfiguredTarget] should be updated.
         */
        get() = this.lookupKey.getConfigurationKey()

    val actual: CqueryNode
        /**
         * If the configured target is an alias, return the actual target, otherwise return the current
         * target. This follows alias chains.
         */
        get() = this

    val originalLabel: Label
        /**
         * If the configured target is an alias, return the original label, otherwise return the current
         * label. This is not the same as `getActual().getLabel()`, because it does not follow alias
         * chains.
         */
        get() = this.label

    val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>
        /**
         * The configuration conditions that trigger this configured target's configurable attributes. For
         * targets that do not support configurable attributes, this will be an empty map.
         */
        get() = com.google.common.collect.ImmutableMap.of<Label?, ConfigMatchingProvider?>()

    val isRuleConfiguredTarget: Boolean
        get() = false

    /**
     * The base configured target if it has been merged with aspects otherwise the current value.
     * 
     * 
     * Unwrapping is recursive if there are multiple layers.
     */
    fun unwrapIfMerged(): CqueryNode {
        return this
    }

    val providersDictForQuery: net.starlark.java.eval.Dict<String?, Any?>?
        /**
         * This is only intended to be called from the query dialects of Starlark.
         * 
         * @return a map of provider names to their values, or null if there are no providers
         */
        get() = null
}
