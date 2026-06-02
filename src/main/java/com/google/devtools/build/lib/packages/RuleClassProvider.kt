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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment

/**
 * The collection of the supported build rules. Provides an StarlarkThread for Starlark rule
 * creation.
 */
interface RuleClassProvider : RuleDefinitionEnvironment {
    /** Label referencing the prelude file.  */
    fun getPreludeLabel(): Label?

    /** Returns true if a package location is considered to be experimental.  */
    fun isPackageUnderExperimental(packageIdentifier: PackageIdentifier?): Boolean

    /** Returns true if a package location is considered to be under prototypes.  */
    fun isPackageUnderPrototypes(packageIdentifier: PackageIdentifier?): Boolean

    /**
     * Returns true if the given non-experimental, non-prototype package is allowed to depend on (via
     * target visibility or .bzl load visibility) prototype packages.
     */
    fun mayPackageDependOnPrototypes(packageIdentifier: PackageIdentifier?): Boolean

    /** The runfiles prefix.  */
    fun getRunfilesPrefix(): String?

    /**
     * Where the bundled builtins bzl files are located. These are the builtins files used if `--experimental_builtins_bzl_path` is set to `%bundled%`. Note that this root lives in a
     * separate [InMemoryFileSystem].
     * 
     * 
     * May be null in tests, in which case `--experimental_builtins_bzl_path` must point to
     * the builtins root to be used.
     */
    fun getBundledBuiltinsRoot(): Root?

    /**
     * The relative location of the builtins_bzl directory within a Bazel source tree.
     * 
     * 
     * May be null in tests, in which case --experimental_builtins_bzl_path may not be
     * "%workspace%".
     */
    fun getBuiltinsBzlPackagePathInSource(): String?

    /** Returns a map from rule names to rule class objects.  */
    fun getRuleClassMap(): com.google.common.collect.ImmutableMap<String?, RuleClass?>?

    /** Returns a map from aspect names to aspect factory objects.  */
    fun getNativeAspectClassMap(): MutableMap<String?, NativeAspectClass?>?

    /**
     * Returns the [BazelStarlarkEnvironment], which is the final determiner of the BUILD and
     * .bzl environment (with and without builtins injection).
     */
    fun getBazelStarlarkEnvironment(): BazelStarlarkEnvironment?

    /** Retrieves an aspect from the aspect factory map using the key provided  */
    fun getNativeAspectClass(key: String?): NativeAspectClass?

    /**
     * Retrieves a [Map] from Starlark configuration fragment name to configuration fragment
     * class.
     */
    fun getConfigurationFragmentMap(): com.google.common.collect.ImmutableMap<String?, java.lang.Class<*>?>?
}
