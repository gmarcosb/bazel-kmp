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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.collect.HashMultimap
import com.google.common.collect.SetMultimap
import com.google.devtools.build.lib.cmdline.Label
import java.util.*
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/**
 * Utility class to hold all conditional edges in a graph. Allows easy look-up of all conditions
 * between two nodes.
 */
class ConditionalEdges {
    // Map containing all the conditions for all the conditional edges in a graph.
    private var map: HashMap<Label?, SetMultimap<Label?, Label?>?>? = null

    constructor()

    /** Builds ConditionalEdges from given graph.  */
    constructor(graph: Digraph<Target?>) {
        this.map = HashMap<Label?, SetMultimap<Label?, Label?>?>()

        for (node in graph.getNodes()) {
            val rule: Rule? = node.label.getAssociatedRule()
            if (rule == null) {
                // rule is null for source files and package groups. Skip them.
                continue
            }

            val conditions: SetMultimap<Label?, Label?> = getAllConditions(rule, RawAttributeMapper.of(rule))
            if (conditions.isEmpty()) {
                // bail early for most common case of no conditions in the rule.
                continue
            }

            val nodeLabel: Label? = node.label.getLabel()
            for (succ in node.successors) {
                val successorLabel: Label? = succ.label.getLabel()
                if (conditions.containsKey(successorLabel)) {
                    insert(nodeLabel, successorLabel, conditions.get(successorLabel))
                }
            }
        }
    }

    /** Inserts `conditions` for edge src --> dest.  */
    fun insert(src: Label?, dest: Label?, conditions: MutableSet<Label?>) {
        map.computeIfAbsent(src) { k: Label? -> HashMultimap.create<Label?, Label?>() }
        map.get(src).putAll(dest, conditions)
    }

    /**
     * Returns all conditions for edge src --> dest, if they exist. Does not return default
     * conditions.
     */
    fun get(src: Label?, dest: Label?): Optional<MutableSet<Label?>?> {
        if (!map.containsKey(src) || !map.get(src).containsKey(dest)) {
            return Optional.empty<MutableSet<Label?>?>()
        }

        return Optional.of<MutableSet<Label?>?>(map.get(src).get(dest))
    }

    /**
     * Returns map of dependency to list of condition-labels.
     * 
     * 
     * Example: For a rule like below,
     * 
     * <pre>
     * some_rule(
     * ...
     * deps = [
     * ... default dependencies ...
     * ] + select ({
     * "//some:config1": [ "//some:a", "//some:common" ],
     * "//some:config2": [ "//other:a", "//some:common" ],
     * "//conditions:default": [ "//some:default" ],
     * })
     * )
    </pre> * 
     * 
     * it returns following map:
     * 
     * <pre>
     * {
     * "//some:a": ["//some:config1" ]
     * "//other:a": ["//some:config2" ]
     * "//some:common": ["//some:config1", "//some:config2" ]
     * "//some:default": [ "//conditions:default" ]
     * }
    </pre> * 
     */
    private fun getAllConditions(rule: Rule, attributeMap: RawAttributeMapper): SetMultimap<Label?, Label?> {
        val conditions: SetMultimap<Label?, Label?> = HashMultimap.create<Label?, Label?>()
        for (attr in rule.getAttributes()) {
            // TODO(bazel-team): Handle the case where dependency exists through both configurable as well
            // as non-configurable attributes. Currently this prints such an edge as a conditional one.
            if (!attributeMap.isConfigurable(attr.name)) {
                // skip non configurable attributes
                continue
            }
            if (rule.getAttr(attr.name) is Attribute.ComputedDefault) {
                // isConfigurable above checks that the attribute is either a `select()` or a computed
                // default. We don't currently handle the latter so skip it.
                // TODO: b/375344172 - (bazel-team) Decide how to resolve computed defaults.
                // TODO: b/375344172 - (bazel-team) Add a regression test for this case.
                continue
            }

            for (selector in (attributeMap.getRawAttributeValue(rule, attr) as BuildType.SelectorList<*>).selectors) {
                if (selector.isUnconditional()) {
                    // skip unconditional selectors
                    continue
                }
                selector.forEach(
                    { key, value ->
                        if (value is MutableList<*>) {
                            for (dep in value) {
                                if (dep is Label) {
                                    conditions.put(dep, key)
                                }
                            }
                        } else if (value is Label) {
                            conditions.put(value, key)
                        }
                    })
            }
        }
        return conditions
    }
}
