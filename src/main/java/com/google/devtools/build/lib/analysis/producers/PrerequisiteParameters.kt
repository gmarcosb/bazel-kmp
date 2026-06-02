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

import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache

/** Common parameters for computing prerequisites.  */
class PrerequisiteParameters(
    configuredTargetKey: ConfiguredTargetKey,
    target: com.google.devtools.build.lib.packages.Target,
    aspects: Iterable<Aspect?>,
    loadExecAspectsKey: LoadAspectsKey?,
    starlarkTransitionProvider: StarlarkAttributeTransitionProvider?,
    transitionCache: StarlarkTransitionCache?,
    toolchainContexts: ToolchainCollection<ToolchainContext?>?,
    attributeMap: ConfiguredAttributeMapper?,
    transitiveState: TransitiveDependencyState?,
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
    baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?,
    baseTargetToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?
) {
    private val configuredTargetKey: ConfiguredTargetKey
    private val target: com.google.devtools.build.lib.packages.Target

    private val aspects: com.google.common.collect.ImmutableList<Aspect?>

    // This is the key for loading the aspects passed to the --exec_aspects flag, which get attached
    // to targets in the exec configuration.
    private val loadExecAspectsKey: LoadAspectsKey?
    private val starlarkTransitionProvider: StarlarkAttributeTransitionProvider?
    private val transitionCache: StarlarkTransitionCache?
    private val toolchainContexts: ToolchainCollection<ToolchainContext?>?

    private val attributeMap: ConfiguredAttributeMapper?
    private val transitiveState: TransitiveDependencyState?

    private val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?

    /**
     * Cache for [ConfiguredTargetValue] and [BuildConfigurationValue]
     * 
     * 
     * Check [AspectFunction.baseTargetPrerequisitesSupplier] for more details.
     */
    private val baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier?

    /**
     * The [UnloadedToolchainContext]s for the base target of the aspect under evaluation.
     * 
     * 
     * This is only non-null during aspect evaluation if the aspects path can propagate to
     * toolchains.
     */
    private val baseTargetToolchainContexts: ToolchainCollection<UnloadedToolchainContext?>?

    init {
        this.configuredTargetKey = configuredTargetKey
        this.target = target
        this.aspects = com.google.common.collect.ImmutableList.copyOf<Aspect?>(aspects)
        this.loadExecAspectsKey = loadExecAspectsKey
        this.starlarkTransitionProvider = starlarkTransitionProvider
        this.transitionCache = transitionCache
        this.toolchainContexts = toolchainContexts
        this.attributeMap = attributeMap
        this.transitiveState = transitiveState
        this.eventHandler = eventHandler
        this.baseTargetPrerequisitesSupplier = baseTargetPrerequisitesSupplier
        this.baseTargetToolchainContexts = baseTargetToolchainContexts
    }

    fun baseTargetToolchainContexts(): ToolchainCollection<UnloadedToolchainContext?>? {
        return baseTargetToolchainContexts
    }

    fun baseTargetPrerequisitesSupplier(): BaseTargetPrerequisitesSupplier? {
        return baseTargetPrerequisitesSupplier
    }

    fun label(): com.google.devtools.build.lib.cmdline.Label? {
        return configuredTargetKey.getLabel()
    }

    fun target(): com.google.devtools.build.lib.packages.Target {
        return target
    }

    fun associatedRule(): com.google.devtools.build.lib.packages.Rule? {
        return target.getAssociatedRule()
    }

    fun configurationKey(): BuildConfigurationKey? {
        return configuredTargetKey.getConfigurationKey()
    }

    fun aspects(): com.google.common.collect.ImmutableList<Aspect?> {
        return aspects
    }

    fun loadExecAspectsKey(): LoadAspectsKey? {
        return loadExecAspectsKey
    }

    fun starlarkTransitionProvider(): StarlarkAttributeTransitionProvider? {
        return starlarkTransitionProvider
    }

    fun transitionCache(): StarlarkTransitionCache? {
        return transitionCache
    }

    fun toolchainContexts(): ToolchainCollection<ToolchainContext?>? {
        return toolchainContexts
    }

    // Non-null for rules, and output files when there are aspects that apply to files.
    fun attributeMap(): ConfiguredAttributeMapper? {
        return attributeMap
    }

    fun location(): net.starlark.java.syntax.Location? {
        return target.getLocation()
    }

    fun eventId(): BuildEventId? {
        return BuildEventIdUtil.configurationId(configurationKey())
    }

    fun getExecutionPlatformLabel(
        execGroup: String?,
        isBaseTargetToolchain: Boolean
    ): com.google.devtools.build.lib.cmdline.Label? {
        val context: ToolchainCollection<*>? =
            if (isBaseTargetToolchain) baseTargetToolchainContexts else toolchainContexts

        val platform: com.google.devtools.build.lib.analysis.platform.PlatformInfo? =
            context.getToolchainContext(execGroup).executionPlatform()
        if (platform == null) {
            return null
        }
        return platform.label()
    }

    fun transitiveState(): TransitiveDependencyState? {
        return transitiveState
    }

    fun eventHandler(): com.google.devtools.build.lib.events.ExtendedEventHandler? {
        return eventHandler
    }
}
