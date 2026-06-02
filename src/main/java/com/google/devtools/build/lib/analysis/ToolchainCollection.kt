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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/**
 * A wrapper class for a map of exec_group names to their relevant ToolchainContext.
 * 
 * @param <T> any class that extends ToolchainContext. This generic allows ToolchainCollection to be
 * used, e.g., both before and after toolchain resolution.
 * @param contextMap A map of execution group names to toolchain contexts.
</T> */
@AutoCodec
class ToolchainCollection<T : ToolchainContext?>(contextMap: com.google.common.collect.ImmutableMap<String?, T?>?) {
    val defaultToolchainContext: T?
        get() = this.contextMap.get(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)

    fun hasToolchainContext(execGroup: String?): Boolean {
        return this.contextMap.containsKey(execGroup)
    }

    fun getToolchainContext(execGroup: String?): T? {
        return this.contextMap.get(execGroup)
    }

    val resolvedToolchains: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>
        get() = this.contextMap.values.stream()
            .flatMap<com.google.devtools.build.lib.cmdline.Label?> { c: T? -> c.resolvedToolchainLabels().stream() }
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<com.google.devtools.build.lib.cmdline.Label?>())

    val execGroupNames: com.google.common.collect.ImmutableSet<String?>
        get() = this.contextMap.keys

    val targetPlatform: PlatformInfo?
        /**
         * This is safe because all toolchain context in a toolchain collection should have the same
         * target platform
         */
        get() = this.defaultToolchainContext.targetPlatform()

    fun asToolchainContexts(): ToolchainCollection<ToolchainContext?> {
        return this as ToolchainCollection<ToolchainContext?>
    }

    /** Builder for ToolchainCollection.  */
    class Builder<T : ToolchainContext?> {
        // This is not immutable so that we can check for duplicate keys easily.
        private val toolchainContexts: SequencedMap<String?, T?>

        private constructor() {
            this.toolchainContexts = LinkedHashMap<String?, T?>()
        }

        private constructor(expectedSize: Int) {
            this.toolchainContexts =
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, T?>(expectedSize)
        }

        fun build(): ToolchainCollection<T?> {
            com.google.common.base.Preconditions.checkArgument(
                toolchainContexts.containsKey(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME)
            )
            return ToolchainCollection<T?>(com.google.common.collect.ImmutableMap.copyOf<String?, T?>(toolchainContexts))
        }

        fun addContext(execGroup: String?, context: T?) {
            com.google.common.base.Preconditions.checkArgument(
                !toolchainContexts.containsKey(execGroup),
                "Duplicate add of '%s' exec group to toolchain collection.",
                execGroup
            )
            toolchainContexts.put(execGroup, context)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDefaultContext(context: T?): Builder<T?> {
            addContext(DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME, context)
            return this
        }
    }

    val contextMap: com.google.common.collect.ImmutableMap<String?, T?>?

    init {
        this.contextMap = contextMap
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, T?>?>(contextMap, "contextMap")
    }

    companion object {
        /** Returns a new builder for [ToolchainCollection] instances.  */
        @kotlin.jvm.JvmStatic
        fun <T : ToolchainContext?> builder(): Builder<T?> {
            return com.google.devtools.build.lib.analysis.ToolchainCollection.Builder<T?>()
        }

        fun <T : ToolchainContext?> builderWithExpectedSize(expectedSize: Int): Builder<T?> {
            return com.google.devtools.build.lib.analysis.ToolchainCollection.Builder<T?>(expectedSize)
        }
    }
}
