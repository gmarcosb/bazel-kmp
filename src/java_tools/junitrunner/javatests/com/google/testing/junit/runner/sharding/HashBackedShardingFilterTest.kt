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

import com.google.testing.junit.runner.sharding.HashBackedShardingFilter
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory
import com.google.testing.junit.runner.sharding.testing.ShardingFilterTestCase

/**
 * Tests for the [HashBackedShardingFilter].
 */
class HashBackedShardingFilterTest : ShardingFilterTestCase() {
    private class HashBackedShardingFilterFactory : ShardingFilterFactory {
        override fun createFilter(
            testDescriptions: MutableCollection<org.junit.runner.Description?>?, shardIndex: Int, totalShards: Int
        ): org.junit.runner.manipulation.Filter {
            return HashBackedShardingFilter(shardIndex, totalShards)
        }
    }

    override fun createShardingFilterFactory(): ShardingFilterFactory? {
        return HashBackedShardingFilterFactory()
    }
}
