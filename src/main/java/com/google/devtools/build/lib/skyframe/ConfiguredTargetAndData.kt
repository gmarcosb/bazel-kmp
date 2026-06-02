// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * A container class for a [ConfiguredTarget] and associated data, [Target], [ ], and transition keys. In the future, [ConfiguredTarget] objects
 * will no longer contain their associated [BuildConfigurationValue]. Consumers that need the
 * [Target] or [BuildConfigurationValue] must therefore have access to one of these
 * objects.
 * 
 * 
 * These objects are intended to be short-lived, never stored in Skyframe, since they pair three
 * heavyweight objects, a [ConfiguredTarget], a [Target] (which holds a [ ]), and a [BuildConfigurationValue].
 */
class ConfiguredTargetAndData private constructor(
    configuredTarget: ConfiguredTarget,
    target: TargetData,
    configuration: BuildConfigurationValue?,
    transitionKeys: com.google.common.collect.ImmutableList<String?>,
    checkConsistency: Boolean
) {
    private val configuredTarget: ConfiguredTarget

    // TODO(b/297857068): Remove transient, serializing this by creating a projection of the
    // underlying target.
    /** A [Target] when locally derived but a lightweight projection when fetched remotely.  */
    @Transient
    private val target: TargetData

    // Null iff configuredTarget's configuration key is null.
    private val configuration: BuildConfigurationValue?
    private val transitionKeys: com.google.common.collect.ImmutableList<String?>

    @com.google.common.annotations.VisibleForTesting
    constructor(
        configuredTarget: ConfiguredTarget,
        target: TargetData,
        configuration: BuildConfigurationValue?,
        transitionKeys: com.google.common.collect.ImmutableList<String?>
    ) : this(configuredTarget, target, configuration, transitionKeys,  /*checkConsistency=*/true)

    init {
        this.configuredTarget = configuredTarget
        this.target = target
        this.configuration = configuration
        this.transitionKeys = transitionKeys
        if (!checkConsistency) {
            return
        }
        checkState(
            configuredTarget.getLabel().equals(target.getLabel()),
            "Unable to construct ConfiguredTargetAndData:"
                    + " ConfiguredTarget's label %s is not equal to Target's label %s",
            configuredTarget.getLabel(),
            target.getLabel()
        )
        val innerConfigurationKey: BuildConfigurationKey? = configuredTarget.getConfigurationKey()
        if (configuration == null) {
            com.google.common.base.Preconditions.checkState(
                innerConfigurationKey == null,
                "Non-null configuration key for %s but configuration is null (%s)",
                configuredTarget,
                target
            )
        } else {
            checkState(
                innerConfigurationKey.getOptions().equals(configuration.getOptions()),
                "Configurations don't match: %s %s (%s %s)",
                innerConfigurationKey,
                configuration,
                configuredTarget,
                target
            )
        }
    }

    /**
     * For use with `MergedConfiguredTarget` and similar, where we create a virtual [ ] corresponding to the same [Target].
     */
    fun fromConfiguredTarget(maybeNew: ConfiguredTarget): ConfiguredTargetAndData {
        if (configuredTarget.equals(maybeNew)) {
            return this
        }
        return ConfiguredTargetAndData(maybeNew, target, configuration, transitionKeys)
    }

    /**
     * Variation of [.fromConfiguredTarget] that doesn't check the new target has the same
     * configuration as the original.
     * 
     * 
     * Intended for trimming (like `--trim_test_configuration`).
     */
    fun fromConfiguredTargetNoCheck(maybeNew: ConfiguredTarget): ConfiguredTargetAndData {
        if (configuredTarget.equals(maybeNew)) {
            return this
        }
        return ConfiguredTargetAndData(maybeNew, target, configuration, transitionKeys, false)
    }

    fun getConfiguration(): BuildConfigurationValue? {
        return configuration
    }

    val configurationKey: BuildConfigurationKey?
        get() = configuredTarget.getConfigurationKey()

    fun getConfiguredTarget(): ConfiguredTarget {
        return configuredTarget
    }

    fun getTransitionKeys(): com.google.common.collect.ImmutableList<String?> {
        return transitionKeys
    }

    val targetLabel: Label
        get() = target.getLabel()

    val location: net.starlark.java.syntax.Location
        get() = target.getLocation()

    val targetKind: String
        get() = target.getTargetKind()

    val isForDependencyResolution: Boolean
        get() = target.isForDependencyResolution()

    val ruleClass: String
        /** Returns the rule class name if the target is a rule and "" otherwise.  */
        get() = target.getRuleClass()

    val onlyTagsAttribute: com.google.common.collect.ImmutableList<String?>?
        /** Returns the rule tags attribute value if the target is a rule and null otherwise.  */
        get() = target.getOnlyTagsAttribute()

    val ruleTags: MutableSet<String?>
        /** Returns the rule tags if the target is a rule and an empty set otherwise.  */
        get() = target.getRuleTags()

    val isTargetRule: Boolean
        get() = target.isRule()

    val isTargetFile: Boolean
        get() = target.isFile()

    val isTargetInputFile: Boolean
        get() = target.isInputFile()

    val isTargetOutputFile: Boolean
        get() = target.isOutputFile()

    val generatingRuleLabel: Label?
        /** The generating rule's label if the target is an [OutputFile] otherwise null.  */
        get() = target.getGeneratingRuleLabel()

    val inputPath: com.google.devtools.build.lib.vfs.Path?
        /** The input file path if the target is an [InputFile] otherwise null.  */
        get() = target.getInputPath()

    val deprecationWarning: String?
        /** Any deprecation warning of the associated rule (maybe generating) otherwise null.  */
        get() = target.getDeprecationWarning()

    val isTestOnly: Boolean
        /** True if the target is a testonly rule or an output file generated by a testonly rule.  */
        get() = target.isTestOnly()

    val isMaterializerRule: Boolean
        /** True if the underlying target is a materializer rule.  */
        get() = target.isMaterializerRule()

    val targetAdvertisedProviders: AdvertisedProviderSet
        get() =// TODO(shahan): If this is an output file, refers to the providers of the generating rule.
        // However, in such cases, aspects are not permitted to have required providers. Consider
            // short-circuiting the logic for that case.
            target.getAdvertisedProviders()

    val testTimeout: TestTimeout?
        get() = target.getTestTimeout()

    val ruleDefinitionEnvironmentLabel: Label?
        // non-null if the target is a Starlark-defined rule
        get() = target.getRuleDefinitionEnvironmentLabel()

    fun copyWithClearedTransitionKeys(): ConfiguredTargetAndData? {
        if (transitionKeys.isEmpty()) {
            return this
        }
        return copyWithTransitionKeys(com.google.common.collect.ImmutableList.of<String?>())
    }

    fun copyWithTransitionKeys(keys: com.google.common.collect.ImmutableList<String?>): ConfiguredTargetAndData {
        return ConfiguredTargetAndData(configuredTarget, target, configuration, keys)
    }

    @get:com.google.common.annotations.VisibleForTesting
    val targetForTesting: Target?
        /**
         * This should only be used in testing.
         * 
         * 
         * Distributed implementations of prerequisites do not contain targets, but only a bare minimum
         * of fields needed by consumers.
         */
        get() = target as Target?

    @get:com.google.common.annotations.VisibleForTesting
    val attributeMapperForTesting: ConfiguredAttributeMapper
        get() = ConfiguredAttributeMapper.of(
            target as Rule?, configuredTarget.getConfigConditions(), configuration
        )

    override fun toString(): String {
        return "ConfiguredTargetAndData(target=%s, configuration=%s)"
            .formatted(configuredTarget.getLabel(), configuration)
    }

    private class SplitDependencyComparator

        : java.util.Comparator<ConfiguredTargetAndData?> {
        override fun compare(o1: ConfiguredTargetAndData, o2: ConfiguredTargetAndData): Int {
            val first: BuildConfigurationValue? = o1.getConfiguration()
            val second: BuildConfigurationValue? = o2.getConfiguration()
            val result: Int = first.getMnemonic().compareTo(second.getMnemonic())
            if (result != 0) {
                return result
            }
            return first.checksum().compareTo(second.checksum())
        }
    }

    companion object {
        /**
         * Orders split dependencies by configuration.
         * 
         * 
         * Requires non-null configurations.
         */
        @kotlin.jvm.JvmField
        val SPLIT_DEP_ORDERING: java.util.Comparator<ConfiguredTargetAndData?> = SplitDependencyComparator()

        /**
         * Wraps an existing [ConfiguredTarget] by looking up auxiliary data in Skyframe.
         * 
         * 
         * Assumes that for locally analyzed targets, since the given [ConfiguredTarget] is done,
         * then its associated [PackageValue] and [BuildConfigurationValue] (if applicable)
         * are done too.
         * 
         * 
         * For remotely cached targets, the given [ConfiguredTargetValue] is assumed to have a
         * projection of [TargetData] already, so the [PackageValue] lookup is not needed.
         */
        @Throws(java.lang.InterruptedException::class)
        fun fromExistingConfiguredTargetInSkyframe(
            ctv: ConfiguredTargetValue, env: SkyFunction.Environment
        ): ConfiguredTargetAndData {
            val ct: ConfiguredTarget = ctv.getConfiguredTarget()
            val packageKey: PackageIdentifier? = ct.getLabel().getPackageIdentifier()
            val configurationKeyMaybe: BuildConfigurationKey? = ct.getConfigurationKey()

            // Deserialized ConfiguredTargetValues already have a projection of TargetData.
            var targetData: TargetData? = ctv.getTargetData()
            var configuration: BuildConfigurationValue? = null

            val keysBuilder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
                com.google.common.collect.ImmutableSet.builder<SkyKey?>()
            if (targetData == null) {
                keysBuilder.add(packageKey)
            }
            if (configurationKeyMaybe != null) {
                keysBuilder.add(configurationKeyMaybe)
            }

            val keys: com.google.common.collect.ImmutableSet<SkyKey?> = keysBuilder.build()
            if (!keys.isEmpty()) {
                val lookupResult: SkyframeLookupResult = env.getValuesAndExceptions(keys)

                // Don't test env.valuesMissing(), because values may already be missing from the caller.
                if (targetData == null) {
                    val packageValue: PackageValue? = lookupResult.get(packageKey) as PackageValue?
                    com.google.common.base.Preconditions.checkNotNull<PackageValue?>(
                        packageValue,
                        "Missing package for %s (%s)",
                        ct,
                        packageKey
                    )
                    try {
                        targetData = packageValue.getPackage().getTarget(ct.getLabel().getName())
                    } catch (e: NoSuchTargetException) {
                        throw java.lang.IllegalStateException("Failed to retrieve target for " + ct, e)
                    }
                }

                if (configurationKeyMaybe != null) {
                    configuration = lookupResult.get(configurationKeyMaybe) as BuildConfigurationValue?
                    com.google.common.base.Preconditions.checkNotNull<Any?>(
                        configuration,
                        "Missing configuration for %s (%s)",
                        ct,
                        configurationKeyMaybe
                    )
                }
            }

            return ConfiguredTargetAndData(ct, targetData, configuration, null)
        }
    }
}
