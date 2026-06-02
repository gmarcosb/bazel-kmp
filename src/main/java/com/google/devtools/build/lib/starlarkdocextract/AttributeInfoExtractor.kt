// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.packages.Attribute

/** Starlark API documentation extractor for a rule, macro, or aspect attribute.  */
@com.google.common.annotations.VisibleForTesting
object AttributeInfoExtractor {
    @com.google.common.annotations.VisibleForTesting
    const val UNREPRESENTABLE_VALUE: String = "<unrepresentable value>"

    fun buildAttributeInfo(context: ExtractorContext, attribute: Attribute): AttributeInfo {
        val builder: AttributeInfo.Builder =
            AttributeInfo.newBuilder()
                .setName(StringEncoding.internalToUnicode(attribute.getPublicName()))
                .setType(getAttributeType(context, attribute.getType(), attribute.getPublicName()))
                .setMandatory(attribute.isMandatory())
        java.util.Optional.ofNullable<Any?>(attribute.doc)
            .map<U?>(java.util.function.Function { s: Any? -> StringEncoding.internalToUnicode(s) })
            .ifPresent(builder::setDocString)
        if (!attribute.isConfigurable()) {
            builder.setNonconfigurable(true)
        }
        if (!attribute.starlarkDefined()) {
            builder.setNativelyDefined(true)
        }
        for (providerGroup in attribute.getRequiredProviders().getStarlarkProviders()) {
            // TODO(b/290788853): it is meaningless to require a provider on an attribute of a
            // repository rule or of a module extension tag.
            builder.addProviderNameGroup(
                ProviderNameGroupExtractor.buildProviderNameGroup(context, providerGroup)
            )
        }

        if (!attribute.isMandatory()) {
            try {
                val defaultValue: Any? = Attribute.valueToStarlark(attribute.defaultValueUnchecked)
                builder.setDefaultValue(
                    StringEncoding.internalToUnicode(
                        context.labelRenderer.reprWithoutLabelConstructor(defaultValue)
                    )
                )
            } catch (e: net.starlark.java.eval.Starlark.InvalidStarlarkValueException) {
                builder.setDefaultValue(UNREPRESENTABLE_VALUE)
            }
        }
        if (attribute.getAllowedValues() is Attribute.AllowedValueSet) {
            for (value in allowedValueSet.getAllowedValues()) {
                try {
                    builder.addValues(
                        StringEncoding.internalToUnicode(
                            context.labelRenderer.reprWithoutLabelConstructor(value)
                        )
                    )
                } catch (e: net.starlark.java.eval.Starlark.InvalidStarlarkValueException) {
                    builder.addValues(UNREPRESENTABLE_VALUE)
                }
            }
        }
        return builder.build()
    }

    /**
     * Adds `implicitAttributeInfos`, followed by documentable attributes from `attributes`.
     */
    fun addDocumentableAttributes(
        context: ExtractorContext,
        implicitAttributeInfos: MutableMap<String?, AttributeInfo?>,
        attributes: Iterable<Attribute>,
        builder: java.util.function.Consumer<AttributeInfo?>
    ) {
        // Inject implicit attributes first.
        for (implicitAttributeInfo in implicitAttributeInfos.values()) {
            builder.accept(implicitAttributeInfo)
        }
        for (attribute in attributes) {
            if (implicitAttributeInfos.containsKey(attribute.name)) {
                continue
            }
            if ((attribute.starlarkDefined() || context.extractNativelyDefinedAttrs)
                && attribute.isDocumented()
                && ExtractorContext.Companion.isPublicName(attribute.getPublicName())
            ) {
                builder.accept(buildAttributeInfo(context, attribute))
            }
        }
    }

    fun getAttributeType(
        context: ExtractorContext?, type: Type<*>, attributePublicName: String
    ): AttributeType {
        if (type.equals(Type.INTEGER)) {
            return AttributeType.INT
        } else if (type.equals(BuildType.LABEL)
            || type.equals(BuildType.NODEP_LABEL)
            || type.equals(BuildType.GENQUERY_SCOPE_TYPE)
            || type.equals(BuildType.DORMANT_LABEL)
        ) {
            return AttributeType.LABEL
        } else if (type.equals(Type.STRING) || type.equals(Type.STRING_NO_INTERN)) {
            if (attributePublicName == "name") {
                return AttributeType.NAME
            } else {
                return AttributeType.STRING
            }
        } else if (type.equals(Types.STRING_LIST) || type.equals(Types.STRING_SET)) {
            // Since STRING_SET is not exposed to Starlark attr API, we can treat it as STRING_LIST.
            return AttributeType.STRING_LIST
        } else if (type.equals(Types.INTEGER_LIST)) {
            return AttributeType.INT_LIST
        } else if (type.equals(BuildType.LABEL_LIST)
            || type.equals(BuildType.NODEP_LABEL_LIST)
            || type.equals(BuildType.GENQUERY_SCOPE_TYPE_LIST)
            || type.equals(BuildType.DORMANT_LABEL_LIST)
        ) {
            return AttributeType.LABEL_LIST
        } else if (type.equals(Type.BOOLEAN)) {
            return AttributeType.BOOLEAN
        } else if (type.equals(BuildType.LABEL_KEYED_STRING_DICT)) {
            return AttributeType.LABEL_STRING_DICT
        } else if (type.equals(Types.STRING_DICT)) {
            return AttributeType.STRING_DICT
        } else if (type.equals(Types.STRING_LIST_DICT)) {
            return AttributeType.STRING_LIST_DICT
        } else if (type.equals(BuildType.LABEL_LIST_DICT)) {
            return AttributeType.LABEL_LIST_DICT
        } else if (type.equals(BuildType.LABEL_DICT_UNARY)) {
            return AttributeType.LABEL_DICT_UNARY
        } else if (type.equals(BuildType.OUTPUT)) {
            return AttributeType.OUTPUT
        } else if (type.equals(BuildType.OUTPUT_LIST)) {
            return AttributeType.OUTPUT_LIST
        } else if (type.equals(BuildType.LICENSE)) {
            // TODO(https://github.com/bazelbuild/bazel/issues/6420): deprecated, disabled in Bazel by
            // default, broken and with almost no remaining users, so we don't have an AttributeType for
            // it. Until this type is removed, following the example of legacy Stardoc, pretend it's a
            // list of strings.
            return AttributeType.STRING_LIST
        } else if (type.equals(BuildType.TRISTATE)) {
            // Given that the native TRISTATE type is not exposed to Starlark attr API, let's treat it as
            // an integer.
            return AttributeType.INT
        }

        return AttributeType.UNKNOWN
    }
}
