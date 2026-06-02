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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.BUILD_SETTING_CONVERTERS

/**
 * An options parser for starlark defined options. Takes a mutable [OptionsParser] that has
 * already parsed all native options (including those needed for loading). This class is in charge
 * of parsing and setting the starlark options for this [OptionsParser].
 */
class StarlarkOptionsParser protected constructor(
    private val buildSettingLoader: BuildSettingLoader,
    nativeOptionsParser: com.google.devtools.common.options.OptionsParser,
    includeDefaultValues: Boolean
) {
    /**
     * Interface for caller-specific logic to convert flag names to [Target]s.
     * 
     * 
     * The most important distinction is whether the caller is in a [ ] evaluation environment.
     */
    fun interface BuildSettingLoader {
        /**
         * Converts a flag name into a [Target], or throws an exception if this can't be done.
         * 
         * @param name the flag to lookup, expected to be a valid [Label]
         * @return the [Target] corresponding to the flag, or null if the caller has to do more
         * work to retrieve the target (after which it'll call this parser again)
         */
        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        fun loadBuildSetting(name: String?): Target?
    }

    /** A helper class to create new instances of [StarlarkOptionsParser].  */
    @AutoBuilder(ofClass = StarlarkOptionsParser::class)
    abstract class Builder {
        /** Set the [BuildSettingLoader] used to find flags.  */
        abstract fun buildSettingLoader(buildSettingLoader: BuildSettingLoader?): Builder?

        /** Sets the native [OptionsParser] used for handling flags.  */
        abstract fun nativeOptionsParser(nativeOptionsParser: com.google.devtools.common.options.OptionsParser?): Builder?

        /** Whether or not to report Starlark flags which are set to their default values.  */
        abstract fun includeDefaultValues(includeDefaultValues: Boolean): Builder?

        /** Returns a new [StarlarkOptionsParser].  */
        abstract fun build(): StarlarkOptionsParser?
    }

    private val nativeOptionsParser: com.google.devtools.common.options.OptionsParser

    // TODO: https://github.com/bazelbuild/bazel/issues/22365 - Unify these maps into a common data
    // structure. Consider using OptionDefinition to simplify.
    // Result of #parse, store the parsed options and their values.
    private val starlarkOptions: MutableMap<String?, Any?> = TreeMap<String?, Any?>()

    // Map of starlark options to their {@link Scope.ScopeType}.
    private val scopes: MutableMap<String?, String?> = TreeMap<String?, String?>()

    // Map of starlark options to their on-leave scope values.
    private val onLeaveScopeValues: MutableMap<String?, Any?> = TreeMap<String?, Any?>()

    // Map of parsed starlark options to their loaded BuildSetting objects (used for canonicalization)
    private val parsedBuildSettings: MutableMap<String?, BuildSetting> = LinkedHashMap<String?, BuildSetting>()

    // Local cache of build settings so we don't repeatedly load them.
    private val buildSettings: MutableMap<String?, Target?> = HashMap<String?, Target?>()

    // The default value for each build setting.
    private val buildSettingDefaults: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>()

    // whether options explicitly set to their default values are added to {@code starlarkOptions}
    private val includeDefaultValues: Boolean

    init {
        this.nativeOptionsParser = nativeOptionsParser
        this.includeDefaultValues = includeDefaultValues
    }

    /**
     * Parses all pre "--" residue for Starlark options.
     * 
     * @return true if the flags are parsed, false if the [BuildSettingLoader] needs to do more
     * work to retrieve build setting targets (after which it'll call this method again)
     */
    // TODO(blaze-configurability): This method somewhat reinvents the wheel of
    // OptionsParserImpl.identifyOptionAndPossibleArgument. Consider combining. This would probably
    // require multiple rounds of parsing to fit starlark-defined options into native option format.
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class, com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(): Boolean {
        return parseGivenArgs(nativeOptionsParser.getSkippedArgs())
    }

    /**
     * Parses a specific set of flags.
     * 
     * @return true if the flags are parsed, false if the [BuildSettingLoader] needs to do more
     * work to retrieve build setting targets (after which it'll call this method again)
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class, com.google.devtools.common.options.OptionsParsingException::class)
    fun parseGivenArgs(args: MutableList<String>): Boolean {
        // Map of <option name (label), <unparsed option value, loaded option>>.
        val unparsedOptions: com.google.common.collect.Multimap<String?, com.google.devtools.build.lib.util.Pair<String?, Target?>?> =
            com.google.common.collect.LinkedListMultimap.create<String?, com.google.devtools.build.lib.util.Pair<String?, Target?>?>()

        var allTargetsAvailable = true
        for (arg in args) {
            if (!parseArg(arg, unparsedOptions)) {
                allTargetsAvailable = false
            }
        }

        if (!allTargetsAvailable) {
            return false
        } else if (unparsedOptions.isEmpty()) {
            return true
        }

        // Map of flag label as a string to its loaded target and set value after parsing.
        val buildSettingWithTargetAndValue: HashMap<String?, com.google.devtools.build.lib.util.Pair<Target?, Any?>> =
            HashMap<String?, com.google.devtools.build.lib.util.Pair<Target?, Any?>>()
        for (option in unparsedOptions.entries()) {
            val loadedFlag: String? = option.getKey()
            val unparsedValue: String? = option.getValue().first
            val buildSettingTarget: Target? = option.getValue().second
            val buildSetting: BuildSetting =
                buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting()
            // Do not recognize internal options, which are treated as if they did not exist.
            if (!buildSetting.isFlag) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Unrecognized option: %s=%s", loadedFlag, unparsedValue)
                )
            }
            var type: Type<*> = buildSetting.getType()
            if (buildSetting.isRepeatableFlag) {
                type = com.google.common.base.Preconditions.checkNotNull<T>(type.getListElementType())
            }
            val converter: com.google.devtools.common.options.Converter<*> = BUILD_SETTING_CONVERTERS.get(type)
            var value: Any?
            try {
                value = converter.convert(unparsedValue, nativeOptionsParser.getConversionContext())
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "While parsing option %s=%s: '%s' is not a %s",
                        loadedFlag, unparsedValue, unparsedValue, type
                    ),
                    e
                )
            }
            if (buildSetting.allowsMultiple() || buildSetting.isRepeatableFlag) {
                val newValue: MutableCollection<Any?>?
                val hasLoadedFlag: Boolean = buildSettingWithTargetAndValue.containsKey(loadedFlag)
                if (buildSetting.getType().equals(Types.STRING_SET)) {
                    newValue =
                        if (hasLoadedFlag)
                            LinkedHashSet<Any?>(
                                buildSettingWithTargetAndValue.get(loadedFlag).getSecond() as MutableCollection<*>?
                            )
                        else
                            LinkedHashSet<Any?>()
                } else {
                    newValue =
                        if (hasLoadedFlag)
                            java.util.ArrayList<Any?>(
                                buildSettingWithTargetAndValue.get(loadedFlag).getSecond() as MutableCollection<*>?
                            )
                        else
                            java.util.ArrayList<Any?>()
                }
                newValue!!.add(value)
                value = newValue
            }
            buildSettingWithTargetAndValue.put(
                loadedFlag,
                com.google.devtools.build.lib.util.Pair.of<Target?, Any?>(buildSettingTarget, value)
            )
        }

        val parsedOptions: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        val scopeTypeMap: MutableMap<String?, String?> = HashMap<String?, String?>()
        val onLeaveScopeMap: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        val customExecFlags: MutableList<String?> = java.util.ArrayList<String?>()
        for (buildSetting in buildSettingWithTargetAndValue.keySet()) {
            val buildSettingAndFinalValue: com.google.devtools.build.lib.util.Pair<Target?, Any?> =
                buildSettingWithTargetAndValue.get(buildSetting)
            val buildSettingTarget: Target? = buildSettingAndFinalValue.getFirst()
            val buildSettingObject: BuildSetting =
                buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting()
            val allowsMultiple: Boolean = buildSettingObject.allowsMultiple()
            parsedBuildSettings.put(buildSetting, buildSettingObject)
            var value: Any? = buildSettingAndFinalValue.getSecond()
            if (value is MutableCollection<*>) {
                if (buildSettingObject.getType().equals(Types.STRING_SET)) {
                    value = com.google.common.collect.ImmutableSortedSet.copyOf(value)
                } else {
                    value = com.google.common.collect.ImmutableList.copyOf(value)
                }
            }
            val rawDefaultValue: Any? =
                buildSettingTarget.getAssociatedRule().getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
            if (allowsMultiple) {
                val defaultValue: MutableList<*> = com.google.common.collect.ImmutableList.of<Any?>(
                    java.util.Objects.requireNonNull<Any?>(rawDefaultValue)
                )
                this.buildSettingDefaults.put(buildSetting, defaultValue)
                val newValue = value as MutableList<*>?
                if (newValue != defaultValue || includeDefaultValues) {
                    parsedOptions.put(buildSetting, value)
                }
            } else {
                if (rawDefaultValue != null) {
                    this.buildSettingDefaults.put(buildSetting, rawDefaultValue)
                }
                if (value != rawDefaultValue || includeDefaultValues) {
                    parsedOptions.put(buildSetting, value)
                }
            }

            // TODO: b/384058698 - use NonConfigurableAttributeMapper to ensure "scope" isn't selectable.
            val attrMap: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                RawAttributeMapper.of(buildSettingTarget.getAssociatedRule())
            var scopeType = ScopeType.DEFAULT.toString()
            if (attrMap.isAttributeValueExplicitlySpecified("scope")) {
                scopeType = attrMap.get("scope", Type.STRING)
                if (!ScopeType.allowedAttributeValues().contains(scopeType.toLowerCase(Locale.ROOT))
                    && !scopeType.startsWith(Scope.CUSTOM_EXEC_SCOPE_PREFIX)
                ) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        java.lang.String.format(
                            "Can't load flag --%s: Invalid \"scope\" attribute value \"%s\". Allowed values:"
                                    + " [%s].",
                            buildSetting,
                            scopeType,
                            ScopeType.allowedAttributeValues().stream()
                                .map({ s -> "\"" + s + "\"" })
                                .collect(Collectors.joining(", "))
                        )
                    )
                }
            }
            scopeTypeMap.put(buildSetting, scopeType)
            nativeOptionsParser.setScopesAttributes(
                com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                    scopeTypeMap
                )
            )

            if (scopeType.startsWith(Scope.CUSTOM_EXEC_SCOPE_PREFIX)) {
                customExecFlags.add(scopeType.substring(Scope.CUSTOM_EXEC_SCOPE_PREFIX.length()))
                scopeTypeMap.put(scopeType.substring(Scope.CUSTOM_EXEC_SCOPE_PREFIX.length()), scopeType)
            }

            if (attrMap.isAttributeValueExplicitlySpecified("on_leave_scope")) {
                val onLeaveScopeValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    attrMap.get("on_leave_scope", buildSettingObject.getType())
                onLeaveScopeMap.put(buildSetting, onLeaveScopeValue)
            }
        }

        // handling custom exec case with scope "exec:--<another_flag_name>".
        // For example: --python_launcher=--host_python_launcher
        // have the --<another_flag_name> flag in the target config but also make sure that it
        // won't propagate to the exec config by setting the scope to "target".
        for (customExecFlag in customExecFlags) {
            // if the custom exec flag is already in the parsedOptions, we use that value.
            if (parsedOptions.containsKey(customExecFlag)) {
                continue
            }

            // get the default value for the custom exec flag if it's not set yet.
            parsedOptions.put(customExecFlag, getDefaultValueForAnyBuildSetting(customExecFlag))
            scopeTypeMap.put(customExecFlag, ScopeType.TARGET)
        }

        nativeOptionsParser.setStarlarkOptions(
            com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(parsedOptions),
            this.starlarkOptionsAllowingMultiple
        )
        nativeOptionsParser.setOnLeaveScopeValues(
            com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(
                onLeaveScopeMap
            )
        )
        nativeOptionsParser.setScopesAttributes(
            com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                scopeTypeMap
            )
        )
        this.starlarkOptions.putAll(parsedOptions)
        this.scopes.putAll(scopeTypeMap)
        this.onLeaveScopeValues.putAll(onLeaveScopeMap)
        return true
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.common.options.OptionsParsingException::class)
    fun getDefaultValueForAnyBuildSetting(buildSetting: String?): Any? {
        val buildSettingTarget = loadBuildSetting(buildSetting)
        val buildSettingObject: BuildSetting =
            buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting()
        val defaultValue: Any? =
            buildSettingTarget.getAssociatedRule().getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
        if (buildSettingObject.allowsMultiple()) {
            return com.google.common.collect.ImmutableList.of<Any?>(java.util.Objects.requireNonNull<Any?>(defaultValue))
        }
        return defaultValue
    }

    /**
     * Parses the given `flag=value` setting.
     * 
     * @return true if parsing finishes, false if the [BuildSettingLoader] needs to do more work
     * to retrieve the build setting target
     */
    @Throws(java.lang.InterruptedException::class, com.google.devtools.common.options.OptionsParsingException::class)
    private fun parseArg(
        arg: String,
        unparsedOptions: com.google.common.collect.Multimap<String?, com.google.devtools.build.lib.util.Pair<String?, Target?>?>
    ): Boolean {
        if (!arg.startsWith("--")) {
            throw com.google.devtools.common.options.OptionsParsingException("Invalid options syntax: " + arg, arg)
        }
        // This isn't resilient against labels with the "=" character in them, e.g.
        // "//pkg/prefix=suffix". See https://bazel.build/concepts/labels#target-names.
        val equalsAt: Int = arg.indexOf('='.code)
        var name: String = if (equalsAt == -1) arg.substring(2) else arg.substring(2, equalsAt)
        if (name.trim().isEmpty()) {
            throw com.google.devtools.common.options.OptionsParsingException("Invalid options syntax: " + arg, arg)
        }
        val value: String? = if (equalsAt == -1) null else arg.substring(equalsAt + 1)

        if (value != null) {
            // --flag=value or -flag=value form
            val buildSettingTarget = loadBuildSetting(name)
            if (buildSettingTarget == null) {
                return false
            }
            // Use the canonical form to ensure we don't have
            // duplicate options getting into the starlark options map.
            unparsedOptions.put(
                buildSettingTarget.getLabel().getCanonicalForm(),
                com.google.devtools.build.lib.util.Pair<String?, Target?>(value, buildSettingTarget)
            )
        } else {
            var booleanValue = true
            // check --noflag form
            if (name.startsWith("no")) {
                booleanValue = false
                name = name.substring(2)
            }
            val buildSettingTarget = loadBuildSetting(name)
            if (buildSettingTarget == null) {
                return false
            }
            val current: BuildSetting =
                buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting()
            if (current.getType().equals(BOOLEAN)) {
                // --boolean_flag or --noboolean_flag
                // Ditto w/r/t canonical form.
                unparsedOptions.put(
                    buildSettingTarget.getLabel().getCanonicalForm(),
                    com.google.devtools.build.lib.util.Pair<String?, Target?>(
                        java.lang.String.valueOf(booleanValue),
                        buildSettingTarget
                    )
                )
            } else {
                if (!booleanValue) {
                    // --no(non_boolean_flag)
                    throw com.google.devtools.common.options.OptionsParsingException(
                        "Illegal use of 'no' prefix on non-boolean option: " + name, name
                    )
                }
                throw com.google.devtools.common.options.OptionsParsingException("Expected value after " + arg, arg)
            }
        }
        return true
    }

    /**
     * Returns the given build setting's [Target], following (unconfigured) aliases if needed.
     * 
     * @return the target, or null if the [BuildSettingLoader] needs to do more work to retrieve
     * the target
     */
    @Throws(java.lang.InterruptedException::class, com.google.devtools.common.options.OptionsParsingException::class)
    private fun loadBuildSetting(targetToBuild: String?): Target? {
        if (buildSettings.containsKey(targetToBuild)) {
            return buildSettings.get(targetToBuild)
        }

        var target: Target?
        var targetToLoadNext = targetToBuild
        val aliasChain: SequencedSet<Label?> = LinkedHashSet<Label?>()
        while (true) {
            try {
                target = buildSettingLoader.loadBuildSetting(targetToLoadNext)
                if (target == null) {
                    return null
                }
            } catch (e: TargetParsingException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Error loading option " + targetToBuild + ": " + e.getMessage(), targetToBuild, e
                )
            }
            if (!aliasChain.add(target.getLabel())) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        "Failed to load build setting '%s' due to a cycle in alias chain: %s",
                        targetToBuild,
                        formatAliasChain(
                            java.util.stream.Stream.concat<T?>(
                                aliasChain.stream(),
                                java.util.stream.Stream.of(target.getLabel())
                            )
                        )
                    ),
                    targetToBuild
                )
            }
            if (target.getAssociatedRule() == null) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format("Unrecognized option: %s", formatAliasChain(aliasChain.stream())),
                    targetToBuild
                )
            }
            if (target.getAssociatedRule().isBuildSetting()) {
                break
            }
            // Follow the unconfigured values of aliases.
            if (target.getAssociatedRule().getRuleClass().equals("alias")) {
                targetToLoadNext =
                    when (target.getAssociatedRule().getAttr("actual")) {
                        -> label.getUnambiguousCanonicalForm()
                        -> throw com.google.devtools.common.options.OptionsParsingException(
                            java.lang.String.format(
                                ("Failed to load build setting '%s' as it resolves to an alias with an"
                                        + " actual value that uses select(): %s. This is not supported as"
                                        + " build settings are needed to determine the configuration the"
                                        + " select is evaluated in."),
                                targetToBuild, formatAliasChain(aliasChain.stream())
                            ),
                            targetToBuild
                        )

                        null -> throw java.lang.IllegalStateException(
                            java.lang.String.format(
                                "Alias target '%s' with 'actual' attr value not equals to a label or a"
                                        + " selectorlist",
                                target.getLabel()
                            )
                        )
                    }
                continue
            }
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format("Unrecognized option: %s", formatAliasChain(aliasChain.stream())),
                targetToBuild
            )
        }



        buildSettings.put(targetToBuild, target)
        return target
    }

    fun getStarlarkOptions(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(this.starlarkOptions)
    }

    val starlarkOptionsAllowingMultiple: com.google.common.collect.ImmutableSet<String?>
        get() = parsedBuildSettings.entrySet().stream()
            .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<String?, BuildSetting>? ->
                entry.getValue().allowsMultiple() || entry.getValue().isRepeatableFlag
            })
            .map<String?>(java.util.function.Function { java.util.Map.Entry.getKey() })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())

    val scopesAttributes: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(this.scopes)

    val defaultValues: com.google.common.collect.ImmutableMap<String?, Any?>
        get() = com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(this.buildSettingDefaults)

    fun getOnLeaveScopeValues(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(this.onLeaveScopeValues)
    }

    fun checkIfParsedOptionAllowsMultiple(option: String?): Boolean {
        val setting: BuildSetting = parsedBuildSettings.get(option)
        return setting.allowsMultiple() || setting.isRepeatableFlag
    }

    fun getParsedOptionType(option: String?): Type<*> {
        return parsedBuildSettings.get(option).getType()
    }

    fun getDefaultValue(option: String?): Any? {
        return buildSettingDefaults.get(option)
    }

    /** Return a canoncalized list of the starlark options and values that this parser has parsed.  */
    fun canonicalize(): MutableList<String?> {
        val result: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        for (starlarkOption in starlarkOptions.entrySet()) {
            val starlarkOptionName: String = starlarkOption.getKey()
            val starlarkOptionValue: Any? = starlarkOption.getValue()
            val starlarkOptionString = "--" + starlarkOptionName + "="
            if (checkIfParsedOptionAllowsMultiple(starlarkOptionName)) {
                com.google.common.base.Preconditions.checkState(
                    starlarkOption.getValue() is MutableList<*> || starlarkOption.getValue() is MutableSet<*>,
                    "Found a starlark option value that isn't a list or set for an allow multiple option."
                )
                for (singleValue in (starlarkOptionValue as kotlin.collections.MutableCollection<*>?)!!) {
                    result.add(starlarkOptionString + singleValue)
                }
            } else if (getParsedOptionType(starlarkOptionName).equals(Types.STRING_LIST)
                || getParsedOptionType(starlarkOptionName).equals(Types.STRING_SET)
            ) {
                result.add(
                    starlarkOptionString + java.lang.String.join(",", (starlarkOptionValue as Iterable<String?>?))
                )
            } else {
                result.add(starlarkOptionString + starlarkOptionValue)
            }
        }
        return result.build()
    }

    companion object {
        /** Create a new [Builder] instance for [StarlarkOptionsParser].  */
        fun builder(): Builder {
            return AutoBuilder_StarlarkOptionsParser_Builder().includeDefaultValues(false)
        }

        private fun formatAliasChain(aliasChain: java.util.stream.Stream<Label?>): String? {
            return aliasChain.map<Any?>(Label::getCanonicalForm).collect(Collectors.joining(" -> "))
        }
    }
}
