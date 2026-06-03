// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.RemoteAnalysisCacheStatistics.InvalidationLookupMetrics

/** Unit tests for [AnalysisCacheInvalidator].  */
@RunWith(TestParameterInjector::class)
class AnalysisCacheInvalidatorTest {
    @org.junit.Rule
    val mocks: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val mockAnalysisCacheClient: RemoteAnalysisCacheClient? = null

    @org.mockito.Mock
    private val mockEventHandler: ExtendedEventHandler? = null

    @org.mockito.Mock
    private val mockEventListener: RemoteAnalysisCachingEventListener? = null

    private val objectCodecs: ObjectCodecs = ObjectCodecs()
    private val frontierNodeVersion: FrontierNodeVersion? = FrontierNodeVersion.CONSTANT_FOR_TESTING
    private val baseClientId: ClientId = SnapshotClientId("for_testing", 1)
    private val fingerprintService: FingerprintValueService? = FingerprintValueService.createForTesting()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_emptyInput_returnsEmptySet() {
        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                com.google.common.collect.ImmutableSet::of,
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_cacheHit_returnsEmptySet() {
        val key: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("hit_key")
        val fingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key, frontierNodeVersion
            )

        // Simulate a cache hit by returning a non-empty response.
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fingerprint.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(
                        ByteString.copyFromUtf8("some_value"), MissReason.MISS_REASON_UNSPECIFIED
                    )
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_cacheMiss_returnsKey() {
        val key: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("miss_key")
        val fingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key, frontierNodeVersion
            )

        // Simulate a cache miss by returning an empty response.
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fingerprint.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(key)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_mixedHitAndMiss_returnsMissedKey() {
        val hitKey: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("hit_key_mixed")
        val missKey: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("miss_key_mixed")

        val hitFingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, hitKey, frontierNodeVersion
            )
        val missFingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, missKey, frontierNodeVersion
            )

        // Simulate a cache hit _and_ miss for looking up multiple keys.
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(hitFingerprint.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(
                        ByteString.copyFromUtf8("some_value"), MissReason.MISS_REASON_UNSPECIFIED
                    )
                )
            )
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(missFingerprint.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(hitKey, missKey) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(missKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_differentVersions_returnsAllKeys() {
        val key1: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("key1")
        val key2: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("key2")

        val previousVersion: FrontierNodeVersion =
            FrontierNodeVersion(
                "123",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",  /* useFakeStampData= */
                true,
                java.util.Optional.of<T?>(SnapshotClientId("for_testing", 123))
            )
        val currentVersion: FrontierNodeVersion =
            FrontierNodeVersion(
                "123",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9001),  // changed
                "distinguisher",  /* useFakeStampData= */
                true,
                java.util.Optional.of<T?>(SnapshotClientId("for_testing", 123))
            )
        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,
                currentVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key1, key2) },
                RemoteAnalysisCachingServerState(
                    previousVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(key1, key2)

        // No RPCs should be sent.
        Mockito.verify<Any?>(mockAnalysisCacheClient, Mockito.never()).lookup(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_differentStarlarkSemantics_returnsAllKeys() {
        val key1: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("key1")
        val key2: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("key2")

        val previousVersion: FrontierNodeVersion =
            FrontierNodeVersion(
                "123",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",  /* useFakeStampData= */
                true,
                java.util.Optional.of<T?>(SnapshotClientId("for_testing", 123))
            )
        val currentVersion: FrontierNodeVersion =
            FrontierNodeVersion(
                "123",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(4, 5, 6),  // changed starlark semantics
                IntVersion.of(9000),
                "distinguisher",  /* useFakeStampData= */
                true,
                java.util.Optional.of<T?>(SnapshotClientId("for_testing", 123))
            )
        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,
                currentVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key1, key2) },
                RemoteAnalysisCachingServerState(
                    previousVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(key1, key2)

        // No RPCs should be sent.
        Mockito.verify<Any?>(mockAnalysisCacheClient, Mockito.never()).lookup(ArgumentMatchers.any<T?>())
    }

    private enum class ClientIdTestCase(
        currentClientId: ClientId,
        previousClientId: ClientId,
        expectedInvalidated: Boolean
    ) {
        NEWER_CLIENT_ID_CACHE_MISS_INVALIDATES(
            SnapshotClientId("for_testing", 2),
            SnapshotClientId("for_testing", 1),  /* expectedInvalidated= */
            true
        ),
        OLDER_CLIENT_ID_CACHE_MISS_INVALIDATES(
            SnapshotClientId("for_testing", 1),
            SnapshotClientId("for_testing", 2),  /* expectedInvalidated= */
            true
        ),
        SAME_CLIENT_ID_CACHE_MISS_DOES_NOT_INVALIDATE_ANYTHING(
            SnapshotClientId("for_testing", 1),
            SnapshotClientId("for_testing", 1),  /* expectedInvalidated= */
            false
        ),
        SAME_LONG_VERSION_CLIENT_ID_CACHE_MISS_DOES_NOT_INVALIDATE_ANYTHING(
            LongVersionClientId(123456789),
            LongVersionClientId(123456789),  /* expectedInvalidated= */
            false
        ),
        DIFFERENT_LONG_VERSION_CLIENT_ID_CACHE_MISS_INVALIDATES(
            LongVersionClientId(123456789),
            LongVersionClientId(123456788),  /* expectedInvalidated= */
            true
        ),
        DIFFERENT_CLIENT_ID_SUBCLASS_CACHE_MISS_INVALIDATES(
            LongVersionClientId(123456789),
            SnapshotClientId("for_testing", 1),  /* expectedInvalidated= */
            true
        );

        private val currentClientId: ClientId?
        private val previousClientId: ClientId?
        private val expectedInvalidated: Boolean

        init {
            this.currentClientId = currentClientId
            this.previousClientId = previousClientId
            this.expectedInvalidated = expectedInvalidated
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_clientIdComparison(@TestParameter testCase: ClientIdTestCase) {
        val key: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("key")
        val packedFingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key, frontierNodeVersion
            )
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(packedFingerprint.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                testCase.currentClientId,
                mockEventHandler,
                mockEventListener
            )

        val keysToInvalidate: com.google.common.collect.ImmutableSet<SkyKey?>? =
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key) },
                RemoteAnalysisCachingServerState(frontierNodeVersion, testCase.previousClientId)
            )

        if (testCase.expectedInvalidated) {
            Truth.assertThat(keysToInvalidate).containsExactly(key)
        } else {
            Truth.assertThat(keysToInvalidate).isEmpty()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_timeout_returnsAllKeysAndRecordsTimeout() {
        val key: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("timeout_key")
        val fingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key, frontierNodeVersion
            )

        val neverCompletes: com.google.common.util.concurrent.SettableFuture<LookupResult?> =
            com.google.common.util.concurrent.SettableFuture.create<LookupResult?>()
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fingerprint.toBytes())))
            .thenReturn(neverCompletes)

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(key)

        Mockito.verify<Any?>(mockEventListener)
            .setInvalidationLookupMetrics(
                ArgumentMatchers.argThat<T?>(
                    ArgumentMatcher { metrics: T? -> metrics.getStatus() === InvalidationLookupMetrics.Status.TIMED_OUT && metrics.getNumKeys() === 1 && metrics.getNumInvalidatedKeys() === 1 })
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_allKeysHit_recordsZeroInvalidated() {
        val key1: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("hit_key1")
        val key2: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("hit_key2")
        val fp1: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key1, frontierNodeVersion
            )
        val fp2: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key2, frontierNodeVersion
            )

        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fp1.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(
                        ByteString.copyFromUtf8("val1"), MissReason.MISS_REASON_UNSPECIFIED
                    )
                )
            )
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fp2.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(
                        ByteString.copyFromUtf8("val2"), MissReason.MISS_REASON_UNSPECIFIED
                    )
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key1, key2) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )

        Mockito.verify<Any?>(mockEventListener)
            .setInvalidationLookupMetrics(
                ArgumentMatchers.argThat<T?>(
                    ArgumentMatcher { metrics: T? -> metrics.getStatus() === InvalidationLookupMetrics.Status.OK && metrics.getNumKeys() === 2 && metrics.getNumInvalidatedKeys() === 0 })
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_mixedHitAndMiss_recordsPartialInvalidated() {
        val hitKey: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("hit_key_metrics")
        val missKey: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("miss_key_metrics")
        val hitFp: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, hitKey, frontierNodeVersion
            )
        val missFp: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, missKey, frontierNodeVersion
            )

        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(hitFp.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(
                        ByteString.copyFromUtf8("val"), MissReason.MISS_REASON_UNSPECIFIED
                    )
                )
            )
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(missFp.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(hitKey, missKey) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )

        Mockito.verify<Any?>(mockEventListener)
            .setInvalidationLookupMetrics(
                ArgumentMatchers.argThat<T?>(
                    ArgumentMatcher { metrics: T? -> metrics.getStatus() === InvalidationLookupMetrics.Status.OK && metrics.getNumKeys() === 2 && metrics.getNumInvalidatedKeys() === 1 })
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_allKeysMiss_recordsAllInvalidated() {
        val key1: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("miss_key1")
        val key2: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("miss_key2")
        val fp1: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key1, frontierNodeVersion
            )
        val fp2: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key2, frontierNodeVersion
            )

        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fp1.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )
        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fp2.toBytes())))
            .thenReturn(
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_UNSPECIFIED)
                )
            )

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key1, key2) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )

        Mockito.verify<Any?>(mockEventListener)
            .setInvalidationLookupMetrics(
                ArgumentMatchers.argThat<T?>(
                    ArgumentMatcher { metrics: T? -> metrics.getStatus() === InvalidationLookupMetrics.Status.OK && metrics.getNumKeys() === 2 && metrics.getNumInvalidatedKeys() === 2 })
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun lookupKeysToInvalidate_executionException_returnsAllKeysAndRecordsError() {
        val key: TrivialKey =
            com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisCacheInvalidatorTest.TrivialKey("error_key")
        val fingerprint: PackedFingerprint =
            FingerprintValueService.computeFingerprint(
                fingerprintService, objectCodecs, key, frontierNodeVersion
            )

        Mockito.`when`<T?>(mockAnalysisCacheClient.lookup(ByteString.copyFrom(fingerprint.toBytes())))
            .thenReturn(com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(java.lang.RuntimeException("injected failure")))

        val invalidator: AnalysisCacheInvalidator =
            AnalysisCacheInvalidator(
                mockAnalysisCacheClient,
                objectCodecs,
                fingerprintService,  /* currentVersion= */
                frontierNodeVersion,
                baseClientId,
                mockEventHandler,
                mockEventListener
            )

        assertThat(
            invalidator.lookupKeysToInvalidate(
                { com.google.common.collect.ImmutableSet.of<E?>(key) },
                RemoteAnalysisCachingServerState(
                    frontierNodeVersion, SnapshotClientId("for_testing", 2)
                )
            )
        )
            .containsExactly(key)

        Mockito.verify<Any?>(mockEventListener)
            .setInvalidationLookupMetrics(
                ArgumentMatchers.argThat<T?>(
                    ArgumentMatcher { metrics: T? -> metrics.getStatus() === InvalidationLookupMetrics.Status.ERROR && metrics.getNumKeys() === 1 && metrics.getNumInvalidatedKeys() === 1 })
            )
    }

    @AutoCodec
    @VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class TrivialKey(val text: String?) : SkyKey {
        public override fun functionName(): SkyFunctionName? {
            throw java.lang.UnsupportedOperationException()
        }
    }
}
