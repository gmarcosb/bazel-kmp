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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.Differencer.DiffWithDelta.Delta
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.WalkableGraph

/**
 * Calculate set of changed values in a graph.
 */
interface Differencer {
    /**
     * Represents a set of changed values.
     */
    interface Diff {
        val isEmpty: Boolean
            get() = changedKeysWithoutNewValues()!!.isEmpty() && changedKeysWithNewValues()!!.isEmpty()

        /**
         * Returns the value keys whose values have changed, but for which we don't have the new values.
         */
        fun changedKeysWithoutNewValues(): MutableCollection<SkyKey?>?

        /**
         * Returns the value keys whose values have changed, along with their new values wrapped in a
         * [Delta].
         * 
         * 
         * The values in here cannot have any dependencies. This is required in order to prevent
         * conflation of injected values and derived values.
         */
        fun changedKeysWithNewValues(): MutableMap<SkyKey?, Delta?>?
    }

    /** A [Diff] that also potentially contains the new and old values for each changed key.  */
    interface DiffWithDelta : Diff {
        /**
         * Represents the delta between two values of the same key.
         * 
         * @param oldValue Returns the old value, if any.
         * @param newValue Returns the new value.
         * @param newMaxTransitiveSourceVersion Returns the max transitive source version of the new
         * value.
         */
        class Delta(
            oldValue: SkyValue?,
            newValue: SkyValue?,
            newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
        ) {
            val oldValue: SkyValue?
            val newValue: SkyValue?
            val newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?

            init {
                this.newMaxTransitiveSourceVersion = newMaxTransitiveSourceVersion
                this.newValue = newValue
                this.oldValue = oldValue
                java.util.Objects.requireNonNull<SkyValue?>(newValue, "newValue")
            }

            companion object {
                fun justNew(newValue: SkyValue?): Delta {
                    return changed( /* oldValue= */null, newValue,  /* newMaxTransitiveSourceVersion= */null)
                }

                fun justNew(
                    newValue: SkyValue?, newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
                ): Delta {
                    return changed( /* oldValue= */null, newValue, newMaxTransitiveSourceVersion)
                }

                fun changed(
                    oldValue: SkyValue?,
                    newValue: SkyValue?,
                    newMaxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
                ): Delta {
                    return Delta(
                        oldValue,
                        com.google.common.base.Preconditions.checkNotNull<SkyValue?>(newValue),
                        newMaxTransitiveSourceVersion
                    )
                }
            }
        }
    }

    /**
     * Returns the value keys that have changed between the two Versions.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getDiff(
        fromGraph: WalkableGraph?,
        fromVersion: com.google.devtools.build.skyframe.Version?,
        toVersion: com.google.devtools.build.skyframe.Version?
    ): Diff?
}
