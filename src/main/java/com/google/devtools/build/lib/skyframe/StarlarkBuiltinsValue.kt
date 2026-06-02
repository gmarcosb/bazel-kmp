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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.RepositoryName

/**
 * A Skyframe value representing the result of evaluating the `@_builtins` pseudo-repository.
 * 
 * 
 * To avoid unnecessary Skyframe edges, the `StarlarkSemantics` are included in this value,
 * so that a caller who obtains a StarlarkBuiltinsValue can also access the StarlarkSemantics
 * without an additional dependency.
 * 
 * 
 * These are parsed from `@_builtins//:exports.bzl`.
 */
class StarlarkBuiltinsValue private constructor(
    predeclaredForBuildBzl: com.google.common.collect.ImmutableMap<String?, Any?>?,
    predeclaredForModuleBzl: com.google.common.collect.ImmutableMap<String?, Any?>?,
    predeclaredForBuild: com.google.common.collect.ImmutableMap<String?, Any?>?,
    exportedToJava: com.google.common.collect.ImmutableMap<String?, Any?>?,
    transitiveDigest: ByteArray?,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
) : SkyValue {
    // These are all (except transitiveDigest) deeply immutable since the Starlark values are already
    // frozen, so let's skip the accessors and mutators.
    /**
     * Top-level predeclared symbols for a .bzl file loaded on behalf of a BUILD file, after builtins
     * injection has been applied.
     */
    @kotlin.jvm.JvmField
    val predeclaredForBuildBzl: com.google.common.collect.ImmutableMap<String?, Any?>?

    /**
     * Top-level predeclared symbols for a .bzl file loaded on behalf of a MODULE file after builtins
     * injection has been applied.
     */
    val predeclaredForModuleBzl: com.google.common.collect.ImmutableMap<String?, Any?>?

    /**
     * Top-level predeclared symbols for a BUILD file, after builtins injection but before any prelude
     * file has been applied.
     */
    @kotlin.jvm.JvmField
    val predeclaredForBuild: com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Contents of the `exported_to_java` dict.  */
    @kotlin.jvm.JvmField
    val exportedToJava: com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Transitive digest of all .bzl files in `@_builtins`.  */
    @kotlin.jvm.JvmField
    val transitiveDigest: ByteArray?

    /** The StarlarkSemantics used for `@_builtins` evaluation.  */
    val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?

    init {
        this.predeclaredForBuildBzl = predeclaredForBuildBzl
        this.predeclaredForModuleBzl = predeclaredForModuleBzl
        this.predeclaredForBuild = predeclaredForBuild
        this.exportedToJava = exportedToJava
        this.transitiveDigest = transitiveDigest
        this.starlarkSemantics = starlarkSemantics
    }

    /**
     * Skyframe key for retrieving the `@_builtins` definitions.
     * 
     * 
     * This has no fields since there is only one `StarlarkBuiltinsValue` at a time.
     */
    internal class Key private constructor() : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.STARLARK_BUILTINS
        }

        override fun toString(): String {
            return "Starlark @_builtins"
        }

        override fun equals(other: Any?): Boolean {
            return other is Key
        }

        override fun hashCode(): Int {
            return 7277 // more or less xkcd/221
        }

        companion object {
            private val INSTANCE: Key = com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue.Key()
        }
    }

    companion object {
        /** Reports whether the given repository is the special builtins pseudo-repository.  */
        fun isBuiltinsRepo(repo: RepositoryName): Boolean {
            // Use String.equals(), not RepositoryName.equals(), to force case sensitivity.
            return repo.name.equals(RepositoryName.BUILTINS.name) && repo.isVisible()
        }

        fun create(
            predeclaredForBuildBzl: com.google.common.collect.ImmutableMap<String?, Any?>?,
            predeclaredForModuleBzl: com.google.common.collect.ImmutableMap<String?, Any?>?,
            predeclaredForBuild: com.google.common.collect.ImmutableMap<String?, Any?>?,
            exportedToJava: com.google.common.collect.ImmutableMap<String?, Any?>?,
            transitiveDigest: ByteArray?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        ): StarlarkBuiltinsValue {
            return StarlarkBuiltinsValue(
                predeclaredForBuildBzl,
                predeclaredForModuleBzl,
                predeclaredForBuild,
                exportedToJava,
                transitiveDigest,
                starlarkSemantics
            )
        }

        /**
         * Constructs a placeholder builtins value to be used when builtins injection is disabled, or for
         * use within builtins evaluation itself.
         * 
         * 
         * The placeholder simply wraps the StarlarkSemantics object. This lets code paths that don't
         * use injection still conveniently access the semantics without incurring a separate Skyframe
         * edge.
         */
        fun createEmpty(starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?): StarlarkBuiltinsValue {
            return StarlarkBuiltinsValue( /* predeclaredForBuildBzl= */
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),  /* predeclaredForModuleBzl= */
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),  /* predeclaredForBuild= */
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),  /* exportedToJava= */
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),  /* transitiveDigest= */
                byteArrayOf(),
                starlarkSemantics
            )
        }

        /** Returns the SkyKey for BuiltinsValue containing only additional builtin symbols and rules.  */
        @kotlin.jvm.JvmStatic
        fun key(): Key {
            return com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue.Key.Companion.INSTANCE
        }
    }
}
