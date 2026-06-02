// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME

/** A builder for [StarlarkBuildSettingsDetailsValue] instances.  */
internal class StarlarkBuildSettingsDetailsFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class, StarlarkBuildSettingsDetailsException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key: StarlarkBuildSettingsDetailsValue.Key = skyKey as StarlarkBuildSettingsDetailsValue.Key

        // Ideally, callers would bypass StarlarkBuildSettingsDetailsFunction entirely when the
        // key is empty but provide a fast escape here just in case.
        if (key.buildSettings().isEmpty() && key.hostFlags().isEmpty()) {
            return StarlarkBuildSettingsDetailsValue.EMPTY
        }

        // for each --flag_alias to --host_{flag} alias, we load the starlarkified host flag and get its
        // default value.
        // and scopeType. These information are only needed for flags that declare a scope of
        // exec:--{some_other_flag}. This is necessary because Bazel doesn't know how to find Starlark
        // host flags if they're not set at the command line, and we need to ensure the exec transition
        // correctly sets --foo=--host_foo even for builds that don't reference either flag
        val customExecScopeValuesBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, CustomExecScopeValue?> =
            com.google.common.collect.ImmutableMap.builder<Label?, CustomExecScopeValue?>()
        for (hostFlag in key.hostFlags()) {
            val flag: Label =
                Label.createUnvalidated(
                    hostFlag.getPackageIdentifier(), hostFlag.name.replaceFirst("host_", "")
                )

            try {
                val buildSettingPackage: com.google.common.collect.ImmutableMap<PackageIdentifier?, PackageValue?>? =
                    getBuildSettingPackages(env, com.google.common.collect.ImmutableSet.of<Label?>(flag))
                if (buildSettingPackage == null) {
                    return null
                }

                // if --host_foo exists, then look up for --foo.
                val flagTarget: Target?
                try {
                    flagTarget = Companion.getTarget(buildSettingPackage, flag)
                } catch (e: NoSuchTargetException) {
                    // while we might not expect --host_foo to exist when --foo doesn't exist,
                    // if that happens the outcome is there's no scoped flag we have to account for.
                    // In that case rather than error, we simply break out of this logic since --host_foo
                    // doesn't matter here now.
                    continue
                }

                if (flagTarget == null) {
                    return null
                }
                val attrMap: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    RawAttributeMapper.of(flagTarget.getAssociatedRule())
                val flagDefaultValue: Any? =
                    flagTarget.getAssociatedRule().getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
                if (attrMap.isAttributeValueExplicitlySpecified("scope")) {
                    val scopeType: String = attrMap.get("scope", Type.STRING)
                    if (scopeType.startsWith("exec:--")) {
                        // load the starlarkified host flag
                        val starlarkifiedHostFlag: Target?
                        try {
                            starlarkifiedHostFlag = Companion.getTarget(buildSettingPackage, hostFlag)
                        } catch (e: NoSuchTargetException) {
                            throw StarlarkBuildSettingsDetailsException(e)
                        }

                        if (starlarkifiedHostFlag == null) {
                            return null
                        }
                        val hostAttrMap: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            RawAttributeMapper.of(starlarkifiedHostFlag.getAssociatedRule())
                        val hostScopeType: String? =
                            if (attrMap.isAttributeValueExplicitlySpecified("scope"))
                                hostAttrMap.get("scope", Type.STRING)
                            else
                                "default"
                        val hostFlagDefaultValue: Any =
                            starlarkifiedHostFlag
                                .getAssociatedRule()
                                .getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
                        if (starlarkifiedHostFlag
                                .getAssociatedRule()
                                .getRuleClassObject()
                                .getBuildSetting()
                                .allowsMultiple()
                        ) {
                            // allowed multiple
                            customExecScopeValuesBuilder.put(
                                flagTarget.getLabel(),
                                CustomExecScopeValue(
                                    flagTarget.getLabel(),
                                    flagDefaultValue,
                                    hostFlag,
                                    com.google.common.collect.ImmutableList.of<E?>(hostFlagDefaultValue),
                                    scopeType,
                                    hostScopeType
                                )
                            )
                        } else {
                            // not allowed multiple
                            customExecScopeValuesBuilder.put(
                                flagTarget.getLabel(),
                                CustomExecScopeValue(
                                    flagTarget.getLabel(),
                                    flagDefaultValue,
                                    hostFlag,
                                    hostFlagDefaultValue,
                                    scopeType,
                                    hostScopeType
                                )
                            )
                        }
                    }
                }
            } catch (e: TransitionException) {
                throw StarlarkBuildSettingsDetailsException(e)
            }
        }

        val effectiveBuildSettings: com.google.common.collect.ImmutableSet<Label> =
            com.google.common.collect.ImmutableSet.builder<Label>()
                .addAll(key.buildSettings())
                .addAll(customExecScopeValuesBuilder.buildOrThrow().keySet())
                .build()

        try {
            val buildSettingPackages: com.google.common.collect.ImmutableMap<PackageIdentifier?, PackageValue?>? =
                getBuildSettingPackages(env, effectiveBuildSettings)
            if (buildSettingPackages == null) {
                return null
            }

            // Each setting is unique so don't need a merge function.
            val rawSettingToActualRule: com.google.common.collect.ImmutableMap<Label?, Rule?> =
                effectiveBuildSettings.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            java.util.function.Function { setting: Any? -> setting },
                            java.util.function.Function { setting: Any? ->
                                Companion.getActual(
                                    buildSettingPackages,
                                    setting
                                ).getAssociatedRule()
                            })
                    )
            val actualRules: com.google.common.collect.ImmutableSet<Rule?> =
                com.google.common.collect.ImmutableSet.copyOf<Rule?>(rawSettingToActualRule.values())

            // Calculate info based on the actual rules
            // Different rules have different labels so don't need a merge function
            val buildSettingToDefault: com.google.common.collect.ImmutableMap<Label?, Any?> =
                actualRules.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            Rule::getLabel,
                            java.util.function.Function { rule: Any? ->
                                if (rule.getRuleClassObject().getBuildSetting().allowsMultiple()) {
                                    return@toImmutableMap com.google.common.collect.ImmutableList.of<E?>(
                                        rule.getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
                                    )
                                }
                                rule.getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
                            })
                    )
            val buildSettingToType: com.google.common.collect.ImmutableMap<Label?, Type<*>?> =
                actualRules.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            Rule::getLabel,
                            java.util.function.Function { rule: Any? ->
                                rule.getRuleClassObject().getBuildSetting().getType()
                            })
                    )
            val buildSettingIsAllowsMultiple: com.google.common.collect.ImmutableSet<Label?> =
                actualRules.stream()
                    .filter(java.util.function.Predicate { rule: Rule? ->
                        rule.getRuleClassObject().getBuildSetting().allowsMultiple()
                    })
                    .map<Any?>(Rule::getLabel)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

            // Calculate the alias table (filtering out non-aliases!)
            val aliasToActual: com.google.common.collect.ImmutableMap<Label?, Label?> =
                rawSettingToActualRule.entrySet().stream()
                    .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<Label?, Rule?>? ->
                        !entry.getKey().equals(entry.getValue().getLabel())
                    })
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            java.util.function.Function { java.util.Map.Entry.getKey() },
                            java.util.function.Function { entry: Any? -> entry.getValue().getLabel() })
                    )

            return StarlarkBuildSettingsDetailsValue.create(
                buildSettingToDefault,
                buildSettingToType,
                buildSettingIsAllowsMultiple,
                aliasToActual,
                customExecScopeValuesBuilder.buildOrThrow()
            )
        } catch (e: TransitionException) {
            throw StarlarkBuildSettingsDetailsException(e)
        }
    }

    private class StarlarkBuildSettingsDetailsException : SkyFunctionException {
        internal constructor(e: java.lang.Exception?) : super(e, Transience.PERSISTENT)

        internal constructor(message: NoSuchTargetException?) : super(message, Transience.PERSISTENT)
    }

    companion object {
        // Use the plain strings rather than reaching into the Alias class and adding a dependency edge.
        // TODO(blaze-configurability-team): We can probably afford the edge now that this is
        //   inside of skyframe_cluster.
        private const val ALIAS_RULE_NAME = "alias"
        private const val ALIAS_ACTUAL_ATTRIBUTE_NAME = "actual"

        /**
         * Given a [ConfigurationTransition] find all build settings read or set by the transition
         * and load their packages.
         * 
         * 
         * In the case that build settings are referred to by aliases, we do a couple loops of package
         * loading. We generally don't expect build settings to be aliased multiple times so we don't
         * expect this while loop (and relevant null return) to happen more than two or three times (and
         * usually only once).
         * 
         * @return the package keys and values of build settings or null if not all packages are
         * available. if not null, and some build settings are referenced by alias, the returned map
         * will include both alias and actual packages to allow for alias chain following at a later
         * state.
         */
        @Throws(java.lang.InterruptedException::class, TransitionException::class)
        private fun getBuildSettingPackages(
            env: SkyFunction.Environment, buildSettings: com.google.common.collect.ImmutableSet<Label>
        ): com.google.common.collect.ImmutableMap<PackageIdentifier?, PackageValue?>? {
            val buildSettingPackages: HashMap<PackageIdentifier?, PackageValue?> =
                HashMap<PackageIdentifier?, PackageValue?>()
            // This happens before cycle detection so keep track of all seen build settings to ensure
            // we don't get stuck in endless loops (e.g. //alias1->//alias2 && //alias2->alias1)
            val allSeenBuildSettings: MutableSet<Label?> = HashSet<Label?>()
            var unverifiedBuildSettings: com.google.common.collect.ImmutableSet<Label> = buildSettings
            while (!unverifiedBuildSettings.isEmpty()) {
                for (buildSetting in unverifiedBuildSettings) {
                    if (!allSeenBuildSettings.add(buildSetting)) {
                        throw TransitionException(
                            java.lang.String.format(
                                "Dependency cycle involving '%s' detected in aliased build settings",
                                buildSetting
                            )
                        )
                    }
                }
                val packageKeys: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
                    getPackageKeysFromLabels(unverifiedBuildSettings)
                val newlyLoaded: SkyframeLookupResult = env.getValuesAndExceptions(packageKeys)
                if (env.valuesMissing()) {
                    return null
                }
                for (packageKey in packageKeys) {
                    try {
                        val skyValue: SkyValue? =
                            newlyLoaded.getOrThrow<E?>(packageKey, NoSuchPackageException::class.java)
                        if (skyValue == null) {
                            return null
                        }
                        buildSettingPackages.put(packageKey, skyValue as PackageValue)
                    } catch (e: NoSuchPackageException) {
                        throw TransitionException(e)
                    }
                }
                unverifiedBuildSettings =
                    verifyBuildSettingsAndGetAliases(buildSettingPackages, unverifiedBuildSettings)
            }
            return com.google.common.collect.ImmutableMap.copyOf<PackageIdentifier?, PackageValue?>(buildSettingPackages)
        }

        /** Given a set of labels, return a set of their package [PackageIdentifier] keys.  */
        private fun getPackageKeysFromLabels(
            buildSettings: MutableSet<Label>
        ): com.google.common.collect.ImmutableSet<PackageIdentifier?> {
            val keyBuilder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
                com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?>()
            for (setting in buildSettings) {
                keyBuilder.add(setting.getPackageIdentifier())
            }
            return keyBuilder.build()
        }

        /**
         * Given a preliminary set of alleged build setting labels and relevant packages, verify that the
         * given [Label]s actually correspond to build setting targets.
         * 
         * 
         * This method is meant to be run in a loop to handle aliased build settings. It also
         * explicitly bans configured 'actual' values for aliased build settings. Since build settings are
         * used to define configuration, there should be better ways to accomplish disparate
         * configurations than configured aliases. Also from a technical standpoint, it's unclear what
         * configuration is correct to use to resolve configured attributes.
         * 
         * @param buildSettingPackages packages that include `buildSettingsToVerify`'s packages
         * @param buildSettingsToVerify alleged build setting labels
         * @return a set of "actual" labels of any build settings that are referenced by aliases (note -
         * if the "actual" value of aliasA is aliasB, this method returns aliasB AKA we only follow
         * one link in the alias chain per call of this method)
         */
        @Throws(TransitionException::class)
        private fun verifyBuildSettingsAndGetAliases(
            buildSettingPackages: MutableMap<PackageIdentifier?, PackageValue?>,
            buildSettingsToVerify: MutableSet<Label>
        ): com.google.common.collect.ImmutableSet<Label> {
            val actualSettingBuilder: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.Builder<Label?>()
            for (allegedBuildSetting in buildSettingsToVerify) {
                val buildSettingPackage: Package? =
                    buildSettingPackages.get(allegedBuildSetting.getPackageIdentifier()).getPackage()
                com.google.common.base.Preconditions.checkNotNull<Any?>(
                    buildSettingPackage, "Reading build setting for which we don't have a package"
                )
                val buildSettingTarget: Target
                try {
                    buildSettingTarget = buildSettingPackage.getTarget(allegedBuildSetting.name)
                } catch (e: NoSuchTargetException) {
                    throw TransitionException(e)
                }
                if (buildSettingTarget.getAssociatedRule() == null) {
                    throw TransitionException(
                        java.lang.String.format(
                            "attempting to transition on '%s' which is not a build setting",
                            allegedBuildSetting
                        )
                    )
                }
                if (buildSettingTarget.getAssociatedRule().getRuleClass().equals(ALIAS_RULE_NAME)) {
                    val actualValue: Any? =
                        buildSettingTarget.getAssociatedRule().getAttr(ALIAS_ACTUAL_ATTRIBUTE_NAME)
                    if (actualValue is Label) {
                        actualSettingBuilder.add(actualValue)
                        continue
                    } else if (actualValue is SelectorList) {
                        // configured "actual" value
                        throw TransitionException(
                            java.lang.String.format(
                                ("attempting to transition on aliased build setting '%s', the actual value of"
                                        + " which uses select(). Aliased build settings with configured actual values"
                                        + " is not supported."),
                                allegedBuildSetting
                            )
                        )
                    } else {
                        throw java.lang.IllegalStateException(
                            java.lang.String.format(
                                "Alias target '%s' with 'actual' attr value not equals to "
                                        + "a label or a selectorlist",
                                allegedBuildSetting
                            )
                        )
                    }
                }
                if (!buildSettingTarget.getAssociatedRule().isBuildSetting()) {
                    throw TransitionException(
                        java.lang.String.format(
                            "attempting to transition on '%s' which is not a build setting",
                            allegedBuildSetting
                        )
                    )
                }
            }
            return actualSettingBuilder.build()
        }

        /**
         * Given a [Label] that could be an [com.google.devtools.build.lib.rules.Alias] and a
         * set of packages, find the actual target that [Label] ultimately points to.
         * 
         * 
         * This method assumes that the packages of the entire [ ] chain (if `setting` is indeed an alias) are
         * included in `buildSettingPackages`
         * 
         * 
         * This checking is likely done in [.verifyBuildSettingsAndGetAliases].
         */
        private fun getActual(
            buildSettingPackages: MutableMap<PackageIdentifier?, PackageValue?>, setting: Label
        ): Target {
            var target: Target
            try {
                target = getTarget(buildSettingPackages, setting)
            } catch (e: NoSuchTargetException) {
                throw java.lang.IllegalStateException(e)
            }

            while (target.getAssociatedRule().getRuleClass().equals(ALIAS_RULE_NAME)) {
                try {
                    target =
                        getTarget(
                            buildSettingPackages,
                            target.getAssociatedRule().getAttr(ALIAS_ACTUAL_ATTRIBUTE_NAME) as Label?
                        )
                } catch (e: NoSuchTargetException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
            return target
        }

        /**
         * Return a target given its label and a set of package values we know to contain the target.
         * 
         * 
         * This method is essentially a wrapper around PackageValue.getTarget.
         * 
         * @param buildSettingPackages packages that include `setting`'s package
         */
        @Throws(NoSuchTargetException::class)
        private fun getTarget(
            buildSettingPackages: MutableMap<PackageIdentifier?, PackageValue?>, setting: Label
        ): Target {
            val buildSettingPackage: Package? =
                buildSettingPackages.get(setting.getPackageIdentifier()).getPackage()
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                buildSettingPackage, "Reading build setting for which we don't have a package"
            )
            val target: Target
            target = buildSettingPackage.getTarget(setting.name)
            return target
        }
    }
}
