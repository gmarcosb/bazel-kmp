// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider

/** A marker class for configuration transitions that are defined in Starlark.  */
abstract class StarlarkTransition protected constructor(starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition) :
    ConfigurationTransition {
    private val starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition

    init {
        this.starlarkDefinedConfigTransition = starlarkDefinedConfigTransition
    }

    val name: String
        get() = "Starlark transition:" + starlarkDefinedConfigTransition.getLocation()

    /** Whether this transition has `//command_line_option:stamp` on its inputs.  */
    fun readsStampSetting(): Boolean {
        return this.inputs.contains(STAMP_SETTING)
    }

    /** Whether this transition has `//command_line_option:stamp` on its outputs.  */
    fun setsStampSetting(): Boolean {
        return this.outputs.contains(STAMP_SETTING)
    }

    /** Returns true if the transition is an exec transition.  */
    abstract val isExecTransition: Boolean

    private val inputs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label>
        // Get the inputs of the starlark transition as a set of canonicalized Labels.
        get() = starlarkDefinedConfigTransition.getInputsCanonicalizedToGiven().keySet()

    private val outputs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label>
        // Get the outputs of the starlark transition as a set of canonicalized Labels.
        get() = starlarkDefinedConfigTransition.getOutputsCanonicalizedToGiven().keySet()

    public override fun addRequiredFragments(
        requiredFragments: RequiredConfigFragmentsProvider.Builder, optionDetails: BuildOptionDetails
    ) {
        for (option in com.google.common.collect.Iterables.concat<com.google.devtools.build.lib.cmdline.Label>(
            this.inputs,
            this.outputs
        )) {
            if (option
                    .getPackageIdentifier()
                != LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER
            ) {
                requiredFragments.addStarlarkOption(option)
            } else {
                val optionNativeName: String = option.getName()
                // A null optionsClass means the flag is invalid. Starlark transitions independently catch
                // and report that (search the code for "do not correspond to valid settings").
                val optionsClass: java.lang.Class<out FragmentOptions?>? =
                    optionDetails.getOptionClass(optionNativeName)
                if (optionsClass != null) {
                    requiredFragments.addOptionsClass(optionsClass)
                }
            }
        }
    }

    /** Exception class for exceptions thrown during application of a starlark-defined transition  */ // TODO(blaze-configurability): add more information to this exception e.g. originating target of
    // transition.
    class TransitionException : java.lang.Exception {
        constructor(message: String?) : super(message)

        constructor(cause: Throwable?) : super(cause)

        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    override fun equals(`object`: Any?): Boolean {
        if (`object` === this) {
            return true
        }
        if (`object` is StarlarkTransition) {
            val starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition? =
                `object`.starlarkDefinedConfigTransition
            return starlarkDefinedConfigTransition == this.starlarkDefinedConfigTransition
        }
        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hashCode(starlarkDefinedConfigTransition)
    }

    @java.lang.FunctionalInterface // This is only used to handle the cast and the exception
    interface StarlarkTransitionVisitor

        : ConfigurationTransition.Visitor<TransitionException?> {
        @Throws(TransitionException::class)
        public override fun accept(transition: ConfigurationTransition?) {
            if (transition is StarlarkTransition) {
                this.accept(transition)
            }
        }

        @Throws(TransitionException::class)
        fun accept(transition: StarlarkTransition?)
    }

    companion object {
        private val STAMP_SETTING: com.google.devtools.build.lib.cmdline.Label? =
            com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
                LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER,
                "stamp"
            )

        /**
         * Method to be called after Starlark-transitions are applied. Checks outputs.
         * 
         * 
         * We only do validation on Starlark-defined build settings. Native options (designated with
         * `COMMAND_LINE_OPTION_PREFIX`) already have their output values checked in [ ][FunctionTransitionUtil.applyTransition].
         * 
         * 
         * Remove build settings in `toOptions` that have been set to their default value. This
         * is how we ensure that an unset build setting and a set-to-default build settings represent the
         * same configuration.
         * 
         * @param root transition that was applied. Likely a [     ] so we
         * decompose and post-process all StarlarkTransitions out of whatever transition is passed
         * here.
         * @param details a StarlarkBuildSettingsDetailsValue whose corresponding key was all the input
         * and output settings of root. Use [getAllStarlarkBuildSettings].
         * @param flagsAliases a list of starlark flag aliases defined via --flag_alias.
         * @param toOptions result of applying `root`
         * @return validated toOptions with default values filtered out
         * @throws TransitionException if an error occurred during Starlark transition application.
         */
        // TODO(juliexxia): the current implementation masks certain bad transitions and only checks the
        // final result. I.e. if a transition that writes a non int --//int-build-setting is composed
        // with another transition that writes --//int-build-setting (without reading it first), then
        // the bad output of transition 1 is masked.
        @Throws(TransitionException::class)
        fun validate(
            root: ConfigurationTransition,
            details: StarlarkBuildSettingsDetailsValue,
            flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
            toOptions: MutableMap<String?, BuildOptions?>
        ): MutableMap<String?, BuildOptions?>? {
            // Collect settings that are inputs or outputs of the transition together with their types.
            // Output setting values will be validated and removed if set to their default.
            // Raw means these have not been unaliased.
            val rawInputAndOutputSettingsBuilder: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.cmdline.Label?>()
            // Collect settings that were only used as inputs to the transition and thus possibly had their
            // default values added to the fromOptions. They will be removed if set to ther default, but
            // should not be validated.
            val inputOnlySettingsBuilder: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.cmdline.Label?>()
            root.visit(
                StarlarkTransitionVisitor { transition: StarlarkTransition? ->
                    val inputAndOutputSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
                        Companion.getRelevantStarlarkSettingsFromTransition(
                            transition!!, flagsAliases, Settings.INPUTS_AND_OUTPUTS
                        )
                    val outputSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
                        Companion.getRelevantStarlarkSettingsFromTransition(
                            transition, flagsAliases, Settings.OUTPUTS
                        )
                    for (setting in inputAndOutputSettings) {
                        rawInputAndOutputSettingsBuilder.add(setting)
                        if (!outputSettings.contains(setting)) {
                            inputOnlySettingsBuilder.add(setting)
                        }
                    }
                } as StarlarkTransitionVisitor)

            val rawInputAndOutputSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
                rawInputAndOutputSettingsBuilder.build()
            val inputOnlySettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
                inputOnlySettingsBuilder.build()

            // Return early if the transition has neither inputs nor outputs (rare).
            if (rawInputAndOutputSettings.isEmpty()) {
                return toOptions
            }

            // Verify changed settings were changed to something reasonable for their type and filter out
            // default values.
            val cleanedOptionMap: com.google.common.collect.ImmutableMap.Builder<String?, BuildOptions?> =
                com.google.common.collect.ImmutableMap.builder<String?, BuildOptions?>()
            for (entry in toOptions.entries) {
                // Lazily initialized to optimize for the common case where we don't modify anything.
                var cleanedOptions: BuildOptions.Builder? = null
                // Clean up aliased values.
                // TODO(blaze-configurability-team): This is actually a quagmire of undefined behavior
                //   if a user asks for both an alias and the unaliased build setting.
                var options: BuildOptions = unalias(entry.value, details.aliasToActual())
                for (maybeAliasSetting in rawInputAndOutputSettings) {
                    // Note that if the build setting may be referenced in the transition via an alias
                    val setting: com.google.devtools.build.lib.cmdline.Label? =
                        details.aliasToActual().getOrDefault(maybeAliasSetting, maybeAliasSetting)
                    // Input-only settings may have had their literal default value added to the BuildOptions
                    // so that the transition can read them. We have to remove these explicitly set value here
                    // to preserve the invariant that Starlark settings at default values are not explicitly set
                    // in the BuildOptions.
                    val isInputOnlySettingAtDefault =
                        inputOnlySettings.contains(maybeAliasSetting)
                                && details
                            .buildSettingToDefault()
                            .get(setting)
                            .equals(options.getStarlarkOptions().get(setting))
                    // For output settings, the raw value returned by the transition first has to be validated
                    // and converted to the proper type before it can be compared to the default value.
                    if (isInputOnlySettingAtDefault
                        || validateAndCheckIfAtDefault(
                            details, options, maybeAliasSetting, setting, rawInputAndOutputSettings
                        )
                    ) {
                        if (cleanedOptions == null) {
                            cleanedOptions = options.toBuilder()
                        }
                        cleanedOptions.removeStarlarkOption(setting)
                    }
                }
                // Keep the same instance if we didn't do anything to maintain reference equality later on.
                options = if (cleanedOptions != null) cleanedOptions.build() else options
                cleanedOptionMap.put(entry.key, options)
            }
            return cleanedOptionMap.buildOrThrow()
        }

        /**
         * Validate the value of a particular build setting after a transition has been applied.
         * 
         * @param buildSettingRule the build setting to validate.
         * @param options the [BuildOptions] reflecting the post-transition configuration.
         * @param maybeAliasSetting the label used to refer to the build setting in the transition,
         * possibly an alias. This is only used for error messages.
         * @param inputAndOutputSettings the transition input and output settings. This is only used for
         * error messages.
         * @return `true` if and only if the setting is set to its default value after the
         * transition.
         * @throws TransitionException if the value returned by the transition for this setting has an
         * invalid type.
         */
        @Throws(TransitionException::class)
        private fun validateAndCheckIfAtDefault(
            details: StarlarkBuildSettingsDetailsValue,
            options: BuildOptions,
            maybeAliasSetting: com.google.devtools.build.lib.cmdline.Label?,
            setting: com.google.devtools.build.lib.cmdline.Label?,
            inputAndOutputSettings: MutableSet<com.google.devtools.build.lib.cmdline.Label?>?
        ): Boolean {
            val newValue: Any? = options.getStarlarkOptions().get(setting)
            // TODO(b/154132845): fix NPE occasionally observed here.
            com.google.common.base.Preconditions.checkState(
                newValue != null,
                ("Error while attempting to validate new values from starlark"
                        + " transition(s) with the inputs and outputs %s. Post-transition configuration should"
                        + " include '%s' but only includes starlark options: %s. If you run into this error"
                        + " please ping b/154132845 or email blaze-configurability@google.com."),
                inputAndOutputSettings,
                setting,
                options.getStarlarkOptions().keySet()
            )
            val allowsMultiple: Boolean = details.buildSettingIsAllowsMultiple().contains(setting)
            if (allowsMultiple) {
                // if this setting allows multiple settings
                if (newValue !is MutableList<*>) {
                    throw TransitionException(
                        String.format(
                            "'%s' allows multiple values and must be set"
                                    + " in transition using a starlark list instead of single value '%s'",
                            setting, newValue
                        )
                    )
                }
                val convertedValue: MutableList<Any?> = java.util.ArrayList<Any?>()
                val type: com.google.devtools.build.lib.packages.Type<*> = details.buildSettingToType().get(setting)
                for (value in newValue) {
                    try {
                        convertedValue.add(type.convert(value, maybeAliasSetting))
                    } catch (e: ConversionException) {
                        throw TransitionException(e)
                    }
                }
                return convertedValue == com.google.common.collect.ImmutableList.of<E?>(
                    details.buildSettingToDefault().get(setting)
                )
            } else {
                // if this setting does not allow multiple settings
                val convertedValue: Any
                try {
                    convertedValue =
                        details.buildSettingToType().get(setting).convert(newValue, maybeAliasSetting)
                } catch (e: ConversionException) {
                    throw TransitionException(e)
                }
                return convertedValue == details.buildSettingToDefault().get(setting)
            }
        }

        /**
         * Resolve aliased build setting issues
         * 
         * 
         * If a build setting is transitioned upon via an alias, the resulting [ ][BuildOptions.getStarlarkOptions] map will look like this:
         * 
         * 
         * <entry1>alias-label -> new-value <entry2>actual-label -> old-value
         * 
         * 
         * we need to collapse this to the correct single entry: actual-label -> new-value. By the end
         * of this method, the starlark options map in the returned [BuildOptions] contains only
         * keys that are actual build settings, no aliases.
        </entry2></entry1> */
        private fun unalias(
            options: BuildOptions,
            aliasToActual: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>
        ): BuildOptions {
            if (aliasToActual.isEmpty()) {
                return options
            }
            val aliases: MutableCollection<com.google.devtools.build.lib.cmdline.Label?> = aliasToActual.keys
            val actuals: MutableCollection<com.google.devtools.build.lib.cmdline.Label?> = aliasToActual.values
            val toReturn: BuildOptions.Builder = options.toBuilder()
            for (entry in options.getStarlarkOptions().entrySet()) {
                val setting: com.google.devtools.build.lib.cmdline.Label? = entry.key
                if (actuals.contains(setting)) {
                    // if entry is keyed by an actual (e.g. <entry2> in javadoc), don't care about its value
                    // it's stale
                    continue
                }
                if (aliases.contains(setting)) {
                    // if an entry is keyed by an alias (e.g. <entry1> in javadoc), newly key (overwrite) its
                    // actual to its alias' value and remove the alias-keyed entry
                    toReturn.addStarlarkOption(
                        aliasToActual.get(setting), options.getStarlarkOptions().get(setting)
                    )
                    toReturn.removeStarlarkOption(setting)
                } else {
                    // else - just copy over
                    toReturn.addStarlarkOption(entry.key, entry.value)
                }
            }
            return toReturn.build()
        }

        fun getRelevantStarlarkSettingsFromTransition(
            transition: StarlarkTransition,
            flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
            settings: Settings
        ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> {
            var flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?> =
                flagsAliases
            if (transition.isExecTransition) {
                // Ignore flag aliases for exec transitions. Starlark flags will provide their exec
                // transition semantics in the flag definition.
                flagsAliases =
                    com.google.common.collect.ImmutableMap.of<String?, com.google.devtools.build.lib.cmdline.Label?>()
            }
            val result: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.cmdline.Label?>()
            when (settings) {
                INPUTS -> addLabelIfRelevant(
                    result, flagsAliases,
                    transition.inputs
                )

                OUTPUTS -> addLabelIfRelevant(result, flagsAliases, transition.outputs)
                INPUTS_AND_OUTPUTS -> {
                    addLabelIfRelevant(result, flagsAliases, transition.inputs)
                    addLabelIfRelevant(result, flagsAliases, transition.outputs)
                }
            }
            return result.build()
        }

        private fun addLabelIfRelevant(
            builder: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.cmdline.Label?>,
            flagsAliases: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.cmdline.Label?>,
            entries: Iterable<com.google.devtools.build.lib.cmdline.Label>
        ) {
            for (entry in entries) {
                if (entry
                        .getPackageIdentifier()
                    != LabelConstants.COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER
                ) {
                    builder.add(entry)
                } else {
                    val flagName: String = entry.getName()
                    val aliasTarget: com.google.devtools.build.lib.cmdline.Label? = flagsAliases.get(flagName)
                    if (aliasTarget != null) {
                        builder.add(aliasTarget)
                    }
                }
            }
        }
    }
}
