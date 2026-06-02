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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * In simple form, a ([Label], [BuildConfigurationValue]) pair used to trigger immediate
 * dependency resolution and the rule analysis.
 * 
 * 
 * In practice, a ([Label] and post-transition [BuildConfigurationKey]) pair plus a
 * possible execution platform override [Label] with special constraints described as follows.
 * 
 * 
 * A build should not request keys with equal ([Label], [BuildConfigurationValue])
 * pairs but different execution platform override [Label] if the invoked rule will register
 * actions. (This is potentially OK if all outputs of all registered actions incorporate the
 * execution platform in their name unless the build also requests keys without an override that
 * happen to resolve to the same execution platform.) In practice, this issue has not been seen in
 * any 'real' builds; however, pathologically failure could lead to multiple (potentially different)
 * ConfiguredTarget that have the same ([Label], [BuildConfigurationValue]) pair.
 * 
 * 
 * Note that this key may be used to look up the generating action of an artifact.
 * 
 * 
 * TODO(blaze-configurability-team): Consider just using BuildOptions over a
 * BuildConfigurationKey.
 */
open class ConfiguredTargetKey private constructor(
    label: Label?,
    configurationKey: BuildConfigurationKey?,
    hashCode: Int
) : ActionLookupKey {
    private val label: Label?
    private val configurationKey: BuildConfigurationKey?
    private val hashCode: Int

    init {
        this.label = label
        this.configurationKey = configurationKey
        this.hashCode = hashCode
    }

    public override fun functionName(): SkyFunctionName {
        return SkyFunctions.CONFIGURED_TARGET
    }

    val skyKeyInterner: SkyKeyInterner<*>
        get() = interner

    public override fun getLabel(): Label? {
        return label
    }

    public override fun getConfigurationKey(): BuildConfigurationKey? {
        return configurationKey
    }

    open val executionPlatformLabel: Label?
        get() = null

    /**
     * True if the target's rule transition should be applied.
     * 
     * 
     * True by default but set false when a non-idempotent rule transition is detected. It prevents
     * over-application of such transitions.
     */
    open fun shouldApplyRuleTransition(): Boolean {
        return true
    }

    fun prettyPrint(): String? {
        if (getLabel() == null) {
            return "null"
        }
        return java.lang.String.format("%s (%s)", getLabel(), formatConfigurationKey(configurationKey))
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ConfiguredTargetKey) {
            return false
        }
        return hashCode == obj.hashCode && getLabel().equals(obj.getLabel())
                && configurationKey == obj.configurationKey
                && this.executionPlatformLabel == obj.executionPlatformLabel
                && shouldApplyRuleTransition() == obj.shouldApplyRuleTransition()
    }

    override fun toString(): String {
        // TODO(b/162809183): consider reverting to less verbose toString when bug is resolved.
        val helper: com.google.common.base.MoreObjects.ToStringHelper =
            com.google.common.base.MoreObjects.toStringHelper(this).add("label", getLabel())
                .add("config", configurationKey)
        if (this.executionPlatformLabel != null) {
            helper.add("executionPlatformLabel", this.executionPlatformLabel)
        }
        return helper.toString()
    }

    /**
     * Key indicating that no rule transition should be applied to the configuration.
     * 
     * 
     * NOTE: although it's true that no rule transition is applied when there is a null
     * configuration, this key type is used to handle a special edge case described below. It should
     * only be used with a non-null configuration.
     * 
     * 
     * When a non-noop rule transition occurs, it creates a new *delegation* [ ] with the resulting configuration. This is so if different starting
     * configurations result in the same configuration after transition, they converge on the same
     * key-value entry in Skyframe.
     * 
     * 
     * This can be problematic when transitions are not idempotent because evaluation of the
     * *delegate* repeats the transition, resulting in a another *delegate*. In cases of
     * non-convergent transitions, this may lead to infinite expansion.
     * 
     * 
     * To ensure that transitions are effectively only applied once, prior to delegation, the
     * [ConfiguredTargetFunction] applies the transition a second time to check it for
     * idempotency. It sets [ConfiguredTargetKey.shouldApplyRuleTransition] false when it is not
     * idempotent.
     */
    private class ConfiguredTargetKeyWithFinalConfiguration  // This is implemented using subtypes instead of adding a boolean field to `ConfiguredTargetKey`
    // to reduce memory cost.
        (label: Label?, configurationKey: BuildConfigurationKey?, hashCode: Int) : ConfiguredTargetKey(
        label,
        com.google.common.base.Preconditions.checkNotNull<BuildConfigurationKey?>(configurationKey),
        hashCode
    ) {
        override fun shouldApplyRuleTransition(): Boolean {
            return false
        }
    }

    private open class ToolchainDependencyConfiguredTargetKey(
        label: Label?,
        configurationKey: BuildConfigurationKey?,
        hashCode: Int,
        executionPlatformLabel: Label?
    ) : ConfiguredTargetKey(label, configurationKey, hashCode) {
        private val executionPlatformLabel: Label

        init {
            this.executionPlatformLabel =
                com.google.common.base.Preconditions.checkNotNull<Label>(executionPlatformLabel)
        }

        override fun getExecutionPlatformLabel(): Label {
            return executionPlatformLabel
        }
    }

    private class ToolchainDependencyConfiguredTargetKeyWithFinalConfiguration
        (
        label: Label?,
        configurationKey: BuildConfigurationKey?,
        hashCode: Int,
        executionPlatformLabel: Label?
    ) : ToolchainDependencyConfiguredTargetKey(
        label,
        com.google.common.base.Preconditions.checkNotNull<BuildConfigurationKey?>(configurationKey),
        hashCode,
        executionPlatformLabel
    ) {
        override fun shouldApplyRuleTransition(): Boolean {
            return false
        }
    }

    fun toBuilder(): Builder {
        return builder()
            .setConfigurationKey(configurationKey)
            .setLabel(getLabel())
            .setExecutionPlatformLabel(this.executionPlatformLabel)
            .setShouldApplyRuleTransition(shouldApplyRuleTransition())
    }

    /** A helper class to create instances of [ConfiguredTargetKey].  */
    class Builder
    private constructor() : DeferredValue<ConfiguredTargetKey?> {
        private var label: Label? = null
        private var configurationKey: BuildConfigurationKey? = null
        private var executionPlatformLabel: Label? = null
        private var shouldApplyRuleTransition = true

        /** Sets the label for the target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLabel(label: Label?): Builder {
            this.label = label
            return this
        }

        /** Sets the [BuildConfigurationValue] for the configured target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfiguration(buildConfiguration: BuildConfigurationValue?): Builder {
            return setConfigurationKey(if (buildConfiguration == null) null else buildConfiguration.getKey())
        }

        /** Sets the configuration key for the configured target.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConfigurationKey(configurationKey: BuildConfigurationKey?): Builder {
            this.configurationKey = configurationKey
            return this
        }

        /**
         * Sets the execution platform [Label] this configured target should use for toolchain
         * resolution. When present, this overrides the normally determined execution platform.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutionPlatformLabel(executionPlatformLabel: Label?): Builder {
            this.executionPlatformLabel = executionPlatformLabel
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShouldApplyRuleTransition(shouldApplyRuleTransition: Boolean): Builder {
            this.shouldApplyRuleTransition = shouldApplyRuleTransition
            return this
        }

        /** Builds a new [ConfiguredTargetKey] based on the supplied data.  */
        fun build(): ConfiguredTargetKey {
            val hashCode =
                computeHashCode(
                    label, configurationKey, executionPlatformLabel, shouldApplyRuleTransition
                )
            val newKey: ConfiguredTargetKey?
            if (executionPlatformLabel == null) {
                newKey =
                    if (shouldApplyRuleTransition)
                        ConfiguredTargetKey(label, configurationKey, hashCode)
                    else
                        ConfiguredTargetKeyWithFinalConfiguration(label, configurationKey, hashCode)
            } else {
                newKey =
                    if (shouldApplyRuleTransition)
                        ToolchainDependencyConfiguredTargetKey(
                            label, configurationKey, hashCode, executionPlatformLabel
                        )
                    else
                        ToolchainDependencyConfiguredTargetKeyWithFinalConfiguration(
                            label, configurationKey, hashCode, executionPlatformLabel
                        )
            }
            return interner.intern(newKey)
        }

        /** Implements the [DeferredObjectCodec.DeferredValue] used for deserialization.  */
        override fun call(): ConfiguredTargetKey {
            return build()
        }
    }

    private class ConfiguredTargetKeyValueSharingCodec

        : DeferredObjectCodec<ConfiguredTargetKey?>() {
        override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<ConfiguredTargetKey?>
            get() = ConfiguredTargetKey::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, key: ConfiguredTargetKey, codedOut: CodedOutputStream
        ) {
            val label: Label? = key.getLabel()
            val configurationKey: BuildConfigurationKey? = key.getConfigurationKey()
            val executionPlatformLabel: Label? = key.executionPlatformLabel
            // This is an int because Java converts bytes to ints when performing binary bitwise
            // operations, but it's really only a byte.
            val presenceMask =
                (((if (label != null) LABEL_MASK else 0.toByte())
                    .toInt()
                        or (if (configurationKey != null) CONFIGURATION_KEY_MASK else 0.toByte())
                    .toInt()
                        or (if (executionPlatformLabel != null) EXECUTION_PLATFORM_MASK else 0.toByte())
                    .toInt()
                        or (if (key.shouldApplyRuleTransition()) SHOULD_APPLY_RULE_TRANSITION_MASK else 0.toByte()).toInt()))
            codedOut.writeRawByte(presenceMask.toByte())

            if (label != null) {
                context.putSharedValue<T?>(label,  /* distinguisher= */null, Label.deferredCodec(), codedOut)
            }
            if (configurationKey != null) {
                context.putSharedValue<BuildConfigurationKey?>(
                    configurationKey,  /* distinguisher= */null, BuildConfigurationKey.Companion.codec(), codedOut
                )
            }
            if (executionPlatformLabel != null) {
                context.putSharedValue<T?>(
                    executionPlatformLabel,  /* distinguisher= */null, Label.deferredCodec(), codedOut
                )
            }
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<ConfiguredTargetKey?> {
            val presenceMask: Byte = codedIn.readRawByte()
            val builder = builder()
            if ((presenceMask.toInt() and LABEL_MASK.toInt()) != 0) {
                context.getSharedValue<T?>(
                    codedIn,  /* distinguisher= */
                    null,
                    Label.deferredCodec(),
                    builder,
                    AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                        ConfiguredTargetKeyCodec.Companion.setLabel(
                            builder,
                            value
                        )
                    })
            }
            if ((presenceMask.toInt() and CONFIGURATION_KEY_MASK.toInt()) != 0) {
                context.getSharedValue<Builder?>(
                    codedIn,  /* distinguisher= */
                    null,
                    BuildConfigurationKey.Companion.codec(),
                    builder,
                    AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                        ConfiguredTargetKeyCodec.Companion.setConfigurationKey(
                            builder,
                            value
                        )
                    })
            }
            if ((presenceMask.toInt() and EXECUTION_PLATFORM_MASK.toInt()) != 0) {
                context.getSharedValue<T?>(
                    codedIn,  /* distinguisher= */
                    null,
                    Label.deferredCodec(),
                    builder,
                    AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                        ConfiguredTargetKeyCodec.Companion.setExecutionPlatformLabel(
                            builder,
                            value
                        )
                    })
            }
            return builder.setShouldApplyRuleTransition(
                (presenceMask.toInt() and SHOULD_APPLY_RULE_TRANSITION_MASK.toInt()) != 0
            )
        }

        companion object {
            private val LABEL_MASK = 8.toByte()
            private val CONFIGURATION_KEY_MASK = 4.toByte()
            private val EXECUTION_PLATFORM_MASK = 2.toByte()
            private val SHOULD_APPLY_RULE_TRANSITION_MASK = 1.toByte()

            private val INSTANCE = ConfiguredTargetKeyValueSharingCodec()
        }
    }

    /** Codec for all [ConfiguredTargetKey] subtypes.  */
    @com.google.errorprone.annotations.Keep
    private class ConfiguredTargetKeyCodec : DeferredObjectCodec<ConfiguredTargetKey?>() {
        val encodedClass: java.lang.Class<ConfiguredTargetKey?>
            get() = ConfiguredTargetKey::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, key: ConfiguredTargetKey, codedOut: CodedOutputStream
        ) {
            context.serialize(key.getLabel(), codedOut)
            context.serialize(key.getConfigurationKey(), codedOut)
            context.serialize(key.executionPlatformLabel, codedOut)
            codedOut.writeBoolNoTag(key.shouldApplyRuleTransition())
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<ConfiguredTargetKey?> {
            val builder = builder()
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    Companion.setLabel(
                        builder!!,
                        value
                    )
                })
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    Companion.setConfigurationKey(
                        builder!!,
                        value
                    )
                })
            context.deserialize<Builder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: Builder?, value: Any? ->
                    Companion.setExecutionPlatformLabel(
                        builder!!,
                        value
                    )
                })
            return builder.setShouldApplyRuleTransition(codedIn.readBool())
        }

        companion object {
            private fun setLabel(builder: Builder, value: Any?) {
                builder.setLabel(value as Label?)
            }

            private fun setConfigurationKey(builder: Builder, value: Any?) {
                builder.setConfigurationKey(value as BuildConfigurationKey?)
            }

            private fun setExecutionPlatformLabel(builder: Builder, value: Any?) {
                builder.setExecutionPlatformLabel(value as Label?)
            }
        }
    }

    companion object {
        /**
         * Cache so that the number of ConfiguredTargetKey instances is `O(configured targets)` and
         * not `O(edges between configured targets)`.
         */
        private val interner: SkyKeyInterner<ConfiguredTargetKey?> = SkyKey.newInterner<ConfiguredTargetKey?>()

        val ORDERING: java.util.Comparator<ConfiguredTargetKey?>? =
            java.util.Comparator.comparing<T?, U?>(java.util.function.Function { obj: T? -> obj.getLabel() })
                .thenComparing<U?>(
                    java.util.function.Function { obj: T? -> obj.getExecutionPlatformLabel() },
                    java.util.Comparator.nullsFirst<T?>(java.util.Comparator.naturalOrder<T?>())
                )
                .thenComparing<U?>(
                    java.util.function.Function { obj: T? -> obj.getConfigurationKey() },
                    java.util.Comparator.nullsFirst<BuildConfigurationKey?>(
                        java.util.Comparator.comparing<BuildConfigurationKey?, String?>(
                            java.util.function.Function { obj: BuildConfigurationKey? -> obj.getOptionsChecksum() })
                    )
                )

        /** Returns a new [Builder] to create instances of [ConfiguredTargetKey].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.skyframe.ConfiguredTargetKey.Builder()
        }

        /** Returns the [ConfiguredTargetKey] that owns `configuredTarget`.  */
        fun fromConfiguredTarget(configuredTarget: CqueryNode): ConfiguredTargetKey? {
            // If configuredTarget is a MergedConfiguredTarget unwraps it first. MergedConfiguredTarget is
            // ephemeral and does not have a directly corresponding entry in Skyframe.
            //
            // The cast exists because the key passes through parts of analysis that work on both aspects
            // and configured targets. This process discards the key's specific type information.
            return configuredTarget.unwrapIfMerged().getLookupKey() as ConfiguredTargetKey?
        }

        private fun computeHashCode(
            label: Label?,
            configurationKey: BuildConfigurationKey?,
            executionPlatformLabel: Label?,
            shouldApplyRuleTransition: Boolean
        ): Int {
            var hashCode: Int = HashCodes.hashObjects(label, configurationKey, executionPlatformLabel)
            if (!shouldApplyRuleTransition) {
                hashCode = hashCode.inv()
            }
            return hashCode
        }

        private fun formatConfigurationKey(key: BuildConfigurationKey?): String? {
            if (key == null) {
                return "null"
            }
            return key.getOptions().checksum()
        }

        @kotlin.jvm.JvmStatic
        fun valueSharingCodec(): ConfiguredTargetKeyValueSharingCodec {
            return ConfiguredTargetKeyValueSharingCodec.Companion.INSTANCE
        }
    }
}
