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
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.BUILD_SETTING_CONVERTERS

/**
 * Implementation for the config_setting rule.
 * 
 * 
 * This is a "pseudo-rule" in that its purpose isn't to generate output artifacts from input
 * artifacts. Rather, it provides configuration context to rules that depend on it.
 */
class ConfigSetting : RuleConfiguredTargetFactory {
    /**
     * The settings this `config_setting` expects.
     * 
     * @param nativeFlagSettings native flags that match this rule (defined in Bazel code)
     * @param userDefinedFlagSettings user-defined flags that match this rule (defined in Starlark)
     * @param constraintValueSettings the current platform's expected `constraint_value`s
     */
    private class Settings(
        nativeFlagSettings: com.google.common.collect.ImmutableMultimap<String?, String?>?,
        userDefinedFlagSettings: com.google.common.collect.ImmutableMap<Label?, String?>?,
        constraintValueSettings: com.google.common.collect.ImmutableList<Label?>?
    ) {
        val nativeFlagSettings: com.google.common.collect.ImmutableMultimap<String?, String?>?
        val userDefinedFlagSettings: com.google.common.collect.ImmutableMap<Label?, String?>?
        val constraintValueSettings: com.google.common.collect.ImmutableList<Label?>?

        init {
            this.nativeFlagSettings = nativeFlagSettings
            this.userDefinedFlagSettings = userDefinedFlagSettings
            this.constraintValueSettings = constraintValueSettings
        }
    }

    @Throws(java.lang.InterruptedException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val attributes: AttributeMap = NonconfigurableAttributeMapper.of(ruleContext.getRule())

        val likelyLabelInvalidSetting: java.util.Optional<String?> =
            attributes.get(ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE, Types.STRING_DICT).keySet().stream()
                .filter({ s -> s.startsWith("@") || s.startsWith("//") || s.startsWith(":") })
                .findFirst()
        if (likelyLabelInvalidSetting.isPresent()) {
            ruleContext.attributeError(
                ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE,
                String.format(
                    "'%s' is not a valid setting name, but appears to be a label. Did you mean to place"
                            + " it in %s instead?",
                    likelyLabelInvalidSetting.get(), ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE
                )
            )
            return null
        }

        val settings = getSettings(ruleContext, attributes)
        // Check that this config_setting contains at least one of {values, define_values,
        // constraint_values}
        if (!valuesAreSet(settings, ruleContext)) {
            return null
        }

        val optionDetails: BuildOptionDetails = ruleContext.getConfiguration().getBuildOptionDetails()
        val nativeFlagsResult: MatchResult? =
            diffNativeFlags(settings.nativeFlagSettings.entries(), optionDetails, ruleContext)
        val userDefinedFlags =
            UserDefinedFlagMatch.Companion.fromAttributeValueAndPrerequisites(
                settings.userDefinedFlagSettings, optionDetails, ruleContext
            )
        val constraintValuesResult: MatchResult? = diffConstraintValues(ruleContext)

        if (ruleContext.hasErrors()) {
            return null
        }

        if (ruleContext.getConfiguration().stampBinaries()
            && settings.nativeFlagSettings.containsKey("stamp")
        ) {
            ruleContext.getAnalysisEnvironment().declareStampSettingDep()
        }

        val configMatcher: ConfigMatchingProvider? =
            ConfigMatchingProvider.create(
                ruleContext.getLabel(),
                settings.nativeFlagSettings,
                userDefinedFlags.getSpecifiedFlagValues(),
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (getSpecifiedConstraintValues(ruleContext)),
                java.util.stream.Stream.of<Any?>(userDefinedFlags.result(), nativeFlagsResult, constraintValuesResult)
                    .reduce(MatchResult::combine)
                    .get()
            )

        return RuleConfiguredTargetBuilder(ruleContext)
            .addProvider(RunfilesProvider::class.java, RunfilesProvider.EMPTY)
            .addProvider(FileProvider::class.java, FileProvider.EMPTY)
            .addProvider(FilesToRunProvider::class.java, FilesToRunProvider.EMPTY)
            .addProvider(ConfigMatchingProvider::class.java, configMatcher)
            .build()
    }

    public override fun addRuleImplSpecificRequiredConfigFragments(
        requiredFragments: RequiredConfigFragmentsProvider.Builder,
        attributes: AttributeMap,
        configuration: BuildConfigurationValue
    ) {
        // values
        attributes
            .get(ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE, Types.STRING_DICT)
            .forEach(
                { optionName, value ->
                    if (optionName.equals("define")) {
                        val equalsIndex: Int = value.indexOf('=')
                        requiredFragments.addDefine(
                            if (equalsIndex > 0) value.substring(0, equalsIndex) else value
                        )
                    } else {
                        val optionsClass: java.lang.Class<out FragmentOptions?>? =
                            configuration.getBuildOptionDetails().getOptionClass(optionName)
                        if (optionsClass != null) {
                            requiredFragments.addOptionsClass(optionsClass)
                        }
                    }
                })

        // define_values
        requiredFragments.addDefines(
            attributes.get(ConfigSettingRule.Companion.DEFINE_SETTINGS_ATTRIBUTE, Types.STRING_DICT).keySet()
        )

        // flag_values
        requiredFragments.addStarlarkOptions(
            attributes
                .get(ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE, BuildType.LABEL_KEYED_STRING_DICT)
                .keySet()
        )
    }

    private class UserDefinedFlagMatch(
        result: MatchResult?,
        specifiedFlagValues: com.google.common.collect.ImmutableMap<Label?, String?>?
    ) {
        private val result: MatchResult?
        private val specifiedFlagValues: com.google.common.collect.ImmutableMap<Label?, String?>?

        init {
            this.result = result
            this.specifiedFlagValues = specifiedFlagValues
        }

        /** Returns whether the specified flag values matched the actual flag values.  */
        fun result(): MatchResult? {
            return result
        }

        /** Gets the specified flag values, with aliases converted to their original targets' labels.  */
        fun getSpecifiedFlagValues(): com.google.common.collect.ImmutableMap<Label?, String?>? {
            return specifiedFlagValues
        }

        companion object {
            private val QUOTED_COMMA_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on("', '")

            /** Groups aliases in the list of prerequisites by the target they point to.  */
            private fun collectAliases(
                prerequisites: Iterable<out TransitiveInfoCollection>
            ): com.google.common.collect.ListMultimap<Label?, Label?> {
                val targetsToAliases: com.google.common.collect.ImmutableListMultimap.Builder<Label?, Label?> =
                    com.google.common.collect.ImmutableListMultimap.Builder<Label?, Label?>()
                for (target in prerequisites) {
                    targetsToAliases.put(target.label, AliasProvider.getDependencyLabel(target))
                }
                return targetsToAliases.build()
            }

            /**
             * The 'flag_values' attribute takes a label->string dictionary of feature flags and
             * starlark-defined settings to their values in string form.
             * 
             * @param attributeValue map of user-defined flag labels to their values as set in the
             * 'flag_values' attribute
             * @param optionDetails information about the configuration to match against
             * @param ruleContext this rule's RuleContext
             */
            fun fromAttributeValueAndPrerequisites(
                attributeValue: MutableMap<Label?, String?>,
                optionDetails: BuildOptionDetails,
                ruleContext: RuleContext
            ): UserDefinedFlagMatch {
                val specifiedFlagValues: MutableMap<Label?, String?> = LinkedHashMap<Label?, String?>()

                val diffs: java.util.ArrayList<NoMatch.Diff?> = java.util.ArrayList<NoMatch.Diff?>()
                // Only configuration-dependent errors should be deferred.
                val deferredErrors: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
                var foundDuplicate = false

                // Get the actual targets the 'flag_values' keys reference.
                val prerequisites: LinkedHashSet<TransitiveInfoCollection> = LinkedHashSet<TransitiveInfoCollection>()
                prerequisites.addAll(ruleContext.getPrerequisites(ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE))
                prerequisites.addAll(
                    ruleContext.getPrerequisites(ConfigSettingRule.Companion.FLAG_ALIAS_SETTINGS_ATTRIBUTE)
                )

                for (target in prerequisites) {
                    val actualLabel: Label? = target.label
                    val specifiedLabel: Label? = AliasProvider.getDependencyLabel(target)
                    val specifiedValue =
                        maybeCanonicalizeLabel(attributeValue.get(specifiedLabel), target, ruleContext)
                    if (specifiedFlagValues.containsKey(actualLabel)) {
                        foundDuplicate = true
                    }
                    specifiedFlagValues.put(actualLabel, specifiedValue)

                    if (target.satisfies(ConfigFeatureFlagProvider.Companion.REQUIRE_CONFIG_FEATURE_FLAG_PROVIDER)) {
                        // config_feature_flag
                        val provider: ConfigFeatureFlagProvider = ConfigFeatureFlagProvider.Companion.fromTarget(target)
                        if (!provider.isValidValue(specifiedValue)) {
                            // This is a configuration-independent error on the attributes of config_setting.
                            // So, is appropriate to error immediately.
                            ruleContext.attributeError(
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                String.format(
                                    "error while parsing user-defined configuration values: "
                                            + "'%s' is not a valid value for '%s'",
                                    specifiedValue, specifiedLabel
                                )
                            )
                            continue
                        }
                        if (!com.google.common.base.Strings.isNullOrEmpty(provider.getError())) {
                            deferredErrors.add(provider.getError())
                            continue
                        } else if (provider.getFlagValue() != specifiedValue) {
                            diffs.add(
                                NoMatch.Diff.what(specifiedLabel)
                                    .got(specifiedValue)
                                    .want(provider.getFlagValue())
                                    .build()
                            )
                        }
                    } else if (target.satisfies(BuildSettingProvider.REQUIRE_BUILD_SETTING_PROVIDER)) {
                        // build setting
                        val provider: BuildSettingProvider = target.getProvider(BuildSettingProvider::class.java)

                        val configurationValue: Any
                        if (optionDetails.getOptionValue(provider.getLabel()) != null) {
                            configurationValue = optionDetails.getOptionValue(provider.getLabel())
                        } else {
                            configurationValue = provider.getDefaultValue()
                        }

                        val convertedSpecifiedValue: Any
                        try {
                            // We don't need to supply a base package or repo mapping for the conversion here,
                            // because `specifiedValue` is already canonicalized.
                            convertedSpecifiedValue =
                                BUILD_SETTING_CONVERTERS
                                    .get(provider.getType())
                                    .convert(specifiedValue,  /* conversionContext= */null)
                        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                            // This is a configuration-independent error on the attributes of config_setting.
                            // So, is appropriate to error immediately.
                            ruleContext.attributeError(
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                java.lang.String.format(
                                    "error while parsing user-defined configuration values: "
                                            + "'%s' cannot be converted to %s type %s",
                                    specifiedValue, specifiedLabel, provider.getType()
                                )
                            )
                            continue
                        }

                        if (configurationValue is MutableList<*> || configurationValue is MutableSet<*>) {
                            // If the build_setting is a list or set, it's either an allow-multiple string-typed
                            // build setting, a string_list-typed build setting or a string_set-typed build setting.
                            // We use the same semantics as for multi-value native flags: if *any* entry in the list
                            // matches the config_setting's expected entry, it's a match. In other words,
                            // config_setting(flag_values {"//foo": "bar"} matches //foo=["bar", "baz"].

                            // If this is an allow-multiple build setting, the converter will have converted the
                            // config settings value to a singular object, if it's a string_list or string_set build
                            // setting the converter will have converted it to a list or set respectively.

                            val specifiedValueAsIterable: Iterable<*> =
                                (if (provider.allowsMultiple())
                                    com.google.common.collect.ImmutableList.of<kotlin.Any?>(convertedSpecifiedValue)
                                else
                                    convertedSpecifiedValue as Iterable<*>?)!!
                            if (com.google.common.collect.Iterables.size(specifiedValueAsIterable) != 1) {
                                // This is a configuration-independent error on the attributes of config_setting.
                                // So, is appropriate to error immediately.
                                ruleContext.attributeError(
                                    ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                    String.format(
                                        ("\"%s\" not a valid value for flag %s. Only single, exact values are"
                                                + " allowed. If you want to match multiple values, consider Skylib's "
                                                + "selects.config_setting_group"),
                                        specifiedValue, specifiedLabel
                                    )
                                )
                            } else if (!configurationValue
                                    .contains(
                                        com.google.common.collect.Iterables.getOnlyElement(
                                            specifiedValueAsIterable
                                        )
                                    )
                            ) {
                                diffs.add(
                                    NoMatch.Diff.what(specifiedLabel)
                                        .got(convertedSpecifiedValue.toString())
                                        .want(configurationValue.toString())
                                        .build()
                                )
                            }
                        } else if (configurationValue != convertedSpecifiedValue) {
                            diffs.add(
                                NoMatch.Diff.what(specifiedLabel)
                                    .got(convertedSpecifiedValue.toString())
                                    .want(configurationValue.toString())
                                    .build()
                            )
                        }
                    } else {
                        // This should be configuration-independent error on the attributes of config_setting.
                        // So, is appropriate to error immediately.
                        // 'Should' b/c the underlying flag rule COULD change providers based on configuration;
                        // however, this is HIGHLY irregular.
                        ruleContext.attributeError(
                            ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                            String.format(
                                "error while parsing user-defined configuration values: "
                                        + "%s keys must be build settings or feature flags and %s is not",
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE, specifiedLabel
                            )
                        )
                    }
                }

                // attributeValue is the source of the prerequisites in prerequisites, so the final map built
                // from iterating over prerequisites should always be the same size, barring duplicates.
                assert(foundDuplicate || attributeValue.size == specifiedFlagValues.size)

                if (foundDuplicate) {
                    val aliases: com.google.common.collect.ListMultimap<Label?, Label?> = collectAliases(prerequisites)
                    for (actualLabel in aliases.keySet()) {
                        val aliasList: MutableList<Label?> = aliases.get(actualLabel)
                        if (aliasList.size > 1) {
                            // This is a configuration-independent error on the attributes of config_setting.
                            // So, is appropriate to error immediately.
                            ruleContext.attributeError(
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                String.format(
                                    "flag '%s' referenced multiple times as ['%s']",
                                    actualLabel, QUOTED_COMMA_JOINER.join(aliasList)
                                )
                            )
                        }
                    }
                }
                val matchResult: MatchResult?
                if (!deferredErrors.isEmpty()) {
                    matchResult = InError(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (deferredErrors))
                } else if (ruleContext.hasErrors()) {
                    matchResult = MatchResult.ALREADY_REPORTED_NO_MATCH
                } else if (!diffs.isEmpty()) {
                    matchResult = NoMatch(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (diffs))
                } else {
                    matchResult = MatchResult.MATCH
                }
                return UserDefinedFlagMatch(
                    matchResult,
                    com.google.common.collect.ImmutableMap.copyOf<Label?, String?>(specifiedFlagValues)
                )
            }
        }
    }

    companion object {
        /** Flags we'd like to remove once there are no more repo references.  */
        private val DEPRECATED_PRE_PLATFORMS_FLAGS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("cpu", "host_cpu", "crosstool_top")

        /** Returns this `config_setting`'s expected settings.  */
        private fun getSettings(ruleContext: RuleContext, attributes: AttributeMap): Settings {
            // Collect expected flags from "values" and "define_values" attributes.
            val nativeValueAttributes: com.google.common.collect.ImmutableMultimap<String?, String?> =
                com.google.common.collect.ImmutableMultimap.builder<String?, String?>()
                    .putAll(
                        attributes.get(ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE, Types.STRING_DICT).entrySet()
                    )
                    .putAll(
                        attributes
                            .get(ConfigSettingRule.Companion.DEFINE_SETTINGS_ATTRIBUTE, Types.STRING_DICT)
                            .entrySet()
                            .stream()
                            .map({ `in` ->
                                com.google.common.collect.Maps.immutableEntry<K?, V?>(
                                    "define",
                                    `in`.getKey() + "=" + `in`.getValue()
                                )
                            })
                            .collect(TODO("Cannot convert element"))<E> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()
                    )
            build()

            // Find --flag_alias=foo=//bar settings. When these are set, "--foo" isn't a native flag but an
            // alias to "//bar". Since Bazel's options parsing replaces "--foo" with "//bar", we want to do
            // the same here to match the parsed options. Generally, all logic reading any user API that
            // sets "--foo" should do this.
            val commandLineFlagAliases: com.google.common.collect.ImmutableMap<String?, Label?> =
                ruleContext
                    .getConfiguration()
                    .getOptions()
                    .get(CoreOptions::class.java)
                    .getCommandLineFlagAliasesMap()

            // Partition expected "--foo" settings (native flag style) by whether they're flag aliases.
            val nativeValuesPartitionedByAlias: MutableMap<Boolean?, MutableList<MutableMap.MutableEntry<String?, String?>?>?> =
                nativeValueAttributes.entries().stream()
                    .collect(
                        Collectors.partitioningBy(
                            java.util.function.Predicate { entry: MutableMap.MutableEntry<String?, String?>? ->
                                commandLineFlagAliases.containsKey(
                                    entry!!.key
                                )
                            })
                    )

            // Collect actual native flags that aren't flag aliases.
            val nativeFlagSettings: com.google.common.collect.ImmutableMultimap<String?, String?> =
                com.google.common.collect.ImmutableMultimap.< String, String>copyOf<kotlin.String?, kotlin.String?>(
            nativeValuesPartitionedByAlias.get(false).stream()
                .collect(
                    TODO("Cannot convert element")
                ) as com.google.common.collect.ListMultimap<String?, String?>? < java.util.Map.Entry<String, String> , String, String, ListMultimap < String, String shr com.google.common.collect.Multimaps.toMultimap<kotlin.Any?, kotlin.Any?, kotlin.Any?, com.google.common.collect.ListMultimap<kotlin.Any?, kotlin.Any?>?>(
            java.util.function.Function { java.util.Map.Entry.key },
            java.util.function.Function { java.util.Map.Entry.value },
            java.util.function.Supplier {
                com.google.common.collect.MultimapBuilder.linkedHashKeys().arrayListValues().build()
            }))


            // Collect user-defined flags.
            val userDefinedFlagSettings: LinkedHashMap<Label?, String?> = LinkedHashMap<Label?, String?>()
            userDefinedFlagSettings.putAll(
                attributes.get(
                    ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE, BuildType.LABEL_KEYED_STRING_DICT
                )
            )
            for (flagAlias in nativeValuesPartitionedByAlias.get(true)!!) {
                val userDefinedFlag: Label? = commandLineFlagAliases.get(flagAlias!!.key)
                val aliasValue = flagAlias.value
                val flagSettingsAttributeValue: String? = userDefinedFlagSettings.get(userDefinedFlag)
                if (flagSettingsAttributeValue != null && flagSettingsAttributeValue != aliasValue) {
                    ruleContext.ruleError(
                        """

Conflicting flag value expectations:
 - %s has '%s = {"%s": "%s"}'.
 - Because --%s is a flag alias for --%s, this translates to '%s = {"%s: "%s"}'.
 - %s also has '%s = {"%s": "%s"}', which matches a different value.

Either remove one of these settings or ensure they match the same value.



"""
                            .trimIndent()
                            .formatted(
                                ruleContext.getLabel(),
                                ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE,
                                flagAlias.key,
                                aliasValue,
                                flagAlias.key,
                                userDefinedFlag,
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                userDefinedFlag,
                                aliasValue,
                                ruleContext.getLabel(),
                                ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                                userDefinedFlag,
                                flagSettingsAttributeValue
                            )
                    )
                }
                userDefinedFlagSettings.put(userDefinedFlag, aliasValue)
            }

            // Collect platform constraint settings.
            val constraintValueSettings: com.google.common.collect.ImmutableList<Label?> =
                com.google.common.collect.ImmutableList.copyOf(
                    attributes.get(ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE, BuildType.LABEL_LIST)
                )

            return Settings(
                nativeFlagSettings,
                com.google.common.collect.ImmutableMap.copyOf<Label?, String?>(userDefinedFlagSettings),
                constraintValueSettings
            )
        }

        /**
         * Returns true if all `constraint_values` settings are valid and match this
         * configuration, false otherwise.
         * 
         * 
         * May generate rule errors on bad settings (e.g. wrong target types).
         */
        private fun diffConstraintValues(ruleContext: RuleContext): MatchResult? {
            val constraintValues: MutableList<ConstraintValueInfo> = java.util.ArrayList<ConstraintValueInfo>()
            for (dep in ruleContext.getPrerequisites(ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE)) {
                if (!PlatformProviderUtils.hasConstraintValue(dep)) {
                    ruleContext.attributeError(
                        ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE,
                        dep.label + " is not a constraint_value"
                    )
                } else {
                    constraintValues.add(PlatformProviderUtils.constraintValue(dep))
                }
            }
            if (ruleContext.hasErrors()) {
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            if (constraintValues.isEmpty()) {
                return MatchResult.MATCH
            }

            // The set of constraint_values in a config_setting should never contain multiple
            // constraint_values that map to the same constraint_setting. This method checks if there are
            // duplicates and records an error if so.
            try {
                ConstraintCollection.validateConstraints(constraintValues)
            } catch (e: ConstraintCollection.DuplicateConstraintException) {
                ruleContext.ruleError(
                    ConstraintCollection.DuplicateConstraintException.formatError(e.duplicateConstraints())
                )
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            if (ruleContext.getToolchainContext() == null) {
                ruleContext.attributeError(
                    ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE, "No target platform is present"
                )
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            val targetPlatformConstraints: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                ruleContext.getToolchainContext().targetPlatform().constraints()
            if (targetPlatformConstraints.containsAll(constraintValues)) {
                return MatchResult.MATCH
            }

            val diffs: com.google.common.collect.ImmutableList.Builder<NoMatch.Diff?> =
                com.google.common.collect.ImmutableList.builder<NoMatch.Diff?>()
            for (ruleConstraintValue in constraintValues) {
                val setting: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    ruleConstraintValue.constraint()
                val targetPlatformValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    targetPlatformConstraints.get(setting)
                if (!ruleConstraintValue.equals(targetPlatformValue)) {
                    diffs.add(
                        NoMatch.Diff.what(setting.label())
                            .want(ruleConstraintValue.label().getName())
                            .got(
                                if (targetPlatformValue != null) targetPlatformValue.label().getName() else "<unset>"
                            )
                            .build()
                    )
                }
            }
            return NoMatch(diffs.build())
        }

        /**
         * Returns whether the given label falls under the `//tools` package (including subpackages)
         * of the tools repository.
         */
        @com.google.common.annotations.VisibleForTesting
        fun isUnderToolsPackage(label: Label, toolsRepository: RepositoryName?): Boolean {
            val packageId: PackageIdentifier = label.getPackageIdentifier()
            if (!packageId.getRepository().equals(toolsRepository)) {
                return false
            }
            try {
                return packageId.getPackageFragment().subFragment(0, 1).equals(PathFragment.create("tools"))
            } catch (e: java.lang.IndexOutOfBoundsException) {
                // Top-level package (//).
                return false
            }
        }

        /** User error when value settings can't be properly parsed.  */
        private const val PARSE_ERROR_MESSAGE = "error while parsing configuration settings: "

        /**
         * Check to make sure this config_setting contains and sets least one of {values, define_values,
         * flag_value or constraint_values}.
         */
        private fun valuesAreSet(settings: Settings, errors: RuleErrorConsumer): Boolean {
            if (settings.nativeFlagSettings.isEmpty()
                && settings.userDefinedFlagSettings.isEmpty()
                && settings.constraintValueSettings.isEmpty()
            ) {
                errors.ruleError(
                    String.format(
                        "Either %s, %s or %s must be specified and non-empty",
                        ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE,
                        ConfigSettingRule.Companion.FLAG_SETTINGS_ATTRIBUTE,
                        ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE
                    )
                )
                return false
            }
            return true
        }

        /**
         * Given a list of [flagName, flagValue] pairs for native Blaze flags, returns true if flagName ==
         * flagValue for every item in the list under this configuration, false otherwise.
         */
        private fun diffNativeFlags(
            expectedSettings: MutableCollection<MutableMap.MutableEntry<String?, String?>?>,
            options: BuildOptionDetails,
            ruleContext: RuleContext
        ): MatchResult? {
            // Rather than returning fast when we find a mismatch, continue looking at the other flags
            // to check they're indeed valid flag specifications.
            return expectedSettings.stream()
                .map<Any?> { entry: MutableMap.MutableEntry<String?, String?>? ->
                    val optionName = entry!!.key
                    val expectedRawValue = entry.value
                    checkOptionValue(options, ruleContext, optionName, expectedRawValue)
                }
                .reduce(MatchResult.MATCH, MatchResult::combine)
        }

        /** Returns `true` if the option is set to the expected value in the configuration.  */
        private fun checkOptionValue(
            options: BuildOptionDetails,
            ruleContext: RuleContext,
            optionName: String?,
            expectedRawValue: String?
        ): MatchResult? {
            val disabledSelectOptions: com.google.common.collect.ImmutableList<String?> =
                ruleContext
                    .getConfiguration()
                    .getOptions()
                    .get(CoreOptions::class.java)
                    .getDisabledSelectOptions()
            if (disabledSelectOptions.contains(optionName) || options.isNonConfigurable(optionName)) {
                var message = PARSE_ERROR_MESSAGE + "select() on '%s' is not allowed."
                if (DEPRECATED_PRE_PLATFORMS_FLAGS.contains(optionName)) {
                    message +=
                        (" Use platform constraints instead:"
                                + " https://bazel.build/docs/configurable-attributes#platforms.")
                }
                ruleContext.attributeError(
                    ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE, String.format(message, optionName)
                )
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            if (DEPRECATED_PRE_PLATFORMS_FLAGS.contains(optionName)
                && ruleContext.getLabel().getRepository().isMain()
            ) {
                ruleContext.ruleWarning(
                    String.format(
                        "select() on %s is deprecated. Use platform constraints instead:"
                                + " https://bazel.build/docs/configurable-attributes#platforms.",
                        optionName
                    )
                )
            }
            // If option --foo has oldName --old_foo and the config_setting references --old_foo, get the
            // canonical name, which is where the actual option is stored.
            val canonicalOptionName: String? = options.getCanonicalName(optionName)
            val optionClass: java.lang.Class<out FragmentOptions?>? = options.getOptionClass(canonicalOptionName)
            if (optionClass == null) {
                if (isTestOption(canonicalOptionName)) {
                    // If TestOptions isn't present then they were trimmed, so any test options set are
                    // considered unset by default.
                    return NoMatch(
                        NoMatch.Diff.what(toOptionLabel(optionName))
                            .want(expectedRawValue)
                            .got("<test option trimmed>")
                            .build()
                    )
                }

                // Report the unknown option as an error.
                ruleContext.attributeError(
                    ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE,
                    String.format(PARSE_ERROR_MESSAGE + "unknown option: '%s'", optionName)
                )
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            val parser: com.google.devtools.common.options.OptionsParser?
            try {
                parser = com.google.devtools.common.options.OptionsParser.builder().optionsClasses(optionClass).build()
                parser.parse("--" + optionName + "=" + expectedRawValue)
            } catch (ex: com.google.devtools.common.options.OptionsParsingException) {
                ruleContext.attributeError(
                    ConfigSettingRule.Companion.SETTINGS_ATTRIBUTE, PARSE_ERROR_MESSAGE + ex.message
                )
                return MatchResult.ALREADY_REPORTED_NO_MATCH
            }

            val expectedParsedValue: Any? = parser.getOptions<O?>(optionClass).asMap().get(canonicalOptionName)
            return optionMatches(options, canonicalOptionName, expectedParsedValue)
        }

        // Special hard-coded check to allow config_setting to handle test options even when the test
        // configuration has been trimmed.
        private fun isTestOption(optionName: String?): Boolean {
            return com.google.devtools.common.options.IsolatedOptionsData.getAllOptionDefinitionsForClass(TestOptions::class.java)
                .stream()
                .map<String?> { obj: com.google.devtools.common.options.OptionDefinition? -> obj.getOptionName() }
                .anyMatch { name: String? -> name == optionName }
        }

        /**
         * For single-value options, returns true iff the option's value matches the expected value.
         * 
         * 
         * For multi-value List options returns true iff any of the option's values matches the
         * expected value(s). This means "--ios_multi_cpus=a --ios_multi_cpus=b --ios_multi_cpus=c"
         * matches the expected conditions {'ios_multi_cpus': 'a' } and { 'ios_multi_cpus': 'b,c' } but
         * not { 'ios_multi_cpus': 'd' }.
         * 
         * 
         * For multi-value Map options, returns true iff the last instance with the same key as the
         * expected key has the same value. This means "--define foo=1 --define bar=2" matches { 'define':
         * 'foo=1' }, but "--define foo=1 --define bar=2 --define foo=3" doesn't match. Note that the
         * definition of --define states that the last instance takes precedence. Also note that there's
         * no options-parsing support for multiple values in a single clause, e.g. { 'define':
         * 'foo=1,bar=2' } expands to { "foo": "1,bar=2" }, not {"foo": 1, "bar": "2"}.
         */
        private fun optionMatches(
            options: BuildOptionDetails, optionName: String?, expectedValue: Any?
        ): MatchResult? {
            val actualValue: Any? = options.getOptionValue(optionName)
            if (actualValue == null) {
                return if (expectedValue == null)
                    MatchResult.MATCH
                else
                    NoMatch(
                        NoMatch.Diff.what(toOptionLabel(optionName))
                            .want(expectedValue.toString())
                            .got("null")
                            .build()
                    )

                // Single-value case:
            } else if (!options.allowsMultipleValues(optionName)) {
                return if (actualValue == expectedValue)
                    MatchResult.MATCH
                else
                    NoMatch(
                        NoMatch.Diff.what(toOptionLabel(optionName))
                            .want(expectedValue.toString())
                            .got(actualValue.toString())
                            .build()
                    )
            }

            // Multi-value case:
            com.google.common.base.Preconditions.checkState(actualValue is MutableList<*>)
            com.google.common.base.Preconditions.checkState(expectedValue is MutableList<*>)
            val actualList = actualValue as MutableList<*>
            val expectedList = expectedValue as MutableList<*>

            if (actualList.isEmpty() || expectedList.isEmpty()) {
                return if (actualList.isEmpty() && expectedList.isEmpty())
                    MatchResult.MATCH
                else
                    NoMatch(
                        NoMatch.Diff.what(toOptionLabel(optionName))
                            .want(if (expectedList.isEmpty()) "<empty>" else expectedList.toString())
                            .got(if (actualList.isEmpty()) "<empty>" else actualList.toString())
                            .build()
                    )
            }

            // Multi-value map:
            if (actualList.get(0) is MutableMap.MutableEntry<*, *>) {
                // The config_setting's expected value *must* be a single map entry (see method comments).
                val expectedListValue: Any? = com.google.common.collect.Iterables.getOnlyElement(expectedList)
                val expectedEntry = expectedListValue as MutableMap.MutableEntry<*, *>
                for (elem in com.google.common.collect.Lists.reverse(actualList)) {
                    val actualEntry = elem as MutableMap.MutableEntry<*, *>
                    if (actualEntry.key == expectedEntry.key) {
                        // Found a key match!
                        return if (actualEntry.value == expectedEntry.value)
                            MatchResult.MATCH
                        else
                            NoMatch(
                                NoMatch.Diff.what(toOptionLabel(optionName))
                                    .want("%s=%s".formatted(expectedEntry.key, expectedEntry.value))
                                    .got("%s=%s".formatted(actualEntry.key, actualEntry.value))
                                    .build()
                            )
                    }
                }
                return NoMatch(
                    NoMatch.Diff.what(toOptionLabel(optionName))
                        .want("%s=%s".formatted(expectedEntry.key, expectedEntry.value))
                        .got("<key %s not found>".formatted(expectedEntry.key))
                        .build()
                )
            }

            // Multi-value list:
            return if (actualList.containsAll(expectedList))
                MatchResult.MATCH
            else
                NoMatch(
                    NoMatch.Diff.what(toOptionLabel(optionName))
                        .want(expectedList.toString())
                        .got(actualList.toString())
                        .build()
                )
        }

        private val COMMAND_LINE_OPTIONS_PACKAGE: PackageIdentifier? = PackageIdentifier.createInMainRepo(
            com.google.common.base.CharMatcher.anyOf(":").trimTrailingFrom(LabelConstants.COMMAND_LINE_OPTION_PREFIX)
        )

        private fun toOptionLabel(optionName: String?): Label {
            return Label.createUnvalidated(COMMAND_LINE_OPTIONS_PACKAGE, optionName)
        }

        /**
         * Given a 'flag_values = {"//ref:to:flagTarget": "expectedValue"}' pair, if expectedValue is a
         * relative label (e.g. ":sometarget") and flagTarget's value(s) are label-typed, returns an
         * absolute form of the label under the config_setting's package. Else returns the original value
         * unchanged.
         * 
         * 
         * This lets config_setting use relative labels to match against the actual values, which are
         * already represented in absolute form.
         * 
         * 
         * The value is returned as a string because it's subsequently fed through the flag's type
         * converter (which maps a string to the final type). Invalid labels are treated no differently
         * (they don't trigger special errors here) because the type converter will also handle that.
         * 
         * @param expectedValue the raw value the config_setting expects
         * @param flagTarget the target of the flag whose value is being checked
         * @param ruleContext this rule's RuleContext
         */
        private fun maybeCanonicalizeLabel(
            expectedValue: String?, flagTarget: TransitiveInfoCollection, ruleContext: RuleContext
        ): String? {
            if (!flagTarget.satisfies(BuildSettingProvider.REQUIRE_BUILD_SETTING_PROVIDER)) {
                return expectedValue
            }
            if (!BuildType.isLabelType(flagTarget.getProvider(BuildSettingProvider::class.java).getType())) {
                return expectedValue
            }
            try {
                return Label.parseWithPackageContext(expectedValue, ruleContext.getPackageContext())
                    .getUnambiguousCanonicalForm()
            } catch (e: LabelSyntaxException) {
                // Swallow this: the subsequent type conversion already checks for this.
                return expectedValue
            }
        }

        /**
         * Returns a list of labels for all prerequisite constraint values for this rule.
         * 
         * 
         * If any of the constraint values are provided via an alias, this method will resolve them to
         * their concrete targets. This is needed for specialization checking in select() statements.
         * 
         * @param ruleContext this rule's RuleContext
         */
        private fun getSpecifiedConstraintValues(ruleContext: RuleContext): MutableList<Label?> {
            return ruleContext.getPrerequisites(ConfigSettingRule.Companion.CONSTRAINT_VALUES_ATTRIBUTE).stream()
                .map(TransitiveInfoCollection::getLabel)
                .collect(Collectors.toList())
        }
    }
}
