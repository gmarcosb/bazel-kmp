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

import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector.Counts
import com.google.devtools.build.lib.skyframe.serialization.ProfilerLocationProvider
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.protobuf.CodedOutputStream
import java.util.HashMap

/**
 * Records a profile into a given [ProfileCollector] for a single serialization thread.
 * 
 * 
 * The client should call the [.pushLocation] when entering serialization of an object then
 * [.recordBytesAndPopLocation] when that object's serialization completes. Since
 * serialization is a recursive, this typically means the number of pushes will be greater than the
 * number of pops while serialization is ongoing, but must eventually balance.
 * 
 * 
 * This recorder buffers samples internally until a [WriteStatus] completes. If the write
 * was novel, the samples are merged into the global [ProfileCollector].
 */
class ProfileRecorder(profileCollector: ProfileCollector) : com.google.common.util.concurrent.FutureCallback<Boolean?> {
    private val profileCollector: ProfileCollector
    private val locationStack: java.util.ArrayList<ProfilerLocationProvider?> =
        java.util.ArrayList<ProfilerLocationProvider?>()
    private val bufferedSamples: HashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, Counts> =
        HashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, Counts>()
    private var byteScale = 1.0

    init {
        this.profileCollector = profileCollector
    }

    fun pushLocation(provider: ProfilerLocationProvider?) {
        locationStack.add(provider)
    }

    /** Records the given `byteCount` at the current location.  */
    fun recordBytes(byteCount: Int) {
        val stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>? =
            profileCollector.getCanonicalStack(locationStack)

        val counts: Counts = bufferedSamples.computeIfAbsent(
            stack,
            java.util.function.Function { stack: com.google.common.collect.ImmutableList<ProfilerLocationProvider?>? ->
                Counts(
                    stack
                )
            })
        counts.count.getAndIncrement()
        counts.totalBytes.getAndAdd(byteCount)
    }

    /** Pops the current location from the stack.  */
    fun popLocation() {
        locationStack.remove(locationStack.size() - 1)
    }

    fun recordBytesAndPopLocation(startBytes: Int, codedOut: CodedOutputStream) {
        val bytesWritten: Int = codedOut.getTotalBytesWritten()
        com.google.common.base.Preconditions.checkState(bytesWritten >= startBytes)

        recordBytes(bytesWritten - startBytes)
        popLocation()
    }

    /**
     * Sets a multiplier for all recorded byte counts to account for compression.
     * 
     * 
     * This should be called if compression is detected and before [.registerWriteStatus].
     */
    fun setByteScale(byteScale: Double) {
        this.byteScale = byteScale
    }

    /**
     * Registers a [WriteStatus] to trigger the merge of buffered samples.
     * 
     * 
     * If `status` completes with `true`, the samples are recorded in the collector.
     */
    fun registerWriteStatus(status: WriteStatus) {
        com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
            status,
            this,
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    override fun onSuccess(wasNovel: Boolean) {
        if (!wasNovel) {
            return  // Discards the buffered samples.
        }
        if (byteScale != 1.0) {
            // Applies the scaling factor uniformly to all samples.
            for (counts in bufferedSamples.values()) {
                val scaledBytes: Int = java.lang.Math.round(counts.totalBytes.get() * byteScale).toInt()
                counts.totalBytes.set(scaledBytes)
            }
        }
        profileCollector.recordSamples(bufferedSamples)
    }

    override fun onFailure(t: Throwable) {
        // Discard buffered samples on failure.
    }

    fun getProfileCollector(): ProfileCollector {
        return profileCollector
    }

    fun checkStackEmpty(subjectForContext: Any?) {
        com.google.common.base.Preconditions.checkState(
            locationStack.isEmpty(), "subject=%s, locationStack=%s", subjectForContext, locationStack
        )
    }
}
