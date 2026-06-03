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
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory
import com.google.testing.junit.runner.sharding.testing.RoundRobinShardingFilterFactory
import com.google.testing.junit.runner.sharding.testing.ShardingFilterTestCase

/** Tests for the [RoundRobinShardingFilter].  */
class RoundRobinShardingFilterTest : ShardingFilterTestCase() {
    fun testShardingIsBalanced() {
        val run1: MutableMap<org.junit.runner.manipulation.Filter?, MutableList<org.junit.runner.Description?>?> =
            simulateTestRun(
                FILTERS_1, GENERIC_TEST_DESCRIPTIONS
            )
        Truth.assertThat(run1.get(FILTERS_1.get(0))).hasSize(2)
        Truth.assertThat(run1.get(FILTERS_1.get(1))).hasSize(2)
        Truth.assertThat(run1.get(FILTERS_1.get(2))).hasSize(2)

        val run2: MutableMap<org.junit.runner.manipulation.Filter?, MutableList<org.junit.runner.Description?>?> =
            simulateTestRun(
                FILTERS_2, GENERIC_TEST_DESCRIPTIONS
            )
        Truth.assertThat(run2.get(FILTERS_2.get(0))).hasSize(2)
        Truth.assertThat(run2.get(FILTERS_2.get(1))).hasSize(2)
        Truth.assertThat(run2.get(FILTERS_2.get(2))).hasSize(1)
        Truth.assertThat(run2.get(FILTERS_2.get(3))).hasSize(1)
    }

    fun testShouldRun_throwsExceptionForUnknownDescription() {
        assertThrowsExceptionForUnknownDescription(FILTERS_1.get(0))
    }

    override fun createShardingFilterFactory(): ShardingFilterFactory? {
        return RoundRobinShardingFilterFactory()
    }

    companion object {
        private val GENERIC_TEST_DESCRIPTIONS: MutableList<org.junit.runner.Description?>? =
            ShardingFilterTestCase.createGenericTestCaseDescriptions(6)

        private val FILTERS_1: MutableList<org.junit.runner.manipulation.Filter?> = createFilters(
            GENERIC_TEST_DESCRIPTIONS, 3, RoundRobinShardingFilterFactory()
        )
        private val FILTERS_2: MutableList<org.junit.runner.manipulation.Filter?> = createFilters(
            GENERIC_TEST_DESCRIPTIONS, 4, RoundRobinShardingFilterFactory()
        )
    }
}
