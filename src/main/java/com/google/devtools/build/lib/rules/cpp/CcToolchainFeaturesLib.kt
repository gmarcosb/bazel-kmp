// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Utility functions that convert CcToolchainConfigInfo and feature configuration related Starlark
 * providers into native Java objects.
 */
object CcToolchainFeaturesLib {
    @com.google.errorprone.annotations.FormatMethod
    private fun infoError(info: Info, format: String, vararg args: Any?): net.starlark.java.eval.EvalException {
        return net.starlark.java.eval.Starlark.errorf(
            "in %s: %s", info.getProvider().getPrintableName(), java.lang.String.format(format, *args)
        )
    }

    /** Checks whether the [StarlarkInfo] is of the required type.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkRightProviderType(provider: StarlarkInfo, type: String) {
        var providerType = getValueOrNull(provider, "type_name") as String?
        if (providerType == null) {
            providerType = provider.getProvider().getPrintableName()
        }
        if (type != provider.getValue("type_name")) {
            throw infoError(provider, "Expected object of type '%s', received '%s'.", type, providerType)
        }
    }

    private fun getValueOrNull(x: net.starlark.java.eval.Structure, name: String?): Any? {
        try {
            return x.getValue(name)
        } catch (e: net.starlark.java.eval.EvalException) {
            return null
        }
    }

    /** Creates a [Feature] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun featureFromStarlark(featureStruct: StarlarkInfo): com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature {
        checkRightProviderType(featureStruct, "feature")
        val name: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            featureStruct,
            "name",
            kotlin.String::class.java
        )!!
        val enabled: Boolean =
            getMandatoryFieldFromStarlarkProvider<Boolean>(featureStruct, "enabled", Boolean::class.java)!!
        if (name == null || (name.isEmpty() && !enabled)) {
            throw infoError(
                featureStruct, "A feature must either have a nonempty 'name' field or be enabled."
            )
        }

        if (!name.matches("^[_a-z0-9+\\-\\.]*$")) {
            throw infoError(
                featureStruct,
                "A feature's name must consist solely of lowercase ASCII letters, digits, '.', "
                        + "'_', '+', and '-', got '%s'",
                name
            )
        }

        val flagSetBuilder: com.google.common.collect.ImmutableList.Builder<FlagSet?> =
            com.google.common.collect.ImmutableList.builder<FlagSet?>()
        val flagSets: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(featureStruct, "flag_sets")
        for (flagSetObject in flagSets) {
            val flagSet: FlagSet = flagSetFromStarlark(flagSetObject,  /* actionName= */null)
            if (flagSet.actions.isEmpty()) {
                throw infoError(
                    flagSetObject,
                    "A flag_set that belongs to a feature must have nonempty 'actions' parameter."
                )
            }
            flagSetBuilder.add(flagSet)
        }

        val envSetBuilder: com.google.common.collect.ImmutableList.Builder<EnvSet?> =
            com.google.common.collect.ImmutableList.builder<EnvSet?>()
        val envSets: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(featureStruct, "env_sets")
        for (envSet in envSets) {
            envSetBuilder.add(envSetFromStarlark(envSet))
        }

        val requiresBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableSet<String?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableSet<String?>?>()

        val requires: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(featureStruct, "requires")
        for (featureSetStruct in requires) {
            if ("feature_set" != featureSetStruct.getValue("type_name")) { // getValue() may be null
                throw infoError(featureStruct, "expected object of type 'feature_set'.")
            }
            val featureSet: com.google.common.collect.ImmutableSet<String?> =
                getStringSetFromStarlarkProviderField(featureSetStruct, "features")
            requiresBuilder.add(featureSet)
        }

        val implies: com.google.common.collect.ImmutableList<String?> =
            getStringListFromStarlarkProviderField(featureStruct, "implies")

        val provides: com.google.common.collect.ImmutableList<String?> =
            getStringListFromStarlarkProviderField(featureStruct, "provides")

        return com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature(
            name,
            flagSetBuilder.build(),
            envSetBuilder.build(),
            enabled,
            requiresBuilder.build(),
            implies,
            provides
        )
    }

    /**
     * Creates a Pair(name, value) that represents a [ ] from a [ ].
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun makeVariableFromStarlark(makeVariableStruct: StarlarkInfo): com.google.devtools.build.lib.util.Pair<String?, String?> {
        checkRightProviderType(makeVariableStruct, "make_variable")
        val name: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            makeVariableStruct,
            "name",
            kotlin.String::class.java
        )!!
        val value: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            makeVariableStruct,
            "value",
            kotlin.String::class.java
        )!!
        if (name == null || name.isEmpty()) {
            throw infoError(
                makeVariableStruct, "'name' parameter of make_variable must be a nonempty string."
            )
        }
        if (value == null || value.isEmpty()) {
            throw infoError(
                makeVariableStruct, "'value' parameter of make_variable must be a nonempty string."
            )
        }
        return com.google.devtools.build.lib.util.Pair.of<String?, String?>(name, value)
    }

    /**
     * Creates a Pair(name, path) that represents a [ ] from a [ ].
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toolPathFromStarlark(toolPathStruct: StarlarkInfo): com.google.devtools.build.lib.util.Pair<String?, String?> {
        checkRightProviderType(toolPathStruct, "tool_path")
        val name: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            toolPathStruct,
            "name",
            kotlin.String::class.java
        )!!
        val path: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            toolPathStruct,
            "path",
            kotlin.String::class.java
        )!!
        if (name == null || name.isEmpty()) {
            throw infoError(toolPathStruct, "'name' parameter of tool_path must be a nonempty string.")
        }
        if (path == null || path.isEmpty()) {
            throw infoError(toolPathStruct, "'path' parameter of tool_path must be a nonempty string.")
        }
        return com.google.devtools.build.lib.util.Pair.of<String?, String?>(name, path)
    }

    /** Creates a [VariableWithValue] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun variableWithValueFromStarlark(variableWithValueStruct: StarlarkInfo): VariableWithValue {
        checkRightProviderType(variableWithValueStruct, "variable_with_value")
        val name: String =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
                variableWithValueStruct,
                "name",
                kotlin.String::class.java
            )!!
        val value: String =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
                variableWithValueStruct,
                "value",
                kotlin.String::class.java
            )!!
        if (name == null || name.isEmpty()) {
            throw infoError(
                variableWithValueStruct,
                "'name' parameter of variable_with_value must be a nonempty string."
            )
        }
        if (value == null || value.isEmpty()) {
            throw infoError(
                variableWithValueStruct,
                "'value' parameter of variable_with_value must be a nonempty string."
            )
        }
        return VariableWithValue(name, value)
    }

    /** Creates an [EnvEntry] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun envEntryFromStarlark(envEntryStruct: StarlarkInfo): EnvEntry {
        checkRightProviderType(envEntryStruct, "env_entry")
        val key: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            envEntryStruct,
            "key",
            kotlin.String::class.java
        )!!
        val value: String = CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
            envEntryStruct,
            "value",
            kotlin.String::class.java
        )!!
        if (key == null || key.isEmpty()) {
            throw infoError(envEntryStruct, "'key' parameter of env_entry must be a nonempty string.")
        }
        if (value == null || value.isEmpty()) {
            throw infoError(envEntryStruct, "'value' parameter of env_entry must be a nonempty string.")
        }
        val expandIfAvailable =
            getOptionalFieldFromStarlarkProvider<String?>(envEntryStruct, "expand_if_available", String::class.java)
        val parser: StringValueParser = StringValueParser(value)
        return EnvEntry(
            key,
            parser.getChunks(),
            if (expandIfAvailable == null) com.google.common.collect.ImmutableSet.of<String?>() else com.google.common.collect.ImmutableSet.of<String?>(
                expandIfAvailable
            )
        )
    }

    /** Creates a [WithFeatureSet] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun withFeatureSetFromStarlark(withFeatureSetStruct: StarlarkInfo): WithFeatureSet {
        checkRightProviderType(withFeatureSetStruct, "with_feature_set")
        val features: com.google.common.collect.ImmutableSet<String?> =
            getStringSetFromStarlarkProviderField(withFeatureSetStruct, "features")
        val notFeatures: com.google.common.collect.ImmutableSet<String?> =
            getStringSetFromStarlarkProviderField(withFeatureSetStruct, "not_features")
        return WithFeatureSet(features, notFeatures)
    }

    /** Creates an [EnvSet] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun envSetFromStarlark(envSetStruct: StarlarkInfo): EnvSet {
        checkRightProviderType(envSetStruct, "env_set")
        val actions: com.google.common.collect.ImmutableSet<String?> =
            getStringSetFromStarlarkProviderField(envSetStruct, "actions")
        if (actions.isEmpty()) {
            throw infoError(envSetStruct, "actions parameter of env_set must be a nonempty list.")
        }
        val envEntryBuilder: com.google.common.collect.ImmutableList.Builder<EnvEntry?> =
            com.google.common.collect.ImmutableList.builder<EnvEntry?>()
        val envEntryStructs: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(envSetStruct, "env_entries")
        for (envEntryStruct in envEntryStructs) {
            envEntryBuilder.add(envEntryFromStarlark(envEntryStruct))
        }

        val withFeatureSetBuilder: com.google.common.collect.ImmutableSet.Builder<WithFeatureSet?> =
            com.google.common.collect.ImmutableSet.builder<WithFeatureSet?>()
        val withFeatureSetStructs: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(envSetStruct, "with_features")
        for (withFeatureSetStruct in withFeatureSetStructs) {
            withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct))
        }
        return EnvSet(actions, envEntryBuilder.build(), withFeatureSetBuilder.build())
    }

    /** Creates a [FlagGroup] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun flagGroupFromStarlark(flagGroupStruct: StarlarkInfo): FlagGroup {
        checkRightProviderType(flagGroupStruct, "flag_group")

        val expandableBuilder: com.google.common.collect.ImmutableList.Builder<Expandable?> =
            com.google.common.collect.ImmutableList.builder<Expandable?>()
        val flags: com.google.common.collect.ImmutableList<String?> =
            getStringListFromStarlarkProviderField(flagGroupStruct, "flags")
        for (flag in flags) {
            val parser: StringValueParser = StringValueParser(flag)
            expandableBuilder.add(
                com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag.Companion.create(
                    parser.getChunks()
                )
            )
        }

        val flagGroups: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(flagGroupStruct, "flag_groups")
        for (flagGroup in flagGroups) {
            expandableBuilder.add(flagGroupFromStarlark(flagGroup))
        }

        if (flagGroups.size() > 0 && flags.size() > 0) {
            throw infoError(
                flagGroupStruct,
                "flag_group must contain either a list of flags or a list of flag_groups."
            )
        }

        if (flagGroups.size() == 0 && flags.size() == 0) {
            throw infoError(flagGroupStruct, "Both 'flags' and 'flag_groups' are empty.")
        }

        val iterateOver =
            getMandatoryFieldFromStarlarkProvider<String?>(flagGroupStruct, "iterate_over", String::class.java)
        val expandIfAvailable =
            getMandatoryFieldFromStarlarkProvider<String?>(flagGroupStruct, "expand_if_available", String::class.java)
        val expandIfNotAvailable =
            getMandatoryFieldFromStarlarkProvider<String?>(
                flagGroupStruct, "expand_if_not_available", String::class.java
            )
        val expandIfTrue =
            getMandatoryFieldFromStarlarkProvider<String?>(flagGroupStruct, "expand_if_true", String::class.java)
        val expandIfFalse =
            getMandatoryFieldFromStarlarkProvider<String?>(flagGroupStruct, "expand_if_false", String::class.java)
        val expandIfEqualStruct: StarlarkInfo? =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<StarlarkInfo?>(
                flagGroupStruct, "expand_if_equal", StarlarkInfo::class.java
            )
        val expandIfEqual: VariableWithValue? =
            if (expandIfEqualStruct == null) null else variableWithValueFromStarlark(expandIfEqualStruct)

        return FlagGroup(
            expandableBuilder.build(),
            iterateOver,
            if (expandIfAvailable == null) com.google.common.collect.ImmutableSet.of<String?>() else com.google.common.collect.ImmutableSet.of<String?>(
                expandIfAvailable
            ),
            if (expandIfNotAvailable == null) com.google.common.collect.ImmutableSet.of<String?>() else com.google.common.collect.ImmutableSet.of<String?>(
                expandIfNotAvailable
            ),
            expandIfTrue,
            expandIfFalse,
            expandIfEqual
        )
    }

    /** Creates a [FlagSet] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun flagSetFromStarlark(flagSetStruct: StarlarkInfo, actionName: String?): FlagSet {
        checkRightProviderType(flagSetStruct, "flag_set")
        var actions: com.google.common.collect.ImmutableSet<String?> =
            getStringSetFromStarlarkProviderField(flagSetStruct, "actions")
        // if we are creating a flag set for an action_config, we need to propagate the name of the
        // action to its flag_set.action_names
        if (actionName != null) {
            if (!actions.isEmpty()) {
                throw net.starlark.java.eval.Starlark.errorf(
                    ActionConfig.Companion.FLAG_SET_WITH_ACTION_ERROR,
                    actionName
                )
            }
            actions = com.google.common.collect.ImmutableSet.of<String?>(actionName)
        }
        val flagGroupsBuilder: com.google.common.collect.ImmutableList.Builder<FlagGroup?> =
            com.google.common.collect.ImmutableList.builder<FlagGroup?>()
        val flagGroups: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(flagSetStruct, "flag_groups")
        for (flagGroup in flagGroups) {
            flagGroupsBuilder.add(flagGroupFromStarlark(flagGroup))
        }

        val withFeatureSetBuilder: com.google.common.collect.ImmutableSet.Builder<WithFeatureSet?> =
            com.google.common.collect.ImmutableSet.builder<WithFeatureSet?>()
        val withFeatureSetStructs: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(flagSetStruct, "with_features")
        for (withFeatureSetStruct in withFeatureSetStructs) {
            withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct))
        }

        return FlagSet(
            actions,
            com.google.common.collect.ImmutableSet.of<String?>(),
            withFeatureSetBuilder.build(),
            flagGroupsBuilder.build()
        )
    }

    /** Creates a [CcToolchainFeatures.Tool] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toolFromStarlark(
        toolStruct: StarlarkInfo,
        execOs: com.google.devtools.build.lib.util.OS?
    ): com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool {
        checkRightProviderType(toolStruct, "tool")

        val toolPathString = getOptionalFieldFromStarlarkProvider<String?>(toolStruct, "path", String::class.java)
        val toolArtifact: Artifact? =
            CcToolchainFeaturesLib.getOptionalFieldFromStarlarkProvider<Artifact?>(
                toolStruct,
                "tool",
                Artifact::class.java
            )

        val toolPath: PathFragment
        val toolPathOrigin: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin?
        if (toolPathString != null) {
            if (toolArtifact != null) {
                throw infoError(toolStruct, "\"tool\" and \"path\" cannot be set at the same time.")
            }

            toolPath = PathFragment.createForOs(toolPathString, execOs)
            if (toolPath.isEmpty()) {
                throw infoError(toolStruct, "The 'path' field of tool must be a nonempty string.")
            }

            if (toolPath.isAbsolute()) {
                toolPathOrigin =
                    com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.FILESYSTEM_ROOT
            } else {
                toolPathOrigin =
                    com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.CROSSTOOL_PACKAGE
            }
        } else if (toolArtifact != null) {
            toolPath = toolArtifact.getExecPath()
            toolPathOrigin = com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.WORKSPACE_ROOT
        } else {
            throw net.starlark.java.eval.Starlark.errorf("Exactly one of \"tool\" and \"path\" must be set.")
        }
        com.google.common.base.Preconditions.checkState(toolPath != null && toolPathOrigin != null)

        val withFeatureSetBuilder: com.google.common.collect.ImmutableSet.Builder<WithFeatureSet?> =
            com.google.common.collect.ImmutableSet.builder<WithFeatureSet?>()
        val withFeatureSetStructs: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(toolStruct, "with_features")
        for (withFeatureSetStruct in withFeatureSetStructs) {
            withFeatureSetBuilder.add(withFeatureSetFromStarlark(withFeatureSetStruct))
        }

        val executionRequirements: com.google.common.collect.ImmutableSet<String?> =
            getStringSetFromStarlarkProviderField(toolStruct, "execution_requirements")
        return com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool(
            toolPath, toolPathOrigin, executionRequirements, withFeatureSetBuilder.build()
        )
    }

    /** Creates an [ActionConfig] from a [StarlarkInfo].  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun actionConfigFromStarlark(
        actionConfigStruct: StarlarkInfo,
        execOs: com.google.devtools.build.lib.util.OS?
    ): ActionConfig {
        checkRightProviderType(actionConfigStruct, "action_config")
        val actionName: String =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
                actionConfigStruct,
                "action_name",
                kotlin.String::class.java
            )!!
        if (actionName == null || actionName.isEmpty()) {
            throw infoError(
                actionConfigStruct,
                "The 'action_name' field of action_config must be a nonempty string."
            )
        }
        if (!actionName.matches("^[_a-z0-9+\\-\\.]*$")) {
            throw infoError(
                actionConfigStruct,
                "An action_config's name must consist solely of lowercase ASCII letters, digits, "
                        + "'.', '_', '+', and '-', got '%s'",
                actionName
            )
        }

        val enabled: Boolean =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<Boolean>(
                actionConfigStruct,
                "enabled",
                Boolean::class.java
            )!!

        val toolBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool?>()
        val toolStructs: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(actionConfigStruct, "tools")
        for (toolStruct in toolStructs) {
            toolBuilder.add(toolFromStarlark(toolStruct, execOs))
        }

        val flagSetBuilder: com.google.common.collect.ImmutableList.Builder<FlagSet?> =
            com.google.common.collect.ImmutableList.builder<FlagSet?>()
        val flagSets: com.google.common.collect.ImmutableList<StarlarkInfo> =
            getStarlarkProviderListFromStarlarkField(actionConfigStruct, "flag_sets")
        for (flagSet in flagSets) {
            flagSetBuilder.add(flagSetFromStarlark(flagSet, actionName))
        }

        val implies: com.google.common.collect.ImmutableList<String?> =
            getStringListFromStarlarkProviderField(actionConfigStruct, "implies")

        return ActionConfig(
            actionName, actionName, toolBuilder.build(), flagSetBuilder.build(), enabled, implies
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(net.starlark.java.eval.EvalException::class)
    fun artifactNamePatternFromStarlark(
        artifactNamePatternStruct: StarlarkInfo, adder: ArtifactNamePatternAdder
    ) {
        checkRightProviderType(artifactNamePatternStruct, "artifact_name_pattern")
        val categoryName: String =
            CcToolchainFeaturesLib.getMandatoryFieldFromStarlarkProvider<String>(
                artifactNamePatternStruct, "category_name", String::class.java
            )!!
        if (categoryName == null || categoryName.isEmpty()) {
            throw infoError(
                artifactNamePatternStruct,
                "The 'category_name' field of artifact_name_pattern must be a nonempty string."
            )
        }
        var foundCategory: ArtifactCategory? = null
        for (artifactCategory in ArtifactCategory.entries) {
            if (categoryName == artifactCategory.getCategoryName()) {
                foundCategory = artifactCategory
            }
        }

        if (foundCategory == null) {
            throw infoError(
                artifactNamePatternStruct, "Artifact category %s not recognized.", categoryName
            )
        }

        val extension: String =
            com.google.common.base.Strings.nullToEmpty(
                getMandatoryFieldFromStarlarkProvider<String?>(
                    artifactNamePatternStruct, "extension", String::class.java
                )
            )
        if (!foundCategory.getAllowedExtensions().contains(extension)) {
            throw infoError(
                artifactNamePatternStruct,
                "Unrecognized file extension '%s', allowed extensions are %s,"
                        + " please check artifact_name_pattern configuration for %s in your rule.",
                extension,
                com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(foundCategory.getAllowedExtensions()),
                foundCategory.getCategoryName()
            )
        }

        val prefix: String =
            com.google.common.base.Strings.nullToEmpty(
                getMandatoryFieldFromStarlarkProvider<String?>(
                    artifactNamePatternStruct, "prefix", String::class.java
                )
            )
        adder.add(foundCategory, prefix, extension)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun <T> getOptionalFieldFromStarlarkProvider(
        provider: StarlarkInfo, fieldName: String?, clazz: java.lang.Class<T?>
    ): T? {
        return getFieldFromStarlarkProvider<T?>(provider, fieldName, clazz, false)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun <T> getMandatoryFieldFromStarlarkProvider(
        provider: StarlarkInfo, fieldName: String?, clazz: java.lang.Class<T?>
    ): T? {
        return getFieldFromStarlarkProvider<T?>(provider, fieldName, clazz, true)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun <T> getFieldFromStarlarkProvider(
        provider: StarlarkInfo, fieldName: String?, clazz: java.lang.Class<T?>, mandatory: Boolean
    ): T? {
        val obj: Any? = provider.getValue(fieldName)
        if (obj == null) {
            if (mandatory) {
                throw infoError(provider, "Missing mandatory field '%s'", fieldName)
            }
            return null
        }
        if (clazz.isInstance(obj)) {
            return clazz.cast(obj)
        }
        if (net.starlark.java.eval.NoneType::class.java.isInstance(obj)) {
            return null
        }
        throw infoError(provider, "Field '%s' is not of '%s' type.", fieldName, clazz.getName())
    }

    /** Returns a list of strings from a field of a [StarlarkInfo].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getStringListFromStarlarkProviderField(
        provider: StarlarkInfo, fieldName: String?
    ): com.google.common.collect.ImmutableList<String?> {
        val v = getValueOrNull(provider, fieldName)
        return if (v == null)
            com.google.common.collect.ImmutableList.of<String?>()
        else
            com.google.common.collect.ImmutableList.copyOf<String?>(
                net.starlark.java.eval.Sequence.noneableCast<String?>(
                    v,
                    String::class.java,
                    fieldName
                )
            )
    }

    /** Returns a set of strings from a field of a [StarlarkInfo].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getStringSetFromStarlarkProviderField(
        provider: StarlarkInfo, fieldName: String?
    ): com.google.common.collect.ImmutableSet<String?> {
        val v = getValueOrNull(provider, fieldName)
        return if (v == null)
            com.google.common.collect.ImmutableSet.of<String?>()
        else
            com.google.common.collect.ImmutableSet.copyOf<String?>(
                net.starlark.java.eval.Sequence.noneableCast<String?>(
                    v,
                    String::class.java,
                    fieldName
                )
            )
    }

    /** Returns a list of StarlarkInfo providers from a field of a [StarlarkInfo].  */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getStarlarkProviderListFromStarlarkField(
        provider: StarlarkInfo, fieldName: String?
    ): com.google.common.collect.ImmutableList<StarlarkInfo> {
        val v = getValueOrNull(provider, fieldName)
        return if (v == null)
            com.google.common.collect.ImmutableList.of<StarlarkInfo>()
        else
            com.google.common.collect.ImmutableList.copyOf<StarlarkInfo?>(
                net.starlark.java.eval.Sequence.noneableCast<StarlarkInfo?>(
                    v,
                    StarlarkInfo::class.java,
                    fieldName
                )
            )
    }

    @com.google.common.annotations.VisibleForTesting
    internal interface ArtifactNamePatternAdder {
        fun add(category: ArtifactCategory?, prefix: String?, extension: String?)
    }
}
