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

import com.google.devtools.build.lib.analysis.ProviderCollection
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.skyframe.NotComparableSkyValue

/**
 * Super-interface for [ConfiguredTargetValue] and [RuleConfiguredObjectValue]
 * (transitively including [AspectValue]).
 */
interface ConfiguredObjectValue : NotComparableSkyValue {
    /** Returns the configured target/aspect for this value.  */
    @kotlin.jvm.JvmField
    val configuredObject: ProviderCollection?

    /**
     * Returns the metadata for the set packages transitively loaded by this value. Must only be used
     * for:
     * 
     * 
     *  * constructing the package -> source root map needed for some builds, OR
     *  * building the repo mapping manifest for runfiles
     * 
     * 
     * If the caller has not specified that this map needs to be constructed (via the constructor
     * argument in [ ][com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredTargetFunction] or
     * [com.google.devtools.build.lib.skyframe.AspectFunction.AspectFunction]), calling this
     * will crash.
     */
    // TODO(b/283125139): Most builds never need to build a repo mapping manifest. Store transitive
    // packages outside of configured object values to save the wasted field.
    val transitivePackages: NestedSet<com.google.devtools.build.lib.packages.Package.Metadata?>?
        /**
         * Returns the metadata for the set packages transitively loaded by this value. Must only be used
         * for:
         * 
         * 
         *  * constructing the package -> source root map needed for some builds, OR
         *  * building the repo mapping manifest for runfiles
         * 
         * 
         * If the caller has not specified that this map needs to be constructed (via the constructor
         * argument in [ ][com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction.ConfiguredTargetFunction] or
         * [com.google.devtools.build.lib.skyframe.AspectFunction.AspectFunction]), calling this
         * will crash.
         */
        get
}
