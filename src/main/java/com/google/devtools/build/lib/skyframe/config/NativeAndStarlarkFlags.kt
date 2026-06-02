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

import com.google.devtools.build.lib.analysis.config.FragmentOptions

/**
 * Container for storing a set of native and Starlark flag settings in separate buckets.
 * 
 * 
 * This is necessary because native and Starlark flags are parsed with different logic.
 */
@AutoValue
abstract class NativeAndStarlarkFlags {
    /** Builder for new [NativeAndStarlarkFlags] instances.  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun nativeFlags(nativeFlags: com.google.common.collect.ImmutableList<String?>?): Builder?

        abstract fun starlarkFlags(starlarkFlags: com.google.common.collect.ImmutableMap<String?, Any?>?): Builder?

        abstract fun scopesAttributes(scopesAttributes: com.google.common.collect.ImmutableMap<String?, String?>?): Builder?

        abstract fun starlarkFlagDefaults(starlarkFlagDefaults: com.google.common.collect.ImmutableMap<String?, Any?>?): Builder?

        abstract fun starlarkOptionAllowingMultiple(
            starlarkOptionAllowingMultiple: com.google.common.collect.ImmutableSet<String?>?
        ): Builder?

        abstract fun optionsClasses(
            optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?
        ): Builder?

        abstract fun repoMapping(repoMapping: RepositoryMapping?): Builder?

        abstract fun build(): NativeAndStarlarkFlags?
    }

    /**
     * The native flags from a given set of flags, in the format `[--flag=value]` or `
     * ["--flag", "value"]`.
     */
    abstract fun nativeFlags(): com.google.common.collect.ImmutableList<String?>?

    /**
     * The Starlark flags from a given set of flags, mapped to the correct converted data type. If a
     * Starlark flag is explicitly set to the default value it should still appear in this map so that
     * consumers can properly handle the flag.
     */
    abstract fun starlarkFlags(): com.google.common.collect.ImmutableMap<String?, Any?>?

    abstract fun scopesAttributes(): com.google.common.collect.ImmutableMap<String?, String?>?

    // TODO: https://github.com/bazelbuild/bazel/issues/22365 - Improve looking up Starlark flag
    // option definitions and do not store this.
    abstract fun starlarkFlagDefaults(): com.google.common.collect.ImmutableMap<String?, Any?>?

    // TODO: https://github.com/bazelbuild/bazel/issues/22365 - Same as above.
    abstract fun starlarkOptionAllowingMultiple(): com.google.common.collect.ImmutableSet<String?>?

    abstract fun optionsClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?

    abstract fun repoMapping(): RepositoryMapping?

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(): com.google.devtools.common.options.OptionsParsingResult {
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder()
                .optionsClasses(this.optionsClasses()) // We need the ability to re-map internal options in the mappings file.
                .ignoreInternalOptions(false)
                .withConversionContext(this.repoMapping())
                .build()
        parser.parse(this.nativeFlags())
        parser.setStarlarkOptions(this.starlarkFlags(), this.starlarkOptionAllowingMultiple())
        parser.setScopesAttributes(this.scopesAttributes())
        return parser
    }

    companion object {
        val EMPTY: NativeAndStarlarkFlags? = builder().build()

        /** Returns a new [Builder].  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return Builder()
                .nativeFlags(com.google.common.collect.ImmutableList.of<E?>())
                .starlarkFlags(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .starlarkFlagDefaults(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .starlarkOptionAllowingMultiple(com.google.common.collect.ImmutableSet.of<E?>())
                .scopesAttributes(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .optionsClasses(com.google.common.collect.ImmutableSet.of<E?>())!!
        }
    }
}
