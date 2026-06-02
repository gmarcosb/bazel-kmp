// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar.javac.statistics

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.auto.value.AutoValue
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.sun.tools.javac.util.Context
import java.time.Duration
import java.util.*
import java.util.function.Function

/**
 * A class representing statistics for an invocation of [ ][com.google.devtools.build.buildjar.javac.BlazeJavacMain.compile].
 * 
 * 
 * This will generally include performance statistics (how long the process ran, how many times
 * did an annotation processor run, how many Error Prone checks were checked, etc.).
 */
@AutoValue
abstract class BlazeJavacStatistics {
    abstract fun auxiliaryData(): ImmutableMap<AuxiliaryDataSource?, ByteArray?>?

    abstract fun totalErrorProneTime(): Optional<Duration?>?

    abstract fun errorProneInitializationTime(): Optional<Duration?>?

    abstract fun bugpatternTiming(): ImmutableMap<String?, Duration?>?

    abstract fun totalProcessorTime(): Optional<Duration?>?

    abstract fun processorTiming(): ImmutableMap<String?, Duration?>?

    abstract fun processors(): ImmutableSet<String?>?

    abstract fun transitiveClasspathLength(): Int

    abstract fun reducedClasspathLength(): Int

    abstract fun minClasspathLength(): Int

    abstract fun transitiveClasspathFallback(): Boolean

    // TODO(glorioso): We really need to think out more about what data to collect/store here.
    /**
     * Known sources of additional data to add to the statistics. Each data source can put a single
     * byte[] of serialized proto data into this statistics object with [ ][Builder.addAuxiliaryData]
     */
    enum class AuxiliaryDataSource {
        DAGGER,
    }

    abstract fun toBuilder(): Builder?

    /**
     * Builder of [BlazeJavacStatistics] instances.
     * 
     * 
     * Normally available through a [Context] via: `context.getKey({ BlazeJavacStatistics.Builder}.class` after [BlazeJavacStatistics.preRegister]
     * has been called.
     */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun totalErrorProneTime(totalErrorProneTime: Duration?): Builder?

        abstract fun errorProneInitializationTime(errorProneInitializationTime: Duration?): Builder?

        abstract fun totalProcessorTime(totalProcessorTime: Duration?): Builder?

        abstract fun bugpatternTimingBuilder(): ImmutableMap.Builder<String?, Duration?>?

        abstract fun processorTimingBuilder(): ImmutableMap.Builder<String?, Duration?>?

        abstract fun auxiliaryDataBuilder(): ImmutableMap.Builder<AuxiliaryDataSource?, ByteArray?>?

        abstract fun processorsBuilder(): ImmutableSet.Builder<String?>?

        abstract fun transitiveClasspathLength(length: Int): Builder?

        abstract fun reducedClasspathLength(length: Int): Builder?

        abstract fun minClasspathLength(length: Int): Builder?

        abstract fun transitiveClasspathFallback(fallback: Boolean): Builder?

        @CanIgnoreReturnValue
        fun addBugpatternTiming(key: String?, value: Duration?): Builder {
            bugpatternTimingBuilder()!!.put(key, value)
            return this
        }

        @CanIgnoreReturnValue
        fun addProcessorTiming(key: String?, value: Duration?): Builder {
            processorTimingBuilder()!!.put(key, value)
            return this
        }

        abstract fun build(): BlazeJavacStatistics?

        /**
         * Add an auxiliary attachment of data to this statistics object. The data should be a proto
         * serialization of a google.protobuf.Any protobuf.
         * 
         * 
         * Since this method is called across the boundaries of an annotation processorpath and the
         * runtime classpath of the compiler, we want to reduce the number of classes mentioned, hence
         * the byte[] data type. If we find a way to make this more safe, we would prefer to use a
         * protobuf ByteString instead for its immutability.
         */
        @CanIgnoreReturnValue
        fun addAuxiliaryData(key: AuxiliaryDataSource?, serializedData: ByteArray): Builder {
            auxiliaryDataBuilder()!!.put(key, serializedData.clone())
            return this
        }

        @CanIgnoreReturnValue
        fun addProcessor(processor: String?): Builder {
            processorsBuilder()!!.add(processor)
            return this
        }
    }

    companion object {
        // Weak refs to contexts we've init'ed into
        private val contextsInitialized: Cache<Context?, Builder?> =
            Caffeine.newBuilder().weakKeys().build<Context?, Builder?>()

        fun preRegister(context: Context?) {
            val unused: Builder? =
                contextsInitialized.get(
                    context,
                    Function { c: Context? ->
                        val instance: Builder = newBuilder()
                        c.put<Builder?>(Builder::class.java, instance)
                        instance
                    })
        }

        fun empty(): BlazeJavacStatistics? {
            return newBuilder().build()
        }

        private fun newBuilder(): Builder {
            return Builder()
                .transitiveClasspathLength(0)
                .reducedClasspathLength(0)
                .minClasspathLength(0)
                .transitiveClasspathFallback(false)!!
        }
    }
}
