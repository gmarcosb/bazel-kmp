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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.SkyKey

/**
 * Result of [ProcessableGraph.analyzeDepsDoneness]: Equivalent to an `Optional<Collection<SkyKey>>` but without the overhead of the wrapper `Optional`.
 */
class DepsReport private constructor(private val size: Int, arr: Array<SkyKey?>?) : MutableCollection<SkyKey?> {
    /** Note that this array may have trailing null elements past [.size].  */
    private val arr: Array<SkyKey?>?

    init {
        this.arr = arr
    }

    fun hasInformation(): Boolean {
        return arr != null
    }

    override fun size(): Int {
        return size
    }

    override fun isEmpty(): Boolean {
        return size == 0
    }

    override fun iterator(): MutableIterator<SkyKey?> {
        return com.google.common.collect.Iterators.limit<SkyKey?>(
            com.google.common.collect.Iterators.forArray<SkyKey?>(
                *arr
            ), size
        )
    }

    private fun throwUnsupported(): java.lang.UnsupportedOperationException? {
        throw java.lang.UnsupportedOperationException(this.toString())
    }

    override fun contains(o: Any?): Boolean {
        throw throwUnsupported()
    }

    override fun toArray(): Array<Any?>? {
        throw throwUnsupported()
    }

    override fun <T> toArray(a: Array<T?>?): Array<T?>? {
        throw throwUnsupported()
    }

    override fun add(skyKey: SkyKey?): Boolean {
        throw throwUnsupported()
    }

    override fun remove(o: Any?): Boolean {
        throw throwUnsupported()
    }

    override fun containsAll(c: MutableCollection<*>?): Boolean {
        throw throwUnsupported()
    }

    override fun addAll(c: MutableCollection<out SkyKey?>?): Boolean {
        throw throwUnsupported()
    }

    override fun removeAll(c: MutableCollection<*>?): Boolean {
        throw throwUnsupported()
    }

    override fun retainAll(c: MutableCollection<*>?): Boolean {
        throw throwUnsupported()
    }

    override fun clear() {
        throw throwUnsupported()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("size", size)
            .add("arr", java.util.Arrays.toString(arr))
            .toString()
    }

    /** Builder for [DepsReport].  */
    class Builder(maxSize: Int) {
        private var size = 0
        private val arr: Array<SkyKey?>

        init {
            arr = arrayOfNulls<SkyKey>(maxSize)
        }

        fun add(key: SkyKey?) {
            check(size < arr.size) { "Too many adds: " + key + ", " + this }
            arr[size] = key
            size++
        }

        fun build(): DepsReport {
            return DepsReport(size, arr)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("size", size)
                .add("arr", java.util.Arrays.toString(arr))
                .toString()
        }
    }

    companion object {
        val NO_INFORMATION: DepsReport = DepsReport(-1, null)
    }
}
