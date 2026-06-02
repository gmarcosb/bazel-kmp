// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ObjectGraphTraverser.EdgeType
import com.google.devtools.build.lib.util.ObjectGraphTraverser.ObjectReceiver
import com.google.devtools.build.lib.util.ShallowObjectSizeComputer
import java.util.Collections
import java.util.HashMap

/** An object receiver for [ObjectGraphTraverser] that collects statistics about RAM use.  */
class MemoryAccountant(measurers: Iterable<Measurer?>, collectDetails: Boolean) : ObjectReceiver {
    /** Measures the shallow size of an object.  */
    interface Measurer {
        /**
         * Return the shallow size of an objet.
         * 
         * 
         * If this instance doesn't know how to compute it, returns -1.
         */
        fun maybeGetShallowSize(o: Any?): Long
    }

    /** Statistics collected about RAM use.  */
    class Stats private constructor(collectDetails: Boolean) {
        private val objectCountByClass: MutableMap<String?, Long?>?
        private val memoryByClass: MutableMap<String?, Long?>?
        var objectCount: Long = 0L
            private set
        var memoryUse: Long = 0L
            private set

        init {
            objectCountByClass = if (collectDetails) HashMap<String?, Long?>() else null
            memoryByClass = if (collectDetails) HashMap<String?, Long?>() else null
        }

        private fun addObject(clazz: String?, size: Long) {
            objectCount += 1
            this.memoryUse += size
            if (objectCountByClass != null) {
                objectCountByClass.put(clazz, objectCountByClass.getOrDefault(clazz, 0L)!! + 1)
            }

            if (memoryByClass != null) {
                memoryByClass.put(clazz, memoryByClass.getOrDefault(clazz, 0L)!! + size)
            }
        }

        fun getObjectCountByClass(): MutableMap<String?, Long?> {
            return Collections.unmodifiableMap<String?, Long?>(objectCountByClass)
        }

        fun getMemoryByClass(): MutableMap<String?, Long?> {
            return Collections.unmodifiableMap<String?, Long?>(memoryByClass)
        }
    }

    private val measurers: com.google.common.collect.ImmutableList<Measurer>
    val stats: Stats

    init {
        this.measurers = com.google.common.collect.ImmutableList.copyOf<Measurer?>(measurers)
        stats = com.google.devtools.build.lib.util.MemoryAccountant.Stats(collectDetails)
    }

    @kotlin.jvm.Synchronized
    override fun objectFound(o: Any, context: String?) {
        var context = context
        if (context == null) {
            if (o.javaClass.isArray()) {
                context = "[] " + o.javaClass.getComponentType().getName()
            } else {
                context = o.javaClass.getName()
            }
        }

        val size = getShallowSize(o)
        stats.addObject(context, size)
    }

    private fun getShallowSize(o: Any): Long {
        for (measurer in measurers) {
            val candidate: Long = measurer.maybeGetShallowSize(o)
            if (candidate >= 0) {
                return candidate
            }
        }

        return ShallowObjectSizeComputer.getShallowSize(o)
    }

    override fun edgeFound(from: Any?, to: Any?, toContext: String?, edgeType: EdgeType?) {
        // Ignored for now.
    }
}
