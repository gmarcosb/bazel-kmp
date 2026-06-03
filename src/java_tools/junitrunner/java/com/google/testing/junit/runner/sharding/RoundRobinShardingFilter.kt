// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.sharding

import java.util.Collections
import java.util.HashMap

/**
 * Implements the round-robin sharding strategy.
 * 
 * 
 * This is done by equally dividing up the tests across all the shards
 * Each test is numbered and the test number is modded with the number of
 * shards and checked against the shard number to see whether it should run
 * on a particular shard.
 * 
 * 
 * Equals and hashCode implementations are not necessary for correct
 * sharding, but are done so that this filter can be compared in tests.
 */
class RoundRobinShardingFilter(
    testDescriptions: MutableCollection<org.junit.runner.Description?>,
    shardIndex: Int, totalShards: Int
) : org.junit.runner.manipulation.Filter() {
    // VisibleForTesting
    val testToShardMap: MutableMap<org.junit.runner.Description?, Int>

    // VisibleForTesting
    val shardIndex: Int

    // VisibleForTesting
    val totalShards: Int

    init {
        require(!(shardIndex < 0 || totalShards <= shardIndex))
        this.testToShardMap = buildTestToShardMap(testDescriptions)
        this.shardIndex = shardIndex
        this.totalShards = totalShards
    }

    override fun shouldRun(description: org.junit.runner.Description): Boolean {
        if (description.isSuite()) {
            return true
        }
        val testNumber: Int = testToShardMap.get(description)!!
        requireNotNull(testNumber) {
            ("This filter keeps a mapping from each test "
                    + "description to a shard, and the given description was not passed in when "
                    + "filter was constructed: " + description)
        }
        return (testNumber % totalShards) == shardIndex
    }

    override fun describe(): String {
        return "round robin sharding filter"
    }

    // VisibleForTesting
    internal class DescriptionComparator : java.util.Comparator<org.junit.runner.Description?> {
        override fun compare(d1: org.junit.runner.Description, d2: org.junit.runner.Description): Int {
            return d1.getDisplayName().compareTo(d2.getDisplayName())
        }
    }

    companion object {
        /**
         * Given a list of test case descriptions, returns a mapping from each
         * to its index in the list.
         */
        private fun buildTestToShardMap(
            testDescriptions: MutableCollection<org.junit.runner.Description?>
        ): MutableMap<org.junit.runner.Description?, Int> {
            val map: MutableMap<org.junit.runner.Description?, Int?> = HashMap<org.junit.runner.Description?, Int?>()

            // Sorting this list is incredibly important to correctness. Otherwise,
            // "shuffled" suites would break the sharding protocol.
            val sortedDescriptions: MutableList<org.junit.runner.Description> =
                java.util.ArrayList<org.junit.runner.Description>(testDescriptions)
            Collections.sort<org.junit.runner.Description?>(sortedDescriptions, DescriptionComparator())

            // If we get two descriptions that are equal, the shard number for the second
            // one will overwrite the shard number for the first.  Thus they'll run on the
            // same shard.
            var index = 0
            for (description in sortedDescriptions) {
                require(description.isTest()) {
                    ("Test suite should not be included in the set of tests "
                            + "to shard: " + description.getDisplayName())
                }
                map.put(description, index)
                index++
            }
            return Collections.unmodifiableMap<org.junit.runner.Description?, Int?>(map)
        }
    }
}
