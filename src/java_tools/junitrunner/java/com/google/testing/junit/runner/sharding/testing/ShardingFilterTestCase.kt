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
package com.google.testing.junit.runner.sharding.testing

import com.google.common.truth.Truth
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory
import junit.framework.TestCase
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import java.util.*

/**
 * Common base class for all sharding filter tests.
 */
abstract class ShardingFilterTestCase : TestCase() {
    /**
     * Returns a filter of the subclass type using the given descriptions,
     * shard index, and total number of shards.
     */
    protected abstract fun createShardingFilterFactory(): ShardingFilterFactory?

    fun testShardingIsCompleteAndPartitioned_oneShard() {
        assertShardingIsCompleteAndPartitioned(createFilters(TEST_DESCRIPTIONS, 1), TEST_DESCRIPTIONS)
    }

    fun testShardingIsStable_oneShard() {
        assertShardingIsStable(createFilters(TEST_DESCRIPTIONS, 1), TEST_DESCRIPTIONS)
    }

    fun testShardingIsCompleteAndPartitioned_moreTestsThanShards() {
        assertShardingIsCompleteAndPartitioned(createFilters(TEST_DESCRIPTIONS, 5), TEST_DESCRIPTIONS)
    }

    fun testShardingIsStable_moreTestsThanShards() {
        assertShardingIsStable(createFilters(TEST_DESCRIPTIONS, 5), TEST_DESCRIPTIONS)
    }

    fun testShardingIsCompleteAndPartitioned_sameNumberOfTestsAndShards() {
        assertShardingIsCompleteAndPartitioned(createFilters(TEST_DESCRIPTIONS, 6), TEST_DESCRIPTIONS)
    }

    fun testShardingIsStable_sameNumberOfTestsAndShards() {
        assertShardingIsStable(createFilters(TEST_DESCRIPTIONS, 6), TEST_DESCRIPTIONS)
    }

    fun testShardingIsCompleteAndPartitioned_moreShardsThanTests() {
        assertShardingIsCompleteAndPartitioned(createFilters(TEST_DESCRIPTIONS, 7), TEST_DESCRIPTIONS)
    }

    fun testShardingIsStable_moreShardsThanTests() {
        assertShardingIsStable(createFilters(TEST_DESCRIPTIONS, 7), TEST_DESCRIPTIONS)
    }

    fun testShardingIsCompleteAndPartitioned_duplicateDescriptions() {
        val descriptions: MutableList<Description?> = ArrayList<Description?>()
        descriptions.addAll(createGenericTestCaseDescriptions(6))
        descriptions.addAll(createGenericTestCaseDescriptions(6))
        assertShardingIsCompleteAndPartitioned(createFilters(descriptions, 7), descriptions)
    }

    fun testShardingIsStable_duplicateDescriptions() {
        val descriptions: MutableList<Description?> = ArrayList<Description?>()
        descriptions.addAll(createGenericTestCaseDescriptions(6))
        descriptions.addAll(createGenericTestCaseDescriptions(6))
        assertShardingIsStable(createFilters(descriptions, 7), descriptions)
    }

    fun testShouldRunTestSuite() {
        val testSuiteDescription: Description = createTestSuiteDescription()
        val filter = createShardingFilterFactory()!!.createFilter(TEST_DESCRIPTIONS, 0, 1)
        Truth.assertThat(filter.shouldRun(testSuiteDescription)).isTrue()
    }

    companion object {
        val TEST_DESCRIPTIONS: MutableList<Description?> = createGenericTestCaseDescriptions(6)

        /**
         * Creates a list of generic test case descriptions.
         * 
         * @param numDescriptions the number of generic test descriptions to add to the list.
         */
        fun createGenericTestCaseDescriptions(numDescriptions: Int): MutableList<Description?> {
            val descriptions: MutableList<Description?> = ArrayList<Description?>()
            for (i in 0..<numDescriptions) {
                descriptions.add(Description.createTestDescription(Test::class.java, "test" + i))
            }
            return descriptions
        }

        protected fun createFilters(
            descriptions: MutableList<Description?>?, numShards: Int,
            factory: ShardingFilterFactory = createShardingFilterFactory()
        ): MutableList<Filter> {
            val filters: MutableList<Filter> = ArrayList<Filter>()
            for (shardIndex in 0..<numShards) {
                filters.add(factory.createFilter(descriptions, shardIndex, numShards))
            }
            return filters
        }

        protected fun assertThrowsExceptionForUnknownDescription(filter: Filter) {
            Assert.assertThrows<IllegalArgumentException?>(
                IllegalArgumentException::class.java,
                ThrowingRunnable { filter.shouldRun(Description.createTestDescription(Any::class.java, "unknown")) })
        }

        /**
         * Simulates test sharding with the given filters and test descriptions.
         * 
         * @param filters a list of filters, one per test shard
         * @param descriptions a list of test descriptions
         * @return a mapping from each filter to the descriptions of the tests that would be run
         * by the shard associated with that filter.
         */
        protected fun simulateTestRun(
            filters: MutableList<Filter>,
            descriptions: MutableList<Description?>
        ): MutableMap<Filter?, MutableList<Description?>?> {
            val descriptionsRun: MutableMap<Filter?, MutableList<Description?>?> =
                HashMap<Filter?, MutableList<Description?>?>()
            for (filter in filters) {
                for (description in descriptions) {
                    if (filter.shouldRun(description)) {
                        addDescriptionForFilterToMap(descriptionsRun, filter, description)
                    }
                }
            }
            return descriptionsRun
        }

        /**
         * Simulates test sharding with the given filters and test descriptions, for a
         * set of test descriptions that is in a different order in every test shard.
         * 
         * @param filters a list of filters, one per test shard
         * @param descriptions a list of test descriptions
         * @return a mapping from each filter to the descriptions of the tests that would be run
         * by the shard associated with that filter.
         */
        protected fun simulateSelfRandomizingTestRun(
            filters: MutableList<Filter>, descriptions: MutableList<Description?>
        ): MutableMap<Filter?, MutableList<Description?>?> {
            if (descriptions.isEmpty()) {
                return HashMap<Filter?, MutableList<Description?>?>()
            }
            val mutatingDescriptions: Deque<Description?> = LinkedList<Description?>(descriptions)
            val descriptionsRun: MutableMap<Filter?, MutableList<Description?>?> =
                HashMap<Filter?, MutableList<Description?>?>()

            for (filter in filters) {
                // rotate the queue so that each filter gets the descriptions in a different order
                mutatingDescriptions.addLast(mutatingDescriptions.pollFirst())
                for (description in descriptions) {
                    if (filter.shouldRun(description)) {
                        addDescriptionForFilterToMap(descriptionsRun, filter, description)
                    }
                }
            }
            return descriptionsRun
        }

        /**
         * Creates a test suite description (a Description that returns true
         * when [Description.isSuite] is called.)
         */
        protected fun createTestSuiteDescription(): Description {
            val testSuiteDescription = Description.createSuiteDescription("testSuite")
            testSuiteDescription.addChild(Description.createSuiteDescription("testCase"))
            return testSuiteDescription
        }

        /**
         * Tests that the sharding is complete (each test is run at least once) and
         * partitioned (each test is run at most once) -- in other words, that
         * each test is run exactly once.  This is a requirement of all test
         * sharding functions.
         */
        protected fun assertShardingIsCompleteAndPartitioned(
            filters: MutableList<Filter>,
            descriptions: MutableList<Description?>
        ) {
            var run: MutableMap<Filter?, MutableList<Description?>?> = simulateTestRun(filters, descriptions)
            assertThatCollectionContainsExactlyElementsInList(getAllValuesInMap(run), descriptions)

            run = simulateSelfRandomizingTestRun(filters, descriptions)
            assertThatCollectionContainsExactlyElementsInList(getAllValuesInMap(run), descriptions)
        }

        /**
         * Tests that sharding is stable for the given filters, regardless of the
         * ordering of the descriptions.  This is useful for verifying that sharding
         * works with self-randomizing test suites, and a requirement of all test
         * sharding functions.
         */
        protected fun assertShardingIsStable(
            filters: MutableList<Filter>, descriptions: MutableList<Description?>
        ) {
            val run1: MutableMap<Filter?, MutableList<Description?>?> = simulateTestRun(filters, descriptions)
            val run2: MutableMap<Filter?, MutableList<Description?>?> = simulateTestRun(filters, descriptions)
            Truth.assertThat(run2).isEqualTo(run1)

            val randomizedRun1: MutableMap<Filter?, MutableList<Description?>?> =
                simulateSelfRandomizingTestRun(filters, descriptions)
            val randomizedRun2: MutableMap<Filter?, MutableList<Description?>?> =
                simulateSelfRandomizingTestRun(filters, descriptions)
            Truth.assertThat(randomizedRun2).isEqualTo(randomizedRun1)
        }

        private fun addDescriptionForFilterToMap(
            descriptionsRun: MutableMap<Filter?, MutableList<Description?>?>, filter: Filter?, description: Description?
        ) {
            var descriptions = descriptionsRun.get(filter)
            if (descriptions == null) {
                descriptions = ArrayList<Description?>()
                descriptionsRun.put(filter, descriptions)
            }
            descriptions.add(description)
        }

        private fun getAllValuesInMap(map: MutableMap<Filter?, MutableList<Description?>?>): MutableCollection<Description?> {
            val allDescriptions: MutableCollection<Description?> = ArrayList<Description?>()
            for (descriptions in map.values) {
                allDescriptions.addAll(descriptions!!)
            }
            return allDescriptions
        }

        /**
         * Returns whether the Collection and the List contain exactly the same elements with the same
         * frequency, ignoring the ordering.
         */
        private fun assertThatCollectionContainsExactlyElementsInList(
            actual: MutableCollection<Description?>, expectedDescriptions: MutableList<Description?>
        ) {
            val basicAssertionMessage = ("Elements of collection " + actual + " are not the same as the "
                    + "elements of expected list " + expectedDescriptions + ". ")
            if (actual.size != expectedDescriptions.size) {
                throw AssertionError(basicAssertionMessage + "The number of elements is different.")
            }

            val actualDescriptions: MutableList<Description> = ArrayList<Description>(actual)
            // Keeps track of already reviewed descriptions, so they won't be checked again when next
            // encountered.
            // Note: this algorithm has O(n^2) time complexity and will be slow for large inputs.
            val reviewedDescriptions: MutableSet<Description?> = HashSet<Description?>()
            for (i in actual.indices) {
                val currDescription = actualDescriptions.get(i)
                // If already reviewed, skip.
                if (reviewedDescriptions.contains(currDescription)) {
                    continue
                }
                var actualFreq = 0
                var expectedFreq = 0
                // Count the frequency of the current description in both lists.
                for (j in actual.indices) {
                    if (currDescription == actualDescriptions.get(j)) {
                        actualFreq++
                    }
                    if (currDescription == expectedDescriptions.get(j)) {
                        expectedFreq++
                    }
                }
                if (actualFreq < expectedFreq) {
                    throw AssertionError(
                        (basicAssertionMessage + "There are " + (expectedFreq - actualFreq)
                                + " missing occurrences of " + currDescription + ".")
                    )
                } else if (actualFreq > expectedFreq) {
                    throw AssertionError(
                        (basicAssertionMessage + "There are " + (actualFreq - expectedFreq)
                                + " unexpected occurrences of " + currDescription + ".")
                    )
                }
                reviewedDescriptions.add(currDescription)
            }
        }
    }
}
