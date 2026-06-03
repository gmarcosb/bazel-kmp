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

import java.io.IOException

/**
 * Utility class that encapsulates dependencies from sharding implementations
 * on the test environment.  See http://bazel.build/docs/test-sharding.html for a
 * list of all environment variables related to test sharding.
 */
open class ShardingEnvironment {
    open val isShardingEnabled: Boolean
        /**
         * Return true iff the current test should be sharded.
         */
        get() = java.lang.System.getenv("TEST_TOTAL_SHARDS") != null

    open val shardIndex: Int
        /**
         * Returns the 0-indexed test shard number, where
         * 0 <= shard index < total shards.
         * If the environment does not specify a test shard number, returns 0.
         */
        get() {
            val shardIndex: String? = java.lang.System.getenv("TEST_SHARD_INDEX")
            return if (shardIndex == null) 0 else shardIndex.toInt()
        }

    open val totalShards: Int
        /**
         * Returns the total number of test shards, or 1 if not specified by the
         * test environment.
         */
        get() {
            val totalShards: String? = java.lang.System.getenv("TEST_TOTAL_SHARDS")
            return if (totalShards == null) 1 else totalShards.toInt()
        }

    /**
     * Creates the shard file that is used to indicate that tests are
     * being sharded.
     */
    open fun touchShardFile() {
        val shardStatusPath: String? = java.lang.System.getenv("TEST_SHARD_STATUS_FILE")
        val shardFile: java.io.File? = (if (shardStatusPath == null) null else java.io.File(shardStatusPath))
        touchShardFile(shardFile)
    }

    open val testShardingStrategy: String?
        /**
         * Returns the test sharding strategy optionally specified by the JVM flag
         * [.TEST_SHARDING_STRATEGY], which maps to the enums in
         * [com.google.testing.junit.runner.sharding.ShardingFilters.ShardingStrategy].
         */
        get() = java.lang.System.getProperty(TEST_SHARDING_STRATEGY)

    companion object {
        /**
         * A singleton instance of ShardingEnvironment declared for convenience.
         */
        val DEFAULT: ShardingEnvironment = ShardingEnvironment()

        /** Usage: -Dtest.sharding.strategy=round_robin  */
        private const val TEST_SHARDING_STRATEGY = "test.sharding.strategy"

        // VisibleForTesting
        fun touchShardFile(shardFile: java.io.File?) {
            if (shardFile != null) {
                try {
                    if (!shardFile.createNewFile() && !shardFile.setLastModified(java.lang.System.currentTimeMillis())) {
                        throw IOException("Unable to update modification time of " + shardFile)
                    }
                } catch (e: IOException) {
                    throw java.lang.RuntimeException("Error writing shard file " + shardFile, e)
                }
            }
        }
    }
}
