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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** SkyValue returned by [BuildOptionsScopeFunction].  */
class BuildOptionsScopeValue(
    resolvedBuildOptionsWithScopeTypes: BuildOptions?,
    scopedFlags: MutableList<Label?>?,
    fullyResolvedScopes: LinkedHashMap<Label?, Scope?>?
) : SkyValue {
    var resolvedBuildOptionsWithScopeTypes: BuildOptions?
    var scopedFlags: MutableList<Label?>?
    var fullyResolvedScopes: LinkedHashMap<Label?, Scope?>?

    /** Key for [BuildOptionsScopeValue].  */
    @ThreadSafety.Immutable
    @AutoCodec
    class Key(buildOptions: BuildOptions?, flagsWithIncompleteScopeInfo: MutableList<Label?>?) : SkyKey {
        private val buildOptions: BuildOptions?
        private val flagsWithIncompleteScopeInfo: MutableList<Label?>?

        init {
            this.buildOptions = buildOptions
            this.flagsWithIncompleteScopeInfo = flagsWithIncompleteScopeInfo
        }

        fun getBuildOptions(): BuildOptions? {
            return buildOptions
        }

        /**
         * Returns the list of flags that are either project scoped or their scopes are not yet
         * resolved.
         */
        fun getFlagsWithIncompleteScopeInfo(): MutableList<Label?>? {
            return flagsWithIncompleteScopeInfo
        }

        val skyKeyInterner: SkyKeyInterner<*>
            get() = com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key.Companion.interner

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.BUILD_OPTIONS_SCOPE
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val key = o as Key
            return buildOptions == key.buildOptions
                    && flagsWithIncompleteScopeInfo == key.flagsWithIncompleteScopeInfo
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(buildOptions, flagsWithIncompleteScopeInfo)
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()
            fun create(buildOptions: BuildOptions?, flagsWithIncompleteScopeInfo: MutableList<Label?>?): Key {
                return com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key(
                        buildOptions,
                        flagsWithIncompleteScopeInfo
                    )
                )
            }
        }
    }

    init {
        this.resolvedBuildOptionsWithScopeTypes = resolvedBuildOptionsWithScopeTypes
        this.scopedFlags = scopedFlags
        this.fullyResolvedScopes = fullyResolvedScopes
    }

    /**
     * Returns the [BuildOptions] with the all starlark flags having their [ ] resolved.
     */
    fun getResolvedBuildOptionsWithScopeTypes(): BuildOptions? {
        return resolvedBuildOptionsWithScopeTypes
    }

    /**
     * Returns the map of [Label] of scoped flags to their [Scope] including both [ ] and [Scope.ScopeDefinition].
     */
    fun getFullyResolvedScopes(): LinkedHashMap<Label?, Scope?>? {
        return fullyResolvedScopes
    }

    companion object {
        fun create(
            inputBuildOptions: BuildOptions?,  // BuildOptions buildOptionsWithScopes,
            scopedFlags: MutableList<Label?>?,
            fullyResolvedScopes: LinkedHashMap<Label?, Scope?>?
        ): BuildOptionsScopeValue {
            return BuildOptionsScopeValue(inputBuildOptions, scopedFlags, fullyResolvedScopes)
        }
    }
}
