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

/** A SkyValue representing the parsed definitions from a PROJECT.scl file.  */
class ProjectValue(
  @kotlin.jvm.JvmField val enforcementPolicy: EnforcementPolicy?,
  projectDirectories: com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?>,
  buildableUnits: com.google.common.collect.ImmutableMap<String?, BuildableUnit?>?,
  alwaysAllowedConfigs: com.google.common.collect.ImmutableList<String?>?,
  actualProjectFile: Label?
) : SkyValue {
    /**
     * Represents the enforcement policy for a PROJECT.scl file.
     * 
     * 
     * "warn" (default) - warn if the user set any output-affecting options that are not present in
     * the selected config in a blazerc or on the command line.
     * 
     * 
     * "compatible" - fail if the user set any options that are present in the selected config to a
     * different value than the one in the config. Also warn for other output-affecting options
     * 
     * 
     * "strict" - fail if the user set any output-affecting options that are not present in the
     * selected config.
     */
    enum class EnforcementPolicy(private val value: String) {
        WARN("warn"),  // Default, enforced in ProjectFunction#compute.
        COMPATIBLE("compatible"),
        STRICT("strict");

        companion object {
            fun fromString(value: String?): EnforcementPolicy {
                for (policy in EnforcementPolicy.entries) {
                    if (policy.value == value) {
                        return policy
                    }
                }
                throw java.lang.IllegalArgumentException(
                    java.lang.String.format(
                        "invalid enforcement_policy '%s'",
                        value
                    )
                )
            }
        }
    }

    private val projectDirectories: com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?>
    private val buildableUnits: com.google.common.collect.ImmutableMap<String?, BuildableUnit?>?
    private val alwaysAllowedConfigs: com.google.common.collect.ImmutableList<String?>?
    private val actualProjectFile: Label?

    /**
     * A project's buildable units.
     * 
     * 
     * A buildable unit is a named pair of build flags and target patterns. The name is stored as a
     * map key in [ProjectValue.getBuildableUnits]
     * 
     * 
     * See `third_party/bazel/src/main/protobuf/project/project.proto` for precise
     * definitions.
     */
    @AutoValue
    abstract class BuildableUnit {
        abstract fun name(): String?

        abstract fun targetPatternMatcher(): SimpleTargetPatternMatcher?

        abstract fun description(): String?

        abstract fun flags(): com.google.common.collect.ImmutableList<String?>?

        @kotlin.jvm.JvmField
        abstract val isDefault: Boolean

        companion object {
            /**
             * Creates a buildable unit.
             * 
             * @param name the buildable unit's name
             * @param targetPatterns the buildable unit's target patterns, or empty if they weren't set
             * @param description the buildable unit's user-friendly description, or empty if not set
             * @param flags the buildable unit's flags
             * @param isDefault whether this is the default buildable unit
             */
            @Throws(LabelSyntaxException::class)
            fun create(
                name: String?,
                targetPatterns: com.google.common.collect.ImmutableList<String?>?,
                description: String?,
                flags: com.google.common.collect.ImmutableList<String?>?,
                isDefault: Boolean
            ): BuildableUnit {
                return AutoValue_ProjectValue_BuildableUnit(
                    name, SimpleTargetPatternMatcher.create(targetPatterns), description, flags, isDefault
                )
            }
        }
    }

    init {
        this.projectDirectories = projectDirectories
        this.buildableUnits = buildableUnits
        this.alwaysAllowedConfigs = alwaysAllowedConfigs
        this.actualProjectFile = actualProjectFile
    }

    val defaultProjectDirectories: com.google.common.collect.ImmutableSet<String?>
        /**
         * Return the "default" `project_directories` map entry. If there are zero entries, returns
         * an empty set.
         */
        get() {
            if (projectDirectories.isEmpty()) {
                return com.google.common.collect.ImmutableSet.of<String?>()
            }
            // TODO: b/409377907 - Make sure this check still makes sense with the new format.
            com.google.common.base.Preconditions.checkArgument(
                projectDirectories.containsKey("default"),
                "project_directories must contain the 'default' key"
            )
            return com.google.common.collect.ImmutableSet.copyOf<String?>(projectDirectories.get("default"))
        }

    /**
     * If a project file has the content
     * 
     * {@snippet :
     * *   project = {
     * *     "actual": "//other:PROJECT.scl"
     * *   }
     * * }
     * 
     * 
     * then this is the same project defined canonically in `//other:PROJECT.scl` and this
     * method returns `//other:PROJECT.scl`. Else returns the [ProjectValue.Key] label
     * that produces this value.
     * 
     * 
     * Files that define "actual" cannot define any other content. That's considered a parsing
     * error.
     */
    fun getActualProjectFile(): Label? {
        return actualProjectFile
    }

    /**
     * Maps buildable unit names to definitions. Null if not specified. Note that an empty list is not
     * the same as unspecified.
     * 
     * 
     * Builds can trigger a buildable unit by setting `--scl_config=<name>`.
     */
    fun getBuildableUnits(): com.google.common.collect.ImmutableMap<String?, BuildableUnit?>? {
        return buildableUnits
    }

    fun getAlwaysAllowedConfigs(): com.google.common.collect.ImmutableList<String?>? {
        return alwaysAllowedConfigs
    }

    /**
     * Returns the map of named `project_directories` in the project. If the map is not defined
     * in the file, returns an empty map.
     */
    fun getProjectDirectories(): com.google.common.collect.ImmutableMap<String?, MutableCollection<String?>?> {
        return projectDirectories
    }

    /** The SkyKey. Uses the label of the project file as the input.  */
    class Key(projectFile: Label?) : SkyKey {
        private val projectFile: Label

        init {
            this.projectFile = com.google.common.base.Preconditions.checkNotNull<Label>(projectFile)
        }

        fun getProjectFile(): Label {
            return projectFile
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PROJECT
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val key = o as Key
            return projectFile == key.projectFile
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(projectFile)
        }
    }
}
