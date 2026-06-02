// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/**
 * Resolves the appropriate toolchains for the given parameters.
 * 
 * @param toolchainTypesMap Returns the underlying map from label to ToolchainTypeRequirement.
 * @param execCompatibleWith Returns the execution constraints for this exec group.
 * @param copyFromDefault Whether this exec group should copy the data from the default exec group
 * in the same rule.
 */
@AutoCodec
class DeclaredExecGroup(
  toolchainTypesMap: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeRequirement?>?,
  execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?,
  @kotlin.jvm.JvmField val copyFromDefault: Boolean
) : ExecGroupApi {
    /** Returns the required toolchain types for this exec group.  */
    fun toolchainTypes(): com.google.common.collect.ImmutableSet<ToolchainTypeRequirement?> {
        return com.google.common.collect.ImmutableSet.copyOf<ToolchainTypeRequirement?>(this.toolchainTypesMap.values())
    }

    fun toolchainType(label: Label?): ToolchainTypeRequirement? {
        return this.toolchainTypesMap.get(label)
    }

    fun toBuilder(): Builder {
        return AutoBuilder_DeclaredExecGroup_Builder(this)
    }

    /** A builder interface to create DeclaredExecGroup instances.  */
    @AutoBuilder
    interface Builder {
        /** Sets the toolchain type requirements.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun toolchainTypes(toolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>): Builder {
            toolchainTypes.forEach(java.util.function.Consumer { toolchainTypeRequirement: ToolchainTypeRequirement ->
                this.addToolchainType(
                    toolchainTypeRequirement
                )
            })
            return this
        }

        fun toolchainTypesMapBuilder(): com.google.common.collect.ImmutableMap.Builder<Label?, ToolchainTypeRequirement?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainType(toolchainTypeRequirement: ToolchainTypeRequirement): Builder {
            this.toolchainTypesMapBuilder()
                .put(toolchainTypeRequirement.toolchainType(), toolchainTypeRequirement)
            return this
        }

        /** Sets the execution constraints.  */
        fun execCompatibleWith(execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?): Builder?

        /** Do not call, internal usage only.  */
        fun copyFromDefault(copyFromDefault: Boolean): Builder?

        /** Returns the new DeclaredExecGroup instance.  */
        fun build(): DeclaredExecGroup
    }

    val toolchainTypesMap: com.google.common.collect.ImmutableMap<Label?, ToolchainTypeRequirement?>?
    val execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>?

    init {
        this.execCompatibleWith = execCompatibleWith
        this.toolchainTypesMap = toolchainTypesMap
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<Label?, ToolchainTypeRequirement?>?>(
            toolchainTypesMap,
            "toolchainTypesMap"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<Label?>?>(
            execCompatibleWith,
            "execCompatibleWith"
        )
        com.google.common.base.Preconditions.checkArgument(
            !copyFromDefault || (toolchainTypesMap.isEmpty() && execCompatibleWith.isEmpty())
        )
    }

    companion object {
        // This is intentionally a string that would fail {@code Identifier.isValid} so that
        // users can't create a group with the same name.
        const val DEFAULT_EXEC_GROUP_NAME: String = "default-exec-group"

        /** An exec group that copies all data from the default exec group.  */
        @kotlin.jvm.JvmField
        val COPY_FROM_DEFAULT: DeclaredExecGroup = builder().copyFromDefault(true)!!.build()

        /** Returns a builder for a new DeclaredExecGroup.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_DeclaredExecGroup_Builder()
                .copyFromDefault(false)
                .toolchainTypes(com.google.common.collect.ImmutableSet.of<E?>())
                .execCompatibleWith(com.google.common.collect.ImmutableSet.of<E?>())
        }

        /** Returns true if the given exec group is an automatic exec group.  */
        fun isAutomatic(execGroupName: String): Boolean {
            return !net.starlark.java.syntax.Identifier.isValid(execGroupName) && execGroupName != DEFAULT_EXEC_GROUP_NAME
        }

        /**
         * Prepares the input exec groups.
         * 
         * 
         * Adds auto exec groups when `useAutoExecGroups` is true.
         */
        fun process(
            execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>,
            defaultExecWith: com.google.common.collect.ImmutableSet<Label?>,
            execGroupExecWith: com.google.common.collect.ImmutableMultimap<String?, Label?>,
            defaultToolchainTypes: com.google.common.collect.ImmutableSet<ToolchainTypeRequirement>,
            useAutoExecGroups: Boolean
        ): com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?> {
            val processedGroups: com.google.common.collect.ImmutableMap.Builder<String?, DeclaredExecGroup?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, DeclaredExecGroup?>(
                    if (useAutoExecGroups)
                        (execGroups.size() + defaultToolchainTypes.size())
                    else
                        execGroups.size()
                )
            for (entry in execGroups.entrySet()) {
                val name: String? = entry.getKey()
                var declaredExecGroup: DeclaredExecGroup = entry.getValue()

                if (declaredExecGroup.copyFromDefault) {
                    declaredExecGroup =
                        builder()
                            .execCompatibleWith(defaultExecWith)!!
                            .toolchainTypes(defaultToolchainTypes)
                            .build()
                }
                val extraExecWith: com.google.common.collect.ImmutableCollection<Label?> = execGroupExecWith.get(name)
                if (!extraExecWith.isEmpty()) {
                    declaredExecGroup =
                        declaredExecGroup.toBuilder()
                            .execCompatibleWith(
                                com.google.common.collect.ImmutableSet.builder<Label?>()
                                    .addAll(declaredExecGroup.execCompatibleWith)
                                    .addAll(extraExecWith)
                                    .build()
                            )!!
                            .build()
                }

                processedGroups.put(name, declaredExecGroup)
            }

            if (useAutoExecGroups) {
                // Creates one exec group for each toolchain (automatic exec groups).
                for (toolchainType in defaultToolchainTypes) {
                    var execCompatibleWith: com.google.common.collect.ImmutableSet<Label?>? = defaultExecWith
                    val extraExecWith: com.google.common.collect.ImmutableCollection<Label?> =
                        execGroupExecWith.get(toolchainType.toolchainType().getUnambiguousCanonicalForm())
                    if (!extraExecWith.isEmpty()) {
                        execCompatibleWith =
                            com.google.common.collect.ImmutableSet.builder<Label?>().addAll(defaultExecWith)
                                .addAll(extraExecWith).build()
                    }
                    processedGroups.put(
                        toolchainType.toolchainType().toString(),
                        builder()
                            .addToolchainType(toolchainType)
                            .execCompatibleWith(execCompatibleWith)!!
                            .build()
                    )
                }
            }
            return processedGroups.buildOrThrow()
        }
    }
}
