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

/** Common utilities for serializing [Attribute]s as protocol buffers.  */
object AttributeFormatter {
    private val depTypes: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.packages.Type<*>?> =
        com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.packages.Type<*>?>(
            com.google.devtools.build.lib.packages.Type.Companion.STRING,
            com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN,
            BuildType.LABEL,
            BuildType.OUTPUT,
            com.google.devtools.build.lib.packages.Types.STRING_LIST,
            BuildType.LABEL_LIST,
            BuildType.LABEL_DICT_UNARY,
            BuildType.LABEL_KEYED_STRING_DICT,
            BuildType.OUTPUT_LIST
        )

    private val noDepTypes: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.packages.Type<*>?> =
        com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.packages.Type<*>?>(
            BuildType.NODEP_LABEL_LIST,
            BuildType.NODEP_LABEL
        )

    /**
     * Convert attribute value to proto representation.
     * 
     * 
     * If `value` is null, only the `name`, `explicitlySpecified`, `nodep`
     * (if applicable), and `type` fields will be included in the proto message.
     * 
     * 
     * If {@param encodeBooleanAndTriStateAsIntegerAndString} is true then boolean and tristate
     * values are also encoded as integers and strings.
     */
    fun getAttributeProto(
        attr: com.google.devtools.build.lib.packages.Attribute,
        value: Any?,
        explicitlySpecified: Boolean,
        encodeBooleanAndTriStateAsIntegerAndString: Boolean
    ): Build.Attribute {
        return getAttributeProto(
            attr.getName(),
            attr.getType(),
            value,
            explicitlySpecified,
            encodeBooleanAndTriStateAsIntegerAndString,  /* sourceAspect= */
            null,  /* includeAttributeSourceAspects */
            false,
            LabelPrinter.Companion.legacy()
        )
    }

    fun getAttributeProto(
        attr: com.google.devtools.build.lib.packages.Attribute,
        value: Any?,
        explicitlySpecified: Boolean,
        encodeBooleanAndTriStateAsIntegerAndString: Boolean,
        sourceAspect: Aspect?,
        includeAttributeSourceAspects: Boolean,
        labelPrinter: LabelPrinter
    ): Build.Attribute {
        return getAttributeProto(
            attr.getName(),
            attr.getType(),
            value,
            explicitlySpecified,
            encodeBooleanAndTriStateAsIntegerAndString,
            sourceAspect,
            includeAttributeSourceAspects,
            labelPrinter
        )
    }

    private fun getAttributeProto(
        name: String?,
        type: com.google.devtools.build.lib.packages.Type<*>?,
        value: Any?,
        explicitlySpecified: Boolean,
        encodeBooleanAndTriStateAsIntegerAndString: Boolean,
        sourceAspect: Aspect?,
        includeAttributeSourceAspects: Boolean,
        labelPrinter: LabelPrinter
    ): Build.Attribute {
        val attrPb: Build.Attribute.Builder = Build.Attribute.newBuilder()
        attrPb.setName(StringEncoding.internalToUnicode(name))
        attrPb.setExplicitlySpecified(explicitlySpecified)
        maybeSetNoDep(type, attrPb)

        if (value is BuildType.SelectorList<*>) {
            attrPb.setType(Discriminator.SELECTOR_LIST)
            writeSelectorListToBuilder(attrPb, type, value as BuildType.SelectorList<*>, labelPrinter)
        } else {
            attrPb.setType(ProtoUtils.getDiscriminatorFromType(type))
            if (value != null) {
                val adapter =
                    AttributeBuilderAdapter(attrPb, encodeBooleanAndTriStateAsIntegerAndString)
                writeAttributeValueToBuilder(adapter, type, value, labelPrinter)
            }
        }

        if (includeAttributeSourceAspects) {
            attrPb.setSourceAspectName(
                if (sourceAspect != null) StringEncoding.internalToUnicode(
                    sourceAspect.getAspectClass().getName()
                ) else ""
            )
        }

        return attrPb.build()
    }

    private fun maybeSetNoDep(type: com.google.devtools.build.lib.packages.Type<*>?, attrPb: Build.Attribute.Builder) {
        if (depTypes.contains(type)) {
            attrPb.setNodep(false)
        } else if (noDepTypes.contains(type)) {
            attrPb.setNodep(true)
        }
    }

    private fun writeSelectorListToBuilder(
        attrPb: Build.Attribute.Builder,
        type: com.google.devtools.build.lib.packages.Type<*>?,
        selectorList: BuildType.SelectorList<*>,
        labelPrinter: LabelPrinter
    ) {
        val selectorListBuilder: Build.Attribute.SelectorList.Builder =
            Build.Attribute.SelectorList.newBuilder()
        selectorListBuilder.setType(ProtoUtils.getDiscriminatorFromType(type))
        for (selector in selectorList.getSelectors()) {
            val selectorBuilder: Build.Attribute.Selector.Builder =
                Build.Attribute.Selector.newBuilder()
                    .setNoMatchError(StringEncoding.internalToUnicode(selector.getNoMatchError()))
                    .setHasDefaultValue(selector.hasDefault())

            // Note that the order of entries returned by selector.getEntries is stable. The map's
            // entries' order is preserved from the fact that Starlark dictionary entry order is stable
            // (it's determined by insertion order).
            selector.forEach { condition: Label?, conditionValue: Any? ->
                val selectorEntryBuilder: SelectorEntry.Builder? =
                    SelectorEntry.newBuilder()
                        .setLabel(StringEncoding.internalToUnicode(labelPrinter.toString(condition)))
                        .setIsDefaultValue(!selector.isValueSet(condition))
                if (conditionValue != null) {
                    writeAttributeValueToBuilder(
                        SelectorEntryBuilderAdapter(selectorEntryBuilder),
                        type,
                        conditionValue,
                        labelPrinter
                    )
                }
                selectorBuilder.addEntries(selectorEntryBuilder)
            }
            selectorListBuilder.addElements(selectorBuilder)
        }
        attrPb.setSelectorList(selectorListBuilder)
    }

    /**
     * Set the appropriate type and value. Since string and string list store values for multiple
     * types, use the toString() method on the objects instead of casting them.
     */
    private fun writeAttributeValueToBuilder(
        builder: AttributeValueBuilderAdapter,
        type: com.google.devtools.build.lib.packages.Type<*>?,
        value: Any,
        labelPrinter: LabelPrinter
    ) {
        if (type === com.google.devtools.build.lib.packages.Type.Companion.INTEGER) {
            builder.setIntValue((value as net.starlark.java.eval.StarlarkInt).toIntUnchecked())
        } else if (type === com.google.devtools.build.lib.packages.Type.Companion.STRING || type === com.google.devtools.build.lib.packages.Type.Companion.STRING_NO_INTERN) {
            builder.setStringValue(StringEncoding.internalToUnicode(value.toString()))
        } else if (type === BuildType.LABEL || type === BuildType.NODEP_LABEL || type === BuildType.OUTPUT || type === BuildType.GENQUERY_SCOPE_TYPE || type === BuildType.DORMANT_LABEL) {
            builder.setStringValue(StringEncoding.internalToUnicode(labelPrinter.toString(value as Label?)))
        } else if (type === com.google.devtools.build.lib.packages.Types.STRING_LIST || type === com.google.devtools.build.lib.packages.Types.STRING_SET) {
            for (entry in (value as kotlin.collections.MutableCollection<*>?)!!) {
                builder.addStringListValue(StringEncoding.internalToUnicode(entry.toString()))
            }
        } else if (type === BuildType.LABEL_LIST || type === BuildType.NODEP_LABEL_LIST || type === BuildType.OUTPUT_LIST || type === BuildType.GENQUERY_SCOPE_TYPE_LIST || type === BuildType.DORMANT_LABEL_LIST) {
            for (entry in (value as kotlin.collections.MutableCollection<Label?>?)!!) {
                builder.addStringListValue(StringEncoding.internalToUnicode(labelPrinter.toString(entry)))
            }
        } else if (type === com.google.devtools.build.lib.packages.Types.INTEGER_LIST) {
            for (elem in (value as kotlin.collections.MutableCollection<*>?)!!) {
                builder.addIntListValue((elem as net.starlark.java.eval.StarlarkInt).toIntUnchecked())
            }
        } else if (type === com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN) {
            builder.setBooleanValue((value as Boolean?)!!)
        } else if (type === BuildType.TRISTATE) {
            builder.setTristateValue(triStateToProto(value as com.google.devtools.build.lib.packages.TriState?))
        } else if (type === BuildType.LICENSE) {
            val license: License = value as License
            val licensePb: Build.License.Builder = Build.License.newBuilder()
            for (licenseType in license.getLicenseTypes()) {
                licensePb.addLicenseType(StringEncoding.internalToUnicode(licenseType.toString()))
            }
            for (exception in license.getExceptions()) {
                licensePb.addException(StringEncoding.internalToUnicode(exception.toString()))
            }
            builder.setLicense(licensePb)
        } else if (type === com.google.devtools.build.lib.packages.Types.STRING_DICT) {
            val dict = value as MutableMap<String?, String?>
            for (keyValueList in dict.entrySet()) {
                val entry: StringDictEntry.Builder? =
                    StringDictEntry.newBuilder()
                        .setKey(StringEncoding.internalToUnicode(keyValueList.getKey()))
                        .setValue(StringEncoding.internalToUnicode(keyValueList.getValue()))
                builder.addStringDictValue(entry)
            }
        } else if (type === com.google.devtools.build.lib.packages.Types.STRING_LIST_DICT || type === BuildType.LABEL_LIST_DICT) {
            val dict = value as MutableMap<String?, MutableList<Any?>?>
            for (dictEntry in dict.entrySet()) {
                val entry: StringListDictEntry.Builder =
                    StringListDictEntry.newBuilder().setKey(StringEncoding.internalToUnicode(dictEntry.getKey()))
                for (dictEntryValue in dictEntry.getValue()) {
                    entry.addValue(StringEncoding.internalToUnicode(dictEntryValue.toString()))
                }
                builder.addStringListDictValue(entry)
            }
        } else if (type === BuildType.LABEL_DICT_UNARY) {
            val dict: MutableMap<String?, Label?> = value as MutableMap<String?, Label?>
            for (dictEntry in dict.entrySet()) {
                val entry: LabelDictUnaryEntry.Builder? =
                    LabelDictUnaryEntry.newBuilder()
                        .setKey(StringEncoding.internalToUnicode(dictEntry.getKey()))
                        .setValue(StringEncoding.internalToUnicode(labelPrinter.toString(dictEntry.getValue())))
                builder.addLabelDictUnaryValue(entry)
            }
        } else if (type === BuildType.LABEL_KEYED_STRING_DICT) {
            val dict: MutableMap<Label?, String?> = value as MutableMap<Label?, String?>
            for (dictEntry in dict.entrySet()) {
                val entry: LabelKeyedStringDictEntry.Builder? =
                    LabelKeyedStringDictEntry.newBuilder()
                        .setKey(StringEncoding.internalToUnicode(labelPrinter.toString(dictEntry.getKey())))
                        .setValue(StringEncoding.internalToUnicode(dictEntry.getValue()))
                builder.addLabelKeyedStringDictValue(entry)
            }
        } else {
            throw java.lang.AssertionError("Unknown type: " + type)
        }
    }

    private fun triStateToProto(value: com.google.devtools.build.lib.packages.TriState): Tristate? {
        return when (value) {
            com.google.devtools.build.lib.packages.TriState.AUTO -> Tristate.AUTO
            com.google.devtools.build.lib.packages.TriState.NO -> Tristate.NO
            com.google.devtools.build.lib.packages.TriState.YES -> Tristate.YES
        }
    }

    /**
     * An adapter used by [.writeAttributeValueToBuilder] in order to reuse the same code for
     * writing to both [Build.Attribute.Builder] and [SelectorEntry.Builder] objects.
     */
    private interface AttributeValueBuilderAdapter {
        fun addStringListValue(s: String?)

        fun addLabelDictUnaryValue(builder: LabelDictUnaryEntry.Builder?)

        fun addLabelKeyedStringDictValue(builder: LabelKeyedStringDictEntry.Builder?)

        fun addIntListValue(i: Int)

        fun addStringDictValue(builder: StringDictEntry.Builder?)

        fun addStringListDictValue(builder: StringListDictEntry.Builder?)

        fun setBooleanValue(b: Boolean)

        fun setIntValue(i: Int)

        fun setLicense(builder: Build.License.Builder?)

        fun setStringValue(s: String?)

        fun setTristateValue(tristate: Tristate?)
    }

    /**
     * An [AttributeValueBuilderAdapter] which writes to a [Build.Attribute.Builder].
     * 
     * 
     * If {@param encodeBooleanAndTriStateAsIntegerAndString} is `true`, then [Boolean]
     * and [TriState] attribute values also write to the integer and string fields. This offers
     * backwards compatibility to clients that expect attribute values of those types.
     */
    private class AttributeBuilderAdapter(
        attributeBuilder: Build.Attribute.Builder?,
        private val encodeBooleanAndTriStateAsIntegerAndString: Boolean
    ) : AttributeValueBuilderAdapter {
        private val attributeBuilder: Build.Attribute.Builder

        init {
            this.attributeBuilder =
                com.google.common.base.Preconditions.checkNotNull<Build.Attribute.Builder>(attributeBuilder)
        }

        override fun addStringListValue(s: String?) {
            attributeBuilder.addStringListValue(s)
        }

        override fun addLabelDictUnaryValue(builder: LabelDictUnaryEntry.Builder?) {
            attributeBuilder.addLabelDictUnaryValue(builder)
        }

        override fun addLabelKeyedStringDictValue(builder: LabelKeyedStringDictEntry.Builder?) {
            attributeBuilder.addLabelKeyedStringDictValue(builder)
        }

        override fun addIntListValue(i: Int) {
            attributeBuilder.addIntListValue(i)
        }

        override fun addStringDictValue(builder: StringDictEntry.Builder?) {
            attributeBuilder.addStringDictValue(builder)
        }

        override fun addStringListDictValue(builder: StringListDictEntry.Builder?) {
            attributeBuilder.addStringListDictValue(builder)
        }

        override fun setBooleanValue(b: Boolean) {
            if (b) {
                attributeBuilder.setBooleanValue(true)
                if (encodeBooleanAndTriStateAsIntegerAndString) {
                    attributeBuilder.setStringValue("true")
                    attributeBuilder.setIntValue(1)
                }
            } else {
                attributeBuilder.setBooleanValue(false)
                if (encodeBooleanAndTriStateAsIntegerAndString) {
                    attributeBuilder.setStringValue("false")
                    attributeBuilder.setIntValue(0)
                }
            }
        }

        override fun setIntValue(i: Int) {
            attributeBuilder.setIntValue(i)
        }

        override fun setLicense(builder: Build.License.Builder?) {
            attributeBuilder.setLicense(builder)
        }

        override fun setStringValue(s: String?) {
            attributeBuilder.setStringValue(s)
        }

        override fun setTristateValue(tristate: Tristate) {
            when (tristate) {
                AUTO -> {
                    attributeBuilder.setTristateValue(Tristate.AUTO)
                    if (encodeBooleanAndTriStateAsIntegerAndString) {
                        attributeBuilder.setIntValue(-1)
                        attributeBuilder.setStringValue("auto")
                    }
                }

                NO -> {
                    attributeBuilder.setTristateValue(Tristate.NO)
                    if (encodeBooleanAndTriStateAsIntegerAndString) {
                        attributeBuilder.setIntValue(0)
                        attributeBuilder.setStringValue("no")
                    }
                }

                YES -> {
                    attributeBuilder.setTristateValue(Tristate.YES)
                    if (encodeBooleanAndTriStateAsIntegerAndString) {
                        attributeBuilder.setIntValue(1)
                        attributeBuilder.setStringValue("yes")
                    }
                }
            }
        }
    }

    /**
     * An [AttributeValueBuilderAdapter] which writes to a [SelectorEntry.Builder].
     * 
     * 
     * Note that there is no `encodeBooleanAndTriStateAsIntegerAndString` parameter needed
     * here. This is because the clients that expect those alternate encodings of boolean and tristate
     * attribute values do not support [SelectorList] values. When providing output to those
     * clients, we compute the set of possible attribute values (expanding [SelectorList]
     * values, evaluating computed defaults, and flattening collections of collections; see [ ][com.google.devtools.build.lib.packages.AggregatingAttributeMapper.visitAttribute]).
     */
    private class SelectorEntryBuilderAdapter(selectorEntryBuilder: SelectorEntry.Builder?) :
        AttributeValueBuilderAdapter {
        private val selectorEntryBuilder: SelectorEntry.Builder

        init {
            this.selectorEntryBuilder =
                com.google.common.base.Preconditions.checkNotNull<SelectorEntry.Builder>(selectorEntryBuilder)
        }

        override fun addStringListValue(s: String?) {
            selectorEntryBuilder.addStringListValue(s)
        }

        override fun addLabelDictUnaryValue(builder: LabelDictUnaryEntry.Builder?) {
            selectorEntryBuilder.addLabelDictUnaryValue(builder)
        }

        override fun addLabelKeyedStringDictValue(builder: LabelKeyedStringDictEntry.Builder?) {
            selectorEntryBuilder.addLabelKeyedStringDictValue(builder)
        }

        override fun addIntListValue(i: Int) {
            selectorEntryBuilder.addIntListValue(i)
        }

        override fun addStringDictValue(builder: StringDictEntry.Builder?) {
            selectorEntryBuilder.addStringDictValue(builder)
        }

        override fun addStringListDictValue(builder: StringListDictEntry.Builder?) {
            selectorEntryBuilder.addStringListDictValue(builder)
        }

        override fun setBooleanValue(b: Boolean) {
            selectorEntryBuilder.setBooleanValue(b)
        }

        override fun setIntValue(i: Int) {
            selectorEntryBuilder.setIntValue(i)
        }

        override fun setLicense(builder: Build.License.Builder?) {
            selectorEntryBuilder.setLicense(builder)
        }

        override fun setStringValue(s: String?) {
            selectorEntryBuilder.setStringValue(s)
        }

        override fun setTristateValue(tristate: Tristate?) {
            selectorEntryBuilder.setTristateValue(tristate)
        }
    }
}
