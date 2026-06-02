// Copyright 2023 The Bazel Authors. All rights reserved.
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

/**
 * A library of the fixed Starlark environment for various contexts.
 * 
 * 
 * This is the source of truth for what symbols are available in what Starlark contexts (BUILD,
 * .bzl, etc.), before considering how symbols may be added by registering them on the rule class
 * provider, or how symbols may be substituted by builtins injection. In other words, this is the
 * starting point for defining the minimum Starlark environments that Bazel supports for BUILD
 * files, .bzl files, etc. See [BazelStarlarkEnvironment] for the final determination of the
 * environment after accounting for registered symbols and builtins injection.
 * 
 * 
 * This is split between an interface in the lib/packages/ directory and an implementation in the
 * lib/analysis/starlark/ directory, in order to avoid new dependency edges from lib/packages/ to
 * lib/analysis/.
 */
interface StarlarkGlobals {
    /**
     * Returns a simple environment containing a few general utility modules, `depset`, and
     * `select()`.
     * 
     * 
     * In general, if you need a Bazel-y Starlark environment and don't know what to choose, prefer
     * to use this one for uniformity with as many other contexts as possible.
     */
    fun getUtilToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /**
     * Similar to [.getUtilToplevels] but without `select()` and with `struct`. Used
     * for cquery.
     */
    // TODO(bazel-team): Consider whether we should replace usage of this with getUtilTopLevels(), at
    // the cost of the cquery dialect changing slightly, for the sake of uniformity and fewer
    // kinds of environments.
    fun getUtilToplevelsForCquery(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /**
     * Returns the fixed top-levels for BUILD files that also happen to be fields of `native`.
     * This does not include any native rules.
     */
    fun getFixedBuildFileToplevelsSharedWithNative(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the fixed top-levels for BUILD files that are *not* also fields of `native`.  */
    fun getFixedBuildFileToplevelsNotInNative(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the fixed top-levels for .bzl files, excluding the `native` object.  */
    fun getFixedBzlToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the top-levels for .scl files.  */
    fun getSclToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the top-levels for MODULE.bazel files and their imports.  */
    fun getModuleToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the top-levels for REPO.bazel files.  */
    fun getRepoToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?

    /** Returns the top-levels for VENDOR.bazel files.  */
    fun getVendorToplevels(): com.google.common.collect.ImmutableMap<String?, Any?>?
}
