// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics

import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent

/**
 * Keeps track of the peak heap usage directly after a full GC by listening for GC notifications.
 * 
 * 
 * The idea behind this class is as follows. We assume that:
 * 
 * <pre>
 * sizeof(heap used) = sizeof(data) + sizeof(garbage)
</pre> * 
 * 
 * and that after a full GC sizeof(garbage) is close to 0.
 * 
 * 
 * This allows us to measure sizeof(data) by measuring sizeof(heap used) immediately after a full
 * GC.
 */
// Using long timestamps for consistency with code base.
class PostGCMemoryUseRecorder @com.google.common.annotations.VisibleForTesting internal constructor(
    mxBeans: Iterable<java.lang.management.GarbageCollectorMXBean>,
    bugReporter: BugReporter
) : NotificationListener {
    /** The memory use and time of a build's peak post-GC heap.  */
    @kotlin.jvm.JvmRecord
    data class PeakHeap(val bytes: Long, val timestampMillis: Long) {
        companion object {
            @kotlin.jvm.JvmStatic
            fun create(bytes: Long, timestampMillis: Long): PeakHeap {
                return PeakHeap(bytes, timestampMillis)
            }
        }
    }

    @javax.annotation.concurrent.GuardedBy("this")
    private var peakHeapTotal: java.util.Optional<PeakHeap?> = java.util.Optional.empty<PeakHeap?>()

    @javax.annotation.concurrent.GuardedBy("this")
    private var peakHeapTenuredSpace: java.util.Optional<PeakHeap?> = java.util.Optional.empty<PeakHeap?>()

    @javax.annotation.concurrent.GuardedBy("this")
    private var peakHeapTotalDuringExecution: java.util.Optional<PeakHeap?> = java.util.Optional.empty<PeakHeap?>()

    @javax.annotation.concurrent.GuardedBy("this")
    private var peakHeapTenuredSpaceDuringExecution: java.util.Optional<PeakHeap?> =
        java.util.Optional.empty<PeakHeap?>()

    // Set to true iff a GarbageCollectionNotification reported that we were using no memory.
    @javax.annotation.concurrent.GuardedBy("this")
    private var memoryUsageReportedZero = false

    @javax.annotation.concurrent.GuardedBy("this")
    private var garbageStats: MutableMap<String?, Long?> = HashMap<String?, Long?>()

    private val bugReporter: BugReporter

    @javax.annotation.concurrent.GuardedBy("this")
    private var analysisComplete = false

    @javax.annotation.concurrent.GuardedBy("this")
    private var executionStarted = false

    init {
        for (mxBean in mxBeans) {
            // The "Copy" collector only does minor collections.
            if ("Copy" == mxBean.getName()) {
                continue
            }
            logger.atInfo().log("Listening for notifications from GC: %s", mxBean.getName())
            (mxBean as NotificationEmitter).addNotificationListener(this, null, null)
        }
        this.bugReporter = bugReporter
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    private fun analysisComplete(event: AnalysisPhaseCompleteEvent?) {
        analysisComplete = true
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    private fun executionStartedSkymeld(event: SomeExecutionStartedEvent?) {
        executionStarted = true
    }

    @com.google.common.eventbus.Subscribe
    @kotlin.jvm.Synchronized
    private fun executionStartedNonSkymeld(event: ExecutionStartingEvent?) {
        executionStarted = true
    }

    @kotlin.jvm.Synchronized
    fun getPeakPostGcHeap(): java.util.Optional<PeakHeap?> {
        return peakHeapTotal
    }

    @kotlin.jvm.Synchronized
    fun getPeakPostGcHeapTenuredSpace(): java.util.Optional<PeakHeap?> {
        return peakHeapTenuredSpace
    }

    @kotlin.jvm.Synchronized
    fun getPeakPostGcHeapDuringExecution(): java.util.Optional<PeakHeap?> {
        return peakHeapTotalDuringExecution
    }

    @kotlin.jvm.Synchronized
    fun getPeakPostGcHeapTenuredSpaceDuringExecution(): java.util.Optional<PeakHeap?> {
        return peakHeapTenuredSpaceDuringExecution
    }

    @kotlin.jvm.Synchronized
    fun wasMemoryUsageReportedZero(): Boolean {
        return memoryUsageReportedZero
    }

    /**
     * Returns the number of bytes garbage collected during this invocation. Broken down by GC space.
     */
    @kotlin.jvm.Synchronized
    fun getGarbageStats(): com.google.common.collect.ImmutableMap<String?, Long?> {
        return com.google.common.collect.ImmutableSortedMap.copyOf<String?, Long?>(garbageStats)
    }

    @kotlin.jvm.Synchronized
    fun reset() {
        peakHeapTotal = java.util.Optional.empty<PeakHeap?>()
        peakHeapTenuredSpace = java.util.Optional.empty<PeakHeap?>()
        peakHeapTotalDuringExecution = java.util.Optional.empty<PeakHeap?>()
        peakHeapTenuredSpaceDuringExecution = java.util.Optional.empty<PeakHeap?>()
        memoryUsageReportedZero = false
        garbageStats = HashMap<String?, Long?>()
        analysisComplete = false
        executionStarted = false
    }

    @kotlin.jvm.Synchronized
    private fun updatePostGCHeapMemoryUsed(
        usedTotal: Long, usedTenuredSpace: Long, timestampMillis: Long
    ) {
        peakHeapTotal = updatePeak(peakHeapTotal, usedTotal, timestampMillis)
        peakHeapTenuredSpace = updatePeak(peakHeapTenuredSpace, usedTenuredSpace, timestampMillis)
        if (analysisComplete && executionStarted) {
            peakHeapTotalDuringExecution =
                updatePeak(peakHeapTotalDuringExecution, usedTotal, timestampMillis)
            peakHeapTenuredSpaceDuringExecution =
                updatePeak(peakHeapTenuredSpaceDuringExecution, usedTenuredSpace, timestampMillis)
        }
    }

    @kotlin.jvm.Synchronized
    private fun updateMemoryUsageReportedZero(value: Boolean) {
        memoryUsageReportedZero = value
    }

    override fun handleNotification(notification: javax.management.Notification, handback: Any?) {
        if (notification
                .getType()
            != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        ) {
            return
        }

        val info: GarbageCollectionNotificationInfo =
            GarbageCollectionNotificationInfo.from(notification.getUserData() as CompositeData?)

        val gcBefore: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        val gcInfo: GcInfo = info.getGcInfo()
        for (memoryUsage in gcInfo.getMemoryUsageBeforeGc().entries) {
            val kind: String? = memoryUsage.key
            gcBefore.put(kind, memoryUsage.value.getUsed())
        }
        synchronized(this) {
            for (memoryUsage in gcInfo.getMemoryUsageAfterGc().entries) {
                val kind: String? = memoryUsage.key
                val before: Long = (if (gcBefore.containsKey(kind)) gcBefore.get(kind) else 0)!!
                val diff: Long = before - memoryUsage.value.getUsed()
                // The difference is potentially negative when the JVM propagates objects from one GC space
                // to another. Discard these cases.
                if (diff > 0) {
                    garbageStats.compute(kind) { k: String?, v: Long? -> if (v == null) diff else v + diff }
                }
            }
        }

        if (wasStopTheWorldGc(info)) {
            val durationNs: Long = info.getGcInfo().getDuration() * 1000000
            val end: Long = com.google.devtools.build.lib.profiler.Profiler.Companion.instance().nanoTimeMaybe()
            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                .logSimpleTask(
                    end - durationNs,
                    end,
                    com.google.devtools.build.lib.profiler.ProfilerTask.HANDLE_GC_NOTIFICATION,
                    info.getGcAction().replaceFirst("^end of ".toRegex(), "")
                )
        }
        if (!GarbageCollectionMetricsUtils.isFullGc(info)) {
            return
        }

        var usedTotal: Long = 0
        var usedTenuredSpace: Long = 0
        var tenuredSpaceEventCount = 0
        for (memoryUsageEntry in info.getGcInfo().getMemoryUsageAfterGc().entries) {
            if (GarbageCollectionMetricsUtils.isTenuredSpace(memoryUsageEntry.key)) {
                usedTenuredSpace = memoryUsageEntry.value.getUsed()
                tenuredSpaceEventCount++
            }
            usedTotal += memoryUsageEntry.value.getUsed()
        }
        if (tenuredSpaceEventCount > 1) {
            bugReporter.sendBugReport(
                java.lang.IllegalStateException(
                    "More than one tenured space event was recorded during garbage collection."
                )
            )
        }
        val mem: MutableMap<String?, java.lang.management.MemoryUsage?>? = info.getGcInfo().getMemoryUsageAfterGc()
        updatePostGCHeapMemoryUsed(usedTotal, usedTenuredSpace, notification.getTimeStamp())
        if (usedTotal > 0) {
            logger.atInfo().log(
                "Memory use after full GC: %d total, %d tenured", usedTotal, usedTenuredSpace
            )
        } else {
            logger.atInfo().log(
                "Amount of memory used after GC incorrectly reported as %d by JVM with values %s",
                usedTotal, mem
            )
            updateMemoryUsageReportedZero(true)
        }
    }

    /** Module to support "blaze info peak-heap-size".  */
    class PostGCMemoryUseRecorderModule : BlazeModule() {
        override fun serverInit(
            startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
            builder: com.google.devtools.build.lib.runtime.ServerBuilder
        ) {
            builder.addInfoItems(PeakMemInfoItem())
        }

        override fun beforeCommand(env: CommandEnvironment) {
            if (env.getCommandName() != "info") {
                get().reset()
                env.getEventBus().register(get())
            }
        }
    }

    private class PeakMemInfoItem : InfoItem(
        "peak-heap-size",
        "The peak amount of used memory in bytes after any full GC during the most recent"
                + " invocation.",  /* hidden= */
        true
    ) {
        override fun get(
            configurationSupplier: com.google.common.base.Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
        ): ByteArray? {
            return get()
                .getPeakPostGcHeap()
                .map<ByteArray?>(java.util.function.Function { peak: PeakHeap? ->
                    InfoItem.print(
                        com.google.devtools.build.lib.util.StringUtilities.prettyPrintBytes(
                            peak!!.bytes
                        )
                    )
                })
                .orElseGet(java.util.function.Supplier { InfoItem.print("unknown") })
        }
    }

    /** Module to run a full GC after a build is complete on a Blaze server. *  */
    class GcAfterBuildModule : BlazeModule() {
        private var forceGc = false

        /** Command options for forcing a GC after a build. *  */
        @com.google.devtools.common.options.OptionsClass
        abstract class Options : com.google.devtools.common.options.OptionsBase() {
            @com.google.devtools.common.options.Option(
                name = "experimental_force_gc_after_build",
                defaultValue = "false",
                documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
                effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
                help = ("If true calls System.gc() after a build to try and get a post-gc peak heap"
                        + " measurement.")
            )
            abstract fun getExperimentalForceGcAfterBuild(): Boolean
        }

        override fun getCommonCommandOptions(): com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
            return com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.GcAfterBuildModule.Options::class.java
            )
        }

        override fun beforeCommand(env: CommandEnvironment) {
            val options: Options? = env.getOptions()
                .getOptions<Options?>(com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.GcAfterBuildModule.Options::class.java)
            if (options != null
                && ("test" == env.getCommand().name || "build" == env.getCommand().name)
            ) {
                forceGc = options.getExperimentalForceGcAfterBuild()
            } else {
                forceGc = false
            }
        }

        override fun afterCommand() {
            if (forceGc && !get().getPeakPostGcHeap().isPresent()) {
                java.lang.System.gc()
            }
        }
    }

    companion object {
        private var instance: PostGCMemoryUseRecorder? = null

        @kotlin.jvm.Synchronized
        fun get(): PostGCMemoryUseRecorder {
            if (instance == null) {
                instance =
                    PostGCMemoryUseRecorder(
                        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans(),
                        BugReporter.defaultInstance()
                    )
            }
            return instance!!
        }

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun updatePeak(
            currentPeak: java.util.Optional<PeakHeap?>, newBytes: Long, timestampMillis: Long
        ): java.util.Optional<PeakHeap?> {
            if (currentPeak.isEmpty() || newBytes > currentPeak.get().bytes) {
                return java.util.Optional.of<PeakHeap?>(PeakHeap.Companion.create(newBytes, timestampMillis))
            }
            return currentPeak
        }

        private fun wasStopTheWorldGc(info: GarbageCollectionNotificationInfo): Boolean {
            // Weak heuristic to determine if this was a STW gc.
            return "No GC" != info.getGcCause()
        }
    }
}
