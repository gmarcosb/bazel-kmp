// Copyright 2025 The Bazel Authors. All rights reserved.
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
 * Represents a rule or macro instance.
 * 
 * 
 * This encompasses the shared logic between [Rule] and [MacroInstance].
 */
abstract class RuleOrMacroInstance internal constructor(label: Label?, attrCount: Int) : AttributeInfoProvider {
    /**
     * For [Rule]s, the length of this instance's generator name if it is a prefix of its name,
     * otherwise zero. For [MacroInstance]s, always zero since they never have a generator name.
     * 
     * 
     * The generator name of a rule is the `name` parameter passed to a legacy macro that
     * instantiates the rule. Most rules instantiated via legacy macro follow this pattern:
     * 
     * <pre>`def some_macro(name):   some_rule(name = name + '_some_suffix') `</pre>
     * 
     * thus resulting in a generator name which is a prefix of the rule name. In such a case, we save
     * memory by storing the length of the generator name instead of the string. Note that this saves
     * memory from both the storage in [.attrValues] and the string itself (if it is not
     * otherwise retained). This optimization works because this field does not push the shallow heap
     * cost of [Rule] beyond an 8-byte threshold. If it did, this optimization would be a net
     * loss.
     */
    var generatorNamePrefixLength: Int = 0

    /**
     * Stores attribute values, taking on one of two shapes:
     * 
     * 
     *  1. While the rule or macro instance is mutable, the array length is equal to the number of
     * attributes. Each array slot holds the attribute value for the corresponding index or null
     * if not set.
     *  1. After [.freeze], the array is compacted to store only necessary values. Nulls and
     * values that match [Attribute.getDefaultValue] are omitted to save space. Ordering
     * of attributes by their index is preserved.
     * 
     */
    private var attrValues: Array<Any?>

    /**
     * Holds bits of metadata about attributes, taking on one of three shapes:
     * 
     * 
     *  1. While the rule or macro instance is mutable, contains one bit for each attribute
     * indicating whether it was explicitly set.
     *  1. After [.freeze] for rules or macros with fewer than 126 attributes (extremely
     * common case), contains one byte dedicated to each value in the compact representation of
     * [.attrValues], at corresponding array indices. The first bit indicates whether the
     * attribute was explicitly set. The remaining 7 bits represent the attribute's index (as
     * per [AttributeProvider.getAttributeIndex]). See [.freezeSmall].
     *  1. After [.freeze] for rules with 126 or more attributes (rare case), contains the
     * full set of bytes from the mutable representation, followed by the index of each
     * attribute stored in the compact representation of [.attrValues]. Because attribute
     * indices may require a full byte, there is no room to pack the explicit bit as we do for
     * the small case. See [.freezeLarge].
     * 
     */
    private var attrBytes: ByteArray

    var label: Label

    init {
        this.label = com.google.common.base.Preconditions.checkNotNull<Label>(label)
        this.attrValues = arrayOfNulls<Any>(attrCount)
        this.attrBytes = ByteArray(bitSetSize(attrCount))
    }

    /**
     * Returns true if the subset of this object's fields which are defined in this class equal those
     * of `other`. Intended for use by `equals()` implementations in subclasses.
     */
    protected fun equalsHelper(other: RuleOrMacroInstance): Boolean {
        return generatorNamePrefixLength == other.generatorNamePrefixLength && java.util.Arrays.equals(
            attrValues,
            other.attrValues
        )
                && java.util.Arrays.equals(attrBytes, other.attrBytes)
                && label == other.label
    }

    /**
     * Returns hash code of the subset of this object's fields which are defined in this class.
     * Intended for use by `hashCode()` implementations in subclasses.
     */
    protected fun hashCodeHelper(): Int {
        return (HashCodes.hashObjects(generatorNamePrefixLength, label)
                + HashCodes.MULTIPLIER
                * (java.util.Arrays.hashCode(attrValues) + HashCodes.MULTIPLIER * java.util.Arrays.hashCode(attrBytes)))
    }

    /**
     * Returns the label of the rule or macro instance for error messaging.
     * 
     * 
     * For symbolic macros, this may not be unique, because macros can create macros that create
     * macros.... that create a single target all with the same name.
     */
    // TODO: steinman - This should be the macro ID, not the label.
    fun getLabel(): Label {
        return label
    }

    /** Returns the [AttributeProvider] for this rule or macro's parent class.  */
    abstract fun getAttributeProvider(): com.google.devtools.build.lib.packages.AttributeProvider?

    /**
     * Returns the name part of the label of the target.
     * 
     * 
     * Equivalent to `getLabel().getName()`.
     */
    fun getName(): String {
        return label.name
    }

    /**
     * Returns an (unmodifiable, unordered) collection containing all the Attribute definitions for
     * this kind of rule or macro. (Note, this doesn't include the *values* of the attributes,
     * merely the schema. Call get[Type]Attr() methods to access the actual values.)
     */
    fun getAttributes(): MutableCollection<com.google.devtools.build.lib.packages.Attribute?>? {
        return getAttributeProvider().getAttributes()
    }

    /**
     * Returns true iff the [AttributeProvider] has an attribute with the given name and type.
     * 
     * 
     * Note: RuleContext also has isAttrDefined(), which takes Aspects into account. Whenever
     * possible, use RuleContext.isAttrDefined() instead of this method.
     */
    fun isAttrDefined(attrName: String?, type: com.google.devtools.build.lib.packages.Type<*>?): Boolean {
        return getAttributeProvider().hasAttr(attrName, type)
    }

    /**
     * Copies attribute values from the given rule or macro instance to this rule or macro instance.
     */
    fun copyAttributesFrom(ruleOrMacroInstance: RuleOrMacroInstance) {
        com.google.common.base.Preconditions.checkArgument(
            getAttributeProvider() == ruleOrMacroInstance.getAttributeProvider(),
            "Rule class mismatch: (this=%s, given=%s)",
            getAttributeProvider(),
            ruleOrMacroInstance.getAttributeProvider()
        )
        com.google.common.base.Preconditions.checkArgument(
            ruleOrMacroInstance.isFrozen(), "Not frozen: %s", ruleOrMacroInstance
        )
        com.google.common.base.Preconditions.checkState(!isFrozen(), "Already frozen: %s", this)
        this.attrValues = ruleOrMacroInstance.attrValues
        this.attrBytes = ruleOrMacroInstance.attrBytes
    }

    fun setAttributeValue(attribute: com.google.devtools.build.lib.packages.Attribute, value: Any?, explicit: Boolean) {
        com.google.common.base.Preconditions.checkState(!isFrozen(), "Already frozen: %s", this)
        val attrName: String = attribute.getName()
        if (attrName == NAME) {
            // Avoid unnecessarily storing the name in attrValues - it's stored in the label.
            return
        }
        if (attrName == GENERATOR_NAME) {
            val generatorName = value as String?
            if (getName().startsWith(generatorName)) {
                generatorNamePrefixLength = generatorName.length()
                return
            }
        }
        val attrIndex: Int = getAttributeProvider().getAttributeIndex(attrName)
        com.google.common.base.Preconditions.checkArgument(
            attrIndex != null,
            "Attribute %s is not valid for this %s",
            attrName,
            if (isRuleInstance()) "rule" else "macro"
        )
        if (explicit) {
            com.google.common.base.Preconditions.checkState(
                !getExplicitBit(attrIndex),
                "Attribute %s already explicitly set",
                attrName
            )
            setExplicitBit(attrIndex)
        }
        attrValues[attrIndex] = value
    }

    /**
     * Returns the value of the given attribute for this rule or macro. Returns null for invalid
     * attributes and default value if attribute was not set.
     * 
     * @param attrName the name of the attribute to lookup.
     */
    fun getAttr(attrName: String): Any? {
        if (attrName == NAME) {
            return getName()
        }
        val attrIndex: Int? = getAttributeProvider().getAttributeIndex(attrName)
        return if (attrIndex == null) null else getAttrWithIndex(attrIndex)
    }

    /**
     * Returns the value of the given attribute if it has the right type.
     * 
     * @throws IllegalArgumentException if the attribute does not have the expected type.
     */
    fun <T> getAttr(attrName: String, type: com.google.devtools.build.lib.packages.Type<T?>?): Any? {
        if (attrName == NAME) {
            checkAttrType(attrName, type, RuleClass.Companion.NAME_ATTRIBUTE)
            return getName()
        }

        val index: Int = getAttributeProvider().getAttributeIndex(attrName)
        requireNotNull(index) {
            ("No such attribute "
                    + attrName
                    + " in "
                    + getAttributeProvider()
                    + (if (isRuleInstance()) " rule " else " macro ")
                    + label)
        }
        checkAttrType(attrName, type, getAttributeProvider().getAttribute(index))
        return getAttrWithIndex(index)
    }

    /**
     * Returns the value of the attribute with the given index. Returns null, if no such attribute
     * exists OR no value was set.
     */
    open fun getAttrWithIndex(attrIndex: Int): Any? {
        val value = getAttrIfStored(attrIndex)
        if (value != null) {
            return value
        }
        val attr: com.google.devtools.build.lib.packages.Attribute = getAttributeProvider().getAttribute(attrIndex)
        return attr.getDefaultValueUnchecked()
    }

    /**
     * Returns the attribute value at the specified index if stored in this rule or macro, otherwise
     * `null`.
     * 
     * 
     * Unlike [.getAttr], does not fall back to the default value.
     */
    fun getAttrIfStored(attrIndex: Int): Any? {
        val attrCount: Int = getAttributeProvider().getAttributeCount()
        com.google.common.base.Preconditions.checkPositionIndex(attrIndex, attrCount - 1)
        return when (getAttrState()) {
            AttrState.MUTABLE -> attrValues[attrIndex]
            AttrState.FROZEN_SMALL -> {
                val index = binarySearchAttrBytes(0, attrIndex, 0x7f)
                if (index < 0) null else attrValues[index]
            }

            AttrState.FROZEN_LARGE -> {
                if (attrBytes.size == 0) {
                    null
                }
                val bitSetSize = bitSetSize(attrCount)
                val index = binarySearchAttrBytes(bitSetSize, attrIndex, 0xff)
                if (index < 0) null else attrValues[index - bitSetSize]
            }
        }
    }

    /**
     * Returns raw attribute values stored by this rule or macro.
     * 
     * 
     * The indices of attribute values in the returned list are not guaranteed to be consistent
     * with the other methods of this class. If this is important, which is generally the case, avoid
     * this method.
     * 
     * 
     * The returned iterable may contain null values. Its [Iterable.iterator] is
     * unmodifiable.
     */
    fun getRawAttrValues(): Iterable<Any?> {
        return Iterable { com.google.common.collect.Iterators.forArray<Any?>(*attrValues) }
    }

    /** See [.isAttributeValueExplicitlySpecified]  */
    override fun isAttributeValueExplicitlySpecified(attribute: com.google.devtools.build.lib.packages.Attribute): Boolean {
        return isAttributeValueExplicitlySpecified(attribute.getName())
    }

    /**
     * Returns true iff the value of the specified attribute is explicitly set in the BUILD file. This
     * returns true also if the value explicitly specified in the BUILD file is the same as the
     * attribute's default value. In addition, this method return false if the rule or macro has no
     * attribute with the given name.
     */
    fun isAttributeValueExplicitlySpecified(attrName: String): Boolean {
        if (attrName == NAME) {
            return true
        }
        if ((attrName == GENERATOR_FUNCTION
                    || attrName == GENERATOR_LOCATION
                    || attrName == GENERATOR_NAME)
            && isRuleInstance()
        ) {
            return isRuleCreatedInMacro()
        }
        val attrIndex: Int? = getAttributeProvider().getAttributeIndex(attrName)
        if (attrIndex == null) {
            return false
        }
        return when (getAttrState()) {
            AttrState.MUTABLE, AttrState.FROZEN_LARGE -> getExplicitBit(attrIndex)
            AttrState.FROZEN_SMALL -> {
                val index = binarySearchAttrBytes(0, attrIndex, 0x7f)
                index >= 0 && (attrBytes[index].toInt() and 0x80) != 0
            }
        }
    }

    /* Returns true iff this is a rule instance (v. macro). */
    abstract fun isRuleInstance(): Boolean

    /**
     * Returns whether this is a rule (v. macro) that was created by a legacy or symbolic macro.
     * Always false for macro instances; sometimes true for rules.
     */
    abstract fun isRuleCreatedInMacro(): Boolean

    /** Returns index into [.attrBytes] for `attrIndex`, or -1 if not found  */
    fun binarySearchAttrBytes(start: Int, attrIndex: Int, mask: Int): Int {
        // Binary search, treating values as unsigned bytes.
        var lo = start
        var hi = attrBytes.size - 1
        while (hi >= lo) {
            val mid = (lo + hi) / 2
            val midAttrIndex = attrBytes[mid].toInt() and mask
            if (midAttrIndex == attrIndex) {
                return mid
            } else if (midAttrIndex < attrIndex) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return -1
    }

    fun checkAttrType(
        attrName: String?,
        requestedType: com.google.devtools.build.lib.packages.Type<*>?,
        attr: com.google.devtools.build.lib.packages.Attribute
    ) {
        require(requestedType === attr.getType()) {
            ("Attribute "
                    + attrName
                    + " is of type "
                    + attr.getType()
                    + " and not of type "
                    + requestedType
                    + " in "
                    + getAttributeProvider()
                    + (if (isRuleInstance()) " rule " else " macro ")
                    + label)
        }
    }

    /**
     * Returns `true` if this rule or macro's attributes are immutable.
     * 
     * 
     * Frozen instances optimize for space by omitting storage for non-explicit attribute values
     * that match the [Attribute] default. If [.getAttrIfStored] returns `null`, the
     * value should be taken from either [Attribute.getLateBoundDefault] for late-bound defaults
     * or [Attribute.getDefaultValue] for all other attributes (including computed defaults).
     * 
     * 
     * Mutable instances have no such optimization. During rule creation, this allows for
     * distinguishing whether a computed default (which may depend on other unset attributes) is
     * available.
     */
    fun isFrozen(): Boolean {
        return getAttrState() != AttrState.MUTABLE
    }

    /** Makes this rule or macro's attributes immutable and compacts their representation.  */
    fun freeze() {
        if (isFrozen()) {
            return
        }

        val indicesToStore: BitSet = BitSet()
        for (i in attrValues.indices) {
            val value = attrValues[i]
            if (value == null) {
                continue
            }
            if (!getExplicitBit(i)) {
                val attr: com.google.devtools.build.lib.packages.Attribute = getAttributeProvider().getAttribute(i)
                if (value == attr.getDefaultValueUnchecked()) {
                    // Non-explicit value matches the attribute's default. Save space by omitting storage.
                    continue
                }
            }
            indicesToStore.set(i)
        }

        if (getAttributeProvider().getAttributeCount() < ATTR_SIZE_THRESHOLD) {
            freezeSmall(indicesToStore)
        } else {
            freezeLarge(indicesToStore)
        }
        // Sanity check to ensure mutable vs frozen is distinguishable.
        com.google.common.base.Preconditions.checkState(isFrozen(), "Freeze unsuccessful")
    }

    private fun freezeSmall(indicesToStore: BitSet) {
        val numToStore: Int = indicesToStore.cardinality()
        val compactValues = arrayOfNulls<Any>(numToStore)
        val compactBytes = ByteArray(numToStore)

        var attrIndex = 0
        for (i in 0..<numToStore) {
            attrIndex = indicesToStore.nextSetBit(attrIndex)
            var byteValue = (0x7f and attrIndex).toByte()
            if (getExplicitBit(attrIndex)) {
                byteValue = (byteValue.toInt() or 0x80).toByte()
            }
            compactBytes[i] = byteValue
            compactValues[i] = attrValues[attrIndex]
            attrIndex++
        }

        this.attrValues = compactValues
        this.attrBytes = compactBytes
    }

    private fun freezeLarge(indicesToStore: BitSet) {
        val numToStore: Int = indicesToStore.cardinality()
        val bitSetSize = attrBytes.size
        val compactValues = arrayOfNulls<Any>(numToStore)
        val compactBytes: ByteArray = java.util.Arrays.copyOf(attrBytes, bitSetSize + numToStore)

        var attrIndex = 0
        for (i in 0..<numToStore) {
            attrIndex = indicesToStore.nextSetBit(attrIndex)
            compactBytes[i + bitSetSize] = attrIndex.toByte()
            compactValues[i] = attrValues[attrIndex]
            attrIndex++
        }

        this.attrValues = compactValues
        this.attrBytes = compactBytes
    }

    internal enum class AttrState {
        MUTABLE,
        FROZEN_SMALL,
        FROZEN_LARGE
    }

    fun getAttrState(): AttrState {
        // This check works because the name attribute is never stored, so the compact representation
        // of attrValues will always have length < attr count.
        val attrCount: Int = getAttributeProvider().getAttributeCount()
        if (attrValues.size == attrCount) {
            return AttrState.MUTABLE
        }
        return if (attrCount < ATTR_SIZE_THRESHOLD) AttrState.FROZEN_SMALL else AttrState.FROZEN_LARGE
    }

    private fun getExplicitBit(attrIndex: Int): Boolean {
        val byteIndex = attrIndex / 8
        val bitIndex = attrIndex % 8
        val byteValue = attrBytes[byteIndex]
        return (byteValue.toInt() and (1 shl bitIndex)) != 0
    }

    private fun setExplicitBit(attrIndex: Int) {
        val byteIndex = attrIndex / 8
        val bitIndex = attrIndex % 8
        val byteValue = attrBytes[byteIndex]
        attrBytes[byteIndex] = (byteValue.toInt() or (1 shl bitIndex)).toByte()
    }

    /**
     * Returns a [BuildType.SelectorList] for the given attribute if the attribute is
     * configurable for this rule or macro, null otherwise.
     */
    fun <T> getSelectorList(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>?
    ): BuildType.SelectorList<T?>? {
        val index: Int? = getAttributeProvider().getAttributeIndex(attributeName)
        if (index == null) {
            return null
        }
        val attrValue = getAttrIfStored(index)
        if (attrValue !is BuildType.SelectorList<*>) {
            return null
        }
        require((attrValue as BuildType.SelectorList<*>).getOriginalType() === type) {
            ("Attribute "
                    + attributeName
                    + " is not of type "
                    + type
                    + " in "
                    + getAttributeProvider()
                    + " rule "
                    + label)
        }
        return attrValue as BuildType.SelectorList<T?>
    }

    /**
     * Retrieves the package's default visibility, or for certain rule classes, injects a different
     * default visibility.
     */
    abstract fun getDefaultVisibility(): RuleVisibility?

    /**
     * Implementation of [.getRawVisibility] that avoids constructing a `RuleVisibility`.
     */
    private fun getRawVisibilityLabels(): MutableList<Label?>? {
        val visibilityIndex: Int? = getAttributeProvider().getAttributeIndex("visibility")
        if (visibilityIndex == null) {
            return null
        }
        return getAttrIfStored(visibilityIndex) as MutableList<Label?>?
    }

    /**
     * Returns the declared labels of the visibility attribute, or the default visibility if the
     * attribute is not set.
     */
    fun getVisibilityDeclaredLabels(): MutableList<Label?>? {
        val rawLabels: MutableList<Label?>? = getRawVisibilityLabels()
        return if (rawLabels != null) rawLabels else getDefaultVisibility().getDeclaredLabels()
    }

    /** Returns the metadata of the package where this target or macro instance lives.  */
    abstract fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata?

    abstract fun getPackageDeclarations(): Declarations?

    /**
     * Returns the innermost symbolic macro that declared this target or macro instance, or null if it
     * was declared outside any symbolic macro (i.e. directly in a BUILD file or only in one or more
     * legacy macros).
     */
    abstract fun getDeclaringMacro(): MacroInstance?

    fun getPackageArgs(): PackageArgs? {
        return getPackageDeclarations().getPackageArgs()
    }

    abstract fun reportError(message: String?, eventHandler: EventHandler?)

    companion object {
        val NAME: String = RuleClass.Companion.NAME_ATTRIBUTE.getName()
        const val GENERATOR_NAME: String = "generator_name"

        const val GENERATOR_FUNCTION: String = "generator_function"
        const val GENERATOR_LOCATION: String = "generator_location"

        private const val ATTR_SIZE_THRESHOLD = 126

        /** Calculates the number of bytes necessary to have an explicit bit for each attribute.  */
        private fun bitSetSize(attrCount: Int): Int {
            // ceil(attrCount / 8)
            return (attrCount + 7) / 8
        }
    }
}
