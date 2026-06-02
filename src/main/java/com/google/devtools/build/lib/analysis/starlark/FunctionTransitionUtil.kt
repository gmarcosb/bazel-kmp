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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY

/**
 * Utility class for common work done across [StarlarkAttributeTransitionProvider] and [ ].
 */
object FunctionTransitionUtil {
    private val IS_NATIVE_OPTION: com.google.common.base.Predicate<String?> =
        com.google.common.base.Predicate { setting: String? -> setting.startsWith(LabelConstants.COMMAND_LINE_OPTION_PREFIX) }

    /**
     * Figure out what build settings the given transition changes and apply those changes to the
     * incoming [BuildOptions]. For native options, this involves a preprocess step of
     * converting options to their "command line form".
     * 
     * 
     * Also perform validation on the inputs and outputs:
     * 
     * 
     *  1. Ensure that all native input options exist
     *  1. Ensure that all native output options exist
     *  1. Ensure that there are no attempts to update the `--define` option.
     *  1. Ensure that no [non-configurable][OptionMetadataTag.NON_CONFIGURABLE] native options
     * are updated.
     *  1. Ensure that transitions output all of the declared options.
     * 
     * 
     * @param fromOptions the pre-transition build options
     * @param starlarkTransition the transition to apply
     * @param attrObject the attributes of the rule to which this transition is attached
     * @return the post-transition build options, or null if errors were reported to handler.
     */
    @Throws(java.lang.InterruptedException::class)
    fun applyAndValidate(
        fromOptions: BuildOptions,
        starlarkTransition: StarlarkDefinedConfigTransition,
        allowNonConfigurableFlagChanges: Boolean,
        isExecTransition: Boolean,
        attrObject: StructImpl?,
        handler: com.google.devtools.build.lib.events.EventHandler
    ): com.google.common.collect.ImmutableMap<String?, BuildOptions?>? {
        try {
            // TODO(waltl): Consider building this once and using it across different split transitions,
            // or reusing BuildOptionDetails.
            val optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo> =
                OptionInfo.buildMapFrom(fromOptions)
            val flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>
            if (isExecTransition) {
                // Ignore flag aliases for exec transitions. Starlark flags will provide their exec
                // transition semantics in the flag definition.
                flagsAliases =
                    com.google.common.collect.ImmutableMap.of<String?, com.google.devtools.build.lib.cmdline.Label?>()
            } else {
                flagsAliases = fromOptions.get(CoreOptions::class.java).getCommandLineFlagAliasesMap()
            }

            validateInputOptions(
                starlarkTransition.getInputs(),
                allowNonConfigurableFlagChanges,
                optionInfoMap,
                flagsAliases
            )
            validateOutputOptions(
                starlarkTransition.getOutputs(),
                allowNonConfigurableFlagChanges,
                optionInfoMap,
                flagsAliases
            )

            val settings: com.google.common.collect.ImmutableMap<String?, Any?> =
                buildSettings(fromOptions, optionInfoMap, flagsAliases, starlarkTransition)

            val splitBuildOptions: com.google.common.collect.ImmutableMap.Builder<String?, BuildOptions?> =
                com.google.common.collect.ImmutableMap.builder<String?, BuildOptions?>()

            // For anything except the exec transition this is just fromOptions. See maybeGetExecDefaults
            // for why the exec transition is different.
            val baselineToOptions: BuildOptions = maybeGetExecDefaults(fromOptions, starlarkTransition)

            val transitions: com.google.common.collect.ImmutableMap<String?, MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>? =
                starlarkTransition.evaluate(settings, attrObject, optionInfoMap, handler)
            if (transitions == null) {
                return null // errors reported to handler
            } else if (transitions.isEmpty()) {
                // The transition produced a no-op.
                return com.google.common.collect.ImmutableMap.of<String?, BuildOptions?>(
                    PATCH_TRANSITION_KEY,
                    baselineToOptions
                )
            }

            for (entry in transitions.entries) {
                val newValues: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                    handleImplicitPlatformChange(
                        baselineToOptions, applyStarlarkFlagsAliases(flagsAliases, entry.value)
                    )

                val transitionedOptions: BuildOptions? =
                    applyTransition(baselineToOptions, newValues, optionInfoMap, starlarkTransition)
                splitBuildOptions.put(entry.key, transitionedOptions)
            }
            return splitBuildOptions.buildOrThrow()
        } catch (ex: ValidationException) {
            handler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    starlarkTransition.getLocation(),
                    ex.getMessage()
                )
            )
            return null
        }
    }

    /**
     * For all transitions except the exec transition, returns `fromOptions`.
     * 
     * 
     * The exec transition is special: any options not explicitly set by the transition take their
     * defaults, not `fromOptions`'s values. This method adjusts the baseline options
     * accordingly.
     * 
     * 
     * The exec transition's full sequence is:
     * 
     * 
     *  1. The transition's Starlark function runs over `fromOptions`: `{"//command_line_option:foo": settings["//command_line_option:foo"}` sets `foo` to
     * `fromOptions`'s value (i.e. propagates from the source config)
     *  1. This method constructs a [BuildOptions] default value (which doesn't inherit from
     * the source config)
     *  1. [.applyTransition] creates final options: use whatever options the Starlark logic
     * set (which may propagate from the source config). For all other options, use default
     * values
     * 
     * See [com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory].
     */
    private fun maybeGetExecDefaults(
        fromOptions: BuildOptions, starlarkTransition: StarlarkDefinedConfigTransition?
    ): BuildOptions {
        if (starlarkTransition == null || !starlarkTransition.isExecTransition()) {
            // Not an exec transition: the baseline options are just the input options.
            return fromOptions
        }
        val defaultBuilder: BuildOptions.Builder = BuildOptions.builder()
        // Get the defaults:
        fromOptions.getNativeOptions().forEach({ o -> defaultBuilder.addFragmentOptions(o.getDefault()) })
        // Propagate Starlark options from the source config if allowed.
        defaultBuilder.addStarlarkOptions(
            getExecPropagatingStarlarkFlags(fromOptions.getStarlarkOptions(), fromOptions)
        )
        // Hard-code TestConfiguration for now, which clones the source options.
        // TODO(b/295936652): handle this directly in Starlark. This has two complications:
        //  1: --trim_test_configuration means the flags may not exist. Starlark logic needs to handle
        //     that possibility.
        //  2: --runs_per_test has a non-Starlark readable type.
        val testOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fromOptions.get(TestConfiguration.TestOptions::class.java)
        if (testOptions != null) {
            defaultBuilder.removeFragmentOptions(TestConfiguration.TestOptions::class.java)
            defaultBuilder.addFragmentOptions(testOptions)
        }
        val ans: BuildOptions = defaultBuilder.build()
        if (fromOptions.get(CoreOptions::class.java).getExcludeDefinesFromExecConfig()) {
            ans.get(CoreOptions::class.java)
                .setCommandLineBuildVariables(
                    fromOptions.get(CoreOptions::class.java).getCommandLineBuildVariables().stream()
                        .filter(
                            { define ->
                                fromOptions
                                    .get(CoreOptions::class.java)
                                    .getCustomFlagsToPropagate()
                                    .contains(define.getKey())
                            })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                )
        } else {
            ans.get(CoreOptions::class.java)
                .setCommandLineBuildVariables(
                    fromOptions.get(CoreOptions::class.java).getCommandLineBuildVariables()
                )
        }
        return ans
    }

    /**
     * Filters a map of Starlark flag <Label></Label>, value> pairs to those that should propagate from the
     * target configuration to exec configuration.
     */
    private fun getExecPropagatingStarlarkFlags(
        starlarkOptions: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>, options: BuildOptions
    ): com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        for (flag in starlarkOptions.keys) {
            com.google.common.base.Verify.verify(
                options.getScopeTypeMap().containsKey(flag),
                "No scope info available for Starlark flag %s.",
                flag
            )
        }
        if (!options.get(CoreOptions::class.java).getExcludeStarlarkFlagsFromExecConfig()) {
            // Starlark flags propagate to exec by default. This can only be changed by a flag explicitly
            // setting "scope = 'target'".
            return starlarkOptions.entries.stream()
                .filter { entry: MutableMap.MutableEntry<com.google.devtools.build.lib.cmdline.Label?, Any?>? ->
                    val scopeType: String = options.getScopeTypeMap().get(entry!!.key).scopeType()
                    scopeType == Scope.ScopeType.UNIVERSAL
                            || scopeType == Scope.ScopeType.DEFAULT
                }
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<MutableMap.MutableEntry<com.google.devtools.build.lib.cmdline.Label?, Any?>?, com.google.devtools.build.lib.cmdline.Label?, Any?>(
                        java.util.function.Function { java.util.Map.Entry.key },
                        java.util.function.Function { java.util.Map.Entry.value })
                )
        }

        // --incompatible_exclude_starlark_flags_from_exec_config=True: Starlark flags don't propagate
        // to the exec config by default. This can be overridden by a flag setting "scope = 'universal'"
        // or --experimental_propagate_custom_flag. If both are set, the flag setting takes precedence.
        val partitioned: MutableMap<Boolean?, MutableList<String?>> =
            options.get(CoreOptions::class.java).getCustomFlagsToPropagate().stream()
                .collect(
                    Collectors.partitioningBy(java.util.function.Predicate { f: T? -> f.endsWith(CustomFlagConverter.SUBPACKAGES_SUFFIX) })
                )
        // Holds --experimental_propagate_custom_flag patterns=//pkg/... patterns. These are rare.
        val customPropagatingFlagPatterns: MutableList<String?> = partitioned.get(true)!!
        // Flags that should propagate according to --experimental_propagate_custom_flag.
        val customPropagatingFlags: MutableSet<String?> = HashSet<String?>(partitioned.get(false))

        val ans: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.cmdline.Label?, Any?> =
            com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.cmdline.Label?, Any?>()
        for (entry in starlarkOptions.entries) {
            val scopeType: String = options.getScopeTypeMap().get(entry.key).scopeType()
            if (scopeType == Scope.ScopeType.UNIVERSAL) {
                ans.put(entry)
            } else if (scopeType == Scope.ScopeType.TARGET) {
                val onLeaveScopeValue: Any? = options.getOnLeaveScopeValues().get(entry.key)
                if (onLeaveScopeValue != null) {
                    // if on_leave_scope is set, propagate to exec config with this value.
                    ans.put(entry.key, onLeaveScopeValue)
                }
                // else Don't propagate this flag.
            } else if (customPropagatingFlags.contains(entry.key.getUnambiguousCanonicalForm())) {
                ans.put(entry)
            } else if (customPropagatingFlagPatterns.stream()
                    .anyMatch { pattern: String? ->
                        entry
                            .key
                            .getUnambiguousCanonicalForm()
                            .startsWith(
                                pattern.substring(
                                    0, pattern.lastIndexOf(CustomFlagConverter.SUBPACKAGES_SUFFIX)
                                )
                            )
                    }
            ) {
                ans.put(entry)
            } else if (scopeType.startsWith(Scope.CUSTOM_EXEC_SCOPE_PREFIX)) {
                val anotherFlag: com.google.devtools.build.lib.cmdline.Label? =
                    com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(scopeType.substring(7))
                ans.put(entry.key, com.google.common.base.Verify.verifyNotNull<Any?>(starlarkOptions.get(anotherFlag)))
            }
        }

        return ans.buildOrThrow()
    }

    private val CPU_OPTION: com.google.devtools.build.lib.cmdline.Label? =
        com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
            LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER,
            "cpu"
        )
    private val PLATFORMS_OPTION: com.google.devtools.build.lib.cmdline.Label? =
        com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
            LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER,
            "platforms"
        )

    /**
     * If the transition changes --cpu but not --platforms, clear out --platforms.
     * 
     * 
     * Purpose:
     * 
     * 
     *  1. A platform mapping sets --cpu=foo when --platforms=foo.
     *  1. A transition sets --cpu=bar.
     *  1. Because --platforms=foo, the platform mapping kicks in to set --cpu back to foo.
     *  1. Result: the mapping accidentally overrides the transition
     * 
     * 
     * 
     * Transitions can also explicitly set --platforms to be clear what platform they set.
     * 
     * 
     * Platform mappings: https://bazel.build/concepts/platforms-intro#platform-mappings.
     */
    private fun handleImplicitPlatformChange(
        options: BuildOptions, rawTransitionOutput: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>
    ): MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        val newCpu = rawTransitionOutput.get(CPU_OPTION)
        if (newCpu == null || newCpu == options.get(CoreOptions::class.java).getCpu()) {
            // No effective change to --cpu, so no need to prevent the platform mapping from resetting it.
            return rawTransitionOutput
        }
        if (rawTransitionOutput.containsKey(PLATFORMS_OPTION)) {
            // Explicitly setting --platforms overrides the implicit clearing.
            return rawTransitionOutput
        }
        return com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.cmdline.Label?, Any?>()
            .putAll(rawTransitionOutput)
            .put(
                PLATFORMS_OPTION,
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>()
            )
            .buildOrThrow()
    }

    private val TRUE_STRINGS: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("true", "1")
    private val FALSE_STRINGS: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("false", "0")

    /** Set the Starlark flag value to the value of its alias.  */
    @Throws(ValidationException::class)
    private fun applyStarlarkFlagsAliases(
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
        rawTransitionOutput: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>
    ): MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        if (flagsAliases.isEmpty()) {
            return rawTransitionOutput
        }

        val result: LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
            LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?>(rawTransitionOutput)

        for (flagAlias in flagsAliases.entries) {
            val nativeFlag: com.google.devtools.build.lib.cmdline.Label? =
                com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
                    LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER, flagAlias.key
                )
            val starlarkFlag: com.google.devtools.build.lib.cmdline.Label? = flagAlias.value
            val starlarkValue: java.util.Optional<Any?> =
                if (rawTransitionOutput.containsKey(starlarkFlag))
                    java.util.Optional.of<Any?>(rawTransitionOutput.get(starlarkFlag))
                else
                    java.util.Optional.empty<Any?>()
            val nativeValue: java.util.Optional<Any?> =
                if (rawTransitionOutput.containsKey(nativeFlag))
                    java.util.Optional.of<Any?>(rawTransitionOutput.get(nativeFlag))
                else
                    java.util.Optional.empty<Any?>()
            if (starlarkValue.isPresent() && nativeValue.isPresent()) {
                var mismatch = false
                if (starlarkValue.get() is Boolean) {
                    // Supports migrating Tristate native flags to boolean Starlark flags. The former appear
                    // as strings. But if those strings are "false", "true", etc. those are valid booleans.
                    if (boolValue && !TRUE_STRINGS.contains(
                            nativeValue.get().toString().lowercase(Locale.getDefault())
                        )
                    ) {
                        mismatch = true
                    } else if (!boolValue
                        && !FALSE_STRINGS.contains(nativeValue.get().toString().lowercase(Locale.getDefault()))
                    ) {
                        mismatch = true
                    }
                } else if (starlarkValue.get() != nativeValue.get()) {
                    mismatch = true
                }
                if (mismatch) {
                    throw ValidationException(
                        String.format(
                            "Starlark flag '%s' and its alias '%s' have different values: '%s' and '%s'",
                            starlarkFlag, nativeFlag, starlarkValue.get(), nativeValue.get()
                        )
                    )
                }
            }
            if (nativeValue.isPresent()) {
                // Add the starlark flag to the result, using the value of the alias.
                result.put(
                    starlarkFlag,
                    if (starlarkValue.isPresent() && starlarkValue.get() is Boolean) nativeValue.get().toString()
                        .toBoolean() else
                        nativeValue.get()
                )
                // Remove the entry of the alias.
                result.remove(nativeFlag)
            }
        }
        return result
    }

    private fun isNativeOptionValid(
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
        optionName: String?
    ): Boolean {
        // Make sure the option exists, or it is an alias.
        return optionInfoMap.containsKey(optionName) || flagsAliases.containsKey(optionName)
    }

    /**
     * Check if a native option is non-configurable.
     * 
     * @return whether or not the option is non-configurable
     * @throws VerifyException if the option does not exist
     */
    private fun isNativeOptionNonConfigurable(
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
        optionName: String?
    ): Boolean {
        val optionInfo: OptionInfo? = optionInfoMap.get(optionName)
        if (optionInfo == null) {
            if (flagsAliases.containsKey(optionName)) {
                // All aliases are configurable (for now).
                return false
            }
            throw com.google.common.base.VerifyException(
                "Cannot check if option %s is non-configurable: it does not exist".formatted(optionName)
            )
        }
        return optionInfo.hasOptionMetadataTag(com.google.devtools.common.options.OptionMetadataTag.NON_CONFIGURABLE)
    }

    @Throws(ValidationException::class)
    private fun validateInputOptions(
        options: com.google.common.collect.ImmutableList<String?>,
        allowNonConfigurableFlagChanges: Boolean,
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>
    ) {
        checkForInvalidNativeOptions( /* transitionParameterType= */
            "inputs", options, optionInfoMap, flagsAliases
        )

        checkForNonConfigurableOptions( /* transitionParameterType= */
            "inputs",
            options,
            allowNonConfigurableFlagChanges,
            optionInfoMap,
            flagsAliases
        )
    }

    @Throws(ValidationException::class)
    private fun validateOutputOptions(
        options: MutableCollection<String?>,
        allowNonConfigurableFlagChanges: Boolean,
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>
    ) {
        if (options.contains("//command_line_option:define")) {
            throw ValidationException(
                "Starlark transition on --define not supported - try using build settings"
                        + " (https://bazel.build/rules/config#user-defined-build-settings)."
            )
        }

        // TODO: blaze-configurability - Move the checks for incompatible and experimental flags to here
        // (currently in ConfigGlobalLibrary.validateBuildSettingKeys).
        checkForInvalidNativeOptions( /* transitionParameterType= */
            "outputs", options, optionInfoMap, flagsAliases
        )

        checkForNonConfigurableOptions( /* transitionParameterType= */
            "outputs",
            options,
            allowNonConfigurableFlagChanges,
            optionInfoMap,
            flagsAliases
        )
    }

    @Throws(ValidationException::class)
    private fun checkForInvalidNativeOptions(
        transitionParameterType: String?,
        options: MutableCollection<String?>,
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>
    ) {
        val invalidNativeOptions: com.google.common.collect.ImmutableList<String?> =
            options.stream()
                .filter(IS_NATIVE_OPTION)
                .filter { option: String? ->
                    !isNativeOptionValid(
                        optionInfoMap,
                        flagsAliases,
                        option.substring(LabelConstants.COMMAND_LINE_OPTION_PREFIX.length)
                    )
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        if (!invalidNativeOptions.isEmpty()) {
            throw ValidationException.format(
                "transition %s [%s] do not correspond to valid settings",
                transitionParameterType, com.google.common.base.Joiner.on(", ").join(invalidNativeOptions)
            )
        }
    }

    @Throws(ValidationException::class)
    private fun checkForNonConfigurableOptions(
        transitionParameterType: String?,
        options: MutableCollection<String?>,
        allowNonConfigurableFlagChanges: Boolean,
        optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>
    ) {
        if (!allowNonConfigurableFlagChanges) {
            val nonConfigurableNativeOptions: com.google.common.collect.ImmutableList<String?> =
                options.stream()
                    .filter(IS_NATIVE_OPTION)
                    .filter { option: String? ->
                        isNativeOptionNonConfigurable(
                            optionInfoMap,
                            flagsAliases,
                            option.substring(LabelConstants.COMMAND_LINE_OPTION_PREFIX.length)
                        )
                    }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
            if (!nonConfigurableNativeOptions.isEmpty()) {
                throw ValidationException.format(
                    "transition %s [%s] cannot be changed: they are non-configurable",
                    transitionParameterType, com.google.common.base.Joiner.on(", ").join(nonConfigurableNativeOptions)
                )
            }
        }
    }

    /**
     * Return an ImmutableMap containing only BuildOptions explicitly registered as transition inputs.
     * 
     * 
     * nulls are converted to Starlark.NONE but no other conversions are done.
     * 
     * @throws IllegalArgumentException If the method is unable to look up the value in buildOptions
     * corresponding to an entry in optionInfoMap
     * @throws RuntimeException If the field corresponding to an option value in buildOptions is
     * inaccessible due to Java language access control, or if an option name is an invalid key to
     * the Starlark dictionary
     * @throws ValidationException if any of the specified transition inputs do not correspond to a
     * valid build setting
     */
    @Throws(ValidationException::class)
    private fun buildSettings(
        buildOptions: BuildOptions,
        optionInfoMap: MutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
        starlarkTransition: StarlarkDefinedConfigTransition
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        val inputsCanonicalizedToGiven: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, String?> =
            starlarkTransition.getInputsCanonicalizedToGiven()

        val optionsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()

        // Convert the canonical form to the user requested form that they expect to see.
        inputsCanonicalizedToGiven.forEach { (canonical: com.google.devtools.build.lib.cmdline.Label?, given: String?) ->
            if (canonical.getPackageIdentifier() == LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER) {
                findNativeOptionValue(buildOptions, optionInfoMap, flagsAliases, canonical)
                    .ifPresent(java.util.function.Consumer { optionValue: Any? ->
                        optionsBuilder.put(
                            given,
                            optionValue
                        )
                    })
            } else {
                val optionValue = findStarlarkOptionValue(buildOptions, canonical)
                optionsBuilder.put(given, optionValue)
            }
        }

        val result: com.google.common.collect.ImmutableMap<String?, Any?> = optionsBuilder.buildOrThrow()
        val remainingInputs: com.google.common.collect.Sets.SetView<String?> =
            com.google.common.collect.Sets.difference<String?>(
                com.google.common.collect.ImmutableSet.copyOf<String?>(
                    inputsCanonicalizedToGiven.values
                ), result.keys
            )
        if (!remainingInputs.isEmpty()) {
            throw ValidationException.format(
                "transition inputs [%s] do not correspond to valid settings",
                com.google.common.base.Joiner.on(", ").join(remainingInputs)
            )
        }

        return result
    }

    private fun findNativeOptionValue(
        buildOptions: BuildOptions,
        optionInfoMap: MutableMap<String?, OptionInfo>,
        flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
        setting: com.google.devtools.build.lib.cmdline.Label
    ): java.util.Optional<Any?> {
        val optionName: String? = setting.getName()
        if (flagsAliases.containsKey(optionName)) {
            // If the setting is an alias to a starlark option, use the starlark option value.
            return java.util.Optional.of<Any?>(findStarlarkOptionValue(buildOptions, flagsAliases.get(optionName)))
        }

        if (!optionInfoMap.containsKey(optionName)) {
            return java.util.Optional.empty<Any?>()
        }
        val optionInfo: OptionInfo = optionInfoMap.get(optionName)
        val options: FragmentOptions? = buildOptions.get(optionInfo.getOptionClass())
        // Get the raw value to avoid the default handling for null values.
        val optionValue: Any? = optionInfo.getDefinition().getRawValue(options)
        // convert nulls here b/c ImmutableMap bans null values
        return java.util.Optional.of<Any?>(if (optionValue == null) net.starlark.java.eval.Starlark.NONE else optionValue)
    }

    private fun findStarlarkOptionValue(
        buildOptions: BuildOptions,
        setting: com.google.devtools.build.lib.cmdline.Label?
    ): Any {
        return buildOptions.getStarlarkOptions().get(setting)
    }

    /**
     * Apply the transition dictionary to the build option, using optionInfoMap to look up the option
     * info.
     * 
     * @param fromOptions the pre-transition build options
     * @param newValues a map of option Label: option value entries to override current option values
     * in the buildOptions param
     * @param optionInfoMap a map of all native options (name -> OptionInfo) present in `toOptions`.
     * @param starlarkTransition transition object that is being applied. Used for error reporting and
     * checking for analysis testing
     * @return the post-transition build options
     * @throws ValidationException If a requested option field is inaccessible
     */
    @Throws(ValidationException::class)
    private fun applyTransition(
        fromOptions: BuildOptions,
        newValues: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>,
        optionInfoMap: MutableMap<String?, OptionInfo>,
        starlarkTransition: StarlarkDefinedConfigTransition
    ): BuildOptions? {
        // toOptions being null means the transition hasn't changed anything. We avoid preemptively
        // cloning it from fromOptions since options cloning is an expensive operation.
        var toOptions: BuildOptions? = null
        // Starlark options that are different after this transition. We collect all of them, then clone
        // the build options once with all cumulative changes. Native option changes, in contrast, are
        // set directly in the BuildOptions instance. The former approach is preferred since it makes
        // BuildOptions objects more immutable. Native options use the latter approach for legacy
        // reasons. While not preferred, direct mutation doesn't require expensive cloning.
        val changedStarlarkOptions: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
            LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?>()
        for (entry in newValues.entries) {
            val optionKey: com.google.devtools.build.lib.cmdline.Label = entry.key
            var optionValue = entry.value

            if (optionKey
                    .getPackageIdentifier()
                != LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER
            ) {
                // The transition changes a Starlark option.
                val oldValue: Any? = fromOptions.getStarlarkOptions().get(optionKey)
                if (oldValue is com.google.devtools.build.lib.cmdline.Label) {
                    // If this is a label-typed build setting, we need to convert the provided new value into
                    // a Label object.
                    if (optionValue is String) {
                        try {
                            optionValue =
                                com.google.devtools.build.lib.cmdline.Label.parseWithPackageContext(
                                    optionValue, starlarkTransition.getPackageContext()
                                )
                        } catch (e: LabelSyntaxException) {
                            throw ValidationException.format(
                                "Error parsing value for option '%s': %s", optionKey, e.message
                            )
                        }
                    } else if (optionValue !is com.google.devtools.build.lib.cmdline.Label) {
                        throw ValidationException.format(
                            "Invalid value type for option '%s': want label, got %s",
                            optionKey, net.starlark.java.eval.Starlark.type(optionValue)
                        )
                    }
                } else if (oldValue is MutableSet<*>) {
                    // If this is a set-typed build setting, we need to ensure the value is a sorted
                    // set for consistency and to match rule expectations.
                    if (optionValue is MutableList<*>) {
                        try {
                            optionValue =
                                com.google.common.collect.ImmutableSortedSet.copyOf<Comparable<*>?>(
                                    optionValue.stream() // Cast each element to avoid unchecked exception.
                                        .map<Comparable<*>?> { obj: Any? -> Comparable::class.java.cast(obj) }
                                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Comparable<*>?>()))
                        } catch (e: java.lang.ClassCastException) {
                            // If sorting fails (e.g. mixed types), convert to an unsorted ImmutableSet.
                            // This allows the subsequent type validation to handle the invalid values
                            // and produce a user-friendly error message.
                            optionValue = com.google.common.collect.ImmutableSet.copyOf(optionValue)
                        }
                    } else if (optionValue is MutableSet<*>) {
                        // If the value is already a set, just convert it to a sorted set.
                        optionValue = net.starlark.java.eval.StarlarkSet.immutableCopyOf(
                            com.google.common.collect.ImmutableSortedSet.copyOf(optionValue)
                        )
                    } else {
                        throw ValidationException.format(
                            "Invalid value type for option '%s': want set, got %s",
                            optionKey, net.starlark.java.eval.Starlark.type(optionValue)
                        )
                    }
                }
                if (oldValue != optionValue) {
                    changedStarlarkOptions.put(optionKey, optionValue)
                }
            } else {
                // The transition changes a native option.
                val optionName: String? = optionKey.getName()
                val optionInfo: OptionInfo = optionInfoMap.get(optionName)

                // Convert NoneType to null.
                if (optionValue is net.starlark.java.eval.NoneType) {
                    optionValue = null
                } else if (optionValue is net.starlark.java.eval.StarlarkInt) {
                    optionValue = optionValue.toIntUnchecked()
                } else if (optionValue is MutableList<*>) {
                    // Converting back to the Java-native type makes it easier to check if a Starlark
                    // transition set the same value a native transition would. This is important for
                    // ExecutionTransitionFactory#ComparingTransition.
                    // TODO(b/288258583): remove this case when ComparingTransition is no longer needed for
                    // debugging. Production code just iterates over the lists, which both Starlark and
                    // native List types implement.
                    optionValue = com.google.common.collect.ImmutableList.copyOf(optionValue)
                } else if (optionValue is MutableMap<*, *>) {
                    // TODO(b/288258583): remove this case when ComparingTransition is no longer needed for
                    // debugging. See above TODO.
                    optionValue = com.google.common.collect.ImmutableMap.copyOf(optionValue)
                }
                try {
                    val def: com.google.devtools.common.options.OptionDefinition = optionInfo.getDefinition()
                    // TODO(b/153867317): check for crashing options types in this logic.
                    val convertedValue: Any?
                    if (def.getType() == MutableList::class.java && optionValue is MutableList<*>) {
                        // This is possible with Starlark code like "{ //command_line_option:foo: ["a", "b"] }".
                        // In that case def.getType() == List.class while optionValue.type == StarlarkList.
                        // Unfortunately we can't check the *element* types because OptionDefinition won't tell
                        // us that about def (def.getConverter() returns LabelListConverter but nowhere does it
                        // mention Label.class). Worse, def.getConverter().convert takes a String input. This
                        // forces us to serialize optionValue back to a scalar string to convert. There's no
                        // generically safe way to do this. We convert its elements with .toString() with a ","
                        // separator, which happens to work for most implementations. But that's not universally
                        // guaranteed.
                        if (optionValue.isEmpty()) {
                            convertedValue = com.google.common.collect.ImmutableList.of<Any?>()
                        } else if (!def.allowsMultiple()) {
                            convertedValue =
                                def.getConverter()
                                    .convert(
                                        optionValue.stream()
                                            .map<String?> { element: Any? ->
                                                if (element is com.google.devtools.build.lib.cmdline.Label)
                                                    element.getUnambiguousCanonicalForm()
                                                else
                                                    element.toString()
                                            }
                                            .collect(Collectors.joining(",")),
                                        starlarkTransition.getPackageContext())
                        } else {
                            val valueBuilder: com.google.common.collect.ImmutableList.Builder<Any?> =
                                com.google.common.collect.ImmutableList.builder<Any?>()
                            // We can't use streams because def.getConverter().convert may throw an
                            // OptionsParsingException.
                            for (e in optionValue) {
                                val converted: Any =
                                    def.getConverter()
                                        .convert(e.toString(), starlarkTransition.getPackageContext())
                                if (converted is MutableList<*>) {
                                    valueBuilder.addAll(converted)
                                } else {
                                    valueBuilder.add(converted)
                                }
                            }
                            convertedValue = valueBuilder.build()
                        }
                    } else if (def.getType() == MutableList::class.java && optionValue == null) {
                        throw ValidationException.format(
                            "'None' value not allowed for List-type option '%s'. Please use '[]' instead if"
                                    + " trying to set option to empty value.",
                            optionName
                        )
                    } else if (optionValue == null || def.getType().isInstance(optionValue)) {
                        convertedValue = optionValue
                    } else if (def.getType() == Int::class.javaPrimitiveType && optionValue is Int) {
                        convertedValue = optionValue
                    } else if (def.getType() == Boolean::class.javaPrimitiveType && optionValue is Boolean) {
                        convertedValue = optionValue
                    } else if (optionValue is String) {
                        convertedValue =
                            def.getConverter()
                                .convert(optionValue, starlarkTransition.getPackageContext())
                    } else {
                        throw ValidationException.format("Invalid value type for option '%s'", optionName)
                    }

                    val oldValue: Any? = def.getRawValue(fromOptions.get(optionInfo.getOptionClass()))
                    if (oldValue != convertedValue) {
                        if (toOptions == null) {
                            toOptions = fromOptions.clone()
                        }
                        def.setValue(toOptions.get(optionInfo.getOptionClass()), convertedValue)
                    }
                } catch (e: java.lang.IllegalArgumentException) {
                    throw ValidationException.format(
                        "IllegalArgumentError for option '%s': %s", optionName, e.message
                    )
                } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                    throw ValidationException.format(
                        "OptionsParsingError for option '%s': %s", optionName, e.message
                    )
                }
            }
        }

        if (toOptions == null && changedStarlarkOptions.isEmpty()) {
            return fromOptions
        }
        // Note that rebuilding also calls FragmentOptions.getNormalized() to guarantee --define,
        // --features, and similar flags are consistently ordered.
        toOptions =
            BuildOptions.builder()
                .merge(if (toOptions == null) fromOptions.clone() else toOptions)
                .addStarlarkOptions(changedStarlarkOptions)
                .build()
        if (starlarkTransition.isForAnalysisTesting()) {
            toOptions.get(CoreOptions::class.java).setEvaluatingForAnalysisTest(true)
        }
        return toOptions
    }
}
