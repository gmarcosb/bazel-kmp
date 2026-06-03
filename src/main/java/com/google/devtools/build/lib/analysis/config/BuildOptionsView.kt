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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.config.BuildOptions
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/**
 * Wrapper for [BuildOptions] that only permits [BuildOptions.get] calls to [ ] from a pre-declared set.
 * 
 * 
 * This lets Blaze understand what fragments transitions require, which is helpful for
 * understanding what flags are and are not important to a given build.
 */
class BuildOptionsView(options: BuildOptions, allowedFragments: MutableSet<java.lang.Class<out FragmentOptions?>?>) :
    Cloneable {
    private val options: BuildOptions
    private val allowedFragments: MutableSet<java.lang.Class<out FragmentOptions?>?>

    /** Wraps a given [BuildOptions] with a "permitted" set of [FragmentOptions].  */
    init {
        this.options = options
        this.allowedFragments = allowedFragments
    }

    /**
     * Wrapper for [BuildOptions.get] that throws an [IllegalArgumentException] if the
     * given [FragmentOptions] isn't in the "permitted" set.
     */
    fun <T : FragmentOptions?> get(optionsClass: java.lang.Class<T?>?): T? {
        return options.get<T?>(checkFragment<T?>(optionsClass))
    }

    /**
     * Wrapper for [BuildOptions.contains] that throws an [IllegalArgumentException] if
     * the given [FragmentOptions] isn't in the "permitted" set.
     */
    fun contains(optionsClass: java.lang.Class<out FragmentOptions?>?): Boolean {
        return options.contains(checkFragment(optionsClass))
    }

    /**
     * Returns a new [BuildOptionsView] instance bound to a clone of the original's [ ].
     */
    public override fun clone(): BuildOptionsView {
        return BuildOptionsView(options.clone(), allowedFragments)
    }

    /**
     * Returns the underlying [BuildOptions].
     * 
     * 
     * Since this sheds all extra security from [BuildOptionsView], this should only be used
     * when a transition is returning its final result.
     * 
     * 
     * !!! No transition should call any [BuildOptions] accessor after this! !!!
     */
    fun underlying(): BuildOptions {
        return options
    }

    private fun <T : FragmentOptions?> checkFragment(optionsClass: java.lang.Class<T?>?): java.lang.Class<T?>? {
        com.google.common.base.Preconditions.checkArgument(
            allowedFragments.contains(optionsClass),
            "Can't access %s in allowed fragments %s",
            optionsClass,
            allowedFragments
        )
        return optionsClass
    }
}
