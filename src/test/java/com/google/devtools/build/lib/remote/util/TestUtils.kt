// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase

/** Test utilities  */
object TestUtils {
    fun newRemoteRetrier(
        backoff: java.util.function.Supplier<Backoff?>?,
        resultClassifier: ResultClassifier,
        retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService
    ): RemoteRetrier {
        val zeroDelayRetryScheduler =
            ZeroDelayListeningScheduledExecutorService(retryScheduler)
        return RemoteRetrier(
            backoff,
            { e ->
                if (io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.CANCELLED)
                    Result.SUCCESS
                else
                    resultClassifier.test(e)
            },
            zeroDelayRetryScheduler,
            Retrier.ALLOW_ALL_CALLS,
            { millis -> })
    }

    fun newNoErrorByteStreamService(blob: ByteArray): ByteStreamImplBase {
        return object : ByteStreamImplBase() {
            public override fun write(streamObserver: StreamObserver<WriteResponse?>): StreamObserver<WriteRequest?> {
                return object : StreamObserver<WriteRequest?> {
                    var receivedData: ByteArray = ByteArray(blob.size)
                    var nextOffset: Long = 0

                    override fun onNext(writeRequest: WriteRequest) {
                        if (nextOffset == 0L) {
                            assertThat(writeRequest.getResourceName()).isNotEmpty()
                            assertThat(writeRequest.getResourceName()).endsWith(java.lang.String.valueOf(blob.size))
                        } else {
                            assertThat(writeRequest.getResourceName()).isEmpty()
                        }

                        assertThat(writeRequest.getWriteOffset()).isEqualTo(nextOffset)

                        val data: ByteString = writeRequest.getData()

                        java.lang.System.arraycopy(data.toByteArray(), 0, receivedData, nextOffset.toInt(), data.size())

                        nextOffset += data.size().toLong()
                        val lastWrite = blob.size.toLong() == nextOffset
                        assertThat(writeRequest.getFinishWrite()).isEqualTo(lastWrite)
                    }

                    override fun onError(throwable: Throwable?) {
                        org.junit.Assert.fail("onError should never be called.")
                    }

                    override fun onCompleted() {
                        Truth.assertThat(nextOffset).isEqualTo(blob.size)
                        Truth.assertThat(receivedData).isEqualTo(blob)

                        val response: WriteResponse? =
                            WriteResponse.newBuilder().setCommittedSize(nextOffset).build()
                        streamObserver.onNext(response)
                        streamObserver.onCompleted()
                    }
                }
            }
        }
    }

    /**
     * Wraps around a [ListeningScheduledExecutorService] and schedules all tasks with zero
     * delay.
     */
    private class ZeroDelayListeningScheduledExecutorService
        (delegate: com.google.common.util.concurrent.ListeningScheduledExecutorService) :
        com.google.common.util.concurrent.ListeningScheduledExecutorService {
        private val delegate: com.google.common.util.concurrent.ListeningScheduledExecutorService

        init {
            this.delegate = delegate
        }

        override fun schedule(
            runnable: java.lang.Runnable,
            l: Long,
            timeUnit: TimeUnit
        ): com.google.common.util.concurrent.ListenableScheduledFuture<*> {
            return delegate.schedule(runnable, 0, timeUnit)
        }

        override fun <V> schedule(
            callable: java.util.concurrent.Callable<V?>, l: Long, timeUnit: TimeUnit
        ): com.google.common.util.concurrent.ListenableScheduledFuture<V?> {
            return delegate.schedule<V?>(callable, 0, timeUnit)
        }

        override fun scheduleAtFixedRate(
            runnable: java.lang.Runnable, l: Long, l1: Long, timeUnit: TimeUnit
        ): com.google.common.util.concurrent.ListenableScheduledFuture<*> {
            return delegate.scheduleAtFixedRate(runnable, 0, 0, timeUnit)
        }

        override fun scheduleWithFixedDelay(
            runnable: java.lang.Runnable, l: Long, l1: Long, timeUnit: TimeUnit
        ): com.google.common.util.concurrent.ListenableScheduledFuture<*> {
            return delegate.scheduleWithFixedDelay(runnable, 0, 0, timeUnit)
        }

        override fun shutdown() {
            delegate.shutdown()
        }

        override fun shutdownNow(): MutableList<java.lang.Runnable?>? {
            return delegate.shutdownNow()
        }

        val isShutdown: Boolean
            get() = delegate.isShutdown()

        val isTerminated: Boolean
            get() = delegate.isTerminated()

        @Throws(java.lang.InterruptedException::class)
        override fun awaitTermination(timeout: Long, unit: TimeUnit?): Boolean {
            return delegate.awaitTermination(timeout, unit)
        }

        override fun <T> submit(callable: java.util.concurrent.Callable<T?>): com.google.common.util.concurrent.ListenableFuture<T?> {
            return delegate.submit<T?>(callable)
        }

        override fun submit(runnable: java.lang.Runnable): com.google.common.util.concurrent.ListenableFuture<*> {
            return delegate.submit(runnable)
        }

        override fun <T> submit(
            runnable: java.lang.Runnable,
            t: T?
        ): com.google.common.util.concurrent.ListenableFuture<T?> {
            return delegate.submit<T?>(runnable, t)
        }

        @Throws(java.lang.InterruptedException::class)
        override fun <T> invokeAll(tasks: MutableCollection<out java.util.concurrent.Callable<T?>?>): MutableList<java.util.concurrent.Future<T?>?> {
            return delegate.invokeAll<T?>(tasks)
        }

        @Throws(java.lang.InterruptedException::class)
        override fun <T> invokeAll(
            tasks: MutableCollection<out java.util.concurrent.Callable<T?>?>, timeout: Long, unit: TimeUnit
        ): MutableList<java.util.concurrent.Future<T?>?> {
            return delegate.invokeAll<T?>(tasks, timeout, unit)
        }

        @Throws(java.lang.InterruptedException::class, ExecutionException::class)
        override fun <T> invokeAny(tasks: MutableCollection<out java.util.concurrent.Callable<T?>?>?): T? {
            return delegate.invokeAny<T?>(tasks)
        }

        @Throws(
            java.lang.InterruptedException::class,
            ExecutionException::class,
            java.util.concurrent.TimeoutException::class
        )
        override fun <T> invokeAny(
            tasks: MutableCollection<out java.util.concurrent.Callable<T?>?>?,
            timeout: Long,
            unit: TimeUnit?
        ): T? {
            return delegate.invokeAny<T?>(tasks, timeout, unit)
        }

        override fun execute(command: java.lang.Runnable?) {
            delegate.execute(command)
        }
    }
}
