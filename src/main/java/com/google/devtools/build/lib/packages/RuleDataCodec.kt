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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * A codec for [RuleData].
 * 
 * 
 * For native rules, serializes [RuleClassData] by name using a [RuleClassProvider]
 * dependency to look up the [RuleClass] on deserialization.
 * 
 * 
 * For Starlark rules, [RuleClassData] is reduced to [ ].
 */
internal class RuleDataCodec : DeferredObjectCodec<RuleData?>() {
    override fun getEncodedClass(): java.lang.Class<RuleData?> {
        return RuleData::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: RuleData, codedOut: CodedOutputStream) {
        // There are quite a few fields here that are often null or empty. Writing the mask makes it so
        // that nulls and empty sets take 0 additional storage.
        var presenceMask: Byte = 0
        val ruleClassData: RuleClassData = obj.getRuleClassData()
        if (ruleClassData.isStarlark()) {
            presenceMask = presenceMask.toInt() or RULE_CLASS_IS_STARLARK.toInt()
        }
        val ruleTags: com.google.common.collect.ImmutableSet<String?> = obj.getRuleTags()
        if (!ruleTags.isEmpty()) {
            presenceMask = presenceMask.toInt() or RULE_TAGS_MASK.toInt()
        }
        val deprecationWarning: String? = obj.getDeprecationWarning()
        if (deprecationWarning != null) {
            presenceMask = presenceMask.toInt() or DEPRECATION_WARNING_MASK.toInt()
        }
        if (obj.isTestOnly()) {
            presenceMask = presenceMask.toInt() or IS_TEST_ONLY_MASK.toInt()
        }
        val testTimeout: TestTimeout? = obj.getTestTimeout()
        if (testTimeout != null) {
            presenceMask = presenceMask.toInt() or TEST_TIMEOUT_MASK.toInt()
        }
        codedOut.writeRawByte(presenceMask)

        serializeRuleClassData(context, ruleClassData, codedOut)

        context.serialize(obj.getLocation(), codedOut)
        context.serialize(obj.getLabel(), codedOut)

        if (!ruleTags.isEmpty()) {
            context.serialize(ruleTags, codedOut)
        }
        if (deprecationWarning != null) {
            context.serialize(deprecationWarning, codedOut)
        }
        if (testTimeout != null) {
            context.serialize(testTimeout, codedOut)
        }
        context.serialize(obj.getOnlyTagsAttribute(), codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<RuleData?> {
        val presenceMask: Byte = codedIn.readRawByte()

        val builder: Builder
        if ((presenceMask.toInt() and RULE_CLASS_IS_STARLARK.toInt()) != 0) {
            val starlarkBuilder = StarlarkRuleClassBuilder(presenceMask)
            context.deserialize<StarlarkRuleClassBuilder?>(
                codedIn,
                starlarkBuilder,
                AsyncDeserializationContext.FieldSetter { builder: StarlarkRuleClassBuilder?, value: Any? ->
                    StarlarkRuleClassBuilder.Companion.setRuleClassData(
                        builder,
                        value
                    )
                })
            builder = starlarkBuilder
        } else {
            val nativeBuilder =
                NativeRuleClassBuilder(
                    presenceMask,
                    context.getDependency<RuleClassProvider?>(RuleClassProvider::class.java).getRuleClassMap()
                )
            context.deserialize<NativeRuleClassBuilder?>(
                codedIn,
                nativeBuilder,
                AsyncDeserializationContext.FieldSetter { builder: NativeRuleClassBuilder?, value: Any? ->
                    NativeRuleClassBuilder.Companion.setRuleClassName(
                        builder,
                        value
                    )
                })
            builder = nativeBuilder
        }

        context.deserialize<Builder?>(
            codedIn,
            builder,
            AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setLocation(
                    builder,
                    value
                )
            })
        context.deserialize<Builder?>(
            codedIn,
            builder,
            AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setLabel(
                    builder,
                    value
                )
            })

        if ((presenceMask.toInt() and RULE_TAGS_MASK.toInt()) != 0) {
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setRuleTags(
                        builder,
                        value
                    )
                })
        } else {
            builder.ruleTags = com.google.common.collect.ImmutableSet.of<String?>()
        }

        if ((presenceMask.toInt() and DEPRECATION_WARNING_MASK.toInt()) != 0) {
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setDeprecationWarning(
                        builder,
                        value
                    )
                })
        }

        if ((presenceMask.toInt() and TEST_TIMEOUT_MASK.toInt()) != 0) {
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setTestTimeout(
                        builder,
                        value
                    )
                })
        }

        context.deserialize<Builder?>(
            codedIn,
            builder,
            AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                com.google.devtools.build.lib.packages.RuleDataCodec.Builder.Companion.setOnlyTagsAttribute(
                    builder,
                    value
                )
            })

        return builder
    }

    /**
     * Builder for deserialized [RuleData].
     * 
     * 
     * This is abstract due to the differences in deserialization of [RuleClassData] for
     * Starlark and native.
     * 
     * 
     *  * The [NativeRuleClassBuilder] uses the [RuleClassProvider] serialization
     * dependency to deserialize a rule class from its name alone.
     *  * The [StarlarkRuleClassBuilder] directly deserializes the [       ] object.
     * 
     */
    private abstract class Builder(presenceMask: Byte) : DeferredValue<RuleData?> {
        private val presenceMask: Byte
        private var location: net.starlark.java.syntax.Location? = null
        private var label: Label? = null
        private var ruleTags: com.google.common.collect.ImmutableSet<String?>? = null
        private var deprecationWarning: String? = null
        private var testTimeout: TestTimeout? = null
        private var onlyTagsAttribute: com.google.common.collect.ImmutableList<String?>? = null

        init {
            this.presenceMask = presenceMask
        }

        override fun call(): RuleData {
            return RuleData(
                getRuleClassData(),
                location,
                ruleTags,
                label,
                deprecationWarning,
                (presenceMask.toInt() and IS_TEST_ONLY_MASK.toInt()) != 0,
                testTimeout,
                onlyTagsAttribute
            )
        }

        abstract fun getRuleClassData(): RuleClassData?

        companion object {
            private fun setLocation(builder: Builder, value: Any?) {
                builder.location = value as net.starlark.java.syntax.Location?
            }

            private fun setLabel(builder: Builder, value: Any?) {
                builder.label = value as Label?
            }

            private fun setRuleTags(builder: Builder, value: Any?) {
                builder.ruleTags = value as com.google.common.collect.ImmutableSet<String?>?
            }

            private fun setDeprecationWarning(builder: Builder, value: Any?) {
                builder.deprecationWarning = value as String?
            }

            private fun setTestTimeout(builder: Builder, value: Any?) {
                builder.testTimeout = value as TestTimeout?
            }

            // parameter type for deserialized object
            private fun setOnlyTagsAttribute(builder: Builder, value: Any?) {
                builder.onlyTagsAttribute = value as com.google.common.collect.ImmutableList<String?>?
            }
        }
    }

    private class NativeRuleClassBuilder(
        presenceMask: Byte,
        ruleClassMap: com.google.common.collect.ImmutableMap<String?, RuleClass?>
    ) : Builder(presenceMask) {
        private val ruleClassMap: com.google.common.collect.ImmutableMap<String?, RuleClass?>
        private var ruleClassName: String? = null

        init {
            this.ruleClassMap = ruleClassMap
        }

        override fun getRuleClassData(): RuleClassData? {
            return ruleClassMap.get(ruleClassName)
        }

        companion object {
            private fun setRuleClassName(builder: NativeRuleClassBuilder, value: Any?) {
                builder.ruleClassName = value as String?
            }
        }
    }

    private class StarlarkRuleClassBuilder(presenceMask: Byte) : Builder(presenceMask) {
        private var ruleClassData: StarlarkRuleClassData? = null

        override fun getRuleClassData(): StarlarkRuleClassData? {
            return ruleClassData
        }

        companion object {
            private fun setRuleClassData(builder: StarlarkRuleClassBuilder, value: Any?) {
                builder.ruleClassData = value as StarlarkRuleClassData?
            }
        }
    }

    // TODO(b/297857068): to reduce possible value aliasing (which could happen when an instance of
    // this class co-resides on the same JVM as the actual Starlark RuleClass instance), use a .bzl
    // Starlark reference instead.
    @AutoValue
    internal abstract class StarlarkRuleClassData : RuleClassData {
        override fun isStarlark(): Boolean {
            return true
        }
    }

    companion object {
        private const val RULE_CLASS_IS_STARLARK: Byte = 1
        private const val RULE_TAGS_MASK: Byte = 2
        private const val DEPRECATION_WARNING_MASK: Byte = 4
        private const val IS_TEST_ONLY_MASK: Byte = 8
        private const val TEST_TIMEOUT_MASK: Byte = 16

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        private fun serializeRuleClassData(
            context: SerializationContext, obj: RuleClassData, codedOut: CodedOutputStream?
        ) {
            if (!obj.isStarlark()) {
                context.serialize(obj.getName(), codedOut)
                return
            }

            // Handles the case of previously serialized Starlark rule data.
            if (obj is StarlarkRuleClassData) {
                context.serialize(obj, codedOut)
                return
            }

            // Serializes rule data for Starlark.
            context.serialize(
                AutoValue_RuleDataCodec_StarlarkRuleClassData(
                    obj.getName(),
                    obj.getTargetKind(),
                    obj.isDependencyResolutionRule(),
                    obj.isMaterializerRule(),
                    obj.materializerRuleAllowsRealDeps(),
                    obj.getAdvertisedProviders(),
                    obj.getRuleDefinitionEnvironmentLabel()
                ),
                codedOut
            )
        }
    }
}
