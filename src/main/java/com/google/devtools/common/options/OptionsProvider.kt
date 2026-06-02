// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import java.util.Collections

/**
 * A read-only interface for options parser results, which only allows to query the options of a
 * specific class, but not e.g. the residue any other information pertaining to the command line.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface OptionsProvider {
    /**
     * Returns the options instance for the given `optionsClass`, that is, the parsed options,
     * or null if it is not among those available.
     * 
     * 
     * The returned options should be treated by library code as immutable and a provider is
     * permitted to return the same options instance multiple times.
     */
    fun <O : com.google.devtools.common.options.OptionsBase?> getOptions(optionsClass: java.lang.Class<O?>?): O?

    /**
     * Returns the starlark options in a name:value map.
     * 
     * 
     * These follow the basics of the option syntax, --<name>=<value> but are parsed and stored
     * differently than native options based on <name> starting with "//". This is a sufficient
     * demarcation between starlark flags and native flags for now since all starlark flags are
     * targets and are identified by their package path. But in the future when we implement short
     * names for starlark options, this will need to change.
    </name></value></name> */
    fun getStarlarkOptions(): MutableMap<String?, Any?>?

    /**
     * Variant of [.getStarlarkOptions] that only returns Starlark that were explicitly set in
     * the command line.
     */
    fun getExplicitCommandLineStarlarkOptions(): MutableMap<String?, Any?>?

    /**
     * Returns the names of Starlark options allowing multiple option instances on the command line,
     * which are combined into a list value upon parsing (which then needs to be split if one wishes
     * to obtain the original command line). Corresponds to `allow_multiple = True` or `repeatable = True` in the `config` API in Starlark.
     */
    fun getStarlarkOptionsAllowingMultiple(): MutableSet<String?>?

    /**
     * Returns the options that were parsed from either a user blazerc file or the command line as a
     * map of option name to the option's `expandedFrom`, or "" if the option was not expanded.
     */
    fun getUserOptions(): MutableMap<String?, String?>?

    fun getScopesAttributes(): MutableMap<String?, String?>?

    fun getOnLeaveScopeValues(): MutableMap<String?, Any?>?

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: OptionsProvider = object : OptionsProvider {
            override fun <O : com.google.devtools.common.options.OptionsBase?> getOptions(optionsClass: java.lang.Class<O?>?): O? {
                return null
            }

            override fun getStarlarkOptions(): MutableMap<String?, Any?> {
                return Collections.emptyMap<String?, Any?>()
            }

            override fun getScopesAttributes(): MutableMap<String?, String?> {
                return Collections.emptyMap<String?, String?>()
            }

            override fun getOnLeaveScopeValues(): MutableMap<String?, Any?> {
                return Collections.emptyMap<String?, Any?>()
            }

            override fun getExplicitCommandLineStarlarkOptions(): MutableMap<String?, Any?> {
                return Collections.emptyMap<String?, Any?>()
            }

            override fun getStarlarkOptionsAllowingMultiple(): MutableSet<String?> {
                return Collections.emptySet<String?>()
            }

            override fun getUserOptions(): MutableMap<String?, String?> {
                return Collections.emptyMap<String?, String?>()
            }
        }
    }
}
