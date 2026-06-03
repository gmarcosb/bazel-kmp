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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.BlazeInterners

/** Test for `ReverseDepsUtility`.  */
@RunWith(org.junit.runners.Parameterized::class)
class ReverseDepsUtilityTest(private val numElements: Int) {
    @org.junit.Test
    fun testAddAndRemove() {
        for (numRemovals in 0..numElements) {
            val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
            for (j in 0..<numElements) {
                ReverseDepsUtility.addReverseDep(
                    example,
                    com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(j)
                )
            }
            // Not a big test but at least check that it does not blow up.
            assertThat(ReverseDepsUtility.toString(example)).isNotEmpty()
            assertThat(
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */true
                )
            )
                .hasSize(numElements)
            for (i in 0..<numRemovals) {
                ReverseDepsUtility.removeReverseDep(
                    example,
                    com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(i)
                )
            }
            assertThat(
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */true
                )
            )
                .hasSize(numElements - numRemovals)
            assertThat(example.getReverseDepsDataToConsolidateForReverseDepsUtil()).isNull()
        }
    }

    // Same as testAdditionAndRemoval but we add all the reverse deps in one call.
    @org.junit.Test
    fun testAddAllAndRemove() {
        for (numRemovals in 0..numElements) {
            val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
            for (j in 0..<numElements) {
                ReverseDepsUtility.addReverseDep(
                    example,
                    com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(j)
                )
            }
            assertThat(
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */true
                )
            )
                .hasSize(numElements)
            for (i in 0..<numRemovals) {
                ReverseDepsUtility.removeReverseDep(
                    example,
                    com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(i)
                )
            }
            assertThat(
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */true
                )
            )
                .hasSize(numElements - numRemovals)
            assertThat(example.getReverseDepsDataToConsolidateForReverseDepsUtil()).isNull()
        }
    }

    @org.junit.Test
    fun testDuplicateCheckOnGetReverseDeps() {
        val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
        for (i in 0..<numElements) {
            ReverseDepsUtility.addReverseDep(
                example,
                com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(i)
            )
        }
        // Should only fail when we call getReverseDeps().
        ReverseDepsUtility.addReverseDep(
            example,
            com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(0)
        )
        if (numElements == 0) {
            // Will not throw.
            assertThat(
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */true
                )
            )
                .hasSize(1)
        } else {
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    ReverseDepsUtility.consolidateAndGetReverseDeps(
                        example,  /* checkConsistency= */true
                    )
                })
        }
    }

    @org.junit.Test
    fun duplicateAddNoThrowWithoutCheck() {
        val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
        for (i in 0..<numElements) {
            ReverseDepsUtility.addReverseDep(
                example,
                com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(i)
            )
        }
        ReverseDepsUtility.addReverseDep(
            example,
            com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(0)
        )
        assertThat(
            ReverseDepsUtility.consolidateAndGetReverseDeps(example,  /* checkConsistency= */false)
        )
            .hasSize(numElements + 1)
    }

    @org.junit.Test
    fun doubleAddThenRemove() {
        val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
        val key: SkyKey = com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(0)
        ReverseDepsUtility.addReverseDep(example, key)
        // Should only fail when we call getReverseDeps().
        ReverseDepsUtility.addReverseDep(example, key)
        ReverseDepsUtility.removeReverseDep(example, key)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                ReverseDepsUtility.consolidateAndGetReverseDeps(
                    example,  /* checkConsistency= */
                    true
                )
            })
    }

    @org.junit.Test
    fun doubleAddThenRemoveCheckedOnSize() {
        val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
        val fixedKey: SkyKey = com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(0)
        ReverseDepsUtility.addReverseDep(example, fixedKey)
        val key: SkyKey = com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(1)
        ReverseDepsUtility.addReverseDep(example, key)
        // Should only fail when we reach the limit.
        ReverseDepsUtility.addReverseDep(example, key)
        example.addReverseDepAndCheckIfDone(null)
        assertThat(example.checkIfDoneForDirtyReverseDep(fixedKey))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        assertThat(example.checkIfDoneForDirtyReverseDep(key))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        val e: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { ReverseDepsUtility.removeReverseDep(example, key) })
        Truth.assertThat(e).hasMessageThat().contains("1 duplicate")
    }

    @org.junit.Test
    fun addRemoveAdd() {
        val example: IncrementalInMemoryNodeEntry = IncrementalInMemoryNodeEntry(KEY)
        val fixedKey: SkyKey = com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(0)
        ReverseDepsUtility.addReverseDep(example, fixedKey)
        val key: SkyKey = com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.create(1)
        ReverseDepsUtility.addReverseDep(example, key)
        ReverseDepsUtility.removeReverseDep(example, key)
        ReverseDepsUtility.addReverseDep(example, key)
        assertThat(
            ReverseDepsUtility.consolidateAndGetReverseDeps(example,  /* checkConsistency= */true)
        )
            .containsExactly(fixedKey, key)
    }

    private class Key(arg: Int?) : AbstractSkyKey<Int?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }

        companion object {
            private val interner: com.google.common.collect.Interner<Key> = BlazeInterners.newWeakInterner()

            private fun create(arg: Int?): Key {
                return com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key.Companion.interner.intern(
                    com.google.devtools.build.skyframe.ReverseDepsUtilityTest.Key(
                        arg
                    )
                )
            }
        }
    }

    companion object {
        private val KEY: SkyKey? = GraphTester.Companion.skyKey("KEY")

        @org.junit.runners.Parameterized.Parameters(name = "numElements-{0}")
        fun parameters(): MutableList<Array<Any?>?> {
            val params: MutableList<Array<Any?>?> = java.util.ArrayList<Array<Any?>?>()
            for (i in 0..19) {
                params.add(arrayOf<Any>(i))
            }
            return params
        }
    }
}
