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
package com.google.devtools.build.lib.remote.util

import com.google.devtools.build.lib.remote.util.RxFutures.toCompletable

/** Tests for [RxFutures].  */
@RunWith(JUnit4::class)
class RxFuturesTest {
    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    @org.junit.Test
    fun toCompletable_noSubscription_noExecution() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val executed: AtomicBoolean = AtomicBoolean(false)

        toCompletable(
            {
                executed.set(true)
                future
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )

        Truth.assertThat(executed.get()).isFalse()
    }

    @org.junit.Test
    fun toCompletable_futureOnSuccess_completableOnComplete() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        val observer: TestObserver<java.lang.Void?> = completable.test()
        observer.assertEmpty()
        future.set(null)

        observer.assertComplete()
    }

    @org.junit.Test
    fun toCompletable_futureOnError_completableOnError() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        val observer: TestObserver<java.lang.Void?> = completable.test()
        observer.assertEmpty()
        val error: Throwable = java.lang.IllegalStateException("error")
        future.setException(error)

        observer.assertError(error)
    }

    @org.junit.Test
    fun toCompletable_futureOnSuccessBeforeSubscription_completableOnComplete() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        future.set(null)
        val observer: TestObserver<java.lang.Void?> = completable.test()

        observer.assertComplete()
    }

    @org.junit.Test
    fun toCompletable_futureOnErrorBeforeSubscription_completableOnError() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        val error: Throwable = java.lang.IllegalStateException("error")
        future.setException(error)
        val observer: TestObserver<java.lang.Void?> = completable.test()

        observer.assertError(error)
    }

    @org.junit.Test
    fun toCompletable_futureCancelledBeforeSubscription_completableOnError() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        future.cancel(true)
        val observer: TestObserver<java.lang.Void?> = completable.test()

        observer.assertError(CancellationException::class.java)
    }

    @org.junit.Test
    fun toCompletable_futureCancelled_completableOnError() {
        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        val observer: TestObserver<java.lang.Void?> = completable.test()
        observer.assertNotComplete()
        future.cancel(true)

        observer.assertError(CancellationException::class.java)
    }

    @org.junit.Test
    fun toCompletable_disposeCompletable_cancelFuture() {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())

        val observer: TestObserver<java.lang.Void?> = completable.test()
        observer.assertEmpty()
        observer.dispose()

        Truth.assertThat(future.isCancelled()).isTrue()
    }

    @org.junit.Test
    fun toCompletable_multipleSubscriptions_error() {
        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.Futures.immediateVoidFuture()
        val completable: Completable =
            toCompletable({ future }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        completable.test().assertComplete()

        val observer: TestObserver<java.lang.Void?> = completable.test()

        observer.assertError(java.lang.IllegalStateException::class.java)
    }

    @org.junit.Test
    fun toListenableFutureFromCompletable_noEvents_waiting() {
        val setup = CompletableToListenableFutureSetup.Companion.create()

        Truth.assertThat(setup.getEmitter()).isNotNull()
        Truth.assertThat(setup.getFuture().isDone()).isFalse()
        Truth.assertThat(setup.getFuture().isCancelled()).isFalse()
    }

    @org.junit.Test
    fun toListenableFutureFromCompletable_completableOnComplete_futureOnSuccess() {
        val setup = CompletableToListenableFutureSetup.Companion.create()

        setup.getEmitter().onComplete()

        Truth.assertThat(setup.isSuccess).isTrue()
        Truth.assertThat(setup.failure).isNull()
    }

    @org.junit.Test
    fun toListenableFutureFromCompletable_completableOnError_futureOnFailure() {
        val setup = CompletableToListenableFutureSetup.Companion.create()

        val error: Throwable = java.lang.IllegalStateException("error")
        setup.getEmitter().onError(error)

        Truth.assertThat(setup.isSuccess).isFalse()
        Truth.assertThat(setup.failure).isEqualTo(error)
    }

    @org.junit.Test
    fun toListenableFutureFromCompletable_cancelled() {
        val setup = CompletableToListenableFutureSetup.Companion.create()

        setup.getFuture().cancel(true)

        Truth.assertThat(setup.isSuccess).isFalse()
        Truth.assertThat(setup.failure).isInstanceOf(CancellationException::class.java)
        Truth.assertThat(setup.isDisposed).isTrue()
    }

    @org.junit.Test
    fun toListenableFutureFromCompletable_sourceFutureCancelled_cancelFuture() {
        val source: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            toListenableFuture(
                toCompletable(
                    { source },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            )

        source.cancel(true)

        Truth.assertThat(future.isCancelled()).isTrue()
    }

    private class CompletableToListenableFutureSetup {
        private val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>

        private var emitter: CompletableEmitter? = null
        var isDisposed: Boolean = false
            private set
        var isSuccess: Boolean = false
            private set
        var failure: Throwable? = null
            private set

        init {
            val completable: Completable? =
                Completable.create(CompletableOnSubscribe { emitter: CompletableEmitter? -> this.emitter = emitter })
                    .doOnDispose(io.reactivex.rxjava3.functions.Action { this.isDisposed = true })
            future = toListenableFuture(completable)
            com.google.common.util.concurrent.Futures.addCallback<java.lang.Void?>(
                future,
                object : com.google.common.util.concurrent.FutureCallback<java.lang.Void?> {
                    override fun onSuccess(result: java.lang.Void?) {
                        this.isSuccess = true
                    }

                    override fun onFailure(t: Throwable) {
                        failure = t
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun getEmitter(): CompletableEmitter? {
            return emitter
        }

        fun getFuture(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            return future
        }

        companion object {
            fun create(): CompletableToListenableFutureSetup {
                return CompletableToListenableFutureSetup()
            }
        }
    }
}
