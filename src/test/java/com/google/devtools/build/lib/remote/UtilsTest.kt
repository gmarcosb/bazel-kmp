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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.authandtls.CallCredentialsProvider

/** Tests for remote utility methods  */
@RunWith(JUnit4::class)
class UtilsTest {
    @org.junit.Test
    fun testGrpcAwareErrorMessage() {
        val ioError: IOException = IOException("io error")
        val wrappedGrpcError: IOException =
            IOException(
                "wrapped error", io.grpc.Status.ABORTED.withDescription("grpc error").asRuntimeException()
            )

        assertThat(Utils.grpcAwareErrorMessage(ioError,  /* verboseFailures= */false))
            .isEqualTo("io error")
        assertThat(Utils.grpcAwareErrorMessage(wrappedGrpcError,  /* verboseFailures= */false))
            .isEqualTo("ABORTED: grpc error")
    }

    @org.junit.Test
    fun testGrpcAwareErrorMessage_verboseFailures() {
        val ioError: IOException = IOException("io error")
        val wrappedGrpcError: IOException =
            IOException(
                "wrapped error", io.grpc.Status.ABORTED.withDescription("grpc error").asRuntimeException()
            )

        assertThat(
            Utils.grpcAwareErrorMessage(ioError,  /* verboseFailures= */true).replace("\r\n", "\n")
        )
            .startsWith(
                ("io error\n"
                        + "java.io.IOException: io error\n"
                        + "\tat com.google.devtools.build.lib.remote.UtilsTest.testGrpcAwareErrorMessage_verboseFailures")
            )
        assertThat(
            Utils.grpcAwareErrorMessage(wrappedGrpcError,  /* verboseFailures= */true)
                .replace("\r\n", "\n")
        )
            .startsWith(
                ("ABORTED: grpc error\n"
                        + "java.io.IOException: wrapped error\n"
                        + "\tat com.google.devtools.build.lib.remote.UtilsTest.testGrpcAwareErrorMessage_verboseFailures")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticatedAsync_unauthenticated_shouldRefresh() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        Utils.refreshIfUnauthenticatedAsync(
            {
                if (callTimes.getAndIncrement() == 0) {
                    throw StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED)
                }
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(null)
            },
            callCredentialsProvider
        )
            .get()

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticatedAsync_unauthenticatedFuture_shouldRefresh() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        Utils.refreshIfUnauthenticatedAsync(
            {
                if (callTimes.getAndIncrement() == 0) {
                    return@refreshIfUnauthenticatedAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(
                        StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED)
                    )
                }
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(null)
            },
            callCredentialsProvider
        )
            .get()

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticatedAsync_permissionDenied_shouldRefresh() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        Utils.refreshIfUnauthenticated(
            {
                if (callTimes.getAndIncrement() == 0) {
                    throw StatusRuntimeException(io.grpc.Status.PERMISSION_DENIED)
                }
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(null)
            },
            callCredentialsProvider
        )
            .get()

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticatedAsync_cantRefresh_shouldRefreshOnceAndFail() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable {
                Utils.refreshIfUnauthenticatedAsync(
                    {
                        callTimes.getAndIncrement()
                        throw StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED)
                    },
                    callCredentialsProvider
                )
                    .get()
            })

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticated_unauthenticated_shouldRefresh() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        Utils.refreshIfUnauthenticated(
            {
                if (callTimes.getAndIncrement() == 0) {
                    throw StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED)
                }
                null
            },
            callCredentialsProvider
        )

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticated_permissionDenied_shouldRefresh() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        Utils.refreshIfUnauthenticated(
            {
                if (callTimes.getAndIncrement() == 0) {
                    throw StatusRuntimeException(io.grpc.Status.PERMISSION_DENIED)
                }
                null
            },
            callCredentialsProvider
        )

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun refreshIfUnauthenticated_cantRefresh_shouldRefreshOnceAndFail() {
        val callCredentialsProvider: CallCredentialsProvider? =
            Mockito.mock<CallCredentialsProvider?>(CallCredentialsProvider::class.java)
        val callTimes: AtomicInteger = AtomicInteger()

        org.junit.Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                Utils.refreshIfUnauthenticated(
                    {
                        callTimes.getAndIncrement()
                        throw StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED)
                    },
                    callCredentialsProvider
                )
            })

        Truth.assertThat(callTimes.get()).isEqualTo(2)
        Mockito.verify<Any?>(callCredentialsProvider, Mockito.times(1)).refresh()
    }
}
