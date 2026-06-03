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

/** Unit tests for [PostGCMemoryUseRecorder].  */
@RunWith(JUnit4::class)
class PostGCMemoryUseRecorderTest {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()

    @org.junit.Test
    fun listenToSingleNonCopyGC() {
        val beans: MutableList<java.lang.management.GarbageCollectorMXBean?> =
            Companion.createGCBeans(arrayOf<String>("FooGC"))

        val rec: PostGCMemoryUseRecorder = PostGCMemoryUseRecorder(beans, BugReporter.defaultInstance())
        Mockito.verify<NotificationEmitter?>(beans.get(0) as NotificationEmitter?, Mockito.times(1))
            .addNotificationListener(rec, null, null)
    }

    @org.junit.Test
    fun listenToMultipleNonCopyGCs() {
        val beans: MutableList<java.lang.management.GarbageCollectorMXBean?> =
            Companion.createGCBeans(arrayOf<String>("FooGC", "BarGC"))

        val rec: PostGCMemoryUseRecorder = PostGCMemoryUseRecorder(beans, BugReporter.defaultInstance())
        Mockito.verify<NotificationEmitter?>(beans.get(0) as NotificationEmitter?, Mockito.times(1))
            .addNotificationListener(rec, null, null)
        Mockito.verify<NotificationEmitter?>(beans.get(1) as NotificationEmitter?, Mockito.times(1))
            .addNotificationListener(rec, null, null)
    }

    @org.junit.Test
    fun dontListenToCopyGC() {
        val beans: MutableList<java.lang.management.GarbageCollectorMXBean?> =
            Companion.createGCBeans(arrayOf<String>("FooGC", "Copy"))

        val rec: PostGCMemoryUseRecorder = PostGCMemoryUseRecorder(beans, BugReporter.defaultInstance())
        Mockito.verify<NotificationEmitter?>(beans.get(0) as NotificationEmitter?, Mockito.times(1))
            .addNotificationListener(rec, null, null)
        Mockito.verify<NotificationEmitter?>(beans.get(1) as NotificationEmitter?, Mockito.never())
            .addNotificationListener(
                ArgumentMatchers.any<NotificationListener?>(NotificationListener::class.java),
                ArgumentMatchers.any<NotificationFilter?>(NotificationFilter::class.java),
                ArgumentMatchers.any<Any?>()
            )
    }

    @org.junit.Test
    fun peakHeapsStartAbsent() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        assertThat(rec.getPeakPostGcHeap()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsAbsentAfterReset() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )
        rec.reset()
        assertThat(rec.getPeakPostGcHeap()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).isEmpty()
    }

    @org.junit.Test
    fun noGcCauseEventsNotIgnored() {
        val underTest: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(com.google.common.collect.ImmutableList.of<E?>(), BugReporter.defaultInstance())
        val notificationWithNoGcCause: javax.management.Notification =
            createMockNotification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
                "end of major GC",  /*cause=*/
                "No GC",
                com.google.common.collect.ImmutableMap.of<String?, Long?>("somepool", 100L),  /*memUsedBefore=*/
                null
            )

        underTest.handleNotification(notificationWithNoGcCause,  /*handback=*/null)

        assertThat(underTest.getPeakPostGcHeap())
            .hasValue(PeakHeap.create(100, clock.currentTimeMillis()))
        assertThat(underTest.getPeakPostGcHeapTenuredSpace())
            .hasValue(PeakHeap.create(0, clock.currentTimeMillis()))
    }

    @org.junit.Test
    fun peakHeapsIncreaseWhenBigger() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        clock.advanceMillis(1)
        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )
        assertThat(rec.getPeakPostGcHeap()).hasValue(PeakHeap.create(2000, clock.currentTimeMillis()))
        assertThat(rec.getPeakPostGcHeapTenuredSpace())
            .hasValue(PeakHeap.create(1000, clock.currentTimeMillis()))

        clock.advanceMillis(1)
        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1001L), null
        )
        assertThat(rec.getPeakPostGcHeap()).hasValue(PeakHeap.create(2002, clock.currentTimeMillis()))
        assertThat(rec.getPeakPostGcHeapTenuredSpace())
            .hasValue(PeakHeap.create(1001, clock.currentTimeMillis()))

        clock.advanceMillis(1)
        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1002L), null
        )
        assertThat(rec.getPeakPostGcHeap()).hasValue(PeakHeap.create(2004, clock.currentTimeMillis()))
        assertThat(rec.getPeakPostGcHeapTenuredSpace())
            .hasValue(PeakHeap.create(1002, clock.currentTimeMillis()))
    }

    @org.junit.Test
    fun peakHeapsDontDecrease() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        clock.advanceMillis(1)
        rec.handleNotification(createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000), null)
        val expectedTotal: PeakHeap? = PeakHeap.create(2000, clock.currentTimeMillis())
        val expectedTenuredSpace: PeakHeap? = PeakHeap.create(1000, clock.currentTimeMillis())

        clock.advanceMillis(1)
        rec.handleNotification(createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(500), null)
        assertThat(rec.getPeakPostGcHeap()).hasValue(expectedTotal)
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).hasValue(expectedTenuredSpace)

        clock.advanceMillis(1)
        rec.handleNotification(createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(999), null)
        assertThat(rec.getPeakPostGcHeap()).hasValue(expectedTotal)
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).hasValue(expectedTenuredSpace)
    }

    @org.junit.Test
    fun ignoreNonGCNotification() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        rec.handleNotification(
            createMockNotification(
                "some other notification",
                "end of major GC",
                com.google.common.collect.ImmutableMap.of<String?, Long?>("Foo", 1000L)
            ),
            null
        )
        assertThat(rec.getPeakPostGcHeap()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).isEmpty()
    }

    @org.junit.Test
    fun ignoreNonMajorGCNotification() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        rec.handleNotification(
            createMockNotification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
                "end of minor GC",
                com.google.common.collect.ImmutableMap.of<String?, Long?>("Foo", 1000L)
            ),
            null
        )
        assertThat(rec.getPeakPostGcHeap()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpace()).isEmpty()
    }

    @org.junit.Test
    fun sumMemUsageInfo() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        rec.handleNotification(
            createMajorGCNotification(
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "Foo",
                    111L,
                    "Bar",
                    222L,
                    "Qux",
                    333L,
                    "CMS Old Gen",
                    111L
                )
            ),
            null
        )
        assertThat(rec.getPeakPostGcHeap()).hasValue(PeakHeap.create(777, clock.currentTimeMillis()))
        assertThat(rec.getPeakPostGcHeapTenuredSpace())
            .hasValue(PeakHeap.create(111, clock.currentTimeMillis()))
    }

    @org.junit.Test
    fun memoryUsageReportedZeroGetsSetAndStaysSet() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        assertThat(rec.wasMemoryUsageReportedZero()).isFalse()
        rec.handleNotification(
            createMajorGCNotification(
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "Foo",
                    0L,
                    "Bar",
                    0L,
                    "Qux",
                    0L
                )
            ), null
        )
        assertThat(rec.wasMemoryUsageReportedZero()).isTrue()
        rec.handleNotification(
            createMajorGCNotification(
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "Foo",
                    123L,
                    "Bar",
                    456L,
                    "Qux",
                    789L
                )
            ), null
        )
        assertThat(rec.wasMemoryUsageReportedZero()).isTrue()
    }

    @org.junit.Test
    fun memoryUsageReportedZeroDoesntGetSet() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        assertThat(rec.wasMemoryUsageReportedZero()).isFalse()
        rec.handleNotification(
            createMajorGCNotification(
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "Foo",
                    123L,
                    "Bar",
                    456L,
                    "Qux",
                    789L
                )
            ), null
        )
        assertThat(rec.wasMemoryUsageReportedZero()).isFalse()
    }

    @org.junit.Test
    fun totalGarbageReported() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        assertThat(rec.getGarbageStats()).isEmpty()

        rec.handleNotification(
            createMockNotification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
                "action",
                "cause",
                com.google.common.collect.ImmutableMap.of<String?, Long?>("old", 1000L, "young", 2000L),
                com.google.common.collect.ImmutableMap.of<String?, Long?>("old", 5000L, "young", 10000L)
            ),  /* handback= */
            null
        )
        assertThat(rec.getGarbageStats()).containsExactly("old", 4000L, "young", 8000L)
        rec.handleNotification(
            createMockNotification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
                "action",
                "cause",
                com.google.common.collect.ImmutableMap.of<String?, Long?>("young", 15000L),
                com.google.common.collect.ImmutableMap.of<String?, Long?>("young", 20000L)
            ),  /* handback= */
            null
        )
        assertThat(rec.getGarbageStats()).containsExactly("old", 4000L, "young", 13000L)

        rec.reset()
        assertThat(rec.getGarbageStats()).isEmpty()
    }

    @org.junit.Test
    fun moreThanOneTenuredSpaceEventReportsBug() {
        val bugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        val rec: PostGCMemoryUseRecorder = PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), bugReporter)
        rec.handleNotification(
            createMajorGCNotification(
                com.google.common.collect.ImmutableMap.of<String?, Long?>(
                    "CMS Old Gen",
                    111L,
                    "PS Old Gen",
                    111L
                )
            ), null
        )
        Mockito.verify<BugReporter?>(bugReporter)
            .sendBugReport(
                ArgumentMatchers.argThat<Throwable?>(
                    ArgumentMatcher { e: Throwable? ->
                        e!!.message
                            .contains(
                                "More than one tenured space event was recorded during garbage"
                                        + " collection."
                            )
                    })
            )
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionStartAbsent() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())
        assertThat(rec.getPeakPostGcHeapDuringExecution()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionAbsentAfterReset() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
        eventBus.register(rec)
        eventBus.post(Mockito.mock<AnalysisPhaseCompleteEvent?>(AnalysisPhaseCompleteEvent::class.java))
        eventBus.post(SomeExecutionStartedEvent.create())

        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )
        rec.reset()
        assertThat(rec.getPeakPostGcHeapDuringExecution()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionNotUpdatedBeforeEvents() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )

        assertThat(rec.getPeakPostGcHeapDuringExecution()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionNotUpdatedWithOnlyAnalysisComplete() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
        eventBus.register(rec)
        eventBus.post(Mockito.mock<AnalysisPhaseCompleteEvent?>(AnalysisPhaseCompleteEvent::class.java))

        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )

        assertThat(rec.getPeakPostGcHeapDuringExecution()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionNotUpdatedWithOnlyExecutionStarted() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
        eventBus.register(rec)
        eventBus.post(SomeExecutionStartedEvent.create())

        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )

        assertThat(rec.getPeakPostGcHeapDuringExecution()).isEmpty()
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution()).isEmpty()
    }

    @org.junit.Test
    fun peakHeapsDuringExecutionUpdatedAfterBothEvents() {
        val rec: PostGCMemoryUseRecorder =
            PostGCMemoryUseRecorder(java.util.ArrayList<E?>(), BugReporter.defaultInstance())

        val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
        eventBus.register(rec)
        eventBus.post(Mockito.mock<AnalysisPhaseCompleteEvent?>(AnalysisPhaseCompleteEvent::class.java))
        eventBus.post(SomeExecutionStartedEvent.create())

        clock.advanceMillis(1)
        rec.handleNotification(
            createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(1000L), null
        )

        assertThat(rec.getPeakPostGcHeapDuringExecution())
            .hasValue(PeakHeap.create(2000, clock.currentTimeMillis()))
        assertThat(rec.getPeakPostGcHeapTenuredSpaceDuringExecution())
            .hasValue(PeakHeap.create(1000, clock.currentTimeMillis()))
    }

    private fun createMockNotification(
        type: String?, action: String, memUsed: MutableMap<String?, Long?>
    ): javax.management.Notification {
        return createMockNotification(type, action, "dummycause", memUsed,  /* memUsedBefore= */null)
    }

    private fun createMockNotification(
        type: String?,
        action: String,
        cause: String,
        memUsed: MutableMap<String?, Long?>,
        memUsedBefore: MutableMap<String?, Long?>?
    ): javax.management.Notification {
        val gcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val memoryUsageMap: com.google.common.collect.ImmutableMap<String?, java.lang.management.MemoryUsage?> =
            createMemoryUsageMap(memUsed)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(gcInfo.getMemoryUsageAfterGc())
            .thenReturn(memoryUsageMap)
        if (memUsedBefore != null) {
            val memoryUsageBeforeMap: com.google.common.collect.ImmutableMap<String?, java.lang.management.MemoryUsage?> =
                createMemoryUsageMap(memUsedBefore)
            Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(gcInfo.getMemoryUsageBeforeGc())
                .thenReturn(memoryUsageBeforeMap)
        }

        val notInfo: GarbageCollectionNotificationInfo =
            GarbageCollectionNotificationInfo("DummyGCName", action, cause, gcInfo)

        val n: javax.management.Notification
        Notification > Mockito.mock<javax.management.Notification?>(javax.management.Notification::class.java)
        String > Mockito.`when`<String?>(n.getType()).thenReturn(type)
        Object > Mockito.`when`<Any?>(n.getUserData()).thenReturn(notInfo.toCompositeData(null))
        Long > Mockito.`when`<Long?>(n.getTimeStamp()).thenReturn(clock.currentTimeMillis())
        return n
    }

    private fun createMajorGCNotification(memUsed: MutableMap<String?, Long?>): javax.management.Notification {
        return createMockNotification(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION,
            "end of major GC",
            memUsed
        )
    }

    private fun createOneTenuredSpaceOneNonTenuredSpaceMajorGCNotifications(used: Long): javax.management.Notification {
        return createMajorGCNotification(
            com.google.common.collect.ImmutableMap.of<String?, Long?>(
                "Foo",
                used,
                "CMS Old Gen",
                used
            )
        )
    }

    companion object {
        private fun createMXBeanWithName(name: String?): java.lang.management.GarbageCollectorMXBean {
            val b: java.lang.management.GarbageCollectorMXBean =
                Mockito.mock<java.lang.management.GarbageCollectorMXBean>(
                    java.lang.management.GarbageCollectorMXBean::class.java,
                    Mockito.withSettings().extraInterfaces(NotificationEmitter::class.java)
                )
            Mockito.`when`<String?>(b.getName()).thenReturn(name)
            return b
        }

        private fun createGCBeans(names: Array<String?>): MutableList<java.lang.management.GarbageCollectorMXBean?> {
            val beans: MutableList<java.lang.management.GarbageCollectorMXBean?> =
                java.util.ArrayList<java.lang.management.GarbageCollectorMXBean?>()
            for (n in names) {
                beans.add(createMXBeanWithName(n))
            }
            return beans
        }

        private fun createMockMemoryUsage(used: Long): java.lang.management.MemoryUsage {
            val mu: java.lang.management.MemoryUsage =
                Mockito.mock<java.lang.management.MemoryUsage>(java.lang.management.MemoryUsage::class.java)
            Mockito.`when`<Long?>(mu.getUsed()).thenReturn(used)
            return mu
        }

        private fun createMemoryUsageMap(memUsed: MutableMap<String?, Long?>): com.google.common.collect.ImmutableMap<String?, java.lang.management.MemoryUsage?> {
            val memUsageMap: com.google.common.collect.ImmutableMap.Builder<String?, java.lang.management.MemoryUsage?> =
                com.google.common.collect.ImmutableMap.builder<String?, java.lang.management.MemoryUsage?>()
            for (e in memUsed.entries) {
                memUsageMap.put(e.key, Companion.createMockMemoryUsage(e.value!!))
            }
            return memUsageMap.buildOrThrow()
        }
    }
}
