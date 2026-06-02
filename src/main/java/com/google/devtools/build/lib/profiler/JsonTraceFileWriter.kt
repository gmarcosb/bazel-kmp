// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import com.google.devtools.build.lib.clock.Clock.now
import com.google.devtools.build.lib.profiler.TaskData
import com.google.devtools.build.lib.profiler.ThreadMetadata
import com.google.devtools.build.lib.profiler.TraceData
import com.google.gson.stream.JsonWriter
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock

/** Writes the profile in Json Trace file format.  */
internal class JsonTraceFileWriter(
    outStream: java.io.OutputStream?,
    profileStartTimeNanos: Long,
    slimProfile: Boolean,
    outputBase: String?,
    buildID: UUID
) : java.lang.Runnable {
    protected val queue: java.util.Queue<TraceData>
    private val lock: ReentrantLock = ReentrantLock()
    private val condition: java.util.concurrent.locks.Condition = lock.newCondition()

    // 1_000_000 is a randomly chosen value that is large enough to ensure that:
    //   1. If the speed of producers is slower than consumer (normal cases), they don't get overhead
    //      on posting new events.
    //   2. Otherwise (e.g. with --noslim_profile and --record_full_profiler_data), it eventually
    //      slowed down the producers to avoid OOM.
    private val availableEventSlots: Semaphore = Semaphore(1000000)
    protected val thread: java.lang.Thread
    protected var savedException: IOException? = null

    private val outStream: java.io.OutputStream?
    private val profileStartTimeNanos: Long
    private val metadataPosted: java.lang.ThreadLocal<Boolean?> =
        java.lang.ThreadLocal.withInitial<Boolean?>(java.util.function.Supplier { java.lang.Boolean.FALSE })
    private val slimProfile: Boolean
    private val buildID: UUID
    private val outputBase: String?

    init {
        this.queue = ConcurrentLinkedQueue<TraceData>()
        this.thread = java.lang.Thread(this, "profile-writer-thread")
        this.outStream = outStream
        this.profileStartTimeNanos = profileStartTimeNanos
        this.slimProfile = slimProfile
        this.buildID = buildID
        this.outputBase = outputBase
    }

    @Throws(IOException::class)
    fun shutdown() {
        // Add poison pill to queue and then wait for writer thread to shut down.
        queue.add(POISON_PILL)
        notifyConsumer( /* force= */true)

        try {
            thread.join()
        } catch (e: java.lang.InterruptedException) {
            thread.interrupt()
            java.lang.Thread.currentThread().interrupt()
        }
        if (savedException != null) {
            throw savedException
        }
    }

    fun start() {
        thread.start()
    }

    fun enqueue(data: TraceData?) {
        // We assign a virtual lane for virtual thread and the metadata for the virtual lane is posted
        // at creation time.
        if (!java.lang.Thread.currentThread().isVirtual() && !metadataPosted.get()) {
            metadataPosted.set(java.lang.Boolean.TRUE)
            availableEventSlots.acquireUninterruptibly(2)
            queue.add(ThreadMetadata())
        } else {
            availableEventSlots.acquireUninterruptibly()
        }
        queue.add(data)
        // Not forcing notification to avoid blocking on the lock. This might cause this signal fail to
        // be sent if the consumer is holding the lock -- either it is consuming the event queue or
        // starting to wait on the condition. For the former case, it's fine. For the latter case, we
        // will fail to notify the consumer, but the assumption is that we have events in continuous so
        // that the next event can notify the consumer.
        notifyConsumer( /* force= */false)
    }

    private class MergedEvent {
        var count: Int = 0
        var startTimeNanos: Long = 0
        var endTimeNanos: Long = 0
        var data: TaskData? = null
        var description: String? = null // Null if merged events have different descriptions

        /*
     * Tries to merge an additional event, i.e. if the event is close enough to the already merged
     * event.
     *
     * Returns null, if merging was possible.
     * If not mergeable, returns the TaskData of the previously merged events and clears the
     * internal data structures.
     */
        fun maybeMerge(data: TaskData): TaskData? {
            val startTimeNanos: Long = data.startTimeNanos
            val endTimeNanos: Long = startTimeNanos + data.durationNanos
            if (count > 0 && startTimeNanos >= this.startTimeNanos && endTimeNanos <= this.endTimeNanos) {
                // Skips child tasks.
                return null
            }
            if (count == 0) {
                this.data = data
                this.description = data.description
                this.startTimeNanos = startTimeNanos
                this.endTimeNanos = endTimeNanos
                count++
                return null
            } else if (startTimeNanos <= this.endTimeNanos + SLIM_PROFILE_MAXIMAL_PAUSE_NS) {
                if (data.description != description) {
                    description = null
                }
                this.endTimeNanos = endTimeNanos
                count++
                return null
            } else {
                val ret: TaskData? = getAndReset()
                this.data = data
                this.description = data.description
                this.startTimeNanos = startTimeNanos
                this.endTimeNanos = endTimeNanos
                count = 1
                return ret
            }
        }

        // Returns a TaskData object representing the merged data and clears internal data structures.
        fun getAndReset(): TaskData? {
            val ret: TaskData?
            if (data == null || count <= 1) {
                ret = data
            } else {
                val mergedDescription: String?
                if (description != null) {
                    mergedDescription = java.lang.String.format("%dx %s", count, description)
                } else {
                    mergedDescription = java.lang.String.format("%dx various events", count)
                }
                ret =
                    TaskData(
                        data.threadId,
                        this.startTimeNanos,
                        this.endTimeNanos - this.startTimeNanos,
                        mergedDescription
                    )
            }
            count = 0
            data = null
            return ret
        }
    }

    private fun notifyConsumer(force: Boolean) {
        val locked: Boolean
        if (force) {
            lock.lock()
            locked = true
        } else {
            locked = lock.tryLock()
        }
        if (locked) {
            try {
                condition.signal()
            } finally {
                lock.unlock()
            }
        }
    }

    @javax.annotation.concurrent.GuardedBy("lock")
    @Throws(java.lang.InterruptedException::class)
    private fun takeData(): TraceData {
        var data: TraceData
        while ((queue.poll().also { data = it }) == null) {
            condition.await()
        }
        availableEventSlots.release()
        return data
    }

    /**
     * Saves all gathered information from taskQueue queue to the file. Method is invoked internally
     * by the Timer-based thread and at the end of profiling session.
     */
    override fun run() {
        lock.lock()
        try {
            var receivedPoisonPill = false
            try {
                JsonWriter( // The buffer size of 262144 is chosen at random.
                    // Bazel internally stores strings as raw bytes encoded in ISO_8859_1, so we use the
                    // same encoding here to also write out raw bytes.
                    OutputStreamWriter(
                        BufferedOutputStream(outStream, 262144),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                ).use { writer ->
                    val startDate: Instant = Instant.now()
                    writer.beginObject()
                    writer.name("otherData")
                    writer.beginObject()
                    writer.name("bazel_version").value(BlazeVersionInfo.instance().getReleaseName())
                    writer.name("build_id").value(buildID.toString())
                    writer.name("output_base").value(outputBase)
                    writer.name("date").value(startDate.toString())
                    writer.name("profile_start_ts").value(startDate.toEpochMilli())
                    writer.endObject()
                    writer.name("traceEvents")
                    writer.beginArray()

                    // Generate metadata event for the critical path as thread 0 in disguise.
                    val criticalPathMetadata: ThreadMetadata =
                        ThreadMetadata.Companion.createFakeThreadMetadataForCriticalPath()
                    criticalPathMetadata.writeTraceData(writer, profileStartTimeNanos)

                    val eventsPerThread: HashMap<Long?, MergedEvent> = HashMap<Long?, MergedEvent>()
                    var eventCount = 0
                    var data: TraceData?
                    while ((takeData().also { data = it }) !== POISON_PILL) {
                        com.google.common.base.Preconditions.checkNotNull<TraceData?>(data)
                        eventCount++

                        if (slimProfile
                            && eventCount > SLIM_PROFILE_EVENT_THRESHOLD && data is TaskData
                            && isCandidateForMerging(data as TaskData)
                        ) {
                            eventsPerThread.putIfAbsent(data.threadId, MergedEvent())
                            val mergedTaskData: TaskData? = eventsPerThread.get(data.threadId).maybeMerge(data)
                            if (mergedTaskData != null) {
                                mergedTaskData.writeTraceData(writer, profileStartTimeNanos)
                            }
                        } else {
                            data.writeTraceData(writer, profileStartTimeNanos)
                        }
                    }
                    for (value in eventsPerThread.values()) {
                        val taskData: TaskData? = value.getAndReset()
                        if (taskData != null) {
                            taskData.writeTraceData(writer, profileStartTimeNanos)
                        }
                    }
                    receivedPoisonPill = true
                    writer.setIndent("  ")
                    writer.endArray()
                    writer.endObject()
                }
            } catch (e: IOException) {
                this.savedException = e
                if (!receivedPoisonPill) {
                    while (takeData() !== POISON_PILL) {
                        // We keep emptying the queue, but we can't write anything.
                    }
                }
            }
        } catch (e: java.lang.InterruptedException) {
            // Exit silently.
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val SLIM_PROFILE_EVENT_THRESHOLD: Long = 10000
        private val SLIM_PROFILE_MAXIMAL_PAUSE_NS: Long = java.time.Duration.ofMillis(100).toNanos()
        private val SLIM_PROFILE_MAXIMAL_DURATION_NS: Long = java.time.Duration.ofMillis(250).toNanos()

        private val POISON_PILL: TaskData = TaskData( /* threadId= */
            0,  /* startTimeNanos= */0,  /* eventType= */null, "poison pill"
        )

        private fun isCandidateForMerging(data: TaskData): Boolean {
            return data.durationNanos > 0 && data.durationNanos < SLIM_PROFILE_MAXIMAL_DURATION_NS && data.type != com.google.devtools.build.lib.profiler.ProfilerTask.CRITICAL_PATH_COMPONENT
        }
    }
}
