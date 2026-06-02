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
package com.google.devtools.build.lib.profiler

import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.profiler.MemoryProfiler
import com.google.devtools.build.lib.profiler.MemoryProfiler.MemoryProfileStableHeapParameters
import com.google.devtools.build.lib.util.HeapOffsetHelper
import java.io.PrintStream

/**
 * Blaze memory profiler.
 * 
 * 
 * At each call to `profile` performs garbage collection and stores heap and non-heap
 * memory usage in an external file.
 * 
 * 
 * *Heap memory* is the runtime data area from which memory for all class instances and
 * arrays is allocated. *Non-heap memory* includes the method area and memory required for
 * the internal processing or optimization of the JVM. It stores per-class structures such as a
 * runtime constant pool, field and method data, and the code for methods and constructors. The Java
 * Native Interface (JNI) code or the native library of an application and the JVM implementation
 * allocate memory from the *native heap*.
 * 
 * 
 * The script in /devtools/blaze/scripts/blaze-memchart.sh can be used for post processing.
 */
class MemoryProfiler {
    private var memoryProfile: PrintStream? = null
    private var currentPhase: com.google.devtools.build.lib.profiler.ProfilePhase? = null
    private var heapUsedMemoryAtFinish: Long = 0
    private var memoryProfileStableHeapParameters: MemoryProfileStableHeapParameters? = null
    private var internalJvmObjectPattern: java.util.regex.Pattern? = null

    @kotlin.jvm.Synchronized
    fun setStableMemoryParameters(
        memoryProfileStableHeapParameters: MemoryProfileStableHeapParameters?,
        internalJvmObjectPattern: java.util.regex.Pattern?
    ) {
        this.memoryProfileStableHeapParameters = memoryProfileStableHeapParameters
        this.internalJvmObjectPattern = internalJvmObjectPattern
    }

    @kotlin.jvm.Synchronized
    fun start(out: java.io.OutputStream?) {
        this.memoryProfile = if (out == null) null else PrintStream(out)
        this.currentPhase = com.google.devtools.build.lib.profiler.ProfilePhase.INIT
        heapUsedMemoryAtFinish = 0
    }

    @kotlin.jvm.Synchronized
    fun stop() {
        if (memoryProfile != null) {
            memoryProfile.close()
            memoryProfile = null
        }
        heapUsedMemoryAtFinish = 0
    }

    @kotlin.jvm.Synchronized
    fun getHeapUsedMemoryAtFinish(): Long {
        return heapUsedMemoryAtFinish
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    fun markPhase(nextPhase: com.google.devtools.build.lib.profiler.ProfilePhase) {
        if (memoryProfile != null) {
            val bean: java.lang.management.MemoryMXBean = java.lang.management.ManagementFactory.getMemoryMXBean()
            val memoryUsages =
                prepareBeanAndGetLocalMinUsage(
                    nextPhase,
                    bean,
                    com.google.devtools.build.lib.profiler.MemoryProfiler.Sleeper { duration: java.time.Duration? ->
                        java.lang.Thread.sleep(duration.toMillis())
                    })
            val name: String? = currentPhase.description
            var memoryUsage: java.lang.management.MemoryUsage = memoryUsages.heap
            var usedMemory: Long = memoryUsage.getUsed()
            // TODO: b/406807983 - Remove the subtraction of FillerArray once we figure out an alternative
            if (nextPhase == com.google.devtools.build.lib.profiler.ProfilePhase.FINISH) {
                usedMemory -=
                    HeapOffsetHelper.getSizeOfFillerArrayOnHeap(
                        internalJvmObjectPattern, BugReporter.defaultInstance()
                    )
                heapUsedMemoryAtFinish = usedMemory
            }
            memoryProfile.println(name + ":heap:init:" + memoryUsage.getInit())
            memoryProfile.println(name + ":heap:used:" + usedMemory)
            memoryProfile.println(name + ":heap:commited:" + memoryUsage.getCommitted())
            memoryProfile.println(name + ":heap:max:" + memoryUsage.getMax())

            memoryUsage = memoryUsages.nonHeap
            memoryProfile.println(name + ":non-heap:init:" + memoryUsage.getInit())
            memoryProfile.println(name + ":non-heap:used:" + memoryUsage.getUsed())
            memoryProfile.println(name + ":non-heap:commited:" + memoryUsage.getCommitted())
            memoryProfile.println(name + ":non-heap:max:" + memoryUsage.getMax())
            currentPhase = nextPhase
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    fun prepareBeanAndGetLocalMinUsage(
        nextPhase: com.google.devtools.build.lib.profiler.ProfilePhase?,
        bean: java.lang.management.MemoryMXBean,
        sleeper: Sleeper
    ): HeapAndNonHeap {
        bean.gc()
        var minHeapUsed: java.lang.management.MemoryUsage = bean.getHeapMemoryUsage()
        var minNonHeapUsed: java.lang.management.MemoryUsage = bean.getNonHeapMemoryUsage()

        if (nextPhase == com.google.devtools.build.lib.profiler.ProfilePhase.FINISH && memoryProfileStableHeapParameters != null) {
            for (j in memoryProfileStableHeapParameters.gcSpecs.indices) {
                val spec: com.google.devtools.build.lib.util.Pair<Int?, java.time.Duration?> =
                    memoryProfileStableHeapParameters.gcSpecs.get(j)

                val numTimesToDoGc: Int = spec.first
                val timeToSleepBetweenGcs: java.time.Duration? = spec.second

                for (i in 0..<numTimesToDoGc) {
                    // We want to skip the first cycle for the first spec, since we ran a
                    // GC at the top of this function, but all the rest should get their
                    // proper runs
                    if (j == 0 && i == 0) {
                        continue
                    }

                    sleeper.sleep(timeToSleepBetweenGcs)
                    bean.gc()
                    val currentHeapUsed: java.lang.management.MemoryUsage = bean.getHeapMemoryUsage()
                    if (currentHeapUsed.getUsed() < minHeapUsed.getUsed()) {
                        minHeapUsed = currentHeapUsed
                        minNonHeapUsed = bean.getNonHeapMemoryUsage()
                    }
                }
            }
        }
        return HeapAndNonHeap.Companion.create(minHeapUsed, minNonHeapUsed)
    }

    /**
     * Parameters that control how `MemoryProfiler` tries to get a stable heap at the end of the
     * build.
     */
    class MemoryProfileStableHeapParameters private constructor(gcSpecs: java.util.ArrayList<com.google.devtools.build.lib.util.Pair<Int?, java.time.Duration?>>) {
        private val gcSpecs: java.util.ArrayList<com.google.devtools.build.lib.util.Pair<Int?, java.time.Duration?>>

        init {
            this.gcSpecs = gcSpecs
        }

        /** Converter for `MemoryProfileStableHeapParameters` option.  */
        class Converter

            : com.google.devtools.common.options.Converter.Contextless<MemoryProfileStableHeapParameters?>() {
            @Throws(com.google.devtools.common.options.OptionsParsingException::class)
            override fun convert(input: String): MemoryProfileStableHeapParameters {
                val values: MutableList<String?> =
                    com.google.devtools.build.lib.profiler.MemoryProfiler.MemoryProfileStableHeapParameters.Converter.Companion.SPLITTER.splitToList(
                        input
                    )

                if (values.size() % 2 != 0) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        "Expected even number of comma-separated integer values"
                    )
                }

                val gcSpecs: java.util.ArrayList<com.google.devtools.build.lib.util.Pair<Int?, java.time.Duration?>> =
                    java.util.ArrayList<com.google.devtools.build.lib.util.Pair<Int?, java.time.Duration?>>(values.size() / 2)

                try {
                    var i = 0
                    while (i < values.size()) {
                        val numTimesToDoGc: Int = java.lang.Integer.parseInt(values.get(i))
                        val numSecondsToSleepBetweenGcs: Int = java.lang.Integer.parseInt(values.get(i + 1))

                        if (numTimesToDoGc <= 0) {
                            throw com.google.devtools.common.options.OptionsParsingException("Number of times to GC must be positive")
                        }
                        if (numSecondsToSleepBetweenGcs < 0) {
                            throw com.google.devtools.common.options.OptionsParsingException(
                                "Number of seconds to sleep between GC's must be non-negative"
                            )
                        }
                        gcSpecs.add(
                            com.google.devtools.build.lib.util.Pair.of<Int?, java.time.Duration?>(
                                numTimesToDoGc,
                                java.time.Duration.ofSeconds(numSecondsToSleepBetweenGcs.toLong())
                            )
                        )
                        i += 2
                    }

                    return MemoryProfileStableHeapParameters(gcSpecs)
                } catch (nfe: java.lang.NumberFormatException) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        "Expected even number of comma-separated integer values, could not parse integer in"
                                + " list",
                        nfe
                    )
                } catch (nfe: java.util.NoSuchElementException) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        "Expected even number of comma-separated integer values, could not parse integer in"
                                + " list",
                        nfe
                    )
                }
            }

            override fun getTypeDescription(): String {
                return "integers, separated by a comma expected in pairs"
            }

            companion object {
                private val SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(',')
            }
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("gcSpecs", gcSpecs).toString()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal interface Sleeper {
        @Throws(java.lang.InterruptedException::class)
        fun sleep(duration: java.time.Duration?)
    }

    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.JvmRecord
    internal data class HeapAndNonHeap(
        heap: java.lang.management.MemoryUsage,
        nonHeap: java.lang.management.MemoryUsage
    ) {
        val heap: java.lang.management.MemoryUsage
        val nonHeap: java.lang.management.MemoryUsage

        init {
            this.nonHeap = nonHeap
            this.heap = heap
            java.util.Objects.requireNonNull<java.lang.management.MemoryUsage?>(heap, "heap")
            java.util.Objects.requireNonNull<java.lang.management.MemoryUsage?>(nonHeap, "nonHeap")
        }

        companion object {
            fun create(
                heap: java.lang.management.MemoryUsage,
                nonHeap: java.lang.management.MemoryUsage
            ): HeapAndNonHeap {
                return HeapAndNonHeap(heap, nonHeap)
            }
        }
    }

    companion object {
        private val INSTANCE = MemoryProfiler()

        @kotlin.jvm.JvmStatic
        fun instance(): MemoryProfiler {
            return INSTANCE
        }
    }
}
