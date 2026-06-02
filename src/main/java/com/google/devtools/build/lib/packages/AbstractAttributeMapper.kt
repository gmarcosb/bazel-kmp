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
 * Base [AttributeMap] implementation providing direct, unmanipulated access to
 * underlying attribute data as stored within the Rule.
 * 
 * 
 * Any instantiable subclass should define a clear policy of what it does with this
 * data before exposing it to consumers.
 */
abstract class AbstractAttributeMapper protected constructor(rule: RuleOrMacroInstance) :
    com.google.devtools.build.lib.packages.AttributeMap {
    val ruleClass: com.google.devtools.build.lib.packages.AttributeProvider
    val rule: RuleOrMacroInstance
    private val ruleLabel: Label?

    init {
        this.ruleClass = rule.getAttributeProvider()
        this.ruleLabel = rule.getLabel()
        this.rule = rule
    }

    override fun describeRule(): String? {
        return java.lang.String.format("%s %s", this.rule.getAttributeProvider(), getLabel())
    }

    override fun getLabel(): Label? {
        return ruleLabel
    }

    override fun <T> get(attributeName: String, type: com.google.devtools.build.lib.packages.Type<T?>?): T? {
        return getFromRawAttributeValue<T?>(rule.getAttr<T?>(attributeName, type), attributeName, type)
    }

    fun <T> getFromRawAttributeValue(
        value: Any?,
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): T? {
        var value = value
        if (value is ComputedDefault) {
            value = (value as ComputedDefault).getDefault(this)
        } else if (value is LateBoundDefault<*, *>) {
            value = (value as LateBoundDefault<*, *>).getDefault(rule)
        } else if (value is MaterializingDefault<*, *>) {
            value = (value as MaterializingDefault<*, *>).getDefault()
        } else require(!value is BuildType.SelectorList<*>) {
            java.lang.String.format(
                "Unexpected configurable attribute \"%s\" in %s rule %s: expected %s, is %s",
                attributeName, ruleClass, ruleLabel, type, value
            )
        }

        // Hot code path - avoid the overhead of calling type.cast(value). The rule would have already
        // failed on construction if one of its attributes was of the wrong type (including computed
        // defaults).
        return value as T?
    }

    /**
     * Returns the given attribute if it's a computed default, null otherwise.
     * 
     * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
     * type. This happens whether or not it's a computed default.
     */
    @com.google.common.annotations.VisibleForTesting // Should be protected
    fun <T> getComputedDefault(
        attributeName: String,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): ComputedDefault? {
        val value: Any? = rule.getAttr<T?>(attributeName, type)
        if (value is ComputedDefault) {
            return value
        } else {
            return null
        }
    }

    /**
     * Returns the given attribute if it's a [Attribute.LateBoundDefault], null otherwise.
     * 
     * @throws IllegalArgumentException if the given attribute doesn't exist with the specified
     * type. This happens whether or not it's a late bound default.
     */
    fun <T> getLateBoundDefault(
        attributeName: String, type: com.google.devtools.build.lib.packages.Type<T?>?
    ): LateBoundDefault<*, T?>? {
        val value: Any? = rule.getAttr<T?>(attributeName, type)
        if (value is LateBoundDefault<*, *>) {
            return value as LateBoundDefault<*, T?>
        } else {
            return null
        }
    }

    fun <T> getMaterializer(
        attributeName: String,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): MaterializingDefault<*, T?>? {
        val value: Any? = rule.getAttr<T?>(attributeName, type)
        if (value is MaterializingDefault<*, *>) {
            return value as MaterializingDefault<*, T?>
        } else {
            return null
        }
    }

    override fun getAttributeNames(): Iterable<String?> {
        return com.google.common.collect.Lists.transform<com.google.devtools.build.lib.packages.Attribute?, String?>(
            ruleClass.getAttributes(),
            com.google.common.base.Function { obj: com.google.devtools.build.lib.packages.Attribute? -> obj.getName() })
    }

    override fun getAttributeType(attrName: String?): com.google.devtools.build.lib.packages.Type<*>? {
        val attr: com.google.devtools.build.lib.packages.Attribute? = getAttributeDefinition(attrName)
        return if (attr == null) null else attr.getType()
    }

    override fun getAttributeDefinition(attrName: String?): com.google.devtools.build.lib.packages.Attribute? {
        return ruleClass.getAttributeByNameMaybe(attrName)
    }

    override fun isAttributeValueExplicitlySpecified(attributeName: String): Boolean {
        return rule.isAttributeValueExplicitlySpecified(attributeName)
    }

    override fun getPackageArgs(): PackageArgs? {
        return rule.getPackageArgs()
    }

    override fun visitAllLabels(consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>) {
        visitLabels(DependencyFilter.Companion.ALL_DEPS, consumer)
    }

    override fun visitLabels(attributeName: String?, consumer: java.util.function.Consumer<Label?>) {
        visitLabels(
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.packages.Attribute?>(
                ruleClass.getAttributeByName(
                    attributeName
                )
            ),
            DependencyFilter.Companion.ALL_DEPS,
            java.util.function.BiConsumer { attr: com.google.devtools.build.lib.packages.Attribute?, label: Label? ->
                consumer.accept(
                    label
                )
            })
    }

    override fun visitLabels(
        filter: DependencyFilter,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>
    ) {
        visitLabels(ruleClass.getAttributes(), filter, consumer)
    }

    private fun visitLabels(
        attributes: MutableList<com.google.devtools.build.lib.packages.Attribute>,
        filter: DependencyFilter,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>
    ) {
        val visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor =
            com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                if (label != null) {
                    consumer.accept(attribute, label)
                }
            }
        for (attribute in attributes) {
            val type: com.google.devtools.build.lib.packages.Type<*> = attribute.getType()
            // TODO(bazel-team): clean up the typing / visitation interface so we don't have to
            // special-case these types.
            if (type !== BuildType.OUTPUT && type !== BuildType.OUTPUT_LIST && type !== BuildType.NODEP_LABEL && type !== BuildType.NODEP_LABEL_LIST && filter.test(
                    rule,
                    attribute
                )
            ) {
                visitLabels(attribute, type, visitor)
            }
        }
    }

    /** Visits all labels reachable from the given attribute.  */
    open fun <T> visitLabels(
        attribute: com.google.devtools.build.lib.packages.Attribute,
        type: com.google.devtools.build.lib.packages.Type<T?>,
        visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor?
    ) {
        val value = get<T?>(attribute.getName(), type)
        if (value != null) { // null values are particularly possible for computed defaults.
            type.visitLabels(visitor, value, attribute)
        }
    }

    override fun isConfigurable(attributeName: String): Boolean {
        return com.google.devtools.build.lib.packages.AbstractAttributeMapper.Companion.isConfigurable(
            rule,
            attributeName
        )
    }

    /**
     * Returns a [SelectorList] for the given attribute if the attribute is configurable
     * for this rule, null otherwise.
     * 
     * @return a [SelectorList] if the attribute takes the form
     * "attrName = { 'a': value1_of_type_T, 'b': value2_of_type_T }") for this rule, null
     * if it takes the form "attrName = value_of_type_T", null if it doesn't exist
     * @throws IllegalArgumentException if the attribute is configurable but of the wrong type
     */
    fun <T> getSelectorList(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): BuildType.SelectorList<T?>? {
        return rule.getSelectorList<T?>(attributeName, type)
    }

    /**
     * Helper routine that just checks the given attribute has the given type for this rule and throws
     * an IllegalException if not.
     */
    fun checkType(attrName: String?, type: com.google.devtools.build.lib.packages.Type<*>?) {
        val index: Int = ruleClass.getAttributeIndex(attrName)
        requireNotNull(index) { "No such attribute " + attrName + " in " + ruleClass + " rule " + ruleLabel }
        val attr: com.google.devtools.build.lib.packages.Attribute = ruleClass.getAttribute(index)
        require(attr.getType() === type) {
            ("Attribute " + attrName + " is of type " + attr.getType() + " and not of type " + type
                    + " in " + ruleClass + " rule " + ruleLabel)
        }
    }


    override fun has(attrName: String?): Boolean {
        val attribute: com.google.devtools.build.lib.packages.Attribute? = ruleClass.getAttributeByNameMaybe(attrName)
        return attribute != null
    }

    override fun <T> has(attrName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): Boolean {
        return getAttributeType(attrName) === type
    }

    companion object {
        /**
         * Check if an attribute is configurable (uses select) or, if it's a computed default, if any of
         * its inputs are configurable.
         */
        fun isConfigurable(rule: RuleOrMacroInstance, attributeName: String): Boolean {
            return com.google.devtools.build.lib.packages.AbstractAttributeMapper.Companion.isConfigurable(
                rule,
                attributeName,  /* includeComputedDefaults= */
                true
            )
        }

        /**
         * Checks if an attribute is uses select. If `includeComputedDefaults` is true, also returns
         * true on computed defaults that have any configurable inputs.
         */
        fun isConfigurable(
            rule: RuleOrMacroInstance, attributeName: String, includeComputedDefaults: Boolean
        ): Boolean {
            val attr: Any? = rule.getAttr(attributeName)
            if (attr is ComputedDefault) {
                if (!includeComputedDefaults) {
                    return false
                }
                for (dep in attr.dependencies()) {
                    if (com.google.devtools.build.lib.packages.AbstractAttributeMapper.Companion.isConfigurable(
                            rule,
                            dep
                        )
                    ) {
                        return true
                    }
                }
                return false
            }
            val attrDef: com.google.devtools.build.lib.packages.Attribute? =
                rule.getAttributeProvider().getAttributeByNameMaybe(attributeName)
            return attrDef != null && rule.getSelectorList(attributeName, attrDef.getType()) != null
        }
    }
}
