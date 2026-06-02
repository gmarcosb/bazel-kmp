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
package com.google.devtools.build.lib.skyframe.serialization

/**
 * Collects serialization profiling data.
 * 
 * 
 * This class is thread-safe.
 */
class ProfileCollector {
    private val records: ConcurrentHashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, Counts?> =
        ConcurrentHashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, Counts?>()

    /**
     * Records a sample.
     * 
     * 
     * For ease of implementation, samples are recorded here as transitive bytes. The underlying
     * proto defines samples as self-bytes so there is a cleanup step that converts the transitive
     * byte count to self-bytes by subtracting up the stack.
     * 
     * @param locationStack a path of descriptions of the root object being serialized down to the
     * current object being serialized
     * @param byteCount the transitive bytes serialized at the given object
     */
    fun recordSample(locationStack: MutableList<ProfilerLocationProvider?>, byteCount: Int) {
        val counts = getCounts(locationStack)
        counts.count.getAndIncrement()
        counts.totalBytes.getAndAdd(byteCount)

        // Subtracts bytes from the ancestor to avoid double counting.
        if (locationStack.size() > 1) {
            val prefix: MutableList<ProfilerLocationProvider?> = locationStack.subList(0, locationStack.size() - 1)
            getCounts(prefix).totalBytes.getAndAdd(-byteCount)
        }
    }

    /**
     * Records a batch of samples.
     * 
     * 
     * This is used to merge samples from a [ProfileRecorder] after its novelty check
     * completes.
     * 
     * @param samples a map from location stack to the number of samples and transitive bytes recorded
     * at that location
     */
    fun recordSamples(samples: MutableMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, Counts?>) {
        samples.forEach(
            java.util.function.BiConsumer { stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, counts: Counts? ->
                val count: Int = counts!!.count.get()
                val byteCount: Int = counts.totalBytes.get()
                val target = getCounts(stack)
                target.count.getAndAdd(count)
                target.totalBytes.getAndAdd(byteCount)

                // Subtracts bytes from the ancestor to avoid double counting.
                if (stack.size() > 1) {
                    val prefix: com.google.common.collect.ImmutableList<ProfilerLocationProvider?> =
                        stack.subList(0, stack.size() - 1)
                    getCounts(prefix).totalBytes.getAndAdd(-byteCount)
                }
            })
    }

    /** Creates the [Profile] from the accumulated samples.  */
    fun toProto(): Profile {
        val profileBuilder = ProtoBuilder()
        records.forEach(
            java.util.function.BiConsumer { stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, counts: Counts? ->
                val count: Int = counts!!.count.get()
                val byteCount: Int = counts.totalBytes.get()
                if (count == 0 && byteCount == 0) {
                    return@forEach
                }
                val sample: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    Sample.newBuilder().addValue(count).addValue(byteCount)
                for (provider in com.google.common.collect.Lists.reverse<ProfilerLocationProvider>(stack)) {
                    sample.addLocationId(profileBuilder.getOrAddLocation(provider.getLocationText()))
                }
                profileBuilder.addSample(sample)
            })
        return profileBuilder.build()
    }

    /** Stores the profiling counts associated with `stack`.  */
    internal class Counts(
        stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?,
        count: AtomicInteger?,
        totalBytes: AtomicInteger?
    ) {
        constructor(stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?) : this(
            stack,
            AtomicInteger(),
            AtomicInteger()
        )

        val stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?
        val count: AtomicInteger?
        val totalBytes: AtomicInteger?

        init {
            this.stack = stack
            this.count = count
            this.totalBytes = totalBytes
        }
    }

    /**
     * Obtains a deduplicated instance of the `stack`.
     * 
     * 
     * In practice, many parallel instances of [ProfileRecorder] will be in flight
     * simultaneously and each retains stack instances. This allows the memory for those stack
     * instances to be shared.
     */
    fun getCanonicalStack(stack: MutableList<ProfilerLocationProvider?>): com.google.common.collect.ImmutableList<ProfilerLocationProvider?>? {
        return getCounts(stack).stack
    }

    private fun getCounts(locationStack: MutableList<ProfilerLocationProvider?>): Counts {
        val counts: Counts? = records.get(locationStack)
        if (counts != null) {
            return counts
        }
        val stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?> =
            com.google.common.collect.ImmutableList.copyOf<ProfilerLocationProvider?>(locationStack)
        // putIfAbsent has less contention than computeIfAbsent because the latter causes the allocation
        // of Counts to be inside the critical section.
        val newCounts = Counts(stack)
        val previousCounts: Counts? = records.putIfAbsent(stack, newCounts)
        if (previousCounts != null) {
            return previousCounts
        }
        return newCounts
    }

    private class ProtoBuilder {
        private val stringTableBuilder: HashMap<String?, Int?> = HashMap<String?, Int?>()
        private val locationTableBuilder: HashMap<String?, Int?> = HashMap<String?, Int?>()
        private val profile: Profile.Builder = Profile.newBuilder()

        init {
            // Puts the empty string in the 0 position as required by the schema.
            val unusedEmptyId = getOrAddString("")
            val samplesId = getOrAddString(SAMPLES)
            val countId = getOrAddString(COUNT)
            val storageId = getOrAddString(STORAGE)
            val bytesId = getOrAddString(BYTES)

            // Prepopulates the schema fields. Each data point has a sample count with units "count" and a
            // storage size with units "bytes".
            profile
                .addSampleType(ValueType.newBuilder().setType(samplesId).setUnit(countId))
                .addSampleType(ValueType.newBuilder().setType(storageId).setUnit(bytesId))
        }

        fun getOrAddString(text: String?): Int {
            val existingId: Int? = stringTableBuilder.get(text)
            if (existingId != null) {
                return existingId
            }
            val id: Int = stringTableBuilder.size()
            stringTableBuilder.put(text, id)
            profile.addStringTable(text)
            return id
        }

        fun getOrAddLocation(name: String?): Int {
            val existingId: Int? = locationTableBuilder.get(name)
            if (existingId != null) {
                return existingId
            }

            val stringIndex = getOrAddString(name)
            val locationId: Int = locationTableBuilder.size() + 1 // 0 is reserved
            locationTableBuilder.put(name, locationId)

            // Function and Location are 1-1 here so the IDs are the same.
            profile
                .addFunction(Function.newBuilder().setId(locationId).setName(stringIndex))
                .addLocation(
                    Location.newBuilder()
                        .setId(locationId)
                        .addLine(Line.newBuilder().setFunctionId(locationId))
                )

            return locationId
        }

        fun addSample(sample: Sample.Builder?) {
            profile.addSample(sample)
        }

        fun build(): Profile {
            return profile.build()
        }
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting
        const val SAMPLES: String = "samples"

        @com.google.common.annotations.VisibleForTesting
        const val COUNT: String = "count"

        @com.google.common.annotations.VisibleForTesting
        const val STORAGE: String = "storage"

        @com.google.common.annotations.VisibleForTesting
        const val BYTES: String = "bytes"
    }
}
