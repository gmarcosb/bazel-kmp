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

import com.google.devtools.build.lib.analysis.config.BuildOptionDetails

/**
 * A configuration transition.
 */
interface ConfigurationTransition {
    /**
     * Declares the [FragmentOptions] this transition may read.
     * 
     * 
     * Blaze throws an [IllegalArgumentException] if [.apply] is called on an options
     * fragment that isn't declared here.
     */
    fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>? {
        return com.google.common.collect.ImmutableSet.of<java.lang.Class<out FragmentOptions?>?>()
    }

    /**
     * Adds required configuration fragments to the given [ ].
     * 
     * 
     * A [BuildOptionDetails] instance is provided for Starlark transitions, which need to
     * map required options to their [FragmentOptions].
     * 
     * 
     * Non-Starlark transitions should override [.requiresOptionFragments] and keep the
     * default implementation of this method.
     */
    fun addRequiredFragments(
        requiredFragments: RequiredConfigFragmentsProvider.Builder, optionDetails: BuildOptionDetails?
    ) {
        requiredFragments.addOptionsClasses(requiresOptionFragments())
    }

    /**
     * Returns the map of `BuildOptions` after applying this transition. The returned map keys
     * are only used for dealing with split transitions. Patch transitions, including internal, native
     * Patch transitions, should return a single entry map with key `PATCH_TRANSITION_KEY`.
     * 
     * 
     * Blaze throws an [IllegalArgumentException] if this method reads any options fragment
     * not declared in [.requiresOptionFragments].
     * 
     * 
     * Returning an empty or null map triggers a [RuntimeException].
     */
    @Throws(java.lang.InterruptedException::class)
    fun apply(
        buildOptions: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): MutableMap<String?, BuildOptions?>?

    /**
     * We want to keep the number of transition interfaces no larger than what's necessary to maintain
     * a clear configuration API.
     * 
     * 
     * This method provides a speed bump against creating new interfaces too casually. While we
     * could provide stronger enforcement by making [ConfigurationTransition] an abstract class
     * with a limited access constructor, keeping it as an interface supports defining transitions
     * with lambdas.
     * 
     * 
     * If you're considering adding a new override, contact bazel-discuss@googlegroups.com to
     * discuss.
     */
    @Suppress("unused")
    fun reasonForOverride(): String?

    val name: String?
        get() = this.javaClass.getSimpleName()

    /** Allows the given [Visitor] to inspect this transition.  */
    @Throws(E::class)
    fun <E : java.lang.Exception?> visit(visitor: Visitor<E?>) {
        visitor.accept(this)
    }

    /** Helper object that can be used to inspect [ConfigurationTransition] instances.  */
    fun interface Visitor<E : java.lang.Exception?> {
        @Throws(E::class)
        fun accept(transition: ConfigurationTransition?)
    }

    companion object {
        /**
         * A designated key string for patch transitions. See [ConfigurationTransition.apply] for
         * its usage.
         */
        const val PATCH_TRANSITION_KEY: String = ""
    }
}
