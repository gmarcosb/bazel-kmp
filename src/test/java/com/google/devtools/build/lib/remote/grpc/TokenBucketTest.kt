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
package com.google.devtools.build.lib.remote.grpc

import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Predicate
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/** Tests for [TokenBucket]  */
@RunWith(JUnit4::class)
class TokenBucketTest {
    @Test
    fun acquireToken_smoke() {
        val bucket = TokenBucket<Int?>()
        Truth.assertThat(bucket.size()).isEqualTo(0)
        bucket.addToken(0)
        Truth.assertThat(bucket.size()).isEqualTo(1)

        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.assertValue(0).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(0)
    }

    @Test
    fun acquireToken_releaseInitialTokens() {
        val bucket = TokenBucket<Int?>(ImmutableList.of<Int?>(0))
        Truth.assertThat(bucket.size()).isEqualTo(1)

        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.assertValue(0).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(0)
    }

    @Test
    fun acquireToken_multipleInitialTokens_releaseFirstToken() {
        val bucket = TokenBucket<Int?>(ImmutableList.of<Int?>(0, 1))
        Truth.assertThat(bucket.size()).isEqualTo(2)

        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.assertValue(0).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(1)
    }

    @Test
    fun acquireToken_multipleInitialTokens_releaseSecondToken() {
        val bucket = TokenBucket<Int?>(ImmutableList.of<Int?>(0, 1))
        Truth.assertThat(bucket.size()).isEqualTo(2)
        bucket.acquireToken().test().assertValue(0).assertComplete()

        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.assertValue(1).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(0)
    }

    @Test
    fun acquireToken_releaseTokenToPreviousObserver() {
        val bucket = TokenBucket<Int?>()
        val observer: TestObserver<Int?> = bucket.acquireToken().test()
        observer.assertEmpty()

        bucket.addToken(0)

        observer.assertValue(0).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(0)
    }

    @Test
    fun acquireToken_notReleaseTokenToDisposedObserver() {
        val bucket = TokenBucket<Int?>()
        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.dispose()
        bucket.addToken(0)

        observer.assertEmpty()
        Truth.assertThat(bucket.size()).isEqualTo(1)
    }

    @Test
    fun acquireToken_disposeAfterTokenAcquired() {
        val bucket = TokenBucket<Int?>()
        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        bucket.addToken(0)
        bucket.addToken(1)

        observer.assertValue(0).assertComplete()
        Truth.assertThat(bucket.size()).isEqualTo(1)
    }

    @Test
    fun acquireToken_multipleObservers_onlyOneCanAcquire() {
        val bucket = TokenBucket<Int?>()
        val observer1: TestObserver<Int?> = bucket.acquireToken().test()
        val observer2: TestObserver<Int?> = bucket.acquireToken().test()

        bucket.addToken(0)

        if (!observer1.values().isEmpty()) {
            observer1.assertValue(0).assertComplete()
            observer2.assertEmpty()

            bucket.addToken(1)
            observer2.assertValue(1).assertComplete()
        } else {
            observer1.assertEmpty()
            observer2.assertValue(0).assertComplete()

            bucket.addToken(1)
            observer1.assertValue(1).assertComplete()
        }
    }

    @Test
    fun acquireToken_reSubscription_waitAvailableToken() {
        val bucket = TokenBucket<Int?>()
        bucket.addToken(0)
        val tokenSingle: Single<Int?> = bucket.acquireToken()

        val observer1 = tokenSingle.test()
        val observer2 = tokenSingle.test()

        observer1.assertValue(0).assertComplete()
        observer2.assertEmpty()
    }

    @Test
    fun acquireToken_reSubscription_acquireNewToken() {
        val bucket = TokenBucket<Int?>()
        bucket.addToken(0)
        val tokenSingle: Single<Int?> = bucket.acquireToken()
        val observer1 = tokenSingle.test()
        val observer2 = tokenSingle.test()

        bucket.addToken(1)

        observer1.assertValue(0).assertComplete()
        observer2.assertValue(1).assertComplete()
    }

    @Test
    fun acquireToken_reSubscription_acquireNextToken() {
        val bucket = TokenBucket<Int?>()
        bucket.addToken(0)
        bucket.addToken(1)
        val tokenSingle: Single<Int?> = bucket.acquireToken()

        val observer1 = tokenSingle.test()
        val observer2 = tokenSingle.test()

        observer1.assertValue(0).assertComplete()
        observer2.assertValue(1).assertComplete()
    }

    @Test
    fun acquireToken_disposed_tokenRemains() {
        val bucket = TokenBucket<Int?>()
        val observer: TestObserver<Int?> = bucket.acquireToken().test()
        observer.assertEmpty()

        observer.dispose()
        bucket.addToken(0)

        Truth.assertThat(bucket.size()).isEqualTo(1)
    }

    @Test
    @Throws(IOException::class)
    fun close_errorAfterClose() {
        val bucket = TokenBucket<Int?>()
        bucket.addToken(0)
        bucket.close()

        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        observer.assertError(
            Predicate { e: Throwable? -> e is IllegalStateException && e.message.contains("closed") })
    }

    @Test
    @Throws(IOException::class)
    fun close_errorPreviousObservers() {
        val bucket = TokenBucket<Int?>()
        val observer: TestObserver<Int?> = bucket.acquireToken().test()

        bucket.close()

        observer.assertError(
            Predicate { e: Throwable? -> e is IllegalStateException && e.message.contains("closed") })
    }
}
