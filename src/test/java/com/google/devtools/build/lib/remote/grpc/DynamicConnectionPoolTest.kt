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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.SharedConnection
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Cancellable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Predicate
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.stubbing.Answer
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests for [DynamicConnectionPool].  */
@RunWith(JUnit4::class)
class DynamicConnectionPoolTest {
    @Rule
    val mockito: MockitoRule = MockitoJUnit.rule()
    private val rxGlobalThrowable = AtomicReference<Throwable?>(null)

    @Mock
    private val connection0: Connection? = null

    @Mock
    private val connection1: Connection? = null

    @Mock
    private val connectionFactory: ConnectionFactory? = null
    private val connectionFactoryCreateTimes = AtomicInteger(0)

    @Before
    fun setUp() {
        RxJavaPlugins.setErrorHandler(Consumer { newValue: Throwable? -> rxGlobalThrowable.set(newValue) })

        Mockito.`when`<Any?>(connectionFactory!!.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val times = connectionFactoryCreateTimes.getAndIncrement()
                    if (times == 0) {
                        return@thenAnswer Single.just<Connection?>(connection0)
                    } else {
                        return@thenAnswer Single.just<Connection?>(connection1)
                    }
                })
    }

    @After
    @Throws(Throwable::class)
    fun tearDown() {
        // Make sure rxjava didn't receive global errors
        val t = rxGlobalThrowable.getAndSet(null)
        if (t != null) {
            throw t
        }
    }

    @Test
    fun create_smoke() {
        val pool = DynamicConnectionPool(connectionFactory, 1)

        val observer = pool.create()!!.test()

        observer.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        Truth.assertThat(connectionFactoryCreateTimes.get()).isEqualTo(1)
    }

    @Test
    fun create_exceedingMaxConcurrent_createNewConnection() {
        val pool = DynamicConnectionPool(connectionFactory, 1)

        val observer0 = pool.create()!!.test()
        val observer1 = pool.create()!!.test()

        observer0.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection1 })
            .assertComplete()
        Truth.assertThat(connectionFactoryCreateTimes.get()).isEqualTo(2)
    }

    @Test
    fun create_pendingConnectionCreationAndExceedingMaxConcurrent_createNewConnection() {
        val terminated = AtomicBoolean(false)
        val connectionFactory: ConnectionFactory = Mockito.mock<ConnectionFactory>(ConnectionFactory::class.java)
        Mockito.`when`<Any?>(connectionFactory.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    if (connectionFactoryCreateTimes.getAndIncrement() == 0) {
                        return@thenAnswer Single.create<Any?>(
                            SingleOnSubscribe { emitter: SingleEmitter<Any?>? ->
                                val t =
                                    Thread(
                                        Runnable {
                                            try {
                                                Thread.sleep(Int.Companion.MAX_VALUE.toLong())
                                                emitter!!.onSuccess(connection0)
                                            } catch (e: InterruptedException) {
                                                emitter!!.onError(e)
                                            }
                                            terminated.set(true)
                                        })
                                t.start()
                            })
                    } else {
                        return@thenAnswer Single.just<Connection?>(connection1)
                    }
                })
        val pool = DynamicConnectionPool(connectionFactory, 1)

        val observer0 = pool.create()!!.test()
        val observer1 = pool.create()!!.test()

        Truth.assertThat(terminated.get()).isFalse()
        observer0.assertEmpty()
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection1 })
            .assertComplete()
        Truth.assertThat(connectionFactoryCreateTimes.get()).isEqualTo(2)
    }

    @Test
    fun create_belowMaxConcurrency_shareConnections() {
        val pool = DynamicConnectionPool(connectionFactory, 2)

        val observer0 = pool.create()!!.test()
        val observer1 = pool.create()!!.test()

        observer0.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        Truth.assertThat(connectionFactoryCreateTimes.get()).isEqualTo(1)
    }

    @Test
    @Throws(IOException::class)
    fun create_afterConnectionClosed_shareConnections() {
        val pool = DynamicConnectionPool(connectionFactory, 1)
        val observer0 = pool.create()!!.test()
        observer0.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        observer0.values().get(0)!!.close()

        val observer1 = pool.create()!!.test()

        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection0 })
            .assertComplete()
        Truth.assertThat(connectionFactoryCreateTimes.get()).isEqualTo(1)
    }

    @Test
    @Throws(IOException::class)
    fun closePool_noNewConnectionAllowed() {
        val pool = DynamicConnectionPool(connectionFactory, 1)
        pool.close()

        val observer = pool.create()!!.test()

        observer
            .assertError(IllegalStateException::class.java)
            .assertError(Predicate { e: Throwable? -> e!!.message.contains("closed") })
    }

    @Test
    @Throws(IOException::class)
    fun closePool_closeUnderlyingConnection() {
        val pool = DynamicConnectionPool(connectionFactory, 1)
        val observer = pool.create()!!.test()
        observer.assertComplete()

        pool.close()

        Mockito.verify<Connection?>(connection0, Mockito.times(1)).close()
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun closePool_pendingConnectionCreation_closedError() {
        val canceled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val terminated = Semaphore(0)
        val connectionFactory: ConnectionFactory = Mockito.mock<ConnectionFactory>(ConnectionFactory::class.java)
        Mockito.`when`<Any?>(connectionFactory.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    Single.create<Any?>(
                        SingleOnSubscribe { emitter: SingleEmitter<Any?>? ->
                            val t =
                                Thread(
                                    Runnable {
                                        try {
                                            Thread.sleep(Int.Companion.MAX_VALUE.toLong())
                                            finished.set(true)
                                            emitter!!.onSuccess(connection0)
                                        } catch (ignored: InterruptedException) {
                                            /* no-op */
                                        }
                                        terminated.release()
                                    })
                            t.start()
                            emitter!!.setCancellable(Cancellable { t.interrupt() })
                        })
                        .doOnDispose(Action { canceled.set(true) })
                })
        val pool = DynamicConnectionPool(connectionFactory, 1)
        val observer = pool.create()!!.test()
        observer.assertEmpty()

        Truth.assertThat(canceled.get()).isFalse()
        pool.close()

        terminated.acquire()
        observer
            .assertError(IllegalStateException::class.java)
            .assertError(Predicate { e: Throwable? -> e!!.message.contains("closed") })
        Truth.assertThat(canceled.get()).isTrue()
        Truth.assertThat(finished.get()).isFalse()
    }
}
