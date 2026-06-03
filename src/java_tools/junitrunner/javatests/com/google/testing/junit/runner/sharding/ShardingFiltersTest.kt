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

import com.google.common.truth.Truth
import com.google.testing.junit.runner.sharding.RoundRobinShardingFilter
import com.google.testing.junit.runner.sharding.ShardingEnvironment
import com.google.testing.junit.runner.sharding.ShardingFilters
import com.google.testing.junit.runner.sharding.ShardingFilters.ShardingStrategy
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory
import com.google.testing.junit.runner.sharding.testing.ShardingFilterTestCase
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [ShardingFilters].
 */
@RunWith(MockitoJUnitRunner::class)
class ShardingFiltersTest {
    @Mock
    var mockShardingEnvironment: ShardingEnvironment? = null

    @org.junit.Test
    fun testCreateShardingFilter_defaultStrategy() {
        val descriptions: MutableList<org.junit.runner.Description?>? =
            ShardingFilterTestCase.createGenericTestCaseDescriptions(6)
        val expectedFilter: RoundRobinShardingFilter = RoundRobinShardingFilter(descriptions, 0, 5)

        Mockito.`when`<Int?>(mockShardingEnvironment.getShardIndex()).thenReturn(0)
        Mockito.`when`<Int?>(mockShardingEnvironment.getTotalShards()).thenReturn(5)
        Mockito.`when`<String?>(mockShardingEnvironment.getTestShardingStrategy()).thenReturn(null)

        val shardingFilters: ShardingFilters = ShardingFilters(
            mockShardingEnvironment,
            ShardingStrategy.ROUND_ROBIN
        )
        val filter: org.junit.runner.manipulation.Filter = shardingFilters.createShardingFilter(descriptions)

        Truth.assertThat(filter).isInstanceOf(RoundRobinShardingFilter::class.java)
        val shardingFilter: RoundRobinShardingFilter = filter as RoundRobinShardingFilter
        Truth.assertThat(shardingFilter.testToShardMap).isEqualTo(expectedFilter.testToShardMap)
        Truth.assertThat(shardingFilter.shardIndex).isEqualTo(expectedFilter.shardIndex)
        Truth.assertThat(shardingFilter.totalShards).isEqualTo(expectedFilter.totalShards)
    }

    @org.junit.Test
    fun testCreateShardingFilter_customStrategy() {
        val descriptions: MutableList<org.junit.runner.Description?>? =
            ShardingFilterTestCase.createGenericTestCaseDescriptions(6)

        Mockito.`when`<Int?>(mockShardingEnvironment.getShardIndex()).thenReturn(0)
        Mockito.`when`<Int?>(mockShardingEnvironment.getTotalShards()).thenReturn(5)
        Mockito.`when`<String?>(mockShardingEnvironment.getTestShardingStrategy()).thenReturn(
            "com.google.testing.junit.runner.sharding.ShardingFiltersTest\$TestFilterFactory"
        )

        val shardingFilters: ShardingFilters = ShardingFilters(mockShardingEnvironment)
        val filter: org.junit.runner.manipulation.Filter = shardingFilters.createShardingFilter(descriptions)

        Truth.assertThat(filter.javaClass.getCanonicalName())
            .isEqualTo("com.google.testing.junit.runner.sharding.ShardingFiltersTest.TestFilter")
    }

    class TestFilterFactory : ShardingFilterFactory {
        override fun createFilter(
            testDescriptions: MutableCollection<org.junit.runner.Description?>?, shardIndex: Int, totalShards: Int
        ): org.junit.runner.manipulation.Filter? {
            return TestFilter()
        }
    }

    internal class TestFilter : org.junit.runner.manipulation.Filter() {
        override fun shouldRun(description: org.junit.runner.Description?): Boolean {
            return false
        }

        override fun describe(): String {
            return "test filter factory"
        }
    }
}
