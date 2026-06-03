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

/**
 * Sharding filter that uses the hashcode of the test description to
 * assign it to a shard.
 */
internal class HashBackedShardingFilter(shardIndex: Int, totalShards: Int) : org.junit.runner.manipulation.Filter() {
    private val shardIndex: Int
    private val totalShards: Int

    init {
        require(!(shardIndex < 0 || totalShards <= shardIndex))
        this.shardIndex = shardIndex
        this.totalShards = totalShards
    }

    override fun shouldRun(description: org.junit.runner.Description): Boolean {
        if (description.isSuite()) {
            return true
        }
        var mod: Int = description.getDisplayName().hashCode() % totalShards
        if (mod < 0) {
            mod += totalShards
        }
        check(!(mod < 0 || mod >= totalShards))

        return mod == shardIndex
    }

    override fun describe(): String {
        return "hash-backed sharding filter"
    }
}
