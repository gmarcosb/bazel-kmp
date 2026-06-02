// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

@ThreadSafe
internal class MemoryPressureListener private constructor(executor: java.util.concurrent.Executor) :
    NotificationListener {
    private val eventBus: AtomicReference<com.google.common.eventbus.EventBus?> =
        AtomicReference<com.google.common.eventbus.EventBus?>()
    private val gcThrashingDetector: AtomicReference<GcThrashingDetector?> = AtomicReference<GcThrashingDetector?>()
    private val gcChurnDetector: AtomicReference<GcChurningDetector?> = AtomicReference<GcChurningDetector?>()
    private val executor: java.util.concurrent.Executor

    init {
        this.executor = executor
    }

    override fun handleNotification(notification: javax.management.Notification, handback: Any?) {
        if (notification
                .getType()
            != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
        ) {
            return
        }

        val gcInfo: GarbageCollectionNotificationInfo =
            GarbageCollectionNotificationInfo.from(notification.getUserData() as CompositeData?)

        var tenuredSpaceUsedBytes = 0L
        var tenuredSpaceMaxBytes = 0L
        for (memoryUsageEntry in gcInfo.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
            if (!GarbageCollectionMetricsUtils.isTenuredSpace(memoryUsageEntry.getKey())) {
                continue
            }
            val space: java.lang.management.MemoryUsage = memoryUsageEntry.getValue()
            if (space.getMax() == 0L) {
                // The collector sometimes passes us nonsense stats.
                continue
            }
            tenuredSpaceUsedBytes = space.getUsed()
            tenuredSpaceMaxBytes = space.getMax()
            break
        }
        if (tenuredSpaceMaxBytes == 0L) {
            return
        }

        val event: MemoryPressureEvent =
            MemoryPressureEvent.Companion.newBuilder()
                .setWasManualGc(gcInfo.getGcCause() == "System.gc()")
                .setWasGcLockerInitiatedGc(gcInfo.getGcCause() == "GCLocker Initiated GC")
                .setWasFullGc(GarbageCollectionMetricsUtils.isFullGc(gcInfo))
                .setTenuredSpaceUsedBytes(tenuredSpaceUsedBytes)
                .setTenuredSpaceMaxBytes(tenuredSpaceMaxBytes)
                .setDuration(java.time.Duration.ofMillis(gcInfo.getGcInfo().getDuration()))
                .build()
        executor.execute(java.lang.Runnable { broadcast(event) })
    }

    private fun broadcast(event: MemoryPressureEvent) {
        val gcThrashingDetector: GcThrashingDetector? = this.gcThrashingDetector.get()
        if (gcThrashingDetector != null) {
            // Invoke the GcThrashingDetector directly instead of through the EventBus. This is because
            // the point of GcThrashingDetector is to [conditionally] crash Blaze, but if we crash in a
            // EventBus subscriber that means that CrashEvent and CommandCompleteEvent posted by
            // BugReporter#handleCrash never get handled because EventBus defers recursive posts until
            // after the posting subscriber has returned, but GcThrashingDetector halts the JVM and never
            // returns.
            gcThrashingDetector.handle(event)
        }
        val gcChurningDetector: GcChurningDetector? = this.gcChurnDetector.get()
        if (gcChurningDetector != null) {
            // Same reasoning as above for invoking GcChurningDetector directly.
            gcChurningDetector.handle(event)
        }

        // A null EventBus implies memory pressure event between commands with no active EventBus.
        val eventBus: com.google.common.eventbus.EventBus? = this.eventBus.get()
        if (eventBus != null) {
            eventBus.post(event)
        }
    }

    fun initForInvocation(
        eventBus: com.google.common.eventbus.EventBus?,
        gcThrashingDetector: GcThrashingDetector?,
        gcChurningDetector: GcChurningDetector?
    ) {
        this.eventBus.set(eventBus)
        this.gcThrashingDetector.set(gcThrashingDetector)
        this.gcChurnDetector.set(gcChurningDetector)
    }

    fun targetParsingComplete(numTopLevelTargets: Int) {
        val gcChurningDetector: GcChurningDetector? = gcChurnDetector.get()
        if (gcChurningDetector != null) {
            gcChurningDetector.targetParsingComplete(numTopLevelTargets)
        }
    }

    fun populateStats(memoryPressureStatsBuilder: MemoryPressureStats.Builder) {
        val gcChurningDetector: GcChurningDetector? = gcChurnDetector.get()
        if (gcChurningDetector != null) {
            gcChurningDetector.populateStats(memoryPressureStatsBuilder)
        }
    }

    fun reset() {
        eventBus.set(null)
        gcThrashingDetector.set(null)
        gcChurnDetector.set(null)
    }

    companion object {
        fun create(): MemoryPressureListener {
            return createFromBeans(
                java.lang.management.ManagementFactory.getGarbageCollectorMXBeans(),  // Use a dedicated thread to broadcast memory pressure events. The service thread that calls
                // handleNotification for GC events is not a typical Java thread - it doesn't work with
                // debugger breakpoints and may not show up in thread dumps.
                Executors.newSingleThreadExecutor(
                    com.google.common.util.concurrent.ThreadFactoryBuilder()
                        .setNameFormat("memory-pressure-listener-%d").build()
                )
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun createFromBeans(
            gcBeans: MutableList<java.lang.management.GarbageCollectorMXBean>, executor: java.util.concurrent.Executor
        ): MemoryPressureListener {
            val tenuredGcEmitters: com.google.common.collect.ImmutableList<NotificationEmitter?> =
                findTenuredCollectorBeans(gcBeans)
            if (tenuredGcEmitters.isEmpty()) {
                val names: com.google.common.collect.ImmutableList<MutableList<String?>?> =
                    gcBeans.stream()
                        .map<Array<String?>?>(java.util.function.Function { obj: java.lang.management.GarbageCollectorMXBean? -> obj.getMemoryPoolNames() })
                        .map<MutableList<String?>?>(java.util.function.Function { a: Array<String?>? ->
                            java.util.Arrays.asList(
                                a
                            )
                        })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<MutableList<String?>?>())
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Unable to find tenured collector from %s: names were %s.", gcBeans, names
                    )
                )
            }

            val memoryPressureListener = MemoryPressureListener(executor)
            tenuredGcEmitters.forEach(java.util.function.Consumer { e: NotificationEmitter? ->
                e.addNotificationListener(
                    memoryPressureListener,
                    null,
                    null
                )
            })
            return memoryPressureListener
        }

        @com.google.common.annotations.VisibleForTesting
        fun findTenuredCollectorBeans(
            gcBeans: MutableList<java.lang.management.GarbageCollectorMXBean>
        ): com.google.common.collect.ImmutableList<NotificationEmitter?> {
            val builder: com.google.common.collect.ImmutableList.Builder<NotificationEmitter?> =
                com.google.common.collect.ImmutableList.builder<NotificationEmitter?>()
            // Examine all collectors and register for notifications from those which collect the tenured
            // space. Normally there is one such collector.
            for (gcBean in gcBeans) {
                for (name in gcBean.getMemoryPoolNames()) {
                    if (GarbageCollectionMetricsUtils.isTenuredSpace(name)) {
                        builder.add(gcBean as NotificationEmitter)
                    }
                }
            }
            return builder.build()
        }
    }
}
