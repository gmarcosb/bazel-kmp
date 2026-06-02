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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A [ConfiguredTargetValue] fetched from a remote source.
 * 
 * 
 * This doesn't contain actions, but contains enough information for dependents to perform
 * analysis. In particular, contains [TargetData], allowing the construction of [ ], containing everything needed by dependents of the [ ] in analysis.
 */
open class RemoteConfiguredTargetValue
private constructor(configuredTarget: ConfiguredTarget?, targetData: TargetData?) : ConfiguredTargetValue,
    DeserializedSkyValue {
    // Null after clearing.
    private var configuredTarget: ConfiguredTarget?

    // Null after clearing.
    private var targetData: TargetData?

    init {
        this.configuredTarget = configuredTarget
        this.targetData = targetData
    }

    public override fun getConfiguredTarget(): ConfiguredTarget? {
        return configuredTarget
    }

    val transitivePackages: NestedSet<Package.Metadata?>?
        get() = null

    public override fun clear(clearEverything: Boolean) {
        if (clearEverything) {
            configuredTarget = null
            targetData = null
        }
    }

    // Null after clearing everything.
    public override fun getTargetData(): TargetData? {
        return targetData
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("configuredTarget", configuredTarget)
            .add("targetData", targetData)
            .toString()
    }

    private class RemoteRuleConfiguredTargetValue(ruleConfiguredTarget: RuleConfiguredTarget, targetData: TargetData?) :
        RemoteConfiguredTargetValue(ruleConfiguredTarget, targetData), ActionLookupValue {
        private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

        init {
            this.actions = ruleConfiguredTarget.getActions()
        }

        public override fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? {
            return actions
        }
    }

    /**
     * Codec for [ConfiguredTargetValue]s.
     * 
     * 
     * This codec is crafted to serialize the minimal amount of data needed by its rdeps.
     * 
     * 
     * The serialized constituents are: the [ConfiguredTarget], followed by its (compact)
     * [TargetData], if it already exists. Otherwise, the [TargetData] will be constructed
     * from the [Target] in the [Package] dep.
     */
    @com.google.errorprone.annotations.Keep // Accessed reflectively.
    private class ConfiguredTargetValueCodec

        : DeferredObjectCodec<ConfiguredTargetValue?>() {
        override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<ConfiguredTargetValue?>
            get() = ConfiguredTargetValue::class.java

        override fun additionalEncodedClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out ConfiguredTargetValue?>?> {
            return com.google.common.collect.ImmutableSet.of<E?>(
                RuleConfiguredTargetValue::class.java,
                NonRuleConfiguredTargetValue::class.java,
                RemoteConfiguredTargetValue::class.java
            )
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: ConfiguredTargetValue, codedOut: CodedOutputStream?
        ) {
            val configuredTarget: ConfiguredTarget =
                checkNotNull(
                    obj.getConfiguredTarget(),
                    "tried to serialize a cleared ConfiguredTargetValue? %s",
                    obj
                )
            context.serialize(configuredTarget, codedOut)
            if (obj is RemoteConfiguredTargetValue) {
                context.serialize(obj.targetData, codedOut)
                return
            }

            // Looks up the Target and serializes it as TargetData.
            val label: Label = configuredTarget.getLabel()
            val pkgFunction: PrerequisitePackageFunction =
                context.getDependency<PrerequisitePackageFunction>(PrerequisitePackageFunction::class.java)
            val pkg: Package?
            try {
                pkg = pkgFunction.getExistingPackage(label.getPackageIdentifier())
            } catch (e: java.lang.InterruptedException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    ("serialization of ConfiguredTargetValue "
                            + configuredTarget.getLabel()
                            + " interrupted while looking up its package"),
                    e
                )
            }

            val target: Target
            try {
                target = pkg.getTarget(label.name)
            } catch (e: NoSuchTargetException) {
                throw java.lang.IllegalStateException(
                    "The target associated with " + configuredTarget + " was unexpectedly missing", e
                )
            }
            context.serialize(target.reduceForSerialization(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<RemoteConfiguredTargetValue?> {
            val value: DeserializationBuilder =
                com.google.devtools.build.lib.skyframe.RemoteConfiguredTargetValue.ConfiguredTargetValueCodec.DeserializationBuilder()
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                value,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.skyframe.RemoteConfiguredTargetValue.ConfiguredTargetValueCodec.DeserializationBuilder.Companion.setConfiguredTarget(
                        builder,
                        value
                    )
                })
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                value,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, value: Any? ->
                    com.google.devtools.build.lib.skyframe.RemoteConfiguredTargetValue.ConfiguredTargetValueCodec.DeserializationBuilder.Companion.setTargetData(
                        builder,
                        value
                    )
                })
            return value
        }

        private class DeserializationBuilder

            : DeferredValue<RemoteConfiguredTargetValue?> {
            private var configuredTarget: ConfiguredTarget? = null
            private var targetData: TargetData? = null

            override fun call(): RemoteConfiguredTargetValue {
                com.google.common.base.Preconditions.checkNotNull<Any?>(configuredTarget)
                com.google.common.base.Preconditions.checkNotNull<Any?>(targetData)
                return if (configuredTarget is RuleConfiguredTarget)
                    RemoteRuleConfiguredTargetValue(configuredTarget, targetData)
                else
                    RemoteConfiguredTargetValue(configuredTarget, targetData)
            }

            companion object {
                private fun setConfiguredTarget(builder: DeserializationBuilder, value: Any?) {
                    builder.configuredTarget = value as ConfiguredTarget?
                }

                private fun setTargetData(builder: DeserializationBuilder, value: Any?) {
                    builder.targetData = value as TargetData?
                }
            }
        }

        companion object {
            private val INSTANCE = ConfiguredTargetValueCodec()
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun codec(): ConfiguredTargetValueCodec {
            return ConfiguredTargetValueCodec.Companion.INSTANCE
        }
    }
}
