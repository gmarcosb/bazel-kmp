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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import com.sun.management.GarbageCollectionNotificationInfo
import com.sun.management.GcInfo
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import javax.management.NotificationEmitter

/** Tests for [MemoryPressureListener].  */
@RunWith(JUnit4::class)
class MemoryPressureListenerTest {
    private interface NotificationBean : java.lang.management.GarbageCollectorMXBean, NotificationEmitter

    private val mockBean: NotificationBean? = null
    fun <NotificationBean> mock()
    private val mockUselessBean: NotificationBean? = null
    fun <NotificationBean> mock()
    private val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
    private val events: MutableList<MemoryPressureEvent?> = java.util.ArrayList<MemoryPressureEvent?>()

    @Before
    fun initMocks() {
        Mockito.`when`<Array<String?>?>(mockBean.getMemoryPoolNames())
            .thenReturn(arrayOf<String>("not tenured", TENURED_SPACE_NAME))
        Mockito.`when`<Array<String?>?>(mockUselessBean.getMemoryPoolNames())
            .thenReturn(arrayOf<String>("assistant", "adjunct"))
    }

    @Before
    fun registerSubscriber() {
        eventBus.register(
            object : Any() {
                @com.google.common.eventbus.Subscribe
                fun handle(event: MemoryPressureEvent?) {
                    events.add(event)
                }
            })
    }

    @org.junit.Test
    fun findBeans() {
        assertThat(
            MemoryPressureListener.findTenuredCollectorBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockUselessBean, mockBean)
            )
        )
            .containsExactly(mockBean)
    }

    @org.junit.Test
    fun createFromBeans_throwsIfNoTenuredSpaceBean() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                MemoryPressureListener.createFromBeans(
                    com.google.common.collect.ImmutableList.of<E?>(mockUselessBean),
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            })
    }

    @org.junit.Test
    fun simple() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockUselessBean, mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        underTest.initForInvocation(
            eventBus, TODO("Cannot convert element")
        )<T> Mockito . mock < GcThrashingDetector ? > (GcThrashingDetector::class.java)
        T > Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)

        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)
        NotificationBean > Mockito.verify<NotificationBean?>(mockUselessBean, Mockito.never())
            .addNotificationListener(TODO("Cannot convert element"))<javax.management.NotificationListener> ArgumentMatchers . any < kotlin . Any ? > ()
        javax.management.NotificationFilter > ArgumentMatchers.any<Any?>()
        Object > ArgumentMatchers.any<Any?>()


        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val nonTenuredSpaceName = "nope"
        val mockMemoryUsageForNonTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    nonTenuredSpaceName,
                    mockMemoryUsageForNonTenuredSpace,
                    TENURED_SPACE_NAME,
                    mockMemoryUsageForTenuredSpace
                )
            )
        Long > Mockito.`when`<Long?>(mockGcInfo.getDuration()).thenReturn(42000L)

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        Truth.assertThat(events)
            .containsExactly(
                MemoryPressureEvent.newBuilder()
                    .setWasManualGc(false)
                    .setTenuredSpaceUsedBytes(42L)
                    .setTenuredSpaceMaxBytes(100L)
                    .setDuration(java.time.Duration.ofSeconds(42))
                    .build()
            )
    }

    @org.junit.Test
    fun nullEventBus_doNotPublishEvent() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockUselessBean, mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)
        NotificationBean > Mockito.verify<NotificationBean?>(mockUselessBean, Mockito.never())
            .addNotificationListener(TODO("Cannot convert element"))<javax.management.NotificationListener> ArgumentMatchers . any < kotlin . Any ? > ()
        javax.management.NotificationFilter > ArgumentMatchers.any<Any?>()
        Object > ArgumentMatchers.any<Any?>()


        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val nonTenuredSpaceName = "nope"
        val mockMemoryUsageForNonTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    nonTenuredSpaceName,
                    mockMemoryUsageForNonTenuredSpace,
                    TENURED_SPACE_NAME,
                    mockMemoryUsageForTenuredSpace
                )
            )

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        Truth.assertThat(events).isEmpty()
    }

    @org.junit.Test
    fun manualGc() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        underTest.initForInvocation(
            eventBus, TODO("Cannot convert element")
        )<T> Mockito . mock < GcThrashingDetector ? > (GcThrashingDetector::class.java)
        T > Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)

        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)

        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    TENURED_SPACE_NAME, mockMemoryUsageForTenuredSpace
                )
            )
        Long > Mockito.`when`<Long?>(mockGcInfo.getDuration()).thenReturn(42000L)

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "gcAction", "System.gc()", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        Truth.assertThat(events)
            .containsExactly(
                MemoryPressureEvent.newBuilder()
                    .setWasManualGc(true)
                    .setTenuredSpaceUsedBytes(42L)
                    .setTenuredSpaceMaxBytes(100L)
                    .setDuration(java.time.Duration.ofSeconds(42))
                    .build()
            )
    }

    @org.junit.Test
    fun doesntInvokeHandlerWhenTenuredSpaceMaxSizeIsZero() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        underTest.initForInvocation(
            eventBus, TODO("Cannot convert element")
        )<T> Mockito . mock < GcThrashingDetector ? > (GcThrashingDetector::class.java)
        T > Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)

        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)

        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(42L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(0L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    TENURED_SPACE_NAME, mockMemoryUsageForTenuredSpace
                )
            )

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        Truth.assertThat(events).isEmpty()
    }

    @org.junit.Test
    fun findsTenuredSpaceWithNonZeroMaxSize() {
        val anotherMockBean: NotificationBean
        NotificationBean > Mockito.mock<NotificationBean?>(NotificationBean::class.java)
        val anotherTenuredSpaceName = "G1 Old Gen"
        String
        Mockito.`when`<Array<String?>?>(anotherMockBean.getMemoryPoolNames())
            .thenReturn(arrayOf<String>(anotherTenuredSpaceName))

        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockBean, anotherMockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        underTest.initForInvocation(
            eventBus, TODO("Cannot convert element")
        )<T> Mockito . mock < GcThrashingDetector ? > (GcThrashingDetector::class.java)
        T > Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)

        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)
        NotificationBean > Mockito.verify<NotificationBean?>(anotherMockBean)
            .addNotificationListener(underTest, null, null)

        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(1L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(0L)
        val mockMemoryUsageForAnotherTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForAnotherTenuredSpace.getUsed()).thenReturn(2L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForAnotherTenuredSpace.getMax()).thenReturn(3L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    TENURED_SPACE_NAME,
                    mockMemoryUsageForTenuredSpace,
                    anotherTenuredSpaceName,
                    mockMemoryUsageForAnotherTenuredSpace
                )
            )
        Long > Mockito.`when`<Long?>(mockGcInfo.getDuration()).thenReturn(42000L)

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "gcAction", "non-manual", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        Truth.assertThat(events)
            .containsExactly(
                MemoryPressureEvent.newBuilder()
                    .setWasManualGc(false)
                    .setTenuredSpaceUsedBytes(2L)
                    .setTenuredSpaceMaxBytes(3L)
                    .setDuration(java.time.Duration.ofSeconds(42))
                    .build()
            )
    }

    @org.junit.Test
    fun directlyInvokesGcThrashingDetectorAndGcChurnDetector() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockUselessBean, mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        NotificationBean > Mockito.verify<NotificationBean?>(mockBean).addNotificationListener(underTest, null, null)
        NotificationBean > Mockito.verify<NotificationBean?>(mockUselessBean, Mockito.never())
            .addNotificationListener(TODO("Cannot convert element"))<javax.management.NotificationListener> ArgumentMatchers . any < kotlin . Any ? > ()
        javax.management.NotificationFilter > ArgumentMatchers.any<Any?>()
        Object > ArgumentMatchers.any<Any?>()


        val mockGcThrashingDetector: GcThrashingDetector?
        GcThrashingDetector > Mockito.mock<GcThrashingDetector?>(GcThrashingDetector::class.java)
        val mockGcChurningDetector: GcChurningDetector?
        GcChurningDetector > Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)
        underTest.initForInvocation(eventBus, mockGcThrashingDetector, mockGcChurningDetector)

        val mockGcInfo: GcInfo
        GcInfo > Mockito.mock<GcInfo?>(GcInfo::class.java)
        val nonTenuredSpaceName = "nope"
        val mockMemoryUsageForNonTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        val mockMemoryUsageForTenuredSpace: java.lang.management.MemoryUsage
        MemoryUsage > Mockito.mock<java.lang.management.MemoryUsage?>(java.lang.management.MemoryUsage::class.java)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getUsed()).thenReturn(99L)
        Long > Mockito.`when`<Long?>(mockMemoryUsageForTenuredSpace.getMax()).thenReturn(100L)
        Mockito.`when`<MutableMap<String?, java.lang.management.MemoryUsage?>?>(mockGcInfo.getMemoryUsageAfterGc())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<String?, java.lang.management.MemoryUsage?>(
                    nonTenuredSpaceName,
                    mockMemoryUsageForNonTenuredSpace,
                    TENURED_SPACE_NAME,
                    mockMemoryUsageForTenuredSpace
                )
            )
        Long > Mockito.`when`<Long?>(mockGcInfo.getDuration()).thenReturn(42000L)

        val notification: javax.management.Notification =
            javax.management.Notification(
                GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION, "test", 123
            )
        notification.setUserData(
            GarbageCollectionNotificationInfo("gcName", "end of major GC", "non-manual", mockGcInfo)
                .toCompositeData(null)
        )
        underTest.handleNotification(notification, null)

        val event: MemoryPressureEvent? =
            MemoryPressureEvent.newBuilder()
                .setWasManualGc(false)
                .setWasFullGc(true)
                .setTenuredSpaceUsedBytes(99)
                .setTenuredSpaceMaxBytes(100)
                .setDuration(java.time.Duration.ofSeconds(42))
                .build()
        Truth.assertThat(events).containsExactly(event)
        Object > Mockito.verify<Any?>(mockGcThrashingDetector)
            .handle(TODO("Cannot convert element"))<T> ArgumentMatchers . eq < kotlin . Any ? > (event)

        Object > Mockito.verify<Any?>(mockGcChurningDetector)
            .handle(TODO("Cannot convert element"))<T> ArgumentMatchers . eq < kotlin . Any ? > (event)
    }

    @org.junit.Test
    fun forwardsTargetParsingComplete() {
        val underTest: MemoryPressureListener =
            MemoryPressureListener.createFromBeans(
                com.google.common.collect.ImmutableList.of<E?>(mockUselessBean, mockBean),
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )

        val mockGcChurningDetector: GcChurningDetector? =
            Mockito.mock<GcChurningDetector?>(GcChurningDetector::class.java)
        underTest.initForInvocation(eventBus, < T > mock < T ? > (GcThrashingDetector::class.java), mockGcChurningDetector)
        underTest.targetParsingComplete(42)

        Mockito.verify<Any?>(mockGcChurningDetector).targetParsingComplete(ArgumentMatchers.eq(42))
    }

    companion object {
        private const val TENURED_SPACE_NAME = "CMS Old Gen"
    }
}
