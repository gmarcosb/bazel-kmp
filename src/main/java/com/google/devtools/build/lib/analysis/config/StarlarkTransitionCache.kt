// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.analysis.config.Scope.ScopeType
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition
import com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil
import com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue
import com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue.CustomExecScopeValue
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.StarlarkTransitionVisitor
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException

/**
 * Caches the application of transitions that use Starlark.
 * 
 * 
 * This trivially includes [StarlarkTransition]s. But it also includes transitions that
 * delegate to [StarlarkTransition]s, like some [ ] instances.
 * 
 * 
 * This cache was added to keep builds that heavily rely on Starlark transitions performant. The
 * inspiring build is a large Apple binary that heavily relies on `objc_library.bzl`, which
 * applies a self-transition. The build applies this transition ~600,000 times. Each application has
 * a cost, mostly from setup in translating Java objects to Starlark objects in [ ][com.google.devtools.build.lib.analysis.starlark.FunctionTransitionUtil.applyAndValidate]. This
 * cache saves most of that work, reducing analysis phase CPU time by 17%.
 */
class StarlarkTransitionCache {
    private var cache: com.github.benmanes.caffeine.cache.Cache<Key?, Value?> =
        Caffeine.newBuilder().softValues().build<Key?, Value?>()

    /**
     * Cache of the set of Starlark build settings referenced by a [ConfigurationTransition].
     * 
     * 
     * This is a separate cache because even if a transition value is evaluated, its Starlark build
     * settings are computed multiple times by [TransitionApplier].
     * 
     * 
     * Since `--flag_alias` is non-configurable, we can assume that during a single build, the set
     * of starlark build settings for a given transition won't change.
     */
    private var starlarkBuildSettingsCache: com.github.benmanes.caffeine.cache.Cache<ConfigurationTransition?, com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?> =
        Caffeine.newBuilder().softValues()
            .build<ConfigurationTransition?, com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>()

    /**
     * Given a [ConfigurationTransition], decompose (if possible) and find all referenced
     * Starlark build settings.
     * 
     * 
     * If a transition references a build setting via an alias, this set includes the alias' label
     * and *does not* include the actual label i.e. this method returns all referenced labels exactly
     * as they are.
     * 
     * 
     * If a flag alias (defined via --flag_alias) is used in the transition, include the starlark
     * flag mapped to this alias.
     */
    fun getAllStarlarkBuildSettings(
        root: ConfigurationTransition,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> {
        val cachedValue: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>? =
            starlarkBuildSettingsCache.getIfPresent(root)
        if (cachedValue != null) {
            return cachedValue
        }

        val keyBuilder: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?>()
        try {
            root.visit<TransitionException?>(
                StarlarkTransitionVisitor { transition: StarlarkTransition? ->
                    keyBuilder.addAll(
                        StarlarkTransition.getRelevantStarlarkSettingsFromTransition(
                            transition,
                            flagsAliases,
                            com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.Settings.INPUTS_AND_OUTPUTS
                        )
                    )
                } as StarlarkTransitionVisitor)
        } catch (e: TransitionException) {
            // Not actually thrown in the visitor, but declared.
        }

        val result: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            keyBuilder.build()
        starlarkBuildSettingsCache.put(root, result)
        return result
    }

    /** Adds the default values for a transition's input build settings to its input build options.  */
    private fun addDefaultStarlarkOptions(
        fromOptions: BuildOptions,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>?,
        transition: ConfigurationTransition,
        details: StarlarkBuildSettingsDetailsValue
    ): BuildOptions? {
        if (details.buildSettingToDefault.isEmpty() && details.customExecScopeValues.isEmpty()) {
            // No need to traverse the transition to find its Starlark flag inputs. There are none.
            return fromOptions
        }

        var optionsWithDefaults: BuildOptions.Builder? = null
        for (maybeAliasSetting in getAllStarlarkBuildSettings(transition, flagsAliases)) {
            // details will only have the defaults of the actual setting so must unalias
            val setting: com.google.devtools.build.lib.cmdline.Label? =
                details.aliasToActual.getOrDefault(maybeAliasSetting, maybeAliasSetting)
            if (!fromOptions.getStarlarkOptions().containsKey(maybeAliasSetting)) {
                if (optionsWithDefaults == null) {
                    optionsWithDefaults = fromOptions.toBuilder()
                }
                optionsWithDefaults.addStarlarkOption(
                    maybeAliasSetting, details.buildSettingToDefault.get(setting)
                )
            }
        }

        return getDefaultStarlarkOptionsForCustomExec(optionsWithDefaults, details, fromOptions)
    }

    /**
     * Applies a Starlark transition, possibly returning a cached result.
     * 
     * @param fromOptions source options before the transition
     * @param transition the transition itself
     * @param details information from packages about Starlark build settings needed by transition
     * @param eventHandler handler for errors evaluating the transition.
     * @return transition output
     */
    @Throws(TransitionException::class, java.lang.InterruptedException::class)
    fun computeIfAbsent(
        fromOptions: BuildOptions,
        transition: ConfigurationTransition,
        details: StarlarkBuildSettingsDetailsValue,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?
    ): MutableMap<String?, BuildOptions?>? {
        val cacheKey: Key =
            com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache.Key(transition, fromOptions, details)
        val cachedResult: Value? = cache.getIfPresent(cacheKey)
        if (cachedResult != null) {
            if (cachedResult.nonErrorEvents != null) {
                cachedResult.nonErrorEvents.replayOn(eventHandler)
            }
            return cachedResult.result
        }

        val flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>? =
            fromOptions.get(CoreOptions::class.java).getCommandLineFlagAliasesMap()

        // All code below here only executes on a cache miss and thus should rely only on values that
        // are part of the above cache key or constants that exist throughout the lifetime of the
        // Blaze server instance.
        val adjustedOptions: BuildOptions? =
            addDefaultStarlarkOptions(fromOptions, flagsAliases, transition, details)
        // TODO(bazel-team): Add safety-check that this never mutates fromOptions.
        val handlerWithErrorStatus: com.google.devtools.build.lib.events.StoredEventHandler =
            com.google.devtools.build.lib.events.StoredEventHandler()
        var result: MutableMap<String?, BuildOptions?>? =
            transition.apply(
                TransitionUtil.restrict(transition, adjustedOptions), handlerWithErrorStatus
            )

        // We use a temporary StoredEventHandler instead of the caller's event handler because
        // StarlarkTransition.validate assumes no errors occurred. We need a StoredEventHandler to be
        // able to check that, and fail out early if there are errors.
        //
        // TODO(bazel-team): harden StarlarkTransition.validate so we can eliminate this step.
        // StarlarkRuleTransitionProviderTest#testAliasedBuildSetting_outputReturnMismatch shows the
        // effect.
        handlerWithErrorStatus.replayOn(eventHandler)
        if (handlerWithErrorStatus.hasErrors()) {
            throw TransitionException("Errors encountered while applying Starlark transition")
        }
        result = StarlarkTransition.validate(transition, details, flagsAliases, result)
        // If the transition errored (like bad Starlark code), this method already exited with an
        // exception so the results won't go into the cache. We still want to collect non-error events
        // like print() output.
        val nonErrorEvents: com.google.devtools.build.lib.events.StoredEventHandler? =
            if (!handlerWithErrorStatus.isEmpty()) handlerWithErrorStatus else null
        cache.put(
            cacheKey,
            com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache.Value(result, nonErrorEvents)
        )
        return result
    }

    fun clear() {
        cache = Caffeine.newBuilder().softValues().build<Key?, Value?>()
        starlarkBuildSettingsCache = Caffeine.newBuilder().softValues()
            .build<ConfigurationTransition?, com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>()
    }

    private class Key(
        transition: ConfigurationTransition,
        fromOptions: BuildOptions,
        details: StarlarkBuildSettingsDetailsValue
    ) {
        private val transition: ConfigurationTransition
        private val fromOptions: BuildOptions
        private val details: StarlarkBuildSettingsDetailsValue
        private val hashCode: Int

        init {
            // For rule self-transitions, the transition instance encapsulates both the transition logic
            // and attributes of the target it's attached to. This is important: the same transition in
            // the same configuration applied to distinct targets may produce different outputs. See
            // StarlarkRuleTransitionProvider.FunctionPatchTransition for details.
            this.transition = transition
            this.fromOptions = fromOptions
            this.details = details
            this.hashCode = java.util.Objects.hash(transition, fromOptions, details)
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other !is Key) {
                return false
            }
            return this.transition == other.transition
                    && this.fromOptions.equals(other.fromOptions)
                    && this.details == other.details
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }

    private class Value(
        result: MutableMap<String?, BuildOptions?>?,
        nonErrorEvents: com.google.devtools.build.lib.events.StoredEventHandler?
    ) {
        private val result: MutableMap<String?, BuildOptions?>?

        /**
         * Stores events for successful transitions. Transitions that fail aren't added to the cache.
         * This is meant for non-error events like Starlark `print()` output. See [ ][com.google.devtools.build.lib.starlark.StarlarkIntegrationTest.testPrintFromTransitionImpl]
         * for a test that covers this.
         * 
         * 
         * This is null if the transition lacks non-error events.
         */
        private val nonErrorEvents: com.google.devtools.build.lib.events.StoredEventHandler?

        init {
            this.result = result
            this.nonErrorEvents = nonErrorEvents
        }
    }

    companion object {
        fun getDefaultStarlarkOptionsForCustomExec(
            optionsWithDefaults: BuildOptions.Builder?,
            details: StarlarkBuildSettingsDetailsValue,
            fromOptions: BuildOptions
        ): BuildOptions? {
            var optionsWithDefaults: BuildOptions.Builder? = optionsWithDefaults
            for (customExecSetting in details.customExecScopeValues.keySet()) {
                if (optionsWithDefaults == null) {
                    optionsWithDefaults = fromOptions.toBuilder()
                }

                val customExecScopeValue: CustomExecScopeValue? =
                    details.customExecScopeValues.get(customExecSetting)

                if (!fromOptions.getStarlarkOptions().containsKey(customExecScopeValue.hostFlag)) {
                    optionsWithDefaults.addStarlarkOption(
                        customExecScopeValue.hostFlag, customExecScopeValue.hostFlagDefault
                    )
                    optionsWithDefaults.addScopeType(
                        customExecScopeValue.hostFlag,
                        ScopeType(customExecScopeValue.hostFlagScopeType)
                    )
                }
                if (!fromOptions.getStarlarkOptions().containsKey(customExecScopeValue.flag)) {
                    optionsWithDefaults.addStarlarkOption(
                        customExecScopeValue.flag, customExecScopeValue.flagDefault
                    )
                    optionsWithDefaults.addScopeType(
                        customExecScopeValue.flag, ScopeType(customExecScopeValue.flagScopeType)
                    )
                }
            }

            return if (optionsWithDefaults == null)
                fromOptions
            else
                optionsWithDefaults.addScopeTypeMap(fromOptions.getScopeTypeMap()).build()
        }
    }
}
