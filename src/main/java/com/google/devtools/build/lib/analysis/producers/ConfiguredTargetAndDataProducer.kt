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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Determines [ConfiguredTargetAndData] from [ConfiguredTargetKey].
 * 
 * 
 * The resulting package and configuration are based on the resulting [ConfiguredTarget]
 * and may be different from what is in the key, for example, if there is an alias.
 */
class ConfiguredTargetAndDataProducer
    (
    key: ConfiguredTargetKey?,
    transitionKeys: com.google.common.collect.ImmutableList<String?>?,
    transitiveState: TransitiveDependencyState,
    sink: ResultSink,
    outputIndex: Int,
    baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?
) : StateMachine, java.util.function.Consumer<SkyValue?>,
    ValueOrException3Sink<ConfiguredValueCreationException?, NoSuchThingException?, InconsistentNullConfigException?> {
    /** Interface for accepting values produced by this class.  */
    interface ResultSink {
        fun acceptConfiguredTargetAndData(value: ConfiguredTargetAndData?, index: Int)

        fun acceptConfiguredTargetAndDataError(error: ConfiguredValueCreationException?)

        fun acceptConfiguredTargetAndDataError(error: NoSuchThingException?)

        fun acceptConfiguredTargetAndDataError(error: InconsistentNullConfigException?)
    }

    // -------------------- Input --------------------
    private val key: ConfiguredTargetKey?
    private val transitionKeys: com.google.common.collect.ImmutableList<String?>?
    private val transitiveState: TransitiveDependencyState

    /**
     * Cache for [ConfiguredTargetValue] and [BuildConfigurationValue]
     * 
     * 
     * Check [AspectFunction.baseTargetPrerequisitesSupplier] for more details
     */
    private val baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?

    // -------------------- Output --------------------
    private val sink: ResultSink
    private val outputIndex: Int

    // -------------------- Internal State --------------------
    private var configuredTargetValue: ConfiguredTargetValue? = null

    // Null if the configured target key's configuration key is null.
    private var configurationValue: BuildConfigurationValue? = null
    private var pkg: com.google.devtools.build.lib.packages.Package? = null

    init {
        this.key = key
        this.transitionKeys = transitionKeys
        this.transitiveState = transitiveState
        this.sink = sink
        this.outputIndex = outputIndex
        this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier
    }

    @Throws(java.lang.InterruptedException::class)
    override fun step(tasks: StateMachine.Tasks): StateMachine {
        val cachedConfiguredTargetValue: ConfiguredTargetValue? =
            if (baseTargetPrerequisitesSupplier == null)
                null
            else
                baseTargetPrerequisitesSupplier.getPrerequisite(key)
        if (cachedConfiguredTargetValue != null) {
            acceptValue(cachedConfiguredTargetValue)
        } else {
            tasks.lookUp<E1?, E2?, E3?>(
                key,
                ConfiguredValueCreationException::class.java,
                NoSuchThingException::class.java,
                InconsistentNullConfigException::class.java,
                this as ValueOrException3Sink<ConfiguredValueCreationException?, NoSuchThingException?, InconsistentNullConfigException?>
            )
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.fetchConfigurationAndPackage(tasks) }
    }

    private fun acceptValue(configuredTargetValue: ConfiguredTargetValue) {
        this.configuredTargetValue = configuredTargetValue
        if (transitiveState.storeTransitivePackages()) {
            transitiveState.updateTransitivePackages(
                ConfiguredTargetKey.fromConfiguredTarget(configuredTargetValue.getConfiguredTarget()),
                configuredTargetValue.getTransitivePackages()
            )
        }
    }

    override fun acceptValueOrException3(
        value: SkyValue?,
        error: ConfiguredValueCreationException?,
        missingTargetError: NoSuchThingException?,
        visibilityError: InconsistentNullConfigException?
    ) {
        if (value != null) {
            acceptValue(value as ConfiguredTargetValue)
            return
        }
        if (error != null) {
            transitiveState.addTransitiveCauses(error.getRootCauses())
            sink.acceptConfiguredTargetAndDataError(error)
            return
        }
        if (missingTargetError != null) {
            sink.acceptConfiguredTargetAndDataError(missingTargetError)
            return
        }
        if (visibilityError != null) {
            sink.acceptConfiguredTargetAndDataError(visibilityError)
            return
        }
        throw java.lang.IllegalArgumentException("both value and error were null")
    }

    @Throws(java.lang.InterruptedException::class)
    private fun fetchConfigurationAndPackage(tasks: StateMachine.Tasks): StateMachine {
        if (configuredTargetValue == null) {
            return StateMachine.DONE // There was a previous error.
        }

        val configuredTarget: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuredTargetValue.getConfiguredTarget()
        val configurationKey: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuredTarget.getConfigurationKey()
        if (configurationKey != null) {
            this.configurationValue =
                if (baseTargetPrerequisitesSupplier == null)
                    null
                else
                    baseTargetPrerequisitesSupplier.getPrerequisiteConfiguration(configurationKey)
            if (configurationValue == null) {
                tasks.lookUp(configurationKey, this as java.util.function.Consumer<SkyValue?>?)
            }
        }

        if (configuredTargetValue is RemoteConfiguredTargetValue) {
            // Skips package lookup. The RemoteConfiguredTargetValue includes its own TargetData.
            return StateMachine { tasks: StateMachine.Tasks? -> this.constructResult(tasks) }
        }

        // An alternative to this is to optimistically fetch the package using the label of the
        // configured target key. However, the actual package may differ when this is an
        // AliasConfiguredTarget and would need to be refetched.
        val packageId: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            configuredTarget.getLabel().getPackageIdentifier()
        this.pkg = transitiveState.getDependencyPackage(packageId)
        if (pkg == null) {
            // In incremental builds, it is possible that the package won't be present in the cache. For
            // example, suppose that a configured target A has two children B and C. If B is dirty, it
            // causes A's re-evaluation, which causes this fetch to be performed for C. However, C has not
            // been evaluated this build.
            tasks.lookUp(packageId, this as java.util.function.Consumer<SkyValue?>)
        }

        return StateMachine { tasks: StateMachine.Tasks? -> this.constructResult(tasks) }
    }

    override fun accept(value: SkyValue?) {
        if (value is BuildConfigurationValue) {
            this.configurationValue = value
            return
        }
        if (value is PackageValue) {
            this.pkg = value.getPackage()
            return
        }
        throw java.lang.IllegalArgumentException("unexpected value: " + value)
    }

    private fun constructResult(tasks: StateMachine.Tasks?): StateMachine {
        val configuredTarget: ConfiguredTarget = configuredTargetValue.getConfiguredTarget()
        if (configuredTargetValue is RemoteConfiguredTargetValue) {
            sink.acceptConfiguredTargetAndData(
                ConfiguredTargetAndData(
                    configuredTarget, configuredTargetValue.getTargetData(), configurationValue, transitionKeys
                ),
                outputIndex
            )
            return StateMachine.DONE
        }

        val target: com.google.devtools.build.lib.packages.Target?
        try {
            target = pkg.getTarget(configuredTarget.getLabel().getName())
        } catch (e: NoSuchTargetException) {
            // The package was fetched based on the label of the configured target. Since the configured
            // target exists, it must have existed in the package when it was created.
            throw java.lang.IllegalStateException("Target already verified for " + configuredTarget, e)
        }
        sink.acceptConfiguredTargetAndData(
            ConfiguredTargetAndData(configuredTarget, target, configurationValue, transitionKeys),
            outputIndex
        )
        return StateMachine.DONE
    }
}
