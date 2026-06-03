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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.getFingerprintForTesting

/** Tests for [NestedSetSerializationCache].  */
@RunWith(JUnit4::class)
class NestedSetSerializationCacheTest {
    private val cache: NestedSetSerializationCache = NestedSetSerializationCache(BugReporter.defaultInstance())

    @org.junit.Test
    fun putFutureIfAbsent_newFingerprint_returnsNull() {
        val fingerprint1: PackedFingerprint? = getFingerprintForTesting("abc")
        val fingerprint2: PackedFingerprint? = getFingerprintForTesting("xyz")
        val future1: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val future2: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()

        assertThat(cache.putFutureIfAbsent(fingerprint1, future1, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.putFutureIfAbsent(fingerprint2, future2, DEFAULT_CONTEXT)).isNull()
    }

    @org.junit.Test
    fun putFutureIfAbsent_existingFingerprint_returnsExistingFuture() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()

        assertThat(cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)).isNull()
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(future)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(future)
    }

    @org.junit.Test
    fun putFutureIfAbsent_rejectsAlreadyDoneFuture() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        future.set(arrayOfNulls<Any>(0))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT) })
    }

    @org.junit.Test
    fun putFutureIfAbsent_futureCompletes_unwrapsContents() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future1: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val future2: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val contents = arrayOfNulls<Any>(0)

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.putFutureIfAbsent(fingerprint, future1, DEFAULT_CONTEXT)
        future1.set(contents)

        assertThat(cache.putFutureIfAbsent(fingerprint, future2, DEFAULT_CONTEXT))
            .isSameInstanceAs(contents)
    }

    @org.junit.Test
    fun putFutureIfAbsent_futureCompletes_cachesFingerprint() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val contents = arrayOfNulls<Any>(0)

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)
        future.set(contents)

        val result: PutOperation = cache.fingerprintForContents(contents)
        assertThat(result.fingerprint()).isEqualTo(fingerprint)
        assertThat(result.writeStatus().isDone()).isTrue()
    }

    @org.junit.Test
    fun putFutureIfAbsent_futureFails_notifiesBugReporter() {
        val mockBugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        val cacheWithCustomBugReporter: NestedSetSerializationCache =
            NestedSetSerializationCache(mockBugReporter)
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val e: Throwable = MissingFingerprintValueException(fingerprint)

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cacheWithCustomBugReporter.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)
        future.setException(e)

        Mockito.verify<BugReporter?>(mockBugReporter).sendNonFatalBugReport(e)
    }

    @org.junit.Test
    fun putIfAbsent_newFingerprintAndContents_returnsNullAndCachesBothDirections() {
        val fingerprint1: PackedFingerprint? = getFingerprintForTesting("abc")
        val fingerprint2: PackedFingerprint? = getFingerprintForTesting("xyz")
        val contents1: Array<Any?> = arrayOf<Any>("abc")
        val contents2: Array<Any?> = arrayOf<Any>("xyz")
        val result1: PutOperation = PutOperation(fingerprint1, SettableWriteStatus())
        val result2: PutOperation = PutOperation(fingerprint2, SettableWriteStatus())

        assertThat(cache.putIfAbsent(contents1, result1, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.putIfAbsent(contents2, result2, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.fingerprintForContents(contents1)).isSameInstanceAs(result1)
        assertThat(cache.fingerprintForContents(contents2)).isSameInstanceAs(result2)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint1,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(contents1)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint2,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(contents2)
    }

    @org.junit.Test
    fun putIfAbsent_existingFingerprintAndContents_returnsExistingResult() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val contents = arrayOfNulls<Any>(0)
        val result1: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val result2: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val result3: PutOperation = PutOperation(fingerprint, SettableWriteStatus())

        assertThat(cache.putIfAbsent(contents, result1, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.putIfAbsent(contents, result2, DEFAULT_CONTEXT)).isSameInstanceAs(result1)
        assertThat(cache.putIfAbsent(contents, result3, DEFAULT_CONTEXT)).isSameInstanceAs(result1)
    }

    @org.junit.Test
    fun putIfAbsent_calledDuringPendingDeserialization_overwritesFuture() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val contents = arrayOfNulls<Any>(0)
        val result: PutOperation = PutOperation(fingerprint, SettableWriteStatus())

        assertThat(cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.putIfAbsent(contents, result, DEFAULT_CONTEXT)).isNull()
        assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(contents)

        // After the future completes, the contents should still be cached (doesn't matter which array).
        val deserializedContents = arrayOfNulls<Any>(0)
        future.set(deserializedContents)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isAnyOf(contents, deserializedContents)

        // Both arrays should have a PutOperation.
        val resultForDeserializedContents: PutOperation = cache.fingerprintForContents(deserializedContents)
        assertThat(resultForDeserializedContents.fingerprint()).isEqualTo(fingerprint)
        assertThat(resultForDeserializedContents.writeStatus().isDone()).isTrue()
        assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result)
    }

    @org.junit.Test
    fun putFutureIfAbsent_cacheEntriesHaveLifetimeOfContents() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        var future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?>? =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.putFutureIfAbsent(fingerprint, future, DEFAULT_CONTEXT)

        // Before completing, still cached while future in memory.
        GcFinalization.awaitFullGc()
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(future)

        // After completing, still cached while contents in memory even if future is gone.
        val futureRef: java.lang.ref.WeakReference<com.google.common.util.concurrent.SettableFuture<Array<Any?>?>?> =
            java.lang.ref.WeakReference<com.google.common.util.concurrent.SettableFuture<Array<Any?>?>?>(future)
        var contents: Array<Any?>? = arrayOfNulls<Any>(0)
        future.set(contents)
        future = null
        GcFinalization.awaitClear(futureRef)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(contents)
        val result: PutOperation = cache.fingerprintForContents(contents)
        assertThat(result.fingerprint()).isEqualTo(fingerprint)
        assertThat(result.writeStatus().isDone()).isTrue()

        // Cleared after references are gone, and the cycle of putFutureIfAbsent starts over.
        val contentsRef: java.lang.ref.WeakReference<Array<Any?>?> = java.lang.ref.WeakReference<Array<Any?>?>(contents)
        contents = null
        GcFinalization.awaitClear(contentsRef)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isNull()
    }

    @org.junit.Test
    fun putIfAbsent_cacheEntriesHaveLifetimeOfContents() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        var contents: Array<Any?>? = arrayOfNulls<Any>(0)
        val result: PutOperation = PutOperation(fingerprint, immediateWriteStatus())

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.putIfAbsent(contents, result, DEFAULT_CONTEXT)

        // Still cached while in memory.
        GcFinalization.awaitFullGc()
        assertThat(cache.fingerprintForContents(contents)).isSameInstanceAs(result)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isSameInstanceAs(contents)

        // Cleared after references are gone, and the cycle of putFutureIfAbsent starts over.
        val ref: java.lang.ref.WeakReference<Array<Any?>?> = java.lang.ref.WeakReference<Array<Any?>?>(contents)
        contents = null
        GcFinalization.awaitClear(ref)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                DEFAULT_CONTEXT
            )
        )
            .isNull()
    }

    @org.junit.Test
    fun putFutureIfAbsent_usesContextToDistinguish() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val contextLower = "lower"
        val contextUpper = "UPPER"
        val futureLower: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val futureUpper: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()

        assertThat(cache.putFutureIfAbsent(fingerprint, futureLower, contextLower)).isNull()
        assertThat(cache.putFutureIfAbsent(fingerprint, futureUpper, contextUpper)).isNull()
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextLower
            )
        )
            .isSameInstanceAs(futureLower)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextUpper
            )
        )
            .isSameInstanceAs(futureUpper)

        val contentsLower: Array<Any?> = arrayOf<Any>("abc")
        val contentsUpper: Array<Any?> = arrayOf<Any>("ABC")
        futureLower.set(contentsLower)
        futureUpper.set(contentsUpper)

        val resultLower: PutOperation = cache.fingerprintForContents(contentsLower)
        val resultUpper: PutOperation = cache.fingerprintForContents(contentsUpper)
        assertThat(resultLower.fingerprint()).isEqualTo(fingerprint)
        assertThat(resultUpper.fingerprint()).isEqualTo(fingerprint)
        assertThat(resultLower.writeStatus().isDone()).isTrue()
        assertThat(resultUpper.writeStatus().isDone()).isTrue()
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextLower
            )
        )
            .isSameInstanceAs(contentsLower)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextUpper
            )
        )
            .isSameInstanceAs(contentsUpper)
    }

    @org.junit.Test
    fun putIfAbsent_usesContextToDistinguish() {
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val contextLower = "lower"
        val contextUpper = "UPPER"
        val contentsLower: Array<Any?> = arrayOf<Any>("abc")
        val contentsUpper: Array<Any?> = arrayOf<Any>("ABC")
        val resultLower1: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val resultUpper1: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val resultLower2: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val resultUpper2: PutOperation = PutOperation(fingerprint, SettableWriteStatus())

        assertThat(cache.putIfAbsent(contentsLower, resultLower1, contextLower)).isNull()
        assertThat(cache.putIfAbsent(contentsUpper, resultUpper1, contextUpper)).isNull()
        assertThat(cache.putIfAbsent(contentsLower, resultLower2, contextLower))
            .isSameInstanceAs(resultLower1)
        assertThat(cache.putIfAbsent(contentsUpper, resultUpper2, contextUpper))
            .isSameInstanceAs(resultUpper1)

        assertThat(cache.fingerprintForContents(contentsLower)).isSameInstanceAs(resultLower1)
        assertThat(cache.fingerprintForContents(contentsUpper)).isSameInstanceAs(resultUpper1)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextLower
            )
        )
            .isSameInstanceAs(contentsLower)
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                contextUpper
            )
        )
            .isSameInstanceAs(contentsUpper)
    }

    @org.junit.Test
    fun contextComparedByValueEquality() {
        class Context {
            override fun hashCode(): Int {
                return 1
            }

            override fun equals(o: Any?): Boolean {
                return o is Context
            }
        }

        val fingerprint: PackedFingerprint? = getFingerprintForTesting("abc")
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val contents = arrayOfNulls<Any>(0)
        val result1: PutOperation = PutOperation(fingerprint, SettableWriteStatus())
        val result2: PutOperation = PutOperation(fingerprint, SettableWriteStatus())

        assertThat(cache.putFutureIfAbsent(fingerprint, future, Context())).isNull()
        assertThat(
            cache.putFutureIfAbsent(
                fingerprint,
                com.google.common.util.concurrent.SettableFuture.create<V?>(),
                Context()
            )
        )
            .isSameInstanceAs(future)

        assertThat(cache.putIfAbsent(contents, result1, Context())).isNull()
        assertThat(cache.putIfAbsent(contents, result2, Context())).isSameInstanceAs(result1)
    }

    companion object {
        private val DEFAULT_CONTEXT = Any()
    }
}
