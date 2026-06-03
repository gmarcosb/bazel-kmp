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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.graph.Digraph.equals
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.functions
import com.google.devtools.build.lib.remote.grpc.ConnectionFactory.create
import com.google.devtools.build.lib.remote.grpc.DynamicConnectionPool.create
import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.create
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests for [AsyncTaskCache].  */
@RunWith(JUnit4::class)
class AsyncTaskCacheTest {
    @org.junit.Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    @org.junit.Test
    fun execute_noSubscription_noExecution() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val executed: AtomicBoolean = AtomicBoolean(false)

        cache.executeIfNot(
            "key1",
            Single.create<T?>(
                SingleOnSubscribe { emitter: SingleEmitter<T?>? ->
                    executed.set(true)
                    emitter.onSuccess("value1")
                })
        )

        Truth.assertThat(executed.get()).isFalse()
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_taskFinished_completed() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val observer: TestObserver<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
            ).test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()

        emitter.onSuccess("value1")

        observer.assertValue("value1")
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).containsExactly("key1")
    }

    @org.junit.Test
    fun execute_taskHasError_propagateError() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val observer: TestObserver<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
            ).test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        val error: Throwable = java.lang.IllegalStateException("error")

        emitter.onError(error)

        observer.assertError(error)
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_taskInProgress_noReExecution() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val executionTimes: AtomicInteger = AtomicInteger(0)
        val single: Single<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(
                    SingleOnSubscribe { emitter: SingleEmitter<T?>? ->
                        executionTimes.incrementAndGet()
                        emitterRef.set(emitter)
                    })
            )
        val ob1: TestObserver<String?> = single.test()
        ob1.assertEmpty()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        assertThat(cache.getInProgressTasks()).containsExactly("key1")
        assertThat(cache.getFinishedTasks()).isEmpty()

        val ob2: TestObserver<String?> = single.test()
        ob2.assertEmpty()
        emitter.onSuccess("value1")

        ob1.assertValue("value1")
        ob2.assertValue("value1")
        Truth.assertThat(executionTimes.get()).isEqualTo(1)
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).containsExactly("key1")
    }

    @org.junit.Test
    fun executeForcibly_taskInProgress_noReExecution() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val executionTimes: AtomicInteger = AtomicInteger(0)
        val single: Single<String?> =
            cache.execute(
                "key1",
                Single.create<T?>(
                    SingleOnSubscribe { emitter: SingleEmitter<T?>? ->
                        executionTimes.incrementAndGet()
                        emitterRef.set(emitter)
                    }),  /* force= */
                true
            )
        val ob1: TestObserver<String?> = single.test()
        ob1.assertEmpty()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        assertThat(cache.getInProgressTasks()).containsExactly("key1")
        assertThat(cache.getFinishedTasks()).isEmpty()

        val ob2: TestObserver<String?> = single.test()
        ob2.assertEmpty()
        emitter.onSuccess("value1")

        ob1.assertValue("value1")
        ob2.assertValue("value1")
        Truth.assertThat(executionTimes.get()).isEqualTo(1)
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).containsExactly("key1")
    }

    @org.junit.Test
    fun execute_taskFinished_noReExecution() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val executionTimes: AtomicInteger = AtomicInteger(0)
        val single: Single<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(
                    SingleOnSubscribe { emitter: SingleEmitter<T?>? ->
                        executionTimes.incrementAndGet()
                        emitterRef.set(emitter)
                    })
            )
        val ob1: TestObserver<String?> = single.test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        emitter.onSuccess("value1")
        ob1.assertValue("value1")
        assertThat(cache.getFinishedTasks()).containsExactly("key1")

        val ob2: TestObserver<String?> = single.test()

        ob2.assertValue("value1")
        Truth.assertThat(executionTimes.get()).isEqualTo(1)
    }

    @org.junit.Test
    fun executeForcibly_taskFinished_reExecution() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val executionTimes: AtomicInteger = AtomicInteger(0)
        val single: Single<String?> =
            cache.execute(
                "key1",
                Single.create<T?>(
                    SingleOnSubscribe { emitter: SingleEmitter<T?>? ->
                        executionTimes.incrementAndGet()
                        emitterRef.set(emitter)
                    }),  /* force= */
                true
            )
        val ob1: TestObserver<String?> = single.test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        emitter.onSuccess("value1")
        ob1.assertValue("value1")
        assertThat(cache.getFinishedTasks()).containsExactly("key1")

        val ob2: TestObserver<String?> = single.test()

        ob2.assertEmpty()
        Truth.assertThat(executionTimes.get()).isEqualTo(2)
        assertThat(cache.getInProgressTasks()).containsExactly("key1")
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_dispose_cancelled() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val observer: TestObserver<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
            ).test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        val disposed: AtomicBoolean = AtomicBoolean(false)
        emitter.setCancellable(io.reactivex.rxjava3.functions.Cancellable { disposed.set(true) })

        observer.dispose()

        Truth.assertThat(disposed.get()).isTrue()
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_disposeWhenMultipleSubscriptions_notCancelled() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val single: Single<String?> = cache.executeIfNot(
            "key1",
            Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
        )
        val ob1: TestObserver<String?> = single.test()
        val ob2: TestObserver<String?> = single.test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        val disposed: AtomicBoolean = AtomicBoolean(false)
        emitter.setCancellable(io.reactivex.rxjava3.functions.Cancellable { disposed.set(true) })

        ob1.dispose()

        ob2.assertEmpty()
        Truth.assertThat(disposed.get()).isFalse()
        assertThat(cache.getInProgressTasks()).containsExactly("key1")
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_disposeWhenMultipleSubscriptions_cancelled() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val single: Single<String?> = cache.executeIfNot(
            "key1",
            Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
        )
        val ob1: TestObserver<String?> = single.test()
        val ob2: TestObserver<String?> = single.test()
        val emitter: SingleEmitter<String?>? = emitterRef.get()
        Truth.assertThat(emitter).isNotNull()
        val disposed: AtomicBoolean = AtomicBoolean(false)
        emitter.setCancellable(io.reactivex.rxjava3.functions.Cancellable { disposed.set(true) })

        ob1.dispose()
        ob2.dispose()

        Truth.assertThat(disposed.get()).isTrue()
        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    fun execute_multipleTasks_completeOne() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef1: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val observer1: TestObserver<String?> =
            cache.executeIfNot(
                "key1",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef1.set(newValue) })
            ).test()
        val emitter1: SingleEmitter<String?>? = emitterRef1.get()
        Truth.assertThat(emitter1).isNotNull()
        val emitterRef2: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val observer2: TestObserver<String?> =
            cache.executeIfNot(
                "key2",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef2.set(newValue) })
            ).test()
        val emitter2: SingleEmitter<String?>? = emitterRef1.get()
        Truth.assertThat(emitter2).isNotNull()

        emitter1.onSuccess("value1")

        observer1.assertValue("value1")
        observer2.assertEmpty()
        assertThat(cache.getInProgressTasks()).containsExactly("key2")
        assertThat(cache.getFinishedTasks()).containsExactly("key1")
    }

    private fun newTask(executorService: ExecutorService): Completable {
        return RxFutures.toCompletable(
            {
                val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                    com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                executorService.execute(
                    java.lang.Runnable {
                        try {
                            java.lang.Thread.sleep((java.lang.Math.random() * 1000).toLong())
                            future.set(null)
                        } catch (e: java.lang.InterruptedException) {
                            future.setException(IOException(e))
                        }
                    })
                future
            },
            executorService
        )
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun execute_executeAndDisposeLoop_noErrors() {
        val taskCount = 1000
        val maxKey = 20
        val random: Random = Random()
        val executorService: ExecutorService = Executors.newFixedThreadPool(taskCount)
        val cache: AsyncTaskCache.NoResult<String?> = AsyncTaskCache.NoResult.create()
        val error: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val semaphore: Semaphore = Semaphore(0)

        for (i in 0..<taskCount) {
            executorService.execute(
                java.lang.Runnable {
                    try {
                        val task: Completable =
                            cache.execute("key" + random.nextInt(maxKey), newTask(executorService), true)
                        val observer: TestObserver<java.lang.Void?> = task.test()
                        observer.assertNoErrors()
                        if (random.nextBoolean()) {
                            observer.dispose()
                        } else {
                            observer.await()
                            observer.assertNoErrors()
                        }
                    } catch (e: Throwable) {
                        if (e is java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        error.set(e)
                    } finally {
                        semaphore.release()
                    }
                })
        }
        semaphore.acquire(taskCount)

        if (error.get() != null) {
            throw error.get()
        }
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun execute_executeWithFutureAndCancelLoop_noErrors() {
        val taskCount = 1000
        val maxKey = 20
        val random: Random = Random()
        val executorService: ExecutorService = Executors.newFixedThreadPool(taskCount)
        val cache: AsyncTaskCache.NoResult<String?> = AsyncTaskCache.NoResult.create()
        val error: AtomicReference<Throwable?> = AtomicReference<Throwable?>(null)
        val semaphore: Semaphore = Semaphore(0)

        for (i in 0..<taskCount) {
            executorService.execute(
                java.lang.Runnable {
                    try {
                        val download: Completable? =
                            cache.execute("key" + random.nextInt(maxKey), newTask(executorService), true)
                        val future: java.util.concurrent.Future<java.lang.Void?> =
                            RxFutures.toListenableFuture(download)
                        if (!future.isDone() && random.nextBoolean()) {
                            future.cancel(true)
                        } else {
                            future.get()
                        }
                    } catch (e: Throwable) {
                        if (e is java.lang.InterruptedException) {
                            java.lang.Thread.currentThread().interrupt()
                        }
                        error.set(e)
                    } finally {
                        semaphore.release()
                    }
                })
        }
        semaphore.acquire(taskCount)

        if (error.get() != null) {
            throw error.get()
        }
    }

    @org.junit.Test
    fun execute_pendingShutdown_getCancellationError() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        cache
            .executeIfNot(
                "key1",
                Single.create<T?>(
                    SingleOnSubscribe { emitter: SingleEmitter<T?>? -> })
            )
            .test()
            .assertNotComplete()
        cache.shutdown()
        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isFalse()

        val ob: TestObserver<String?> = cache.executeIfNot("key2", Single.just<T?>("value2")).test()

        ob.assertError(io.reactivex.rxjava3.functions.Predicate { e: Throwable? -> e is CancellationException })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun execute_afterShutdown_getCancellationError() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        cache.shutdown()
        cache.awaitTermination()

        val ob: TestObserver<String?> = cache.executeIfNot("key", Single.just<T?>("value")).test()

        ob.assertError(io.reactivex.rxjava3.functions.Predicate { e: Throwable? -> e is CancellationException })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun shutdownNow_cancelInProgressTasks() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val ob: TestObserver<String?> =
            cache
                .executeIfNot(
                    "key",
                    Single.create<T?>(
                        SingleOnSubscribe { emitter: SingleEmitter<T?>? -> })
                )
                .test()
        cache.shutdown()
        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isFalse()
        ob.assertNotComplete()

        cache.shutdownNow()
        cache.awaitTermination()

        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isTrue()
        ob.assertError(io.reactivex.rxjava3.functions.Predicate { e: Throwable? -> e is CancellationException })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination_pendingShutdown_completeAfterTaskFinished() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        val emitterRef: AtomicReference<SingleEmitter<String?>?> = AtomicReference<SingleEmitter<String?>?>(null)
        val ob: TestObserver<String?> =
            cache.executeIfNot(
                "key",
                Single.create<T?>(SingleOnSubscribe { newValue: SingleEmitter<T?>? -> emitterRef.set(newValue) })
            ).test().assertNotComplete()
        Truth.assertThat(emitterRef.get()).isNotNull()
        cache.shutdown()
        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isFalse()

        emitterRef.get().onSuccess("value")
        cache.awaitTermination()

        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isTrue()
        ob.assertValue("value")

        assertThat(cache.getInProgressTasks()).isEmpty()
        assertThat(cache.getFinishedTasks()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination_afterShutdown_complete() {
        val cache: AsyncTaskCache<String?, String?> = AsyncTaskCache.create()
        cache.shutdownNow()
        cache.awaitTermination()

        cache.awaitTermination()

        assertThat(cache.isShutdown()).isTrue()
        assertThat(cache.isTerminated()).isTrue()
    }
}
