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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.atomic.AtomicInteger

/**
 * A class to build shards (work queues) for a given task.
 * 
 * 
 * [.add]ed elements will be equally distributed among the shards.
 * 
 * @param <T> the type of collection over which we're sharding
</T> */
class Sharder<T>(maxNumShards: Int, expectedTotalSize: Int) : Iterable<MutableList<T?>?> {
    private val shards: com.google.common.collect.ImmutableList<MutableList<T?>>
    private val count: AtomicInteger = AtomicInteger()

    init {
        com.google.common.base.Preconditions.checkArgument(maxNumShards > 0)
        com.google.common.base.Preconditions.checkArgument(expectedTotalSize >= 0)
        this.shards = com.google.devtools.build.lib.concurrent.Sharder.Companion.immutableListOfLists<T?>(
            maxNumShards,
            expectedTotalSize / maxNumShards
        )
    }

    /**
     * Adds an item to a shard.
     * 
     * 
     * May safely be called concurrently by multiple threads.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun add(item: T?) {
        val nextShardIndex: Int = count.incrementAndGet() % shards.size()
        val shard: MutableList<T?> = shards.get(nextShardIndex)
        synchronized(shard) {
            shard.add(item)
        }
    }

    override fun iterator(): MutableIterator<MutableList<T?>?>? {
        return com.google.common.collect.Iterables.filter<MutableList<T?>?>(
            shards,
            com.google.common.base.Predicate { list: MutableList<T?>? -> !list!!.isEmpty() }).iterator()
    }

    companion object {
        /**
         * Returns an immutable list of mutable lists.
         * 
         * @param numLists the number of top-level lists.
         * @param expectedSize the expected size of each mutable list.
         * @return a list of lists.
         */
        private fun <T> immutableListOfLists(
            numLists: Int,
            expectedSize: Int
        ): com.google.common.collect.ImmutableList<MutableList<T?>> {
            val outerList: com.google.common.collect.ImmutableList.Builder<MutableList<T?>?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<MutableList<T?>?>(numLists)
            for (i in 0..<numLists) {
                outerList.add(java.util.ArrayList<T?>(expectedSize))
            }
            return outerList.build()
        }
    }
}
