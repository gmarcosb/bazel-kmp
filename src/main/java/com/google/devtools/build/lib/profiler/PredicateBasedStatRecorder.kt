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
package com.google.devtools.build.lib.profiler

/**
 * A stat recorder that is able to look at the kind of object added and delegate to the appropriate
 * [StatRecorder] based on a predicate.
 * 
 * 
 *  Note that the predicates are evaluated in order and delegated only to the first one. That
 * means that the most specific and cheapest predicates should be passed first.
 */
class PredicateBasedStatRecorder(stats: MutableList<RecorderAndPredicate>) :
    com.google.devtools.build.lib.profiler.StatRecorder {
    private val predicates: Array<java.util.function.Predicate<in String?>?>
    private val recorders: Array<com.google.devtools.build.lib.profiler.StatRecorder>

    init {
        predicates =
            arrayOfNulls<java.util.function.Predicate<*>>(stats.size()) as Array<java.util.function.Predicate<in String?>?> // unchecked, rawtypes
        recorders = arrayOfNulls<com.google.devtools.build.lib.profiler.StatRecorder>(stats.size())
        for (i in stats.indices) {
            val stat = stats.get(i)
            predicates[i] = stat.predicate
            recorders[i] = stat.recorder
        }
    }

    override fun addStat(duration: Int, obj: Any) {
        val description: String? = obj.toString()
        for (i in predicates.indices) {
            if (predicates[i].test(description)) {
                recorders[i].addStat(duration, obj)
                return
            }
        }
    }

    override fun isEmpty(): Boolean {
        for (recorder in recorders) {
            if (!recorder.isEmpty()) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        val sb: java.lang.StringBuilder = java.lang.StringBuilder()
        for (recorder in recorders) {
            if (recorder.isEmpty()) {
                continue
            }
            sb.append(recorder)
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * A Wrapper of a `StatRecorder` and a `Predicate`. Objects that matches the predicate
     * will be delegated to the StatRecorder.
     */
    class RecorderAndPredicate(
        recorder: com.google.devtools.build.lib.profiler.StatRecorder?,
        predicate: java.util.function.Predicate<in String?>?
    ) {
        private val recorder: com.google.devtools.build.lib.profiler.StatRecorder?
        private val predicate: java.util.function.Predicate<in String?>?

        init {
            this.recorder = recorder
            this.predicate = predicate
        }
    }
}
