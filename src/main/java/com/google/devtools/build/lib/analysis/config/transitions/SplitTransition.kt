// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * A configuration transition that maps a single input [BuildOptions] to possibly multiple
 * output [BuildOptions]. This provides the ability to transition to multiple configurations
 * simultaneously.
 * 
 * 
 * Also see [PatchTransition], which maps a single input [BuildOptions] to a single
 * output. If your transition never needs to produce multiple outputs, you should use a [ ].
 * 
 * 
 * Corresponding rule implementations may require special support to handle this in an organized
 * way (e.g. for determining which CPU corresponds to which dep for a multi-arch split dependency).
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
interface SplitTransition : ConfigurationTransition {
    /**
     * Returns the map of `BuildOptions` after splitting, or the original options if this split
     * is a noop. The key values are used as dict keys in ctx.split_attr, so human-readable strings
     * are recommended.
     * 
     * 
     * Blaze throws an [IllegalArgumentException] if this method reads any options fragment
     * not declared in [ConfigurationTransition.requiresOptionFragments].
     * 
     * 
     * Returning an empty or null list triggers a [RuntimeException].
     */
    @Throws(java.lang.InterruptedException::class)
    fun split(
        buildOptions: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): MutableMap<String?, BuildOptions?>

    @Throws(java.lang.InterruptedException::class)
    override fun apply(
        buildOptions: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): MutableMap<String?, BuildOptions?> {
        val splitOptions: MutableMap<String?, BuildOptions?> = split(buildOptions, eventHandler)
        com.google.common.base.Verify.verifyNotNull<MutableMap<String?, BuildOptions?>?>(
            splitOptions,
            "Split transition output may not be null"
        )
        com.google.common.base.Verify.verify(!splitOptions.isEmpty(), "Split transition output may not be empty")
        return splitOptions
    }

    override fun reasonForOverride(): String {
        return "This is a fundamental transition modeling the need for multiply configured deps"
    }

    companion object {
        /**
         * Returns true iff `option` and `splitOptions` are equal.
         * 
         * 
         * This can be used to determine if a split is a noop.
         */
        fun equals(options: BuildOptions?, splitOptions: MutableCollection<BuildOptions?>): Boolean {
            return splitOptions.size == 1 && com.google.common.collect.Iterables.getOnlyElement<BuildOptions?>(
                splitOptions
            ).equals(options)
        }
    }
}
