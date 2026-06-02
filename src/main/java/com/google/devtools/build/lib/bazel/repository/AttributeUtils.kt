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
//
package com.google.devtools.build.lib.bazel.repository


import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException

/** Utilities related to processing attributes in external deps contexts.  */
object AttributeUtils {
    /**
     * Type-checks the given attribute values against a defined attribute schema, potentially
     * converting the values wherever necessary.
     * 
     * @param attrs With `attrIndices`, defines the attribute schema.
     * @param kwargs The supplied attribute values (keyed by the attribute names).
     * @param where A context string used in error messages to denote where this typechecking is
     * happening.
     * @param repoMappingWhere A context string used in error messages about invalid apparent repo
     * names, to denote where this repo mapping is anchored.
     * @return The type-checked and converted values, in the same order as `attrs`.
     */
    @Throws(ExternalDepsException::class)
    fun typeCheckAttrValues(
        attrs: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute>,
        attrIndices: com.google.common.collect.ImmutableMap<String?, Int?>,
        kwargs: MutableMap<String?, Any?>,
        labelConverter: LabelConverter?,
        errorCode: Code?,
        callStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?,
        where: String?,
        repoMappingWhere: String?
    ): com.google.common.collect.ImmutableList<Any?> {
        val attrValues = arrayOfNulls<Any>(attrs.size)
        for (attrValue in kwargs.entries) {
            if (attrValue.value == net.starlark.java.eval.Starlark.NONE) {
                continue
            }
            val attrIndex: Int? = attrIndices.get(attrValue.key)
            if (attrIndex == null) {
                throw ExternalDepsException.withCallStackAndMessage(
                    errorCode,
                    callStack,
                    "in %s, unknown attribute '%s' provided%s",
                    where,
                    attrValue.key,
                    net.starlark.java.spelling.SpellChecker.didYouMean(attrValue.key, attrIndices.keys)
                )
            }
            val attr: com.google.devtools.build.lib.packages.Attribute = attrs.get(attrIndex)
            val nativeValue: Any?
            try {
                nativeValue =
                    attr.getType()
                        .convert(
                            attrValue.value,
                            "attribute '%s'".formatted(attr.getPublicName()),
                            labelConverter
                        )
            } catch (e: ConversionException) {
                throw ExternalDepsException.withCallStackAndMessage(
                    errorCode, callStack, "in %s, %s", where, e.message
                )
            }

            // Check that the value is actually allowed.
            if (attr.checkAllowedValues() && !attr.getAllowedValues().apply(nativeValue)) {
                throw ExternalDepsException.withCallStackAndMessage(
                    errorCode,
                    callStack,
                    "in %s, the value for attribute '%s' %s",
                    where,
                    attr.getPublicName(),
                    attr.getAllowedValues().getErrorReason(nativeValue)
                )
            }

            attrValues[attrIndex] = com.google.devtools.build.lib.packages.Attribute.valueToStarlark(nativeValue)
        }

        // Check that all mandatory attributes have been specified, and fill in default values.
        // Along the way, verify that labels in the attribute values refer to visible repos only.
        for (i in attrValues.indices) {
            val attr: com.google.devtools.build.lib.packages.Attribute = attrs.get(i)
            if (attr.isMandatory() && attrValues[i] == null) {
                throw ExternalDepsException.withCallStackAndMessage(
                    errorCode,
                    callStack,
                    "in %s, mandatory attribute '%s' isn't being specified",
                    where,
                    attr.getPublicName()
                )
            }
            if (attrValues[i] == null) {
                attrValues[i] =
                    com.google.devtools.build.lib.packages.Attribute.valueToStarlark(attr.getDefaultValueUnchecked())
            }
            val maybeFirstNonVisibleLabel: java.util.Optional<com.google.devtools.build.lib.cmdline.Label> =
                nonVisibleLabelsIn(attrValues[i])
            if (maybeFirstNonVisibleLabel.isPresent()) {
                val firstNonVisibleLabel: com.google.devtools.build.lib.cmdline.Label = maybeFirstNonVisibleLabel.get()
                throw ExternalDepsException.withCallStackAndMessage(
                    errorCode,
                    callStack,
                    "in %s, no repository visible as '@%s' %s, but referenced by label '@%s//%s:%s'"
                            + " in attribute '%s'",
                    where,
                    firstNonVisibleLabel.getRepository().getName(),
                    repoMappingWhere,
                    firstNonVisibleLabel.getRepository().getName(),
                    firstNonVisibleLabel.getPackageFragment(),
                    firstNonVisibleLabel.getName(),
                    attr.getPublicName()
                )
            }
        }
        return com.google.common.collect.ImmutableList.copyOf<Any?>(attrValues)
    }

    private fun nonVisibleLabelsIn(nativeAttrValue: Any?): java.util.Optional<com.google.devtools.build.lib.cmdline.Label> {
        return when (nativeAttrValue) {
            -> java.util.Optional.of<com.google.devtools.build.lib.cmdline.Label?>(label)
            -> {
                for (item in list) {
                    val nonVisibleLabel: java.util.Optional<com.google.devtools.build.lib.cmdline.Label> =
                        nonVisibleLabelsIn(item)
                    if (nonVisibleLabel.isPresent()) {
                        nonVisibleLabel
                    }
                }
                java.util.Optional.empty<com.google.devtools.build.lib.cmdline.Label?>()
            }

            -> {
                for (keyOrValue in com.google.common.collect.Iterables.concat<Any?>(map.keySet(), map.values())) {
                    val nonVisibleLabel: java.util.Optional<com.google.devtools.build.lib.cmdline.Label> =
                        nonVisibleLabelsIn(keyOrValue)
                    if (nonVisibleLabel.isPresent()) {
                        nonVisibleLabel
                    }
                }
                java.util.Optional.empty<com.google.devtools.build.lib.cmdline.Label?>()
            }

            null -> java.util.Optional.empty<com.google.devtools.build.lib.cmdline.Label?>()
        }
    }
}
