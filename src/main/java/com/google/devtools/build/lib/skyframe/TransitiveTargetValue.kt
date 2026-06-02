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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * A *transitive* target reference that, when built in skyframe, loads the entire transitive
 * closure of a target.
 * 
 * 
 * Use [.unsuccessfulTransitiveLoading] if this or any of
 * its transitive values failed to load.
 */
@Immutable
@ThreadSafe
open class TransitiveTargetValue private constructor(transitiveTargets: NestedSet<Label?>?) : SkyValue {
    private val transitiveTargets: NestedSet<Label?>?

    init {
        this.transitiveTargets = transitiveTargets
    }

    /**
     * A value that represents an unsuccessful target loading.
     * 
     * 
     * If this TransitiveTargetKey that failed to load, `errorLoadingTarget` is not null.
     * 
     * 
     * If this TransitiveTargetKey loaded successfully, but some other key in its transitive
     * dependencies has failed to load, then this value is [UnsuccessfulTransitiveTargetValue]
     * with a null `errorLoadingTarget`.
     * 
     * 
     * This is kept as a subclass so as to not burden the TransitiveTargetValue class with wasteful
     * fields for error handling.
     */
    internal class UnsuccessfulTransitiveTargetValue private constructor(
        transitiveTargets: NestedSet<Label?>?,
        errorLoadingTarget: NoSuchTargetException?
    ) : TransitiveTargetValue(transitiveTargets) {
        private val errorLoadingTarget: NoSuchTargetException?

        init {
            this.errorLoadingTarget = errorLoadingTarget
        }

        override fun getErrorLoadingTarget(): NoSuchTargetException? {
            return errorLoadingTarget
        }

        override fun encounteredLoadingError(): Boolean {
            return true
        }
    }

    /** Returns the targets that were transitively loaded.  */
    fun getTransitiveTargets(): NestedSet<Label?>? {
        return transitiveTargets
    }

    open fun encounteredLoadingError(): Boolean {
        return false
    }

    open val errorLoadingTarget: NoSuchTargetException?
        get() = null

    companion object {
        fun unsuccessfulTransitiveLoading(
            transitiveTargets: NestedSet<Label?>?, errorLoadingTarget: NoSuchTargetException?
        ): TransitiveTargetValue {
            return UnsuccessfulTransitiveTargetValue(transitiveTargets, errorLoadingTarget)
        }

        fun successfulTransitiveLoading(transitiveTargets: NestedSet<Label?>?): TransitiveTargetValue {
            return TransitiveTargetValue(transitiveTargets)
        }
    }
}
