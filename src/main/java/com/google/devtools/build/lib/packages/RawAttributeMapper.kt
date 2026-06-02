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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * [AttributeMap] implementation that returns raw attribute information as contained within a
 * [RuleOrMacroInstance] via [.getRawAttributeValue]. In particular, configurable
 * attributes of the form { config1: "value1", config2: "value2" } are passed through without being
 * resolved to a final value when obtained via that method.
 */
class RawAttributeMapper private constructor(ruleOrMacroInstance: RuleOrMacroInstance) :
    com.google.devtools.build.lib.packages.AbstractAttributeMapper(ruleOrMacroInstance) {
    /**
     * Variation of [.get] that merges the values of configurable lists together (with
     * duplicates removed).
     * 
     * 
     * For example, given:
     * <pre>
     * attr = select({
     * ':condition1': [A, B, C],
     * ':condition2': [C, D]
     * }),
    </pre> * 
     * this returns the value `[A, B, C, D]`.
     * 
     * 
     * If the attribute isn't configurable (e.g. `attr = [A, B]`), returns
     * its raw value.
     * 
     * 
     * Throws an [IllegalStateException] if the attribute isn't a list type.
     */
    fun <T> getMergedValues(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<MutableList<T?>?>?
    ): MutableCollection<T?>? {
        com.google.common.base.Preconditions.checkState(type is com.google.devtools.build.lib.packages.Type.ListType<*>)
        if (!isConfigurable(attributeName)) {
            return get<MutableList<T?>?>(attributeName, type)
        }

        val mergedValues: com.google.common.collect.ImmutableSet.Builder<T?> =
            com.google.common.collect.ImmutableSet.builder<T?>()
        for (selector in getSelectorList<MutableList<T?>?>(attributeName, type).getSelectors()) {
            selector.forEach(
                SelectorEntryConsumer { label: Label?, value: MutableList<T?>? ->
                    if (value != null) {
                        mergedValues.addAll(value)
                    }
                })
        }
        return mergedValues.build()
    }

    /**
     * If the attribute is configurable for this rule instance, returns its configuration
     * keys. Else returns an empty list.
     */
    fun <T> getConfigurabilityKeys(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): Iterable<Label?> {
        val selectorList: BuildType.SelectorList<T?>? = getSelectorList<T?>(attributeName, type)
        if (selectorList == null) {
            return com.google.common.collect.ImmutableList.of<Label?>()
        }
        val builder: com.google.common.collect.ImmutableList.Builder<Label?> =
            com.google.common.collect.ImmutableList.builder<Label?>()
        for (selector in selectorList.getSelectors()) {
            selector.forEach(SelectorEntryConsumer { label: Label?, value: T? -> builder.add(label) })
        }
        return builder.build()
    }

    /**
     * See [.getRawAttributeValue].
     * 
     * 
     * {@param attrName} must be the name of an [Attribute] defined by the {@param rule}'s
     * [RuleClass].
     */
    fun getRawAttributeValue(rule: com.google.devtools.build.lib.packages.Rule, attrName: String?): Any? {
        val attr: com.google.devtools.build.lib.packages.Attribute =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Attribute>(
                getAttributeDefinition(attrName),
                "%s %s",
                rule,
                attrName
            )
        return getRawAttributeValue(rule, attr)
    }

    /**
     * Returns the object associated with the {@param rule}'s {@param attr}.
     * 
     * 
     * Handles the special case of the "visibility" attribute by returning {@param rule}'s [ ]'s declared labels.
     * 
     * 
     * The returned object will be a [SelectorList] if the attribute value contains a
     * `select(...)` expression.
     * 
     * 
     * The returned object will be a [ComputedDefault] if the rule doesn't explicitly
     * declare an attribute value and the rule's class provides a computed default for it.
     * 
     * 
     * Otherwise, the returned object will be the type declared by the {@param attr}, or `null`.
     */
    fun getRawAttributeValue(
        rule: com.google.devtools.build.lib.packages.Rule,
        attr: com.google.devtools.build.lib.packages.Attribute
    ): Any? {
        // This special case for the visibility attribute is needed because its value is replaced
        // with an empty list during package loading if it is public or private in order not to visit
        // the package called 'visibility'.
        if (attr.getName() == "visibility") {
            return rule.getVisibilityDeclaredLabels()
        }

        // If the attribute value contains one or more select(...) expressions, then return
        // the SelectorList object representing those expressions.
        val selectorList: BuildType.SelectorList<*>? = getSelectorList(attr.getName(), attr.getType())
        if (selectorList != null) {
            return selectorList
        }

        // If the attribute value is not explicitly declared, and the rule class provides a computed
        // default value for it, then we should return the ComputedDefault object.
        //
        // We check for the existence of a ComputedDefault value because AbstractAttributeMapper#get
        // returns either an explicitly declared attribute value or the result of evaluating the
        // computed default function, but does not specify which one it is.
        val computedDefault: ComputedDefault? = getComputedDefault(attr.getName(), attr.getType())
        if (computedDefault != null) {
            return computedDefault
        }
        return get(attr.getName(), attr.getType())
    }

    companion object {
        fun of(ruleOrMacroInstance: RuleOrMacroInstance): RawAttributeMapper {
            return RawAttributeMapper(ruleOrMacroInstance)
        }
    }
}
