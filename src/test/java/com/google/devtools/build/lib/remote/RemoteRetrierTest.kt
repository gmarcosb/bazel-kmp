// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.remote.RemoteRetrier.ExponentialBackoff

/**
 * Tests for [RemoteRetrier].
 */
@RunWith(JUnit4::class)
class RemoteRetrierTest {
    internal interface Foo {
        fun foo(): String?
    }

    private var fooMock: Foo? = null
    private var retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService? = null

    @Before
    fun setUp() {
        retryService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
        fooMock = Mockito.mock<Foo>(com.google.devtools.build.lib.remote.RemoteRetrierTest.Foo::class.java)
    }

    @org.junit.After
    @Throws(java.lang.InterruptedException::class)
    fun tearDown() {
        retryService.shutdownNow()
        retryService.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExponentialBackoff() {
        val e: java.lang.Exception = java.lang.Exception()
        val backoff: Retrier.Backoff =
            ExponentialBackoff(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10), 2, 0, 6)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(1000)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(2000)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(4000)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(8000)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(10000)
        assertThat(backoff.nextDelayMillis(e)).isEqualTo(10000)
        assertThat(backoff.nextDelayMillis(e)).isLessThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExponentialBackoffJittered() {
        val e: java.lang.Exception = java.lang.Exception()
        val backoff: Retrier.Backoff =
            ExponentialBackoff(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(10), 2, 0.1, 6)
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(900L, 1100L))
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(1800L, 2200L))
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(3600L, 4400L))
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(7200L, 8800L))
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(9000L, 11000L))
        assertThat(backoff.nextDelayMillis(e)).isIn(com.google.common.collect.Range.closedOpen<Long?>(9000L, 11000L))
        assertThat(backoff.nextDelayMillis(e)).isLessThan(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoRetries() {
        val options: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        options.remoteMaxRetryAttempts = 0

        val retrier: RemoteRetrier =
            Mockito.spy(
                RemoteRetrier(
                    options, { e -> Result.TRANSIENT_FAILURE }, retryService, Retrier.ALLOW_ALL_CALLS
                )
            )
        Mockito.`when`<String?>(fooMock!!.foo())
            .thenReturn("bla")
            .thenThrow(io.grpc.Status.Code.UNKNOWN.toStatus().asRuntimeException())
        assertThat(retrier.execute({ fooMock!!.foo() })).isEqualTo("bla")
        org.junit.Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            org.junit.function.ThrowingRunnable { retrier.execute({ fooMock!!.foo() }) })
        Mockito.verify<Foo?>(fooMock, Mockito.times(2)).foo()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonRetriableError() {
        val s: java.util.function.Supplier<Backoff?> =
            java.util.function.Supplier {
                ExponentialBackoff(
                    java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(10),
                    2.0,
                    0.0,
                    2
                )
            }
        val retrier: RemoteRetrier =
            Mockito.spy(
                RemoteRetrier(
                    s,
                    { e -> Result.PERMANENT_FAILURE },
                    retryService,
                    Retrier.ALLOW_ALL_CALLS,
                    Mockito.< T > mock < T ? > (Sleeper::class.java)
                )
            )
        Mockito.`when`<String?>(fooMock!!.foo()).thenThrow(io.grpc.Status.Code.UNKNOWN.toStatus().asRuntimeException())
        org.junit.Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            org.junit.function.ThrowingRunnable { retrier.execute({ fooMock!!.foo() }) })
        Mockito.verify<Foo?>(fooMock, Mockito.times(1)).foo()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatedRetriesReset() {
        val s: java.util.function.Supplier<Backoff?> =
            java.util.function.Supplier {
                ExponentialBackoff(
                    java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(10),
                    2.0,
                    0.0,
                    2
                )
            }
        val sleeper: Sleeper? = Mockito.mock<Sleeper?>(Sleeper::class.java)
        val retrier: RemoteRetrier =
            Mockito.spy(
                RemoteRetrier(
                    s,
                    { e -> Result.TRANSIENT_FAILURE },
                    retryService,
                    Retrier.ALLOW_ALL_CALLS,
                    sleeper
                )
            )

        Mockito.`when`<String?>(fooMock!!.foo()).thenThrow(io.grpc.Status.Code.UNKNOWN.toStatus().asRuntimeException())
        org.junit.Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            org.junit.function.ThrowingRunnable { retrier.execute({ fooMock!!.foo() }) })
        org.junit.Assert.assertThrows<StatusRuntimeException?>(
            StatusRuntimeException::class.java,
            org.junit.function.ThrowingRunnable { retrier.execute({ fooMock!!.foo() }) })
        Mockito.verify<Any?>(sleeper, Mockito.times(2)).sleep(1000)
        Mockito.verify<Any?>(sleeper, Mockito.times(2)).sleep(2000)
        Mockito.verify<Foo?>(fooMock, Mockito.times(6)).foo()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterruptedExceptionIsPassedThrough() {
        val thrown: java.lang.InterruptedException = java.lang.InterruptedException()

        val options: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        options.remoteMaxRetryAttempts = 0
        val retrier: RemoteRetrier =
            RemoteRetrier(
                options, { e -> Result.TRANSIENT_FAILURE }, retryService, Retrier.ALLOW_ALL_CALLS
            )
        val expected: java.lang.InterruptedException? =
            org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                java.lang.InterruptedException::class.java,
                org.junit.function.ThrowingRunnable {
                    retrier.execute(
                        {
                            throw thrown
                        })
                })
        Truth.assertThat(expected).isSameInstanceAs(thrown)
    }
}
