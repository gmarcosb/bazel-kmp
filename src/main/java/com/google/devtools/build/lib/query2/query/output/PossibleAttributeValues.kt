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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper

/** Logic for retrieving possible values for an attribute.  */
object PossibleAttributeValues {
    /**
     * Returns the possible values of the specified attribute in the specified rule. For simple
     * attributes, this is a single value. For configurable and computed attributes, this may be a
     * list of values. See [AggregatingAttributeMapper.visitAttribute] for how the values are
     * determined.
     * 
     * 
     * This applies an important optimization for label lists: instead of returning all possible
     * values, it only returns possible *labels*. For example, given:
     * 
     * <pre>
     * select({
     * ":c": ["//a:one", "//a:two"],
     * ":d": ["//a:two"]
     * })</pre>
     * 
     * it returns:
     * 
     * <pre>["//a:one", "//a:two"]</pre>
     * 
     * which loses track of which label appears in which branch.
     * 
     * 
     * This avoids the memory overruns that can happen be iterating over every possible value for
     * an `attr = select(...) + select(...) + select(...) + ...` expression. Query
     * operations generally don't care about specific attribute values - they just care which labels
     * are possible.
     * 
     * @param mayTreatMultipleAsNone signals if attribute-value computation **may** be aborted if
     * more than one possible value is encountered. Useful when the caller needs a single value or
     * none at all, as is the use case of this method for many attribute types. This parameter is
     * respected on a best-effort basis - multiple values may still be returned if an unoptimized
     * code path is visited.
     */
    fun forRuleAndAttribute(
        rule: Rule?, attr: Attribute, mayTreatMultipleAsNone: Boolean
    ): Iterable<Any?>? {
        val attributeMap: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
        if (attr.getType().equals(BuildType.LABEL_LIST)
            && attributeMap.isConfigurable(attr.name)
        ) {
            // TODO(gregce): Expand this to all collection types (we don't do this for scalars because
            // there's currently no syntax for expressing multiple scalar values). This unfortunately
            // isn't trivial because Bazel's label visitation logic includes special methods built
            // directly into Type.
            return ImmutableList.of<E?>(
                attributeMap.getReachableLabels(attr.name,  /* includeSelectKeys= */false)
            )
        }

        val concatenatedSelectsValue: Iterable<*>? =
            attributeMap.getConcatenatedSelectorListsOfListType(attr.name, attr.getType())
        if (concatenatedSelectsValue != null) {
            return Lists.newArrayList<Any?>(concatenatedSelectsValue)
        }

        // The call to visitAttributes below is especially slow with selector lists.
        val possibleValues// Casting Iterable<T> -> Iterable<Object>
                =
            attributeMap.visitAttribute(attr.name, attr.getType(), mayTreatMultipleAsNone) as Iterable<Any?>?
        return possibleValues
    }
}
