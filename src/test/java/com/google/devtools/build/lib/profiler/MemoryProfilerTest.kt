// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler

/** Tests for [MemoryProfiler].  */
@RunWith(JUnit4::class)
class MemoryProfilerTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun profilerDoesOneGcAndNoSleepNormally() {
        val profiler: MemoryProfiler = MemoryProfiler.instance()
        profiler.setStableMemoryParameters(
            Converter().convert("1,10"), NO_OP_PATTERN
        )
        profiler.start(com.google.common.io.ByteStreams.nullOutputStream())
        val bean: java.lang.management.MemoryMXBean =
            Mockito.mock<java.lang.management.MemoryMXBean>(java.lang.management.MemoryMXBean::class.java)
        val heapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(0, 0, 0, 0)
        val nonHeapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(5, 5, 5, 5)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getHeapMemoryUsage()).thenReturn(heapUsage)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getNonHeapMemoryUsage()).thenReturn(nonHeapUsage)
        val sleeper = RecordingSleeper()
        val result: MemoryProfiler.HeapAndNonHeap =
            profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.ANALYZE, bean, sleeper)
        assertThat(result.heap()).isSameInstanceAs(heapUsage)
        assertThat(result.nonHeap()).isSameInstanceAs(nonHeapUsage)
        Truth.assertThat(sleeper.sleeps).isEmpty()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1)).gc()
        profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.FINISH, bean, sleeper)
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(2)).gc()
        Truth.assertThat(sleeper.sleeps).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun profilerDoesOneGcAndNoSleepExceptInFinish() {
        val profiler: MemoryProfiler = MemoryProfiler.instance()
        profiler.setStableMemoryParameters(
            Converter().convert("3,10"), NO_OP_PATTERN
        )
        profiler.start(com.google.common.io.ByteStreams.nullOutputStream())
        val bean: java.lang.management.MemoryMXBean =
            Mockito.mock<java.lang.management.MemoryMXBean>(java.lang.management.MemoryMXBean::class.java)
        val emptyHeap: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(0, 0, 0, 0)
        val emptyNonHeap: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(0, 0, 0, 0)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getHeapMemoryUsage()).thenReturn(emptyHeap)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getNonHeapMemoryUsage()).thenReturn(emptyNonHeap)
        val sleeper = RecordingSleeper()
        var result: MemoryProfiler.HeapAndNonHeap =
            profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.ANALYZE, bean, sleeper)
        assertThat(result.heap()).isSameInstanceAs(emptyHeap)
        assertThat(result.nonHeap()).isSameInstanceAs(emptyNonHeap)
        Truth.assertThat(sleeper.sleeps).isEmpty()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1)).gc()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1)).getHeapMemoryUsage()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1)).getNonHeapMemoryUsage()
        val heapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(0, 1, 2, 2)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getHeapMemoryUsage())
            .thenReturn(
                java.lang.management.MemoryUsage(5, 5, 5, 5),
                heapUsage,
                java.lang.management.MemoryUsage(10, 1, 10, 10)
            )
        val nonHeapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(2, 2, 2, 2)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getNonHeapMemoryUsage())
            .thenReturn(
                java.lang.management.MemoryUsage(1, 1, 1, 1),
                nonHeapUsage,
                java.lang.management.MemoryUsage(2, 2, 2, 2)
            )
        result = profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.FINISH, bean, sleeper)
        assertThat(result.heap()).isSameInstanceAs(heapUsage)
        assertThat(result.nonHeap()).isSameInstanceAs(nonHeapUsage)
        Truth.assertThat(sleeper.sleeps)
            .containsExactly(java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(10))
            .inOrder()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(4)).gc()
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(4)).getHeapMemoryUsage()
        // Avoid call when heap usage is not minimal.
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(3)).getNonHeapMemoryUsage()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun profilerHasMultiplePairs() {
        val profiler: MemoryProfiler = MemoryProfiler.instance()
        profiler.setStableMemoryParameters(
            Converter().convert("2,1,3,4,5,6"), NO_OP_PATTERN
        )
        profiler.start(com.google.common.io.ByteStreams.nullOutputStream())
        val bean: java.lang.management.MemoryMXBean =
            Mockito.mock<java.lang.management.MemoryMXBean>(java.lang.management.MemoryMXBean::class.java)

        val heapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(0, 0, 0, 0)
        val nonHeapUsage: java.lang.management.MemoryUsage = java.lang.management.MemoryUsage(5, 5, 5, 5)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getHeapMemoryUsage()).thenReturn(heapUsage)
        Mockito.`when`<java.lang.management.MemoryUsage?>(bean.getNonHeapMemoryUsage()).thenReturn(nonHeapUsage)

        val sleeper = RecordingSleeper()
        val result: MemoryProfiler.HeapAndNonHeap =
            profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.ANALYZE, bean, sleeper)
        assertThat(result.heap()).isSameInstanceAs(heapUsage)
        assertThat(result.nonHeap()).isSameInstanceAs(nonHeapUsage)
        Truth.assertThat(sleeper.sleeps).isEmpty()

        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1)).gc()
        profiler.prepareBeanAndGetLocalMinUsage(ProfilePhase.FINISH, bean, sleeper)
        // 1 for call to ANALYZE + spec'd runs
        Mockito.verify<java.lang.management.MemoryMXBean?>(bean, Mockito.times(1 + 2 + 3 + 5)).gc()

        Truth.assertThat(sleeper.sleeps)
            .containsExactly(
                java.time.Duration.ofSeconds(1),  // 2 * 1s, but we skip the first sleep
                java.time.Duration.ofSeconds(4),  // 3 * 4s
                java.time.Duration.ofSeconds(4),
                java.time.Duration.ofSeconds(4),
                java.time.Duration.ofSeconds(6),  // 5 * 6s
                java.time.Duration.ofSeconds(6),
                java.time.Duration.ofSeconds(6),
                java.time.Duration.ofSeconds(6),
                java.time.Duration.ofSeconds(6)
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun profilerHasBadInputOddValues() {
        val profiler: MemoryProfiler = MemoryProfiler.instance()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    profiler.setStableMemoryParameters(
                        Converter().convert("1,10,7"),
                        NO_OP_PATTERN
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected even number of comma-separated integer values")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun profilerHasBadInputNotInts() {
        val profiler: MemoryProfiler = MemoryProfiler.instance()
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    profiler.setStableMemoryParameters(
                        Converter()
                            .convert("1,10,74,22,horse,goat"),
                        NO_OP_PATTERN
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Expected even number of comma-separated integer values, could not parse integer in"
                        + " list"
            )
    }

    private class RecordingSleeper : Sleeper {
        private val sleeps: MutableList<java.time.Duration?> = java.util.ArrayList<java.time.Duration?>()

        public override fun sleep(duration: java.time.Duration?) {
            sleeps.add(duration)
        }
    }

    companion object {
        private val NO_OP_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile("no_match")
    }
}
