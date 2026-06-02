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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** A [SkyFunction] that loads metadata from a PROJECT.scl file.  */
class ProjectFunction : SkyFunction {
    /** The top level reserved globals in the PROJECT.scl file.  */
    private enum class ReservedGlobals(key: String) {
        /**
         * Forward-facing PROJECT.scl structure: a single top-level "project" variable that contains all
         * project data in nested data structures.
         */
        PROJECT("project");

        val key: String?

        init {
            this.key = key
        }
    }

    @Throws(ProjectFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.ProjectValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.skyframe.ProjectValue.Key

        val bzlLoadValue: BzlLoadValue?
        try {
            bzlLoadValue =
                env.getValueOrThrow<E?>(
                    BzlLoadValue.keyForBuild(key.getProjectFile()), BzlLoadFailedException::class.java
                ) as BzlLoadValue?
        } catch (e: BzlLoadFailedException) {
            throw ProjectFunctionException(e, Transience.PERSISTENT)
        }
        if (bzlLoadValue == null) {
            return null
        }
        val projectRaw: Any = bzlLoadValue.getModule().getGlobal(ReservedGlobals.PROJECT.key)
        when (projectRaw) {
            null -> {
                throw ProjectFunctionException(
                    TypecheckFailureException(
                        "Project files must define exactly one top-level variable called \"project\""
                    )
                )
            }

            -> {
                val actualProjectFile: Label = maybeResolveAlias(key.getProjectFile(), asDict, bzlLoadValue)
                if (!actualProjectFile.equals(key.getProjectFile())) {
                    // This is an alias for another project file. Delegate there.
                    // TODO: b/382265245 - handle cycles, including self references.
                    return env.getValueOrThrow<ProjectFunctionException?>(
                        com.google.devtools.build.lib.skyframe.ProjectValue.Key(actualProjectFile),
                        ProjectFunctionException::class.java
                    )
                }
                return parseLegacyProjectSchema(asDict, key.getProjectFile())
            }

            -> {
                return parseProtoProjectSchema(starlarkInfo, key.getProjectFile())
            }

            else -> throw ProjectFunctionException(
                TypecheckFailureException(
                    java.lang.String.format(
                        "%s variable: expected a map of string to objects, got %s",
                        ReservedGlobals.PROJECT.key, projectRaw.getClass()
                    )
                )
            )
        }
    }

    private class TypecheckFailureException(msg: String?) : java.lang.Exception(msg)

    private class ActiveDirectoriesException(msg: String?) : java.lang.Exception(msg)

    private class BadProjectFileException(msg: String?) : java.lang.Exception(msg)

    /** Exception thrown by [ProjectFunction].  */
    class ProjectFunctionException : SkyFunctionException {
        internal constructor(cause: TypecheckFailureException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: ActiveDirectoriesException?) : super(cause, Transience.PERSISTENT)

        internal constructor(e: BzlLoadFailedException?, transience: Transience?) : super(e, transience)

        internal constructor(cause: LabelSyntaxException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: BadProjectFileException?) : super(cause, Transience.PERSISTENT)
    }

    companion object {
        private const val ENFORCEMENT_POLICY = "enforcement_policy"

        /**
         * Parses the proto-based PROJECT.scl implementation.
         * 
         * @param starlarkInfo the raw Starlark [StarlarkInfoNoSchema] that `project` is set
         * to
         * @param projectFile name of the project file
         */
        @Throws(ProjectFunctionException::class)
        private fun parseProtoProjectSchema(
            starlarkInfo: StarlarkInfoNoSchema, projectFile: Label?
        ): ProjectValue {
            val buildableUnitsBuilder: MutableMap<String?, BuildableUnit?> = LinkedHashMap<String?, BuildableUnit?>()
            val buildableUnits: MutableCollection<*> =
                Companion.checkAndCast<T>(
                    starlarkInfo.getValue("buildable_units"),
                    MutableCollection::class.java,  /* defaultValue= */
                    null,
                    "buildable_units must be a list of buildable unit definitions"
                )
            for (rawBuildableUnit in buildableUnits) {
                val targetPatternsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                    com.google.common.collect.ImmutableList.builder<String?>()
                val flagsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                    com.google.common.collect.ImmutableList.builder<String?>()
                val buildableUnitStruct: StarlarkInfoNoSchema =
                    Companion.checkAndCast<StarlarkInfoNoSchema>(
                        rawBuildableUnit,
                        StarlarkInfoNoSchema::class.java,  /* defaultValue= */
                        null,
                        "buildable_units entries must be structured objects"
                    )
                val buildableUnitName: String? =
                    Companion.checkAndCast<T?>(
                        buildableUnitStruct.getValue("name"),
                        String::class.java,  /* defaultValue= */
                        null,
                        "buildable_unit names must be strings"
                    )
                val buildableUnitDescription: String? =
                    Companion.checkAndCast<T?>(
                        buildableUnitStruct.getValue("description"),
                        String::class.java,  /* defaultValue= */
                        buildableUnitName,
                        "buildable_unit descriptions must be strings"
                    )
                val isDefault: Boolean =
                    Companion.checkAndCast<T?>(
                        buildableUnitStruct.getValue("is_default"),
                        Boolean::class.java,  /* defaultValue= */
                        false,
                        "is_default must be a boolean"
                    )
                val targetPatterns: MutableCollection<*> =
                    Companion.checkAndCast<T>(
                        buildableUnitStruct.getValue("target_patterns"),
                        MutableCollection::class.java,  /* defaultValue= */
                        com.google.common.collect.ImmutableList.of<Any?>(),
                        "target_patterns must be a list of strings"
                    )
                for (targetPattern in targetPatterns) {
                    targetPatternsBuilder.add(
                        Companion.checkAndCast<String?>(
                            targetPattern!!,
                            String::class.java,  /* defaultValue= */
                            null,
                            "target_patterns entries must be strings"
                        )
                    )
                }
                val flags: MutableCollection<*> =
                    Companion.checkAndCast<T>(
                        buildableUnitStruct.getValue("flags"),
                        MutableCollection::class.java,  /* defaultValue= */
                        com.google.common.collect.ImmutableList.of<Any?>(),
                        "flags must be a list of strings"
                    )
                for (flag in flags) {
                    flagsBuilder.add(
                        Companion.checkAndCast<String?>(
                            flag!!, String::class.java,  /* defaultValue= */null, "flags entries must be strings"
                        )
                    )
                }
                // TODO: b/413130912: cleanly fail when multiple buildable units have the same name.
                var buildableUnit: BuildableUnit? = null
                try {
                    buildableUnit =
                        BuildableUnit.Companion.create(
                            buildableUnitName,
                            targetPatternsBuilder.build(),
                            buildableUnitDescription,
                            flagsBuilder.build(),
                            isDefault
                        )
                } catch (e: LabelSyntaxException) {
                    throw ProjectFunctionException(e)
                }
                if (buildableUnitsBuilder.put(buildableUnitName, buildableUnit) != null) {
                    throw ProjectFunctionException(
                        BadProjectFileException(
                            java.lang.String.format(
                                "buildable_unit name='%s' is repeated. Buildable units must have unique names.",
                                buildableUnitName
                            )
                        )
                    )
                }
            }
            val alwaysAllowedConfigs: com.google.common.collect.ImmutableList<String?> =
                parseAlwaysAllowedConfigs(starlarkInfo.getValue("always_allowed_configs"))
            return ProjectValue(
                parseEnforcementPolicy(starlarkInfo.getValue(ENFORCEMENT_POLICY), projectFile),
                parseProjectDirectories(starlarkInfo.getValue("project_directories")),
                com.google.common.collect.ImmutableMap.copyOf<String?, BuildableUnit?>(buildableUnitsBuilder),
                if (alwaysAllowedConfigs.isEmpty()) null else alwaysAllowedConfigs,
                projectFile
            )
        }

        /**
         * Parses the first PROJECT.scl implementation (pre-proto schema).
         * 
         * @param dict the raw Starlark [Dict] that `project` is set to
         * @param projectFile name of the project file
         */
        @Throws(ProjectFunctionException::class)
        private fun parseLegacyProjectSchema(
            dict: net.starlark.java.eval.Dict<*, *>,
            projectFile: Label?
        ): ProjectValue {
            val buildableUnitsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, BuildableUnit?> =
                com.google.common.collect.ImmutableMap.builder<String?, BuildableUnit?>()
            for (k in dict.keySet()) {
                if (k !is String) {
                    throw ProjectFunctionException(
                        TypecheckFailureException(
                            java.lang.String.format(
                                "%s variable: expected string key, got element of %s",
                                ReservedGlobals.PROJECT.key, k.getClass()
                            )
                        )
                    )
                }
            }
            var defaultConfig: String? = null
            val defaultConfigRaw: Any? = dict.get("default_config")
            if (defaultConfigRaw != null) {
                val defaultConfigString =
                    checkAndCast<String?>(
                        defaultConfigRaw,
                        String::class.java,  /* defaultValue= */
                        null,
                        "default_config must be a string matching a configs variable definition"
                    )
                defaultConfig = defaultConfigString
            }
            var foundDefaultConfig = false
            if (dict.containsKey("configs")) {
                val configs: com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> =
                    parseConfigs(dict.get("configs"), "configs")
                for (config in configs.keySet()) {
                    val isDefault = defaultConfig != null && config == defaultConfig
                    if (isDefault) {
                        foundDefaultConfig = true
                    }
                    var buildableUnit: BuildableUnit? = null
                    try {
                        buildableUnit =
                            BuildableUnit.Companion.create( /* name= */
                                config,  /* targetPatterns= */
                                com.google.common.collect.ImmutableList.of<String?>(),  /* description= */
                                "",
                                com.google.common.collect.ImmutableList.copyOf<String?>(configs.get(config)),
                                isDefault
                            )
                    } catch (e: LabelSyntaxException) {
                        throw ProjectFunctionException(e)
                    }
                    buildableUnitsBuilder.put(config, buildableUnit)
                }
            }
            if (defaultConfig != null && !foundDefaultConfig) {
                throw ProjectFunctionException(
                    BadProjectFileException(
                        "default_config must be a string matching a configs variable definition"
                    )
                )
            }
            return ProjectValue(
                parseEnforcementPolicy(dict.get(ENFORCEMENT_POLICY), projectFile),
                parseProjectDirectories(dict.get("active_directories")),
                if (dict.containsKey("configs")) buildableUnitsBuilder.buildOrThrow() else null,
                parseAlwaysAllowedConfigs(dict.get("always_allowed_configs")),
                projectFile
            )
        }

        @Throws(ProjectFunctionException::class)
        private fun parseConfigs(
            configsRaw: Any?, variableName: String?
        ): com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> {
            // This project file doesn't define configs, so it must not be used for canonical configs.
            if (configsRaw == null) {
                return com.google.common.collect.ImmutableMap.of<String?, MutableCollection<String?>?>()
            }
            val configs: com.google.common.collect.ImmutableMap.Builder<String?, MutableCollection<String?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, MutableCollection<String?>?>()
            var expectedConfigsType = false
            if (configsRaw is net.starlark.java.eval.Dict<*, *>) {
                expectedConfigsType = true
                for (entry in configsRaw.entrySet()) {
                    if (!(entry.getKey() is String
                                && entry.getValue() is MutableCollection<*>)
                    ) {
                        expectedConfigsType = false
                        break
                    }
                    val valuesBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                        com.google.common.collect.ImmutableList.builder<String?>()
                    for (value in values) {
                        if (value !is String) {
                            expectedConfigsType = false
                            break
                        }
                        valuesBuilder.add(value)
                    }
                    configs.put(key, valuesBuilder.build())
                }
            }
            if (!expectedConfigsType) {
                throw ProjectFunctionException(
                    TypecheckFailureException(
                        java.lang.String.format(
                            "%s variable must be a map of strings to lists of strings", variableName
                        )
                    )
                )
            }
            return configs.buildOrThrow()
        }

        @Throws(ProjectFunctionException::class)
        private fun parseAlwaysAllowedConfigs(alwaysAllowedConfigsRaw: Any?): com.google.common.collect.ImmutableList<String?> {
            if (alwaysAllowedConfigsRaw == null) {
                return com.google.common.collect.ImmutableList.of<String?>()
            }
            val alwaysAllowedConfigs: MutableCollection<*> =
                ProjectFunction.Companion.checkAndCast<MutableCollection<*>>(
                    alwaysAllowedConfigsRaw,
                    kotlin.collections.MutableCollection::class.java,  /* defaultValue= */
                    com.google.common.collect.ImmutableList.of<kotlin.Any?>(),
                    "always_allowed_configs must be a list of strings"
                )!!
            val alwaysAllowedConfigsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (config in alwaysAllowedConfigs) {
                alwaysAllowedConfigsBuilder.add(
                    Companion.checkAndCast<String?>(
                        config!!,
                        String::class.java,  /* defaultValue= */
                        null,
                        "always_allowed_configs entires must be strings"
                    )
                )
            }
            return alwaysAllowedConfigsBuilder.build()
        }

        @Throws(ProjectFunctionException::class)
        private fun parseProjectDirectories(
            activeDirectoriesRaw: Any?
        ): com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> {
            val activeDirectories: com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> =
                when (activeDirectoriesRaw) {
                    null -> com.google.common.collect.ImmutableMap.of<String?, MutableCollection<String?>?>()
                    -> {
                        val builder: com.google.common.collect.ImmutableMap.Builder<String?, MutableCollection<String?>?> =
                            com.google.common.collect.ImmutableMap.builder<String?, MutableCollection<String?>?>()
                        for (entry in dict.entrySet()) {
                            val k: Any = entry.getKey()

                            if (k !is String) {
                                throw ProjectFunctionException(
                                    TypecheckFailureException(
                                        "expected string, got element of " + k.getClass()
                                    )
                                )
                            }

                            val values: Any = entry.getValue()
                            if (values !is MutableCollection<*>) {
                                throw ProjectFunctionException(
                                    TypecheckFailureException(
                                        "expected list, got element of " + values.getClass()
                                    )
                                )
                            }

                            for (activeDirectory in values) {
                                if (activeDirectory !is String) {
                                    throw ProjectFunctionException(
                                        TypecheckFailureException(
                                            "expected a list of strings, got element of "
                                                    + activeDirectory.getClass()
                                        )
                                    )
                                }
                            }

                            builder.put(k, values as MutableCollection<String?>)
                        }

                        builder.buildOrThrow()
                    }

                    -> {
                        // The proto schema doesn't need a map. Read a list and store as a {"default": [list}]}
                        // map to preserve backward compatibility.
                        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                            com.google.common.collect.ImmutableList.builder<String?>()
                        for (activeDirectory in list) {
                            builder.add(
                                checkAndCast<String?>(
                                    activeDirectory,
                                    String::class.java,  /* defaultValue= */
                                    null,
                                    "project_directories is a list of strings"
                                )
                            )
                        }
                        com.google.common.collect.ImmutableMap.of<String?, MutableCollection<String?>?>(
                            "default",
                            builder.build()
                        )
                    }

                    else -> throw ProjectFunctionException(
                        TypecheckFailureException(
                            "expected a map of string to list of strings, got "
                                    + activeDirectoriesRaw.getClass()
                        )
                    )
                }

            if (!activeDirectories.isEmpty() && activeDirectories.get("default") == null) {
                throw ProjectFunctionException(
                    ActiveDirectoriesException(
                        "non-empty active_directories must contain the 'default' key"
                    )
                )
            }
            return activeDirectories
        }

        private fun isDefaultValue(v: Any?): Boolean {
            if (((v is net.starlark.java.eval.StarlarkList<*>) && v.isEmpty())) {
                return true
            }

            if (v === net.starlark.java.eval.Starlark.NONE) {
                return true
            }

            return false
        }

        @Throws(ProjectFunctionException::class)
        private fun parseEnforcementPolicy(
            enforcementPolicyRaw: Any?, projectFile: Label?
        ): EnforcementPolicy {
            if (enforcementPolicyRaw == null || isDefaultValue(enforcementPolicyRaw)) {
                // Default if unspecified.
                return EnforcementPolicy.WARN
            }
            try {
                return EnforcementPolicy.Companion.fromString(enforcementPolicyRaw.toString().toLowerCase(Locale.ROOT))
            } catch (e: java.lang.IllegalArgumentException) {
                throw ProjectFunctionException(
                    TypecheckFailureException(e.getMessage() + " in " + projectFile)
                )
            }
        }

        /**
         * If this is an alias for another project file, returns its label. Else returns the original
         * key's label.
         * 
         * 
         * See [ProjectValue.maybeResolveAlias] for schema details.
         * 
         * @throws ProjectFunctionException if the alias schema isn't valid or the actual reference isn't
         * a valid label.
         */
        @Throws(ProjectFunctionException::class)
        private fun maybeResolveAlias(
            originalProjectFile: Label, project: net.starlark.java.eval.Dict<*, *>, bzlLoadValue: BzlLoadValue
        ): Label {
            if (project.get("actual") == null) {
                return originalProjectFile
            } else if (project.get("actual") !is String) {
                throw ProjectFunctionException(
                    TypecheckFailureException(
                        java.lang.String.format(
                            "project[\"actual\"]: expected string, got %s", project.get("actual")
                        )
                    )
                )
            } else if (project.keySet().size() > 1) {
                throw ProjectFunctionException(
                    TypecheckFailureException(
                        java.lang.String.format(
                            "project[\"actual\"] is present, but other keys are present as well: %s",
                            project.keySet()
                        )
                    )
                )
            } else if (bzlLoadValue.getModule().getGlobals().keySet().size() > 1) {
                throw ProjectFunctionException(
                    TypecheckFailureException(
                        java.lang.String.format(
                            "project global variable is present, but other globals are present as well: %s",
                            bzlLoadValue.getModule().getGlobals().keySet()
                        )
                    )
                )
            }
            try {
                return Label.parseCanonical(project.get("actual") as String?)
            } catch (e: LabelSyntaxException) {
                throw ProjectFunctionException(e)
            }
        }

        /**
         * Checks that `rawValue` is an instance of `clazz`. If so, returns it cast to that
         * type. Else if its an empty [StarlarkList] and `defaultValue` is not null, returns
         * `defaultValue`. Else throws a [ProjectFunctionException].
         * 
         * 
         * Note that all unspecified protolark settings default to an empty `StarlarkList`.
         */
        @Throws(ProjectFunctionException::class)
        private fun <T> checkAndCast(
            rawValue: Any, clazz: java.lang.Class<T?>, defaultValue: Any?, errorMessage: String?
        ): T? {
            if (clazz.isInstance(rawValue)) {
                return clazz.cast(rawValue)
            }
            if (defaultValue != null && isDefaultValue(rawValue)) {
                return clazz.cast(defaultValue)
            }
            throw ProjectFunctionException(
                TypecheckFailureException(
                    java.lang.String.format("%s, got %s", errorMessage, rawValue.getClass())
                )
            )
        }
    }
}
