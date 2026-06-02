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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Creates the needed [BuildConfigurationKey] instance for a single [BuildOptions],
 * including merging in any platform-based flags or a platform mapping.
 * 
 * 
 * Platform-based flags and platform mappings are mutually exclusive: only one will be applied if
 * they are present. Trying to mix and match would be possible but confusing, especially if they try
 * to change the same flag. The logic is:
 * 
 * 
 *  * If [PlatformOptions.platforms] specifies a target platform, look up the [       ]. If it specifies [flags][PlatformValue.parsedFlags], use [       ][ParsedFlagsValue.mergeWith].
 *  * If [PlatformOptions.platforms] does not specify a target platform, or if the target
 * platform does not specify [flags][PlatformValue.parsedFlags], look up the [       ] and use [PlatformMappingValue.map].
 * 
 * 
 * 
 * Scopes for starlark flags also get applied before producing the final BuildConfigurationKey.
 * Scopes are applied after platform-based flags or platform mappings are applied. The logic is:
 * 
 * 
 *  * If all starlark flags have ScopeType.UNIVERSAL, no further processing is done.
 *  * If any starlark flag has ScopeType.PROJECT or its ScopeType is not yet resolved, a lookup
 * for [BuildOptionsScopeValue] via [BuildOptionsScopesFunction] is performed.
 *  * If the ScopeType for a flag is ScopeType.PROJECT, and the flag is not in the scope of the
 * current package, the flag is reset to its baseline value if it is present in the baseline.
 * If the flag is not present in the baseline, it is removed. This is to ensure that we do not
 * trigger an addition ST-<hash>, which defeats the purpose of scoping.
 *  * If the ScopeType for a flag is ScopeType.PROJECT, and the flag is in the scope of the
 * current package, the flag keeps its current value.
</hash> * 
 * 
 * @param <C> The type of the context variable that the producer will pass via the [     ] so that consumers can identify which options are which.
</C> */
class BuildConfigurationKeyProducer<C>
internal constructor(// -------------------- Input --------------------
    private val sink: ResultSink<C?>,
    runAfter: StateMachine,
    context: C?,
    options: BuildOptions,
    label: com.google.devtools.build.lib.cmdline.Label?
) : StateMachine, ValueOrExceptionSink<PlatformMappingException?>, java.util.function.Consumer<SkyValue?>,
    com.google.devtools.build.lib.analysis.producers.PlatformProducer.ResultSink {
    /** Interface for clients to accept results of this computation.  */
    interface ResultSink<C> {
        fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException?)

        fun acceptPlatformMappingError(e: PlatformMappingException?)

        fun acceptPlatformFlagsError(error: InvalidPlatformException?)

        fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?)

        fun acceptTransitionedConfiguration(context: C?, transitionedOptionKey: BuildConfigurationKey?)
    }

    private val runAfter: StateMachine
    private val context: C?
    private val options: BuildOptions
    private val label: com.google.devtools.build.lib.cmdline.Label?

    // -------------------- Internal State --------------------
    private var targetPlatformValue: PlatformValue? = null
    private var platformMappingValue: PlatformMappingValue? = null
    private var buildOptionsScopeValue: BuildOptionsScopeValue? = null
    private var postPlatformProcessedOptions: BuildOptions? = null
    private var baselineConfiguration: BuildOptions? = null

    init {
        this.runAfter = runAfter
        this.context = context
        this.options = options
        this.label = label
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        // Short-circuit if there are no platform options.
        val platformOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            options.get(PlatformOptions::class.java)
        if (platformOptions == null) {
            this.postPlatformProcessedOptions = options
            return StateMachine { tasks: StateMachine.Tasks? -> this.findBuildOptionsScopes(tasks) }
        }

        val targetPlatforms: MutableList<com.google.devtools.build.lib.cmdline.Label?> = platformOptions.getPlatforms()
        if (targetPlatforms.size == 1) {
            // TODO: https://github.com/bazelbuild/bazel/issues/19807 - We define this flag to only use
            //  the first value and ignore any subsequent ones. Remove this check as part of cleanup.
            tasks.enqueue(
                PlatformProducer(
                    targetPlatforms.getFirst(),
                    options.get(CoreOptions::class.java).getCommandLineFlagAliasesMap(),
                    this,
                    StateMachine { tasks: StateMachine.Tasks? -> this.checkTargetPlatformFlags(tasks) })
            )
            return runAfter
        } else {
            com.google.common.base.Verify.verify(targetPlatforms.isEmpty())
            return StateMachine { tasks: StateMachine.Tasks? -> this.mergeFromPlatformMapping(tasks) }
        }
    }

    /**
     * Determine whether to update the BuildOptions with platform-based flags via [ ][ParsedFlagsValue.mergeWith] or with platform mappings via [PlatformMappingValue.map]
     * based on the presence of [ParsedFlagsValue].
     */
    private fun checkTargetPlatformFlags(tasks: StateMachine.Tasks?): StateMachine {
        if (targetPlatformValue == null) {
            return StateMachine.DONE // Error.
        }
        val parsedFlags: java.util.Optional<ParsedFlagsValue?> = targetPlatformValue.parsedFlags
        if (parsedFlags.isPresent()) {
            this.postPlatformProcessedOptions = parsedFlags.get().mergeWith(options).getOptions()
            return StateMachine { tasks: StateMachine.Tasks? -> this.findBuildOptionsScopes(tasks) }
        } else {
            return StateMachine { tasks: StateMachine.Tasks? -> this.mergeFromPlatformMapping(tasks) }
        }
    }

    /**
     * Performs a lookup for [BuildOptionsScopeValue] via [BuildOptionsScopesFunction]
     * given [postPlatformProcessedOptions]. This is only done if there are any flag that has
     * [ScopeType.PROJECT] or its [ScopeType] is not yet resolved.
     */
    private fun findBuildOptionsScopes(tasks: StateMachine.Tasks): StateMachine {
        com.google.common.base.Preconditions.checkNotNull<Any?>(this.postPlatformProcessedOptions)
        // including platform-based flags in skykey for scopes lookUp
        if (postPlatformProcessedOptions.getStarlarkOptions().isEmpty()) {
            return StateMachine { tasks: StateMachine.Tasks? -> this.possiblyApplyScopes(tasks) }
        }

        // the list of flags that are either project scoped or their scopes are not yet resolved.
        // Lookup via BuildOptionsScopeFunction will be done for these flags
        val flagsWithIncompleteScopeInfo: MutableList<com.google.devtools.build.lib.cmdline.Label?> =
            java.util.ArrayList<com.google.devtools.build.lib.cmdline.Label?>()
        for (entry in postPlatformProcessedOptions.getStarlarkOptions().entrySet()) {
            val scopeType: Scope.ScopeType? =
                this.postPlatformProcessedOptions.getScopeTypeMap().get(entry.key)
            // scope is null is applicable for cases where a transition applies starlark flags that are
            // not already part of the baseline configuration.
            if (scopeType == null || scopeType.scopeType().equals(Scope.ScopeType.PROJECT)
                || scopeType.scopeType().startsWith(Scope.CUSTOM_EXEC_SCOPE_PREFIX)
            ) {
                flagsWithIncompleteScopeInfo.add(entry.key)
            }
        }

        // if flagsWithIncompleteScopeInfo is empty, we do not need to do any further lookUp for the
        // ScopeType and ScopeDefinition
        if (flagsWithIncompleteScopeInfo.isEmpty()) {
            return StateMachine { tasks: StateMachine.Tasks? -> this.possiblyApplyScopes(tasks) }
        }

        val buildOptionsScopeValueKey: com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key? =
            BuildOptionsScopeValue.Key.create(
                this.postPlatformProcessedOptions, flagsWithIncompleteScopeInfo
            )
        tasks.lookUp(buildOptionsScopeValueKey, this as java.util.function.Consumer<SkyValue?>)
        return StateMachine { tasks: StateMachine.Tasks? -> this.possiblyApplyScopes(tasks) }
    }

    /**
     * Performs a lookup for [PlatformMappingValue] via [PlatformMappingFunction] given
     * [options] and will transform the input [BuildOptions] with any matching platform
     * mappings.
     */
    private fun mergeFromPlatformMapping(tasks: StateMachine.Tasks): StateMachine {
        tasks.lookUp<E?>(
            options.get(PlatformOptions::class.java).getPlatformMappingKey(),
            PlatformMappingException::class.java,
            this
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.applyPlatformMapping(tasks) }
    }

    private fun applyPlatformMapping(tasks: StateMachine.Tasks?): StateMachine? {
        if (platformMappingValue == null) {
            return StateMachine.DONE // Error.
        }
        try {
            this.postPlatformProcessedOptions = platformMappingValue.map(options).getOptions()
            return StateMachine { tasks: StateMachine.Tasks? -> this.findBuildOptionsScopes(tasks) }
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            sink.acceptOptionsParsingError(e)
            return runAfter
        }
    }

    // Handles results from the PlatformMappingValueKey lookup.
    override fun acceptValueOrException(
        value: SkyValue?, exception: PlatformMappingException?
    ) {
        check(!(value == null && exception == null)) { "No value or exception was provided" }
        check(!(value != null && exception != null)) { "Both value and exception were provided" }

        if (exception != null) {
            sink.acceptPlatformMappingError(exception)
        } else {
            this.platformMappingValue = value as PlatformMappingValue
        }
    }

    override fun acceptPlatformValue(value: PlatformValue?) {
        this.targetPlatformValue = value
    }

    override fun acceptPlatformInfoError(error: InvalidPlatformException?) {
        sink.acceptPlatformFlagsError(error)
    }

    override fun acceptOptionsParsingError(error: com.google.devtools.common.options.OptionsParsingException?) {
        sink.acceptOptionsParsingError(error)
    }

    override fun accept(value: SkyValue?) {
        this.buildOptionsScopeValue = value as BuildOptionsScopeValue?
    }

    private fun possiblyApplyScopes(tasks: StateMachine.Tasks): StateMachine? {
        // This is not the same as null associated with Skyframe lookUp. This happens when scoping logic
        // is not enabled. This means the lookup via BuildOptionsScopesFunction was not performed.
        if (buildOptionsScopeValue == null
            || postPlatformProcessedOptions.getStarlarkOptions().isEmpty()
        ) {
            return finishConfigurationKeyProcessing(postPlatformProcessedOptions)
        }

        val shouldApplyScopes: Boolean =
            buildOptionsScopeValue.getFullyResolvedScopes().values.stream()
                .anyMatch { scope: Scope? -> scope.scopeType.scopeType().equals(Scope.ScopeType.PROJECT) }

        if (!shouldApplyScopes) {
            return finishConfigurationKeyProcessing(
                buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes()
            )
        }

        val resolvedOptions: BuildOptions = buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes()
        tasks.lookUp(
            BaselineOptionsValue.key(
                resolvedOptions.get(CoreOptions::class.java).getIsExec(),
                !resolvedOptions.contains(TestConfiguration.TestOptions::class.java),  /* newPlatform= */
                null
            ),
            java.util.function.Consumer { `val`: SkyValue? ->
                this.baselineConfiguration = (`val` as BaselineOptionsValue).toOptions()
            })
        return StateMachine { tasks: StateMachine.Tasks? -> this.applyScopes(tasks) }
    }

    private fun applyScopes(tasks: StateMachine.Tasks?): StateMachine {
        val resolved: BuildOptions = buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes()
        val finalBuildOptions: BuildOptions? =
            if (baselineConfiguration.getStarlarkOptions().equals(resolved.getStarlarkOptions()))
                resolved
            else
                resetFlags(buildOptionsScopeValue, baselineConfiguration, label)
        return finishConfigurationKeyProcessing(finalBuildOptions)
    }

    private fun finishConfigurationKeyProcessing(finalBuildOptions: BuildOptions?): StateMachine {
        sink.acceptTransitionedConfiguration(context, BuildConfigurationKey.create(finalBuildOptions))
        return runAfter
    }

    companion object {
        /**
         * If a flag is considered to be out of scope, resetFlags does either of the following:
         * 
         * 
         *  * If the flag is not present in the baseline configuration, remove the flag from the [       ].
         *  * If the flag is present in the baseline configuration, set the flag to the baseline value.
         * 
         * This is to ensure that we do not trigger an additional ST-<hash>, which defeats the
         * 
         * purpose of scoping.
        </hash> * 
         * 
         * This method returns the final [BuildOptions] after scoping is applied and the object only
         * has the [Scope.ScopeType] information for all starlark flags.
         */
        private fun resetFlags(
            buildOptionsScopeValue: BuildOptionsScopeValue?,
            baselineConfiguration: BuildOptions?,
            label: com.google.devtools.build.lib.cmdline.Label?
        ): BuildOptions? {
            com.google.common.base.Preconditions.checkNotNull<BuildOptionsScopeValue?>(buildOptionsScopeValue)
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.cmdline.Label?>(label)

            val transitionedOptionsWithScopeType: BuildOptions =
                buildOptionsScopeValue.getResolvedBuildOptionsWithScopeTypes()
            // If there are no scopes, short circuit.
            if (buildOptionsScopeValue.getFullyResolvedScopes().isEmpty()) {
                return transitionedOptionsWithScopeType
            }

            com.google.common.base.Preconditions.checkNotNull<Any?>(baselineConfiguration)
            var flagsRemoved = false
            var flagsResetToBaseline = false
            val optionsWithScopeTypesBuilder: BuildOptions.Builder =
                transitionedOptionsWithScopeType.toBuilder()
            for (flagEntry in transitionedOptionsWithScopeType.getStarlarkOptions().entrySet()) {
                val flagLabel: com.google.devtools.build.lib.cmdline.Label? = flagEntry.key
                val scope: Scope? = buildOptionsScopeValue.getFullyResolvedScopes().get(flagLabel)
                if (scope == null) {
                    com.google.common.base.Verify.verify(
                        !transitionedOptionsWithScopeType
                            .getScopeTypeMap()
                            .get(flagLabel)
                            .scopeType()
                            .equals(Scope.ScopeType.PROJECT)
                    )
                } else if (scope.scopeType.scopeType().equals(Scope.ScopeType.PROJECT)) {
                    val flagValue: Any? = flagEntry.value
                    val baselineValue: Any? = baselineConfiguration.getStarlarkOptions().get(flagLabel)
                    if (flagValue !== baselineValue && !isInScope(label, scope.scopeDefinition)) {
                        if (baselineValue == null) {
                            optionsWithScopeTypesBuilder.removeStarlarkOption(flagLabel)
                            flagsRemoved = true
                        } else {
                            optionsWithScopeTypesBuilder.addStarlarkOption(flagLabel, baselineValue)
                            flagsResetToBaseline = true
                        }
                    }
                }
            }

            if (!flagsRemoved && !flagsResetToBaseline) {
                return transitionedOptionsWithScopeType
            }

            val scopedBuildOptions: BuildOptions = optionsWithScopeTypesBuilder.build()
            if (scopedBuildOptions.equals(baselineConfiguration)) {
                return baselineConfiguration
            }

            return scopedBuildOptions
        }

        private fun isInScope(
            label: com.google.devtools.build.lib.cmdline.Label,
            scopeDefinition: Scope.ScopeDefinition?
        ): Boolean {
            com.google.common.base.Preconditions.checkNotNull<Any?>(scopeDefinition)
            for (path in scopeDefinition.getOwnedCodePaths()) {
                if (label.getCanonicalForm().startsWith(path)) {
                    return true
                }
            }
            return false
        }
    }
}
