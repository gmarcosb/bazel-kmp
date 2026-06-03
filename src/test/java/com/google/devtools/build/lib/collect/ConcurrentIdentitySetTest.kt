// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(JUnit4::class)
class ConcurrentIdentitySetTest {
    @org.junit.Test
    fun testDedupe() {
        val deduper: ConcurrentIdentitySet = ConcurrentIdentitySet( /* sizeHint= */UNIQUE_ELEMENTS)
        Truth.assertThat(runDedup(deduper::add)).isEqualTo(UNIQUE_ELEMENTS)
    }

    @org.junit.Test
    fun testResize() {
        val deduper: ConcurrentIdentitySet = ConcurrentIdentitySet( /* sizeHint= */0)
        Truth.assertThat(runDedup(deduper::add)).isEqualTo(UNIQUE_ELEMENTS)
    }

    private interface BooleanFunction {
        fun apply(obj: Any?): Boolean
    }

    companion object {
        private const val PARALLELISM = 12
        private const val UNIQUE_ELEMENTS = 10000
        private val TEST_DATA: com.google.common.collect.ImmutableList<Any?> = createTestObjects()

        /** Ensures that [.TEST_DATA] is populated before any test runs.  */
        @BeforeClass
        fun warmup() {
            for (obj in TEST_DATA) {
                Truth.assertThat(obj).isNotNull()
            }
        }

        private fun runDedup(deduper: BooleanFunction): Int {
            val pool: ForkJoinPool = ForkJoinPool(PARALLELISM)
            val nextUnused: AtomicInteger = AtomicInteger(0)
            val uniqueCount: AtomicInteger = AtomicInteger(0)
            for (i in 0..<PARALLELISM) {
                pool.execute(
                    java.lang.Runnable {
                        var next = 0
                        while ((nextUnused.getAndIncrement().also { next = it }) < TEST_DATA.size) {
                            if (deduper.apply(TEST_DATA.get(next))) {
                                uniqueCount.incrementAndGet()
                            }
                        }
                    })
            }
            Truth.assertThat(
                pool.awaitQuiescence(
                    com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            ).isTrue()
            return uniqueCount.get()
        }

        private fun createTestObjects(): com.google.common.collect.ImmutableList<Any?> {
            val data: java.util.ArrayList<Any?> =
                java.util.ArrayList<Any?>((UNIQUE_ELEMENTS * (UNIQUE_ELEMENTS + 1)) / 2)
            for (i in 1..UNIQUE_ELEMENTS) {
                val next = Any()
                for (j in 0..<i) {
                    data.add(next)
                }
            }
            Collections.shuffle(data)
            return com.google.common.collect.ImmutableList.copyOf<Any?>(data)
        }
    }
}
