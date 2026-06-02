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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * A SkyFunction that, given an scl file path and the name of scl configs, does the following:
 * 
 * 
 *  1. calls [com.google.devtools.build.lib.skyframe.ProjectFunction] to load the content of
 * scl files given the provided scl config name
 *  1. calls [ParsedFlagsFunction] to parse the list of options
 *  1. returns the list of flags in command line format to be applied to the build
 * 
 * 
 * 
 * If --enforce_project_configs is set, invalid --scl_config values or invalid project files will
 * cause the build to fail.
 */
class FlagSetFunction : SkyFunction {
    @Throws(FlagSetFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key
        if (!key.enforceCanonical) {
            if (!key.sclConfig.isEmpty()) {
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.info(
                            java.lang.String.format(
                                "Ignoring --scl_config=%s because --enforce_project_configs is not set",
                                key.sclConfig
                            )
                        )
                    )
            }
            // --noenforce_project_configs. Nothing to do.
            return FlagSetValue.Companion.create(
                com.google.common.collect.ImmutableSet.of<String?>(),
                com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.Event?>()
            )
        }
        val projectValue: ProjectValue? =
            env.getValue(com.google.devtools.build.lib.skyframe.ProjectValue.Key(key.projectFile)) as ProjectValue?
        if (projectValue == null) {
            return null
        }

        // Skyframe doesn't replay warnings or info messages on cache hits: see Event.storeForReplay and
        // Reportable.storeForReplay. We want some flag set messages to be more persistent, so we
        // return them in the Skyvalue for the caller to emit.
        val persistentMessages: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.events.Event?>()
        val sclConfigAsStarlarkList: com.google.common.collect.ImmutableSet<String?>? =
            getSclConfig(key, projectValue, persistentMessages, key.targets, env)
        if (sclConfigAsStarlarkList == null) {
            return null
        }
        return FlagSetValue.Companion.create(sclConfigAsStarlarkList, persistentMessages.build())
    }

    /**
     * Groups project-declared flag settings by flag type. All entries use `"flag_name"` form.
     * 
     * @param supportedFlags flags that project files can legitimately set
     * @param unsupportedFlags valid Bazel flags that project files can't set
     * @param unrecognizedFlags unrecognized native flags
     * @param starlarkFlags Starlark flags - not checked for actual existence
     */
    private class FlagTypes(
        supportedFlags: com.google.common.collect.ImmutableSet<String?>?,
        unsupportedFlags: com.google.common.collect.ImmutableSet<String?>?,
        unrecognizedFlags: com.google.common.collect.ImmutableSet<String?>?,
        starlarkFlags: com.google.common.collect.ImmutableSet<String?>?
    ) {
        val supportedFlags: com.google.common.collect.ImmutableSet<String?>?
        val unsupportedFlags: com.google.common.collect.ImmutableSet<String?>?
        val unrecognizedFlags: com.google.common.collect.ImmutableSet<String?>?
        val starlarkFlags: com.google.common.collect.ImmutableSet<String?>?

        init {
            this.supportedFlags = supportedFlags
            this.unsupportedFlags = unsupportedFlags
            this.unrecognizedFlags = unrecognizedFlags
            this.starlarkFlags = starlarkFlags
        }
    }

    private class FlagSetFunctionException(cause: java.lang.Exception?, transience: Transience?) :
        SkyFunctionException(cause, transience)

    private class UnsupportedConfigException(msg: String?) : java.lang.Exception(msg)
    companion object {
        /**
         * Given an .scl file and `--scl_config` value, returns the flags denoted by that `--scl_config`. Flags are a list of strings (not parsed through the options parser).
         * 
         * 
         * Returns null if Skyframe dependencies need to be evaluated
         */
        @Throws(FlagSetFunctionException::class)
        private fun getSclConfig(
            key: com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key,
            sclContent: ProjectValue,
            persistentMessages: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.events.Event?>,
            targets: MutableSet<Label?>,
            env: SkyFunction.Environment
        ): com.google.common.collect.ImmutableSet<String?>? {
            val projectFile: Label = key.projectFile
            val sclConfigName: String? = key.sclConfig
            val enforcementPolicy: EnforcementPolicy = sclContent.getEnforcementPolicy()

            val configs: com.google.common.collect.ImmutableMap<String?, BuildableUnit?>? =
                sclContent.getBuildableUnits()
            if (configs == null || configs.isEmpty()) {
                // This project file doesn't define configs, so it must not be used for canonical configs.
                return com.google.common.collect.ImmutableSet.of<String?>()
            }

            var sclConfigNameForMessage = sclConfigName
            var sclConfigValue: com.google.common.collect.ImmutableList<String>? = null
            if (sclConfigName.isEmpty()) {
                // If there's no --scl_config, try to use the default_config.
                val buildableUnits: com.google.common.collect.ImmutableMap<String?, BuildableUnit?> =
                    sclContent.getBuildableUnits()

                val defaultBuildableUnits: com.google.common.collect.ImmutableList<BuildableUnit> =
                    filterProjects(targets, buildableUnits)

                // check that all targets resolves to the same set of flags.
                // orders of flags should not matter here.
                val resolvedDefaultBuildableUnit: com.google.common.collect.ImmutableSet<BuildableUnit> =
                    resolveSingleMatchingDefaultBuildableUnitForAllTargets(defaultBuildableUnits)
                if (resolvedDefaultBuildableUnit.size() > 1) {
                    throw FlagSetFunctionException(
                        UnsupportedConfigException(
                            "Building target(s) with different configurations are not supported."
                        ),
                        Transience.PERSISTENT
                    )
                }

                if (resolvedDefaultBuildableUnit.isEmpty()) {
                    throw FlagSetFunctionException(
                        UnsupportedConfigException(
                            java.lang.String.format(
                                ("This project's builds must set --scl_config because no default config is"
                                        + " defined.\n"
                                        + "%s"),
                                Companion.supportedConfigsDesc(projectFile, configs)
                            )
                        ),
                        Transience.PERSISTENT
                    )
                }
                val buildableUnit: BuildableUnit = resolvedDefaultBuildableUnit.iterator().next()
                sclConfigValue = buildableUnit.flags()
                sclConfigNameForMessage = buildableUnit.name()
            } else {
                if (!configs.containsKey(sclConfigName)) {
                    // The user set --scl_config to an unknown config.
                    throw FlagSetFunctionException(
                        UnsupportedConfigException(
                            java.lang.String.format(
                                "--scl_config=%s is not a valid configuration for this project.%s",
                                sclConfigName, Companion.supportedConfigsDesc(projectFile, configs)
                            )
                        ),
                        Transience.PERSISTENT
                    )
                }
                sclConfigValue = configs.get(sclConfigName).flags()
            }

            // Canonicalize space-separated flags to equals-separated flags.
            sclConfigValue =
                sclConfigValue.stream()
                    .map<String?>(
                        java.util.function.Function { flag: String? ->
                            // Leave normal and malformed flags alone.
                            if (!flag.startsWith("--") || !flag.contains(" ")) {
                                return@map flag
                            }
                            val spaceIndex: Int = flag.indexOf(' '.code)
                            val equalsIndex: Int = flag.indexOf('='.code)
                            // Space-separated flags will always have the space before the equals sign.
                            // e.g. we need to canonicalize --define bar=baz, but not --foo='bar baz'
                            if (spaceIndex < equalsIndex) {
                                return@map flag.substring(0, spaceIndex) + "=" + flag.substring(spaceIndex + 1)
                            }
                            flag
                        })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

            val buildOptionsAsStrings: com.google.common.collect.ImmutableList<String?> =
                getBuildOptionsAsStrings(key.targetOptions)
            val directlySetFlags =
                groupFlags(sclConfigValue, key.allOptionNames, buildOptionsAsStrings)

            // Error on unrecognized native flags.
            val evalContext: String? =
                java.lang.String.format("Applying config '%s' in %s", sclConfigNameForMessage, projectFile)
            if (!directlySetFlags.unrecognizedFlags.isEmpty()) {
                throw FlagSetFunctionException(
                    UnsupportedConfigException(
                        java.lang.String.format(
                            "%s: unrecognized option%s: %s.",
                            evalContext,
                            if (directlySetFlags.unrecognizedFlags.size() == 1) "" else "s",
                            directlySetFlags.unrecognizedFlags.stream()
                                .map<String?>(java.util.function.Function { f: String? -> "--" + f })
                                .collect(Collectors.joining(", "))
                        )
                    ),
                    Transience.PERSISTENT
                )

                // Error on native flags that project files don't support, i.e. non-output affecting flags.
            } else if (!directlySetFlags.unsupportedFlags.isEmpty()) {
                throw FlagSetFunctionException(
                    UnsupportedConfigException(
                        java.lang.String.format(
                            "%s: project flags don't support non-output affecting option%s: %s.",
                            evalContext,
                            if (directlySetFlags.unsupportedFlags.size() == 1) "" else "s",
                            directlySetFlags.unsupportedFlags.stream()
                                .map<String?>(java.util.function.Function { f: String? -> "--" + f })
                                .collect(Collectors.joining(", "))
                        )
                    ),
                    Transience.PERSISTENT
                )
            }

            // Check that directly set Starlark flags are valid.
            if (!validateStarlarkFlags(directlySetFlags.starlarkFlags, evalContext, env)) {
                return null
            }

            // Replace --config=foo entries with their expanded definitions.
            sclConfigValue = expandConfigFlags(sclConfigName, sclConfigValue, key.configFlagDefinitions)
            // TODO: b/388289978 - Fail on unrecognized options from --config and warn when ignoring
            //     recognized options that are filtered out here.
            val optionsToApply: com.google.common.collect.ImmutableSet<String?> =
                filterOptions(sclConfigValue, buildOptionsAsStrings)

            if (optionsToApply.isEmpty()) {
                return com.google.common.collect.ImmutableSet.of<String?>()
            }

            val alwaysAllowedConfigs: MutableCollection<String?> =
                if (sclContent.getAlwaysAllowedConfigs() == null)
                    com.google.common.collect.ImmutableList.of<String?>()
                else
                    sclContent.getAlwaysAllowedConfigs()

            validateNoExtraFlagsSet(
                enforcementPolicy,
                alwaysAllowedConfigs,
                buildOptionsAsStrings,
                key.userOptions,
                optionsToApply,
                persistentMessages,
                projectFile
            )
            persistentMessages.add(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        "Applying flags from the config '%s' defined in %s: %s ",
                        sclConfigNameForMessage, projectFile, optionsToApply
                    )
                )
            )
            return optionsToApply
        }

        /**
         * Returns all default [buildable units][BuildableUnit] that contain the specific target
         * in the `targetPatterns` field. If there are multiple matching default buildable units, an
         * exception will be thrown.
         */
        @Throws(FlagSetFunctionException::class)
        private fun filterProjects(
            targets: MutableSet<Label?>, buildableUnits: com.google.common.collect.ImmutableMap<String?, BuildableUnit?>
        ): com.google.common.collect.ImmutableList<BuildableUnit> {
            val targetsAndMatchingDefaultBuildableUnits: MutableMap<Label?, BuildableUnit?> =
                HashMap<Label?, BuildableUnit?>()
            for (target in targets) {
                for (buildableUnit in buildableUnits.values()) {
                    if (doesBuildableUnitMatchTarget(buildableUnit, target)
                        && buildableUnit.isDefault()
                        && targetsAndMatchingDefaultBuildableUnits.put(target, buildableUnit) != null
                    ) {
                        throw FlagSetFunctionException(
                            UnsupportedConfigException(
                                java.lang.String.format(
                                    ("Multiple matching default configs found for target %s. Please check your"
                                            + " project file and ensure that for target %s, there should be only"
                                            + " 1 matching default config."),
                                    target, target
                                )
                            ),
                            Transience.PERSISTENT
                        )
                    }
                }
            }

            return com.google.common.collect.ImmutableList.copyOf<BuildableUnit?>(
                targetsAndMatchingDefaultBuildableUnits.values()
            )
        }

        /**
         * Takes a list of default buildable units and compares the flags values of all buildable units.
         * If the flags from all buildable units are the same, returns the first matching buildable unit.
         * Else returns the first matching buildable unit for each distinct set of flags.
         * 
         * 
         * The caller should check that there are no more than 1 buildable unit returned.
         */
        private fun resolveSingleMatchingDefaultBuildableUnitForAllTargets(
            defaultBuildableUnitsForAllTargets: com.google.common.collect.ImmutableList<BuildableUnit>
        ): com.google.common.collect.ImmutableSet<BuildableUnit> {
            val flagsToFirstBuildableUnit: LinkedHashMap<com.google.common.collect.ImmutableList<String?>?, BuildableUnit?> =
                LinkedHashMap<com.google.common.collect.ImmutableList<String?>?, BuildableUnit?>()
            for (buildableUnit in defaultBuildableUnitsForAllTargets) {
                flagsToFirstBuildableUnit.putIfAbsent(buildableUnit.flags(), buildableUnit)
            }
            return com.google.common.collect.ImmutableSet.copyOf<BuildableUnit?>(flagsToFirstBuildableUnit.values())
        }

        /**
         * Returns `true` iff the `specificTarget` matches the target patterns in the [ ].
         */
        @com.google.common.annotations.VisibleForTesting
        fun doesBuildableUnitMatchTarget(buildableUnit: BuildableUnit, specificTarget: Label?): Boolean {
            if (buildableUnit.targetPatternMatcher().isEmpty()) {
                return true
            }
            return buildableUnit.targetPatternMatcher().contains(specificTarget)
        }

        private fun getBuildOptionsAsStrings(targetOptions: BuildOptions): com.google.common.collect.ImmutableList<String?> {
            val allOptionsAsStringsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()

            // Collect a list of BuildOptions, excluding TestOptions.
            targetOptions.getStarlarkOptions().keySet().stream()
                .map({ obj: Any? -> obj.toString() })
                .forEach(allOptionsAsStringsBuilder::add)
            for (fragmentOptions in targetOptions.getNativeOptions()) {
                if (fragmentOptions is TestConfiguration.TestOptions) {
                    continue
                }
                fragmentOptions.asMap().keySet().forEach(allOptionsAsStringsBuilder::add)
            }
            return allOptionsAsStringsBuilder.build()
        }

        /**
         * Filters the options from the selected config to only those that are part of [ ], excluding [TestConfiguration.TestOptions].
         * 
         * 
         * Only the options that are part of [BuildOptions] are allowed to be set in the project
         * file.
         */
        // TODO: steinman - I don't think we need this anymore since we're already failing the build
        // if there are any unrecognized flags.
        private fun filterOptions(
            flagsFromSelectedConfig: MutableCollection<String>,
            buildOptionsAsStrings: com.google.common.collect.ImmutableList<String?>
        ): com.google.common.collect.ImmutableSet<String?> {
            val filteredFlags: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (flagSetting in flagsFromSelectedConfig) {
                // Remove options that aren't part of BuildOptions from the selected config.
                if (buildOptionsAsStrings.contains(
                        com.google.common.collect.Iterables.get<String?>(
                            com.google.common.base.Splitter.on("=").split(flagSetting), 0
                        )
                            .replaceFirst(
                                "--",
                                ""
                            ) // Don't strip out negative boolean flags, e.g --nostamp, even though they aren't
                            // part of BuildOptions in the negative form.
                            .replaceFirst("no", "")
                            .replace("'", "")
                    )
                ) {
                    filteredFlags.add(flagSetting)
                } else if (com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES.stream()
                        .anyMatch(java.util.function.Predicate { prefix: String? -> flagSetting.startsWith(prefix) })
                ) {
                    // Because the BuildOptions might not already include Starlark flags that are set in the
                    // flagset, explicitly add them to the set of options to return.
                    filteredFlags.add(flagSetting)
                }
            }
            return filteredFlags.build()
        }

        /**
         * Groups an input of `"--flag_name=value"` pairs into [FlagTypes] categories.
         * 
         * @param flagSettings input flag settings
         * @param allOptionNames all recognizable native Bazel options in `"flag_name"` form
         * @param buildOptionsAsStrings all native Bazel options that are also in [BuildOptions], in
         * `"flag_name"` form
         */
        private fun groupFlags(
            flagSettings: com.google.common.collect.ImmutableList<String>,
            allOptionNames: com.google.common.collect.ImmutableSet<String?>,
            buildOptionsAsStrings: com.google.common.collect.ImmutableList<String?>
        ): FlagTypes {
            val supportedFlags: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            val unsupportedFlags: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            val unrecognizedFlags: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            val starlarkFlags: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (flagSetting in flagSettings) {
                val flagName: String =
                    com.google.common.base.Splitter.on("=").splitToList(flagSetting).get(0).replaceFirst("--", "")
                if (flagName == "config") {
                    supportedFlags.add(flagSetting)
                } else if (com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES.stream()
                        .anyMatch(java.util.function.Predicate { prefix: String? -> flagSetting.startsWith(prefix) })
                ) {
                    starlarkFlags.add(flagName)
                } else if (!allOptionNames.contains(flagName)) {
                    unrecognizedFlags.add(flagName)
                } else if (!buildOptionsAsStrings.contains(flagName) // We really only want to check the option without its "no" prefix if the flag is a
                    // boolean flag, but it's hard to know what type it is at this point in the build. Since
                    // we're already sure that this flag is a recognized flag from the previous check, it's
                    // probably ok to overapproximate here.
                    && !buildOptionsAsStrings.contains(flagName.replaceFirst("no", ""))
                ) {
                    unsupportedFlags.add(flagName)
                } else {
                    supportedFlags.add(flagName)
                }
            }
            return FlagTypes(
                supportedFlags.build(),
                unsupportedFlags.build(),
                unrecognizedFlags.build(),
                starlarkFlags.build()
            )
        }

        /**
         * Checks that Starlark flags exist, throws a [FlagSetFunctionException] if they don't.
         * 
         * @param starlarkFlags Starlark flags to check, in `"//pkg:flag_name"` form
         * @param evalContext User-friendly description of where the flags are set
         * @param env Skyframe evaluation environment
         * @return true if validation completed, false if Skyframe dependencies need to be evaluated.
         */
        @Throws(FlagSetFunctionException::class)
        private fun validateStarlarkFlags(
            starlarkFlags: com.google.common.collect.ImmutableSet<String?>,
            evalContext: String?,
            env: SkyFunction.Environment
        ): Boolean {
            // Maps Starlark flag labels to the strings they appear in in in the project file.
            val flagLabels: LinkedHashMap<Label?, String?> = LinkedHashMap<Label?, String?>()
            val badFlags: LinkedHashSet<String?> = LinkedHashSet<String?>()

            // Get the context we need to properly parse labels that may come from other repos.
            val mainRepoMapping: RepositoryMappingValue?
            try {
                mainRepoMapping =
                    env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
            } catch (e: java.lang.InterruptedException) {
                return true
            }
            if (mainRepoMapping == null) {
                return false
            }
            val mainRepoContext: RepoContext? =
                RepoContext.of(RepositoryName.MAIN, mainRepoMapping.repositoryMapping)

            // Parse the flags into repo-aware labels.
            for (starlarkFlag in starlarkFlags) {
                try {
                    val label: Label? = Label.parseWithRepoContext(starlarkFlag, mainRepoContext)
                    flagLabels.put(label, starlarkFlag)
                } catch (e: LabelSyntaxException) {
                    badFlags.add(starlarkFlag)
                }
            }

            // Skyframe-load their packages.
            val evaluated: SkyframeLookupResult
            try {
                evaluated =
                    env.getValuesAndExceptions(
                        flagLabels.keySet().stream()
                            .map<Any?>(Label::getPackageIdentifier)
                            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                    )
            } catch (e: java.lang.InterruptedException) {
                return true
            }
            if (env.valuesMissing()) {
                return false
            }

            // Check that they're real, flag-typed targets.
            for (flagLabel in flagLabels.entrySet()) {
                try {
                    val pkg: PackageValue? =
                        evaluated.getOrThrow<E?>(
                            flagLabel.getKey().getPackageIdentifier(), NoSuchPackageException::class.java
                        ) as PackageValue?
                    val target: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        pkg.getPackage().getTarget(flagLabel.getKey().name)
                    if (!target.isRule() || !(target as Rule).isBuildSetting()) {
                        badFlags.add(flagLabel.getValue())
                    }
                } catch (e: NoSuchTargetException) {
                    badFlags.add(flagLabel.getValue())
                } catch (e: NoSuchPackageException) {
                    badFlags.add(flagLabel.getValue())
                }
            }

            // Report bad results.
            if (!badFlags.isEmpty()) {
                throw FlagSetFunctionException(
                    UnsupportedConfigException(
                        java.lang.String.format(
                            "%s: unrecognized Starlark flag%s: %s. %s",
                            evalContext,
                            if (badFlags.size() == 1) "" else "s",
                            badFlags.stream().map<String?>(java.util.function.Function { f: String? -> "--" + f })
                                .collect(Collectors.joining(", ")),
                            if (badFlags.size() == 1)
                                "Check that this is a valid target that can be set as a flag."
                            else
                                "Check that these are valid targets that can be set as flags."
                        )
                    ),
                    Transience.PERSISTENT
                )
            }
            return true
        }

        /**
         * In-place expands `--config=foo` entries in `inputFlags`.
         * 
         * 
         * Doesn't parse flags or check where they're defined. It's up to callers to determine if flags
         * are, for example, part of [BuildOptions], if they parse correctly, or if they even exist.
         * 
         * @throws FlagSetFunctionException if `--config=foo` doesn't evaluate, it defines
         * non-[BuildOptions] flags, isn't defined in a global rc file, or is defined multiple
         * times.
         */
        @Throws(FlagSetFunctionException::class)
        private fun expandConfigFlags(
            sclConfigName: String?,
            inputFlags: MutableCollection<String>,
            configFlagDefinitions: ConfigFlagDefinitions?
        ): com.google.common.collect.ImmutableList<String> {
            // First look for dupes.
            val dupeChecker: HashSet<String?> = HashSet<String?>()
            for (flag in inputFlags) {
                if (flag.startsWith("--config=") && !dupeChecker.add(flag)) {
                    throw FlagSetFunctionException(
                        UnsupportedConfigException(
                            java.lang.String.format(
                                "--scl_config=%s: %s appears multiple times. Please ensure it appears at most"
                                        + " once.",
                                sclConfigName, flag
                            )
                        ),
                        Transience.PERSISTENT
                    )
                }
            }

            // Now rebuild the input list while in-place expanding each "--config=foo" entry.
            val ans: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (flag in inputFlags) {
                if (!flag.startsWith("--config=")) {
                    ans.add(flag)
                    continue
                }
                // TODO: b/388289978 - fail when a --config sets non-BuildOptions flags.
                val expandedFlags: ConfigFlagDefinitions.ConfigValue
                try {
                    expandedFlags =
                        ConfigFlagDefinitions.get(flag.substring(flag.indexOf("=") + 1), configFlagDefinitions)
                } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                    throw FlagSetFunctionException(
                        UnsupportedConfigException(
                            java.lang.String.format("--scl_config=%s: %s", sclConfigName, e.getMessage())
                        ),
                        Transience.PERSISTENT
                    )
                }
                for (rcSource in expandedFlags.rcSources()) {
                    if (!com.google.devtools.common.options.GlobalRcUtils.isGlobalRcFile(rcSource)) {
                        throw FlagSetFunctionException(
                            UnsupportedConfigException(
                                java.lang.String.format(
                                    "--scl_config=%s: can't set %s because its definition depends on %s which"
                                            + " isn't a global rc file.",
                                    sclConfigName, flag, rcSource
                                )
                            ),
                            Transience.PERSISTENT
                        )
                    }
                }
                ans.addAll(expandedFlags.flags())
            }
            return ans.build()
        }

        /**
         * Enforces one of the following `enforcement_policies`:
         * 
         * 
         * WARN - warn if the user set any output-affecting options that are not present in the
         * selected config in a bazelrc or on the command line.
         * 
         * 
         * COMPATIBLE - fail if the user set any options that are present in the selected config to a
         * different value than the one in the config. Also warn for other output-affecting options
         * 
         * 
         * STRICT - fail if the user set any output-affecting options that are not present in the
         * selected config.
         * 
         * 
         * Conflicting output-affecting options may be set in global RC files (including the `InvocationPolicy`). Flags that do not affect outputs are always allowed.
         * 
         * @param userOptions the user options set in the command line or user bazelrc as a map from
         * option.getCanonicalForm()to option.getExpandedFrom(), {"--define=foo=bar": "--config=foo"}.
         */
        @Throws(FlagSetFunctionException::class)
        private fun validateNoExtraFlagsSet(
            enforcementPolicy: EnforcementPolicy,
            alwaysAllowedConfigs: MutableCollection<String?>,
            buildOptionsAsStrings: com.google.common.collect.ImmutableList<String?>,
            userOptions: com.google.common.collect.ImmutableMap<String?, String?>,
            flagsFromSelectedConfig: com.google.common.collect.ImmutableSet<String?>,
            persistentMessages: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.events.Event?>,
            projectFile: Label?
        ) {
            val overlap: com.google.common.collect.ImmutableSet<String?> =
                userOptions.keySet()
                    .stream() // Remove options that aren't part of BuildOptions. This section can be removed once
                    // we only include BuildOptions in the passed userOptions.
                    .filter(
                        java.util.function.Predicate { option: String? ->
                            buildOptionsAsStrings.contains(
                                com.google.common.collect.Iterables.get<String?>(
                                    com.google.common.base.Splitter.on("=").split(option), 0
                                )
                                    .replaceFirst("--", "")
                                    .replace("'", "")
                            )
                        })
                    .filter(java.util.function.Predicate { option: String? -> !option.startsWith("--scl_config") })
                    .filter(java.util.function.Predicate { option: String? -> !flagsFromSelectedConfig.contains(option) }) // Remove options that are expanded from always-allowed configs either defined in the
                    // project file...
                    .filter(java.util.function.Predicate { option: String? ->
                        !alwaysAllowedConfigs.contains(
                            userOptions.get(
                                option
                            )
                        )
                    }) // ... or globally
                    .filter(
                        java.util.function.Predicate { option: String? ->
                            !com.google.devtools.common.options.GlobalRcUtils.ALLOWED_GLOBAL_CONFIGS.contains(
                                userOptions.get(option)
                            )
                        })
                    .map<String?>(
                        java.util.function.Function { option: String? ->
                            if (userOptions.get(option).isEmpty())
                                "'" + option + "'"
                            else
                                "'" + userOptions.get(option) + "'"
                        })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
            if (overlap.isEmpty()) {
                return
            }
            when (enforcementPolicy) {
                EnforcementPolicy.WARN -> {}
                EnforcementPolicy.COMPATIBLE -> {
                    val optionNamesFromSelectedConfig: com.google.common.collect.ImmutableSet<String?> =
                        flagsFromSelectedConfig.stream()
                            .map<String?>(java.util.function.Function { flag: String? ->
                                com.google.common.collect.Iterables.get<String?>(
                                    com.google.common.base.Splitter.on("=").split(flag),
                                    0
                                ).replace("'", "")
                            })
                            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
                    val conflictingOptions: com.google.common.collect.ImmutableSet<String?> =
                        overlap.stream()
                            .filter(
                                java.util.function.Predicate { option: String? ->
                                    optionNamesFromSelectedConfig.contains(
                                        com.google.common.collect.Iterables.get<String?>(
                                            com.google.common.base.Splitter.on(
                                                "="
                                            ).split(option), 0
                                        ).replace("'", "")
                                    )
                                })
                            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
                    if (!conflictingOptions.isEmpty()) {
                        throw FlagSetFunctionException(
                            UnsupportedConfigException(
                                java.lang.String.format(
                                    ("This build uses a project file (%s) that does not allow conflicting flags"
                                            + " in the command line or user bazelrc. Found %s. Please remove these"
                                            + " flags or disable project file resolution via"
                                            + " --noenforce_project_configs."),
                                    projectFile, conflictingOptions
                                )
                            ),
                            Transience.PERSISTENT
                        )
                    }
                }

                EnforcementPolicy.STRICT -> throw FlagSetFunctionException(
                    UnsupportedConfigException(
                        java.lang.String.format(
                            ("This build uses a project file (%s) that does not allow output-affecting"
                                    + " flags in the command line or user bazelrc. Found %s. Please remove"
                                    + " these flags or disable project file resolution via"
                                    + " --noenforce_project_configs."),
                            projectFile, overlap
                        )
                    ),
                    Transience.PERSISTENT
                )
            }
            // This appears in the WARN case, or for a COMPATIBLE project file that doesn't have
            // conflicting flags. We never hit this in the STRICT case, since we've already thrown.
            persistentMessages.add(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        ("This build uses a project file (%s), but also sets output-affecting"
                                + " flags in the command line or user bazelrc: %s. Please consider"
                                + " removing these flags."),
                        projectFile, overlap
                    )
                )
            )
        }

        /** Returns a user-friendly description of project-supported configurations.  */
        private fun supportedConfigsDesc(
            projectFile: Label, configs: MutableMap<String?, BuildableUnit?>
        ): String {
            var ans = "\nThis project supports:\n"
            val longestNameLength: Int =
                configs.keySet().stream().map<Int?>(java.util.function.Function { obj: String? -> obj.length() })
                    .max(java.util.Comparator { obj: Int?, anotherInteger: Int? -> obj!!.compareTo(anotherInteger!!) })
                    .get()
            for (configInfo in configs.entrySet()) {
                ans +=
                    java.lang.String.format(
                        "  --scl_config=%s -> ",
                        com.google.common.base.Strings.padEnd(configInfo.getKey(), longestNameLength, ' ')
                    )
                val desc: String = configInfo.getValue().description()
                // Add user-friendly description if specified, else list of applied flags.
                ans +=
                    if (desc.isEmpty() || desc == configInfo.getKey())
                        java.lang.String.format("[%s]", java.lang.String.join(" ", configInfo.getValue().flags()))
                    else
                        desc
                ans += "\n"
            }
            ans += java.lang.String.format("\nThis policy is defined in %s.\n", projectFile.toPathFragment())
            return ans
        }
    }
}
