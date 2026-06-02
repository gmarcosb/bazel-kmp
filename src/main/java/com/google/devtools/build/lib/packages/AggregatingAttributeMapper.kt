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
 * [AttributeMap] implementation that provides the ability to retrieve *all possible*
 * values an attribute might take.
 */
class AggregatingAttributeMapper private constructor(rule: RuleOrMacroInstance) :
    com.google.devtools.build.lib.packages.AbstractAttributeMapper(rule) {
    /**
     * Returns all of this rule's attributes that are non-configurable. These are unconditionally
     * available to computed defaults no matter what dependencies they've declared.
     */
    private fun getNonConfigurableAttributes(): MutableList<String?>? {
        return rule.getAttributeProvider().getNonConfigurableAttributes()
    }

    /**
     * Override that also visits the rule's configurable attribute keys (which are themselves labels).
     * 
     * 
     * This method directly parses each selector, vs. calling [.visitAttribute] to iterate
     * over all possible values. The latter has dangerous efficiency consequences, as discussed in
     * [.visitAttribute]'s documentation. So we want to avoid that code path when possible.
     */
    override fun visitLabels(
        filter: DependencyFilter,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>
    ) {
        val visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor =
            com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                if (label != null) {
                    consumer.accept(attribute, label)
                }
            }
        visitLabels(filter, visitor)
    }

    override fun <T> visitLabels(
        attribute: com.google.devtools.build.lib.packages.Attribute,
        type: com.google.devtools.build.lib.packages.Type<T?>,
        visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor
    ) {
        visitLabels<T?>(
            visitor,
            attribute,
            type,  /*includeSelectKeys=*/
            true,
            ruleClass.getAttributeIndex(attribute.getName())
        )
    }

    /** See [.visitLabels].  */
    fun visitLabels(filter: DependencyFilter, visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor) {
        val attributes: MutableList<com.google.devtools.build.lib.packages.Attribute> = ruleClass.getAttributes()
        for (i in attributes.indices) {
            val attr: com.google.devtools.build.lib.packages.Attribute = attributes.get(i)
            val type: com.google.devtools.build.lib.packages.Type<*> = attr.getType()
            if (type !== BuildType.OUTPUT && type !== BuildType.OUTPUT_LIST && type !== BuildType.NODEP_LABEL && type !== BuildType.NODEP_LABEL_LIST && filter.test(
                    rule,
                    attr
                )
            ) {
                visitLabels(visitor, attr, type,  /* includeSelectKeys= */true, i)
            }
        }
    }

    private fun <T> visitLabels(
        visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor,
        attr: com.google.devtools.build.lib.packages.Attribute,
        type: com.google.devtools.build.lib.packages.Type<T?>,
        includeSelectKeys: Boolean,
        i: Int
    ) {
        var rawVal: Any?
        if (type.getLabelClass() == LabelClass.NONE) {
            // The only way for LabelClass.NONE to contain labels is in select keys.
            if (includeSelectKeys && attr.isConfigurable()) {
                rawVal = rule.getAttrIfStored(i)
                if (rawVal is BuildType.SelectorList<*>) {
                    visitLabelsInSelect<T?>(
                        rawVal as BuildType.SelectorList<T?>,
                        attr,
                        type,
                        visitor,
                        rule,  /* includeKeys= */
                        true,  /* includeValues= */
                        false
                    )
                }
            }
            return
        }
        rawVal = rule.getAttrIfStored(i)
        if (rawVal == null) {
            // Frozen rules don't store computed defaults.
            if (!attr.hasComputedDefault() || rule.isFrozen()) {
                rawVal = attr.getDefaultValue(rule)
            }
        }
        if (rawVal is BuildType.SelectorList<*>) {
            visitLabelsInSelect<T?>(
                rawVal as BuildType.SelectorList<T?>,
                attr,
                type,
                visitor,
                rule,  /* includeKeys= */
                includeSelectKeys,  /* includeValues= */
                true
            )
            return
        }
        if (rawVal is ComputedDefault) {
            // Computed defaults are a special pain: we have no choice but to iterate through their
            // (computed) values and look for labels.
            for (value in (rawVal as ComputedDefault).getPossibleValues<T?>(type, rule)) {
                if (value != null) {
                    type.visitLabels(visitor, value, attr)
                }
            }
            return
        }
        if (rawVal is LateBoundDefault<*, *>) {
            rawVal = (rawVal as LateBoundDefault<*, *>).getDefault(rule)
        } else if (rawVal is MaterializingDefault<*, *>) {
            rawVal = (rawVal as MaterializingDefault<*, *>).getDefault()
        }
        if (rawVal == null || ((rawVal is MutableCollection<*>) && rawVal.isEmpty())) {
            return
        }
        type.visitLabels(visitor, rawVal as T, attr)
    }

    /**
     * Returns all labels reachable via the given attribute, with duplicate instances removed.
     * 
     * 
     * Use this interface over [.visitAttribute] whenever possible, since the latter has
     * efficiency problems discussed in that method's documentation.
     * 
     * @param includeSelectKeys whether to include config_setting keys for configurable attributes
     */
    fun getReachableLabels(
        attributeName: String?,
        includeSelectKeys: Boolean
    ): com.google.common.collect.ImmutableSet<Label?> {
        val attributeIndex: Int = ruleClass.getAttributeIndex(attributeName)
        val attribute: com.google.devtools.build.lib.packages.Attribute = ruleClass.getAttribute(attributeIndex)
        val builder: com.google.common.collect.ImmutableSet.Builder<Label?> =
            com.google.common.collect.ImmutableSet.builder<Label?>()
        visitLabels(
            com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, attr: com.google.devtools.build.lib.packages.Attribute? ->
                builder.add(
                    label
                )
            },
            attribute,
            attribute.getType(),
            includeSelectKeys,
            attributeIndex
        )
        return builder.build()
    }

    /** Returns the labels that appear multiple times in the same attribute value.  */
    fun checkForDuplicateLabels(attribute: com.google.devtools.build.lib.packages.Attribute): MutableSet<Label?>? {
        val attrType: com.google.devtools.build.lib.packages.Type<*> = attribute.getType()
        if (attrType !== BuildType.LABEL_LIST && attrType !== BuildType.LABEL_LIST_DICT) {
            return com.google.common.collect.ImmutableSet.of<Label?>()
        }
        val attrName: String = attribute.getName()
        val rawVal: Any? = rule.getAttr(attrName, attribute.getType())

        if (attrType === BuildType.LABEL_LIST_DICT) {
            val duplicates: com.google.common.collect.ImmutableSet.Builder<Label?>? = null
            // For LABEL_LIST_DICT, independently check each value list for duplicates.
            if (rawVal !is BuildType.SelectorList<*>) {
                // Plain old attribute (no selects).
                val possibleDicts: MutableList<MutableMap<String?, MutableList<Label?>?>>
                visitRawNonConfigurableAttributeValue<MutableMap<String?, MutableList<Label?>?>?>(
                    rawVal,
                    attrName,
                    BuildType.LABEL_LIST_DICT
                )
                for (dict in possibleDicts) {
                    for (labels in dict.values()) {
                        duplicates = addDuplicateLabels(duplicates, labels)
                    }
                }
                return if (duplicates == null) com.google.common.collect.ImmutableSet.of<Label?>() else duplicates.build()
            }

            val selectors: MutableList<BuildType.Selector<MutableMap<String?, MutableList<Label?>?>?>> =
                (selectorList as BuildType.SelectorList<MutableMap<String?, MutableList<Label?>?>?>).getSelectors()

            for (selector in selectors) {
                for (dict in selector.valuesCopy()) {
                    for (labels in dict.values()) {
                        duplicates = addDuplicateLabels(duplicates, labels)
                    }
                }
            }
            return if (duplicates == null) com.google.common.collect.ImmutableSet.of<Label?>() else duplicates.build()
        }

        // Plain old attribute (no selects).
        if (rawVal !is BuildType.SelectorList<*>) {
            return Companion.checkForDuplicateLabels(
                visitRawNonConfigurableAttributeValue<MutableList<Label?>?>(rawVal, attrName, BuildType.LABEL_LIST)
            )
        }

        val selectors: MutableList<BuildType.Selector<MutableList<Label?>?>> =
            (rawVal as BuildType.SelectorList<MutableList<Label?>?>).getSelectors()

        // "attr = select({...})" with just a single select.
        if (selectors.size() == 1) {
            return Companion.checkForDuplicateLabels(selectors.get(0).valuesCopy())
        }

        // It's expensive to iterate over every possible permutation of values, so instead check for
        // duplicates within a single select branch. Then, after analysis we will check for duplicates
        // within only the used permutations.
        val duplicates: com.google.common.collect.ImmutableSet.Builder<Label?>? = null
        for (selector in selectors) {
            for (labelsInSelectorValue in selector.valuesCopy()) {
                // Duplicates within a single select branch are not okay.
                duplicates = addDuplicateLabels(duplicates, labelsInSelectorValue)
            }
        }

        return if (duplicates == null) com.google.common.collect.ImmutableSet.of<Label?>() else duplicates.build()
    }

    /**
     * If the attribute is a selector list of list type, then this method returns a list with number
     * of elements equal to the number of select statements in the selector list. Each element of this
     * list is equal to concatenating every possible attribute value in a single select statement.
     * The conditions themselves in the select statements are completely ignored. Returns `null`
     * if the attribute isn't of the desired format.
     * 
     * As an example, if we have select({a: ["a"], b: ["a", "b"]}) + select({a: ["c", "d"], c: ["e"])
     * The output will be [["a", "a", "b"], ["c", "d", "e"]]. The idea behind this structure is that
     * at least some of the structure in the original selector list is preserved and we know any
     * possible attribute value is the result of concatenating some sublist of each element.
     */
    fun <T> getConcatenatedSelectorListsOfListType(
        attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?
    ): Iterable<T?>? {
        val selectorList: BuildType.SelectorList<T?>? = getSelectorList<T?>(attributeName, type)
        if (selectorList != null && type is com.google.devtools.build.lib.packages.Type.ListType<*>) {
            val selectList: MutableList<T?> = java.util.ArrayList<T?>()

            for (selector in selectorList.getSelectors()) {
                val values: java.util.ArrayList<T?> =
                    com.google.common.collect.Lists.newArrayListWithCapacity<T?>(selector.getNumEntries())
                selector.forEach(SelectorEntryConsumer { label: Label?, value: T? -> values.add(value) })
                selectList.add(type.concat(values))
            }
            return com.google.common.collect.ImmutableList.copyOf<T?>(selectList)
        }
        return null
    }

    /**
     * Returns a list of all possible values an attribute can take for this rule.
     * 
     * 
     * If the attribute's value is a simple value, then this returns a singleton list of that
     * value.
     * 
     * 
     * If the attribute's value is an expression containing one or many `select(...)`
     * expressions, then this returns a list of all values that expression may evaluate to. This is
     * dangerous because it's easy to write attributes with an exponential number of possible values:
     * 
     * <pre>
     * foo = select({a: 1, b: 2} + select({c: 3, d: 4}) + select({e: 5, f: 6})
    </pre> * 
     * 
     * 
     * Possible values: `[135, 136, 145, 146, 235, 236, 245, 246]` (i.e. 2^3).
     * 
     * 
     * This is true not just for attributes with multiple selects, but also [ ]s depending on such attributes.
     * 
     * 
     * If the attribute does not have an explicit value for this rule, and the rule provides a
     * computed default, the computed default function is evaluated given the rule's other attribute
     * values as inputs and the output is returned in a singleton list.
     * 
     * 
     * If the attribute does not have an explicit value for this rule, and the rule provides a
     * computed default, and the computed default function depends on other attributes whose values
     * contain `select(...)` expressions, then the computed default function is evaluated for
     * every possible combination of input values, and the list of outputs is returned.
     * 
     * 
     * **EFFICIENCY WARNING:** Do not use this method unless you really need every single value
     * the attribute might take.
     * 
     * 
     * More often than not, calling code doesn't really need every value, but really just wants to
     * know, e.g., which labels might appear in a dependency list. For such cases, merging methods
     * like [.getReachableLabels] work just as well without the efficiency hit. Use those
     * whenever possible.
     */
    fun <T> visitAttribute(attributeName: String, type: com.google.devtools.build.lib.packages.Type<T?>): Iterable<T?> {
        return visitAttribute<T?>(attributeName, type,  /*mayTreatMultipleAsNone=*/false)
    }

    /**
     * Specialization of [.visitAttribute] for query output formatters which need
     * one attribute value or none at all. Should be used with the same care as its sibling method.
     * 
     * @param mayTreatMultipleAsNone signals if attribute-value computation **may** be aborted if
     * more than one possible value is encountered. This parameter is respected on a best-effort
     * basis - multiple values may still be returned if an unoptimized code path is visited.
     */
    fun <T> visitAttribute(
        attributeName: String, type: com.google.devtools.build.lib.packages.Type<T?>, mayTreatMultipleAsNone: Boolean
    ): Iterable<T?> {
        val rawVal: Any? = rule.getAttr<T?>(attributeName, type)

        // If this attribute value is configurable, visit all possible values.
        if (rawVal is BuildType.SelectorList<*>) {
            return getAllValues<T?>((rawVal as BuildType.SelectorList<T?>).getSelectors(), type, mayTreatMultipleAsNone)
        }

        return visitRawNonConfigurableAttributeValue<T?>(rawVal, attributeName, type)
    }

    private fun <T> visitRawNonConfigurableAttributeValue(
        rawVal: Any?, attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>
    ): MutableList<T?> {
        // If this attribute is a computed default, feed it all possible value combinations of
        // its declared dependencies and return all computed results. For example, if this default
        // uses attributes x and y, x can configurably be x1 or x2, and y can configurably be y1
        // or y1, then compute default values for the (x1,y1), (x1,y2), (x2,y1), and (x2,y2) cases.
        if (rawVal is ComputedDefault) {
            return (rawVal as ComputedDefault).getPossibleValues<T?>(type, rule)
        }

        if (attributeName == "visibility" && type == BuildType.NODEP_LABEL_LIST) {
            // This special case for the visibility attribute is needed because its value is replaced
            // with an empty list during package loading if it is public or private in order not to visit
            // the package called 'visibility'.
            return com.google.common.collect.ImmutableList.of<T?>(type.cast(rule.getVisibilityDeclaredLabels()))
        }

        // For any other attribute, just return its direct value.
        val value: T? = getFromRawAttributeValue<T?>(rawVal, attributeName, type)
        return if (value == null) com.google.common.collect.ImmutableList.of<T?>() else com.google.common.collect.ImmutableList.of<T?>(
            value
        )
    }

    /**
     * Given a list of attributes, creates an {attrName -> attrValue} map for every possible
     * combination of those attributes' values and returns a list of all the maps.
     * 
     * 
     * For example, given attributes x and y, which respectively have possible values x1, x2 and
     * y1, y2, this returns:
     * 
     * <pre>
     * [
     * {x: x1, y: y1},
     * {x: x1, y: y2},
     * {x: x2, y: y1},
     * {x: x2, y: y2}
     * ]
    </pre> * 
     * 
     * 
     * The work done by this method may be limited by providing a [ComputationLimiter] that
     * throws if too much work is attempted.
     */
    @Throws(ExceptionT::class)
    fun <ExceptionT : java.lang.Exception?> visitAttributes(
        attributes: MutableList<String>, limiter: ComputationLimiter<ExceptionT?>
    ): MutableList<MutableMap<String?, Any?>?> {
        val depMaps: MutableList<MutableMap<String?, Any?>?> = java.util.ArrayList<MutableMap<String?, Any?>?>()
        val combinationsSoFar: AtomicInteger = AtomicInteger(0)
        visitAttributesInner<ExceptionT?>(
            attributes,
            depMaps,
            com.google.common.collect.Maps.newHashMapWithExpectedSize<String?, Any?>(attributes.size()),
            combinationsSoFar,
            limiter
        )
        return depMaps
    }

    /**
     * A recursive function used in the implementation of [.visitAttributes].
     * 
     * @param attributes a list of attributes that are yet to be visited.
     * @param mappings a mutable list of {attrName --> attrValue} maps collected so far. This method
     * will add newly discovered maps to the list.
     * @param currentMap {attrName --> attrValue} assignments accumulated so far, not including those
     * in `attributes`. This map may be mutated and as such must be copied if we wish to
     * preserve its state, such as in the base case.
     * @param combinationsSoFar a counter for all previously processed combinations of possible
     * values.
     * @param limiter a strategy to limit the work done by invocations of this method.
     */
    @Throws(ExceptionT::class)
    private fun <ExceptionT : java.lang.Exception?> visitAttributesInner(
        attributes: MutableList<String>,
        mappings: MutableList<MutableMap<String?, Any?>?>,
        currentMap: MutableMap<String?, Any?>,
        combinationsSoFar: AtomicInteger,
        limiter: ComputationLimiter<ExceptionT?>
    ) {
        if (attributes.isEmpty()) {
            // Because this method uses exponential time/space on the number of inputs, we may limit
            // the total number of method calls.
            limiter.onComputationCount(combinationsSoFar.incrementAndGet())
            // Recursive base case: snapshot and store whatever's already been populated in currentMap.
            mappings.add(HashMap<String?, Any?>(currentMap))
            return
        }

        // Take the first attribute in the dependency list and iterate over all its values. For each
        // value x, update currentMap with the additional entry { firstAttrName: x }, then feed
        // this recursively into a subcall over all remaining dependencies. This recursively
        // continues until we run out of values.
        val currentAttribute = attributes.get(0)
        val firstAttributePossibleValues: Iterable<*> =
            visitAttribute(currentAttribute, getAttributeType(currentAttribute))
        val restOfAttrs = attributes.subList(1, attributes.size())
        for (value in firstAttributePossibleValues) {
            // Overwrite each time.
            currentMap.put(currentAttribute, value)
            visitAttributesInner<ExceptionT?>(restOfAttrs, mappings, currentMap, combinationsSoFar, limiter)
        }
    }

    /**
     * Returns an [AttributeMap] that delegates to `AggregatingAttributeMapper.this`
     * except for [.get] calls for attributes that are configurable. In that case, the [ ] looks up an attribute's value in `directMap`. Any attempt to [.get] a
     * configurable attribute that's not in `directMap` causes an [ ] to be thrown.
     */
    fun createMapBackedAttributeMap(directMap: MutableMap<String?, Any?>): com.google.devtools.build.lib.packages.AttributeMap {
        val owner = this
        return object : DelegatingAttributeMapper(owner) {
            override fun <T> get(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>): T? {
                owner.checkType(attributeName, type)
                if (getNonConfigurableAttributes()!!.contains(attributeName)) {
                    return owner.get<T?>(attributeName, type)
                }

                val `val` = directMap.get(attributeName)
                if (`val` == null) {
                    com.google.common.base.Preconditions.checkArgument(
                        directMap.containsKey(attributeName),
                        "attribute \"%s\" isn't available in this computed default context",
                        attributeName
                    )
                    return null
                }
                return type.cast(`val`)
            }

            override fun getAttributeNames(): com.google.common.collect.ImmutableList<String?> {
                val nonConfigurableAttributes = getNonConfigurableAttributes()
                return com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(
                    directMap.size() + nonConfigurableAttributes.size()
                )
                    .addAll(directMap.keySet())
                    .addAll(nonConfigurableAttributes)
                    .build()
            }
        }
    }

    /**
     * Helper class for [.getAllValues]. Represents a node in the logical DAG of combinations of
     * [Selector]s' values.
     */
    private class ConfigurableAttrVisitationNode<T>(
        /** Offset into the list of selectors being combined.  */
        private val offset: Int, boundKey: Label?, valueSoFar: T?
    ) {
        /** Key of the selector taken.  */
        private val boundKey: Label?

        /** Accumulated value through this node.  */
        private val valueSoFar: T?

        init {
            this.boundKey = boundKey
            this.valueSoFar = valueSoFar
        }
    }

    /**
     * Represents a path previously taken through a previous selector.
     * 
     * 
     * Used to short-circuit visitation when encountering selectors with *equivalent* key
     * sets. See uses for details. Note that this optimization is not safe for overlapping but
     * *different* keysets due to specialization (see [ConfiguredAttributeMapper]).
     */
    private class BoundKeyAndOffset(key: Label?, offset: Int) {
        /** Key chosen from associated select.  */
        private val key: Label?

        /**
         * Offset into the list of selectors where this key was bound. Used to determine when [ ][.key] is safe to follow through equivalent selects.
         */
        private val offset: Int

        init {
            this.key = key
            this.offset = offset
        }
    }

    companion object {
        fun of(rule: RuleOrMacroInstance): AggregatingAttributeMapper {
            return AggregatingAttributeMapper(rule)
        }

        /**
         * Applies `visitor` to the labels appearing in `selectorList`.
         * 
         * 
         * `attribute` and `type` give the context for interpreting `selectorList`.
         * 
         * 
         * If `rule` is not null, its value is used to interpret a possible late-bound default
         * specified by the attribute.
         * 
         * 
         * `includeKeys` and `includeValues` determine which parts of the select entries
         * are traversed.
         */
        fun <T> visitLabelsInSelect(
            selectorList: BuildType.SelectorList<T?>,
            attribute: com.google.devtools.build.lib.packages.Attribute,
            type: com.google.devtools.build.lib.packages.Type<T?>,
            visitor: com.google.devtools.build.lib.packages.Type.LabelVisitor,
            rule: RuleOrMacroInstance?,
            includeKeys: Boolean,
            includeValues: Boolean
        ) {
            val entryProcessor: SelectorEntryConsumer<T?>? =
                object : SelectorEntryConsumer<T?> {
                    var selector: BuildType.Selector<T?>? = null
                    var hasDefault: Boolean = false
                    var unconditional: Boolean = false

                    override fun accept(key: Label?, `val`: T?) {
                        if (includeKeys
                            && !unconditional && (!hasDefault || !com.google.devtools.build.lib.packages.BuildType.Selector.Companion.isDefaultConditionLabel(
                                key
                            ))
                        ) {
                            visitor.visit(key, attribute)
                        }
                        if (includeValues) {
                            val value =
                                if (selector.isValueSet(key)) `val` else type.cast(attribute.getDefaultValue(rule))
                            type.visitLabels(visitor, value, attribute)
                        }
                    }
                }

            val selectors: MutableList<BuildType.Selector<T?>> = selectorList.getSelectors()
            // Avoid iterator construction because of code hotness:
            for (i in selectors.indices) {
                val selector: BuildType.Selector<T?> = selectors.get(i)
                entryProcessor.selector = selector
                entryProcessor.hasDefault = selector.hasDefault()
                entryProcessor.unconditional = selector.isUnconditional()
                selector.forEach(entryProcessor)
            }
        }

        private fun checkForDuplicateLabels(possibleLabels: MutableCollection<MutableList<Label?>?>): MutableSet<Label?>? {
            return when (possibleLabels.size()) {
                0 -> com.google.common.collect.ImmutableSet.of<Label?>()
                1 -> {
                    val onlyPossibility: MutableList<Label?>? =
                        if (possibleLabels is MutableList<*>)
                            (possibleLabels as MutableList<MutableList<Label?>?>).get(0) // Avoid overhead of list iterator.
                        else
                            possibleLabels.iterator().next()
                    CollectionUtils.duplicatedElementsOf(onlyPossibility)
                }

                else -> {
                    var duplicates: com.google.common.collect.ImmutableSet.Builder<Label?>? = null
                    for (labels in possibleLabels) {
                        duplicates = addDuplicateLabels(duplicates, labels)
                    }
                    if (duplicates == null) com.google.common.collect.ImmutableSet.of<Label?>() else duplicates.build()
                }
            }
        }

        private fun addDuplicateLabels(
            builder: com.google.common.collect.ImmutableSet.Builder<Label?>?, labels: MutableList<Label?>?
        ): com.google.common.collect.ImmutableSet.Builder<Label?>? {
            var builder: com.google.common.collect.ImmutableSet.Builder<Label?>? = builder
            val duplicates: MutableSet<Label?> = CollectionUtils.duplicatedElementsOf(labels)
            if (duplicates.isEmpty()) {
                return builder
            }
            if (builder == null) {
                builder = com.google.common.collect.ImmutableSet.builder<Label?>()
            }
            return builder.addAll(duplicates)
        }

        /**
         * Determines all possible values a configurable attribute can take. Do not call this method
         * unless really necessary and avoid all new uses.
         */
        // TODO(bazel-team): minimize or eliminate uses of this interface. It necessarily grows
        // exponentially with the number of selects in the attribute. Is that always necessary?
        // For example, dependency resolution just needs to know every possible label an attribute
        // might reference, but it doesn't need to know the exact combination of labels that make
        // up a value. This may be even less important for non-label values (e.g. strings), which
        // have no impact on the dependency structure.
        private fun <T> getAllValues(
            selectors: MutableList<BuildType.Selector<T?>>,
            type: com.google.devtools.build.lib.packages.Type<T?>,
            mayTreatMultipleAsNone: Boolean
        ): com.google.common.collect.ImmutableList<T?> {
            if (selectors.isEmpty()) {
                return com.google.common.collect.ImmutableList.of<T?>()
            }

            if (selectors.size() == 1) {
                // Optimize for common case.
                val resultBuilder: com.google.common.collect.ImmutableList.Builder<T?> =
                    com.google.common.collect.ImmutableList.builder<T?>()
                selectors
                    .get(0)
                    .forEach(
                        SelectorEntryConsumer { key: Label?, value: T? ->
                            if (value != null) {
                                resultBuilder.add(value)
                            }
                        })
                return resultBuilder.build()
            }

            val selectorMaps: com.google.common.collect.ImmutableList<MutableMap<Label?, T?>> =
                selectors.stream()
                    .map<LinkedHashMap<Label?, T?>?>(java.util.function.Function { obj: BuildType.Selector<T?>? -> obj.mapCopy() })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<MutableMap<Label?, T?>?>())

            val nodes: Deque<ConfigurableAttrVisitationNode<T?>> = ArrayDeque<ConfigurableAttrVisitationNode<T?>>()
            // Track per selector key set when we started visiting a specific key.
            val boundKeysAndOffsets: MutableMap<MutableSet<Label?>?, BoundKeyAndOffset?> =
                HashMap<MutableSet<Label?>?, BoundKeyAndOffset?>()
            val result: com.google.common.collect.ImmutableList.Builder<T?> =
                com.google.common.collect.ImmutableList.builder<T?>()

            // Seed visitation.
            selectorMaps
                .get(0)
                .forEach(java.util.function.BiConsumer { key: Label?, value: T? ->
                    nodes.push(
                        ConfigurableAttrVisitationNode<T?>(0, key, value)
                    )
                })

            var foundResults = false
            while (!nodes.isEmpty()) {
                val node: ConfigurableAttrVisitationNode<T?> = nodes.pop()
                val nextOffset = node.offset + 1
                if (nextOffset >= selectors.size()) {
                    // Null values arise when a None is used as the value of a Selector for a type without a
                    // default value.
                    if (node.valueSoFar != null) {
                        if (foundResults && mayTreatMultipleAsNone) {
                            // Caller wanted one value or none at all, this is the second, so bail.
                            return com.google.common.collect.ImmutableList.of<T?>()
                        }
                        foundResults = true

                        // TODO(gregce): visitAttribute should probably convey that an unset attribute is
                        //  possible. Therefore we need to actually handle null values here.
                        result.add(node.valueSoFar)
                    }
                    continue
                }

                val nextSelectorEntries: MutableMap<Label?, T?> = selectorMaps.get(nextOffset)
                val boundKeyAndOffset = boundKeysAndOffsets.get(nextSelectorEntries.keySet())
                if (boundKeyAndOffset != null && boundKeyAndOffset.offset < node.offset) {
                    // We've seen this select key set before along this path and chosen this key.
                    nodes.push(
                        ConfigurableAttrVisitationNode<T?>(
                            nextOffset,
                            boundKeyAndOffset.key,
                            concat<T?>(type, node.valueSoFar, nextSelectorEntries.get(boundKeyAndOffset.key))
                        )
                    )
                    continue
                }

                val currentKeys: MutableSet<Label?> = selectorMaps.get(node.offset).keySet()
                // Record that we've descended along node.boundKey starting at this offset.
                boundKeysAndOffsets.put(currentKeys, BoundKeyAndOffset(node.boundKey, node.offset))

                if (currentKeys == nextSelectorEntries.keySet()) {
                    nodes.push(
                        ConfigurableAttrVisitationNode<T?>(
                            nextOffset,
                            node.boundKey,
                            concat<T?>(type, node.valueSoFar, nextSelectorEntries.get(node.boundKey))
                        )
                    )
                    continue
                }

                nextSelectorEntries.forEach(
                    java.util.function.BiConsumer { key: Label?, value: T? ->
                        nodes.push(
                            ConfigurableAttrVisitationNode<T?>(
                                nextOffset, key, concat<T?>(type, node.valueSoFar, value)
                            )
                        )
                    })
            }

            return result.build()
        }

        private fun <T> concat(type: com.google.devtools.build.lib.packages.Type<T?>, lhs: T?, rhs: T?): T? {
            return type.concat(com.google.common.collect.ImmutableList.of<T?>(lhs, rhs))
        }
    }
}
