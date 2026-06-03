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

import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.getFingerprintForTesting

@RunWith(TestParameterInjector::class)
class FingerprintValueCacheTest {
    // FingerprintValueService delegates getOrClaimPutOperation and getOrClaimGetOperation to
    // FingerprintValueCache. The tests here test the logic in FingerprintValueCache but go through
    // through the FingerprintValueService to improve coverage there.
    internal enum class Distinguisher(private val value: Any?) {
        NULL_DISTINGUISHER( /* value= */null),
        NON_NULL_DISTINGUISHER( /* value= */Any());

        fun value(): Any? {
            return value
        }
    }

    @org.junit.Test
    fun putOperation_isCached(@TestParameter distinguisher: Distinguisher) {
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.LINKED)

        val value = Any()

        val op1: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        var result: Any? = service.getOrClaimPutOperation(value, distinguisher.value(), op1)
        Truth.assertThat(result).isNull()

        val op2: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        result = service.getOrClaimPutOperation(value, distinguisher.value(), op2)
        Truth.assertThat(result).isSameInstanceAs(op1)
    }

    @org.junit.Test
    fun getOperation_isCached(@TestParameter distinguisher: Distinguisher) {
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.LINKED)

        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")

        val op1: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        var result: Any? = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), op1)
        Truth.assertThat(result).isNull()

        val op2: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        result = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), op2)
        Truth.assertThat(result).isSameInstanceAs(op1)
    }

    @org.junit.Test
    fun putOperation_isUnwrapped(@TestParameter distinguisher: Distinguisher) {
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.LINKED)

        val value = Any()

        val putOp1: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        var putResult: Any? = service.getOrClaimPutOperation(value, distinguisher.value(), putOp1)
        Truth.assertThat(putResult).isNull()

        // Sets the `PutOperation` in `putOp1`, which triggers the first stage of unwrapping and
        // populates the reverse service.
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")
        val writeStatus: SettableWriteStatus = SettableWriteStatus()
        putOp1.set(PutOperation(fingerprint, writeStatus))

        // A get of `fingerprint` now returns `value` immediately.
        val getOp: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        val getResult: Any? = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), getOp)
        Truth.assertThat(getResult).isSameInstanceAs(value)

        // A second "put" of `value' sees the original, wrapped `putOp1`.
        val putOp2: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        putResult = service.getOrClaimPutOperation(value, distinguisher.value(), putOp2)
        Truth.assertThat(putResult).isSameInstanceAs(putOp1)

        // Setting the write status fully unwraps the value.
        writeStatus.markSuccess()
        putResult = service.getOrClaimPutOperation(value, distinguisher.value(), putOp2)
        Truth.assertThat(putResult).isSameInstanceAs(fingerprint)
    }

    @org.junit.Test
    fun getOperation_isUnwrapped(@TestParameter distinguisher: Distinguisher) {
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.LINKED)

        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")

        val getOp: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        val result: Any? = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), getOp)
        Truth.assertThat(result).isNull()

        // The first put operation is owned by the caller.
        val value = Any()
        val putOp: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        var putResult: Any? = service.getOrClaimPutOperation(value, distinguisher.value(), putOp)
        Truth.assertThat(putResult).isNull()

        // Completes the `getOp`, causing it to be unwrapped.
        getOp.set(value)

        // The next put operation gets the unwrapped fingerprint.
        val putOp2: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        putResult = service.getOrClaimPutOperation(value, distinguisher.value(), putOp2)
        Truth.assertThat(putResult).isSameInstanceAs(fingerprint)

        // Completing `putOp` overwrites values, but this is benign because `value`s fingerprint should
        // be deterministic.
        val fingerprint2: PackedFingerprint? = getFingerprintForTesting("foo")
        putOp.set(PutOperation(fingerprint2, immediateWriteStatus()))

        val putOp3: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        putResult = service.getOrClaimPutOperation(value, distinguisher.value(), putOp3)
        Truth.assertThat(putResult).isSameInstanceAs(fingerprint2)
    }

    @org.junit.Test
    fun distinguisher_distinguishesSameFingerprint() {
        // Puts two values with the same fingerprint, but different distinguishers, then verifies that
        // they are distinguishable on retrieval.
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.LINKED)

        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")

        val put: com.google.common.util.concurrent.ListenableFuture<PutOperation?> =
            com.google.common.util.concurrent.Futures.immediateFuture<PutOperation?>(
                PutOperation(
                    fingerprint,
                    immediateWriteStatus()
                )
            )

        val value1 = Any()
        val distinguisher1 = Any()
        var result: Any? = service.getOrClaimPutOperation(value1, distinguisher1, put)
        Truth.assertThat(result).isNull()

        val value2 = Any()
        val distinguisher2 = Any()
        // Reusing `put` here is fine because it's the same fingerprint.
        result = service.getOrClaimPutOperation(value2, distinguisher2, put)
        Truth.assertThat(result).isNull()

        // The correct values are returned for the distinguisher values.
        val unusedGetOperation: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        result = service.getOrClaimGetOperation(fingerprint, distinguisher1, unusedGetOperation)
        Truth.assertThat(result).isSameInstanceAs(value1)
        result = service.getOrClaimGetOperation(fingerprint, distinguisher2, unusedGetOperation)
        Truth.assertThat(result).isSameInstanceAs(value2)
    }

    @org.junit.Test
    fun notLinkedPut_doesNotAddGetEntry(
        @TestParameter distinguisher: Distinguisher,
        @TestParameter mode: SyncMode
    ) {
        val service: FingerprintValueService = FingerprintValueService.createForTesting(mode)

        // Puts the `fingerprint` to `value` association into the service.
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")
        val value = Any()
        var result: Any? =
            service.getOrClaimPutOperation(
                value,
                distinguisher.value(),
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(
                    PutOperation(
                        fingerprint,
                        immediateWriteStatus()
                    )
                )
            )
        Truth.assertThat(result).isNull()

        val getOperation: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        result = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), getOperation)
        when (mode) {
            LINKED -> Truth.assertThat(result).isSameInstanceAs(value)
            NOT_LINKED ->         // The reverse, `fingerprint` to `value` entry, is not added to the cache when NOT_LINKED.
                Truth.assertThat(result).isNull()
        }
    }

    @org.junit.Test
    fun notLinkedGet_doesNotAddPutEntry(
        @TestParameter distinguisher: Distinguisher,
        @TestParameter mode: SyncMode
    ) {
        val service: FingerprintValueService = FingerprintValueService.createForTesting(mode)

        // Puts the `value` to `fingerprint` association into the service.
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("foo")
        val value = Any()
        var result: Any? =
            service.getOrClaimGetOperation(
                fingerprint,
                distinguisher.value(),
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(value)
            )
        Truth.assertThat(result).isNull()

        val putOperation: com.google.common.util.concurrent.SettableFuture<PutOperation?> =
            com.google.common.util.concurrent.SettableFuture.create<PutOperation?>()
        result = service.getOrClaimPutOperation(value, distinguisher.value(), putOperation)
        when (mode) {
            LINKED -> Truth.assertThat(result).isSameInstanceAs(fingerprint)
            NOT_LINKED ->         // The reverse `value` to `fingerprint` entry is not added to the cache when NOT_LINKED.
                Truth.assertThat(result).isNull()
        }
    }

    @org.junit.Test(timeout = 30000) // timeout in case GcFinalization#awaitClear doesn't finish for any reason
    @Throws(java.lang.InterruptedException::class)
    fun missingSharedValueBytesException_isCached(@TestParameter distinguisher: Distinguisher) {
        val service: FingerprintValueService =
            FingerprintValueService.createForTesting(FingerprintValueCache.SyncMode.NOT_LINKED)

        val fingerprint: PackedFingerprint? = getFingerprintForTesting("missing")

        val op1: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        var result: Any? = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), op1)
        Truth.assertThat(result).isNull()

        // Completes op1 with a MissingFingerprintValueException.
        op1.setException(MissingSharedValueBytesException.INSTANCE)

        // Creates a "control" object that should be collected because there are no
        // other strong references to it.
        val controlFingerprint: PackedFingerprint? = getFingerprintForTesting("control")
        var controlFuture: com.google.common.util.concurrent.ListenableFuture<Any?>? =
            com.google.common.util.concurrent.Futures.immediateFuture<Any?>(
                Any()
            )
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            service.getOrClaimGetOperation(controlFingerprint, distinguisher.value(), controlFuture)

        val controlWeakReference: java.lang.ref.WeakReference<Any?> = java.lang.ref.WeakReference<Any?>(controlFuture)
        controlFuture = null
        GcFinalization.awaitClear(controlWeakReference)

        // Forces Caffeine to remove controlFuture that was just GC'd.
        service.cacheCleanUpForTesting()

        // The control object should be gone.
        assertThat(
            service.getOrClaimGetOperation(
                controlFingerprint, distinguisher.value(), com.google.common.util.concurrent.SettableFuture.create<V?>()
            )
        )
            .isNull()

        // A second get of `fingerprint` should now return an immediate failed future from
        // the cache. Note the absence of setting `op2`.
        val op2: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        result = service.getOrClaimGetOperation(fingerprint, distinguisher.value(), op2)

        Truth.assertThat(result).isNotNull() // if this is null, the cache is not working.
        Truth.assertThat(result).isInstanceOf(com.google.common.util.concurrent.ListenableFuture::class.java)

        val cachedFuture: com.google.common.util.concurrent.ListenableFuture<*> =
            result as com.google.common.util.concurrent.ListenableFuture<*>
        Truth.assertThat(cachedFuture.isDone()).isTrue()
        val e: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { cachedFuture.get() })
        Truth.assertThat(e).hasCauseThat().isSameInstanceAs(MissingSharedValueBytesException.INSTANCE)
    }
}
