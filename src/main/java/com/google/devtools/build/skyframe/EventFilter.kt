// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.SkyKey

/** Filters out events which should not be stored during evaluation in [ParallelEvaluator].  */
interface EventFilter {
    /**
     * Returns true if any [events][com.google.devtools.build.lib.events.Reportable] should
     * be stored in skyframe nodes. Otherwise, optimizations may be made to avoid doing unnecessary
     * work when evaluating node entries.
     */
    fun storeEvents(): Boolean

    /**
     * Determines whether stored [events][com.google.devtools.build.lib.events.Reportable]
     * should propagate from `depKey` to `primaryKey`.
     * 
     * 
     * Only relevant if [.storeEvents] returns `true`.
     */
    fun shouldPropagate(depKey: SkyKey?, primaryKey: SkyKey?): Boolean

    companion object {
        @kotlin.jvm.JvmField
        val FULL_STORAGE: EventFilter = object : EventFilter {
            override fun storeEvents(): Boolean {
                return true
            }

            override fun shouldPropagate(depKey: SkyKey?, primaryKey: SkyKey?): Boolean {
                return true
            }
        }

        @kotlin.jvm.JvmField
        val NO_STORAGE: EventFilter = object : EventFilter {
            override fun storeEvents(): Boolean {
                return false
            }

            override fun shouldPropagate(depKey: SkyKey?, primaryKey: SkyKey?): Boolean {
                throw java.lang.UnsupportedOperationException()
            }
        }
    }
}
