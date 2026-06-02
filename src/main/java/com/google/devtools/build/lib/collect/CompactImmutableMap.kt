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
package com.google.devtools.build.lib.collect

/**
 * A minimal map interface that avoids methods whose implementation tends to force GC churn, or
 * otherwise overly constrain implementation freedom.
 */
interface CompactImmutableMap<K, V> : Iterable<K?> {
    fun containsKey(key: K?): Boolean {
        return get(key) != null
    }

    fun get(key: K?): V?

    fun size(): Int

    fun keyAt(index: Int): K?

    fun valueAt(index: Int): V?

    override fun iterator(): MutableIterator<K?> {
        return object : MutableIterator<K?> {
            val index: Int = 0

            override fun hasNext(): Boolean {
                return index < size()
            }

            override fun next(): K? {
                val key = keyAt(index)
                ++index
                return key
            }
        }
    }
}
