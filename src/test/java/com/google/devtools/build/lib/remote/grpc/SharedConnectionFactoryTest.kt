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
import com.google.devtools.build.lib.remote.grpc.Connection.call
import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.SharedConnection
import com.google.devtools.build.lib.remote.util.RxNoGlobalErrorsRule
import io.grpc.*
import io.grpc.MethodDescriptor.Marshaller
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Cancellable
import io.reactivex.rxjava3.functions.Predicate
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.stubbing.Answer
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests for [SharedConnectionFactory].  */
@RunWith(JUnit4::class)
class SharedConnectionFactoryTest {
    @Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Rule
    val rxNoGlobalErrorsRule: RxNoGlobalErrorsRule = RxNoGlobalErrorsRule()

    @Mock
    private val connection: Connection? = null

    @Mock
    private val connectionFactory: ConnectionFactory? = null

    @Before
    fun setUp() {
        Mockito.`when`<Any?>(connectionFactory!!.create())
            .thenAnswer(Answer { invocation: InvocationOnMock? -> Single.just<Connection?>(connection) })
    }

    @Test
    fun create_smoke() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)

        val observer: TestObserver<SharedConnection?> = factory.create().test()

        observer.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()
        Mockito.verify<ConnectionFactory?>(connectionFactory, Mockito.times(1)).create()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
    }

    @Test
    fun create_noConnectionCreationBeforeSubscription() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)

        factory.create()

        Mockito.verify<ConnectionFactory?>(connectionFactory, Mockito.times(0)).create()
    }

    @Test
    fun create_exceedingMaxConcurrency_waiting() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        val observer1: TestObserver<SharedConnection?> = factory.create().test()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()

        val observer2: TestObserver<SharedConnection?> = factory.create().test()
        observer2.assertEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun create_afterConnectionClosed_shareConnections() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        val observer1: TestObserver<SharedConnection?> = factory.create().test()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()
        val observer2: TestObserver<SharedConnection?> = factory.create().test()

        observer1.values().get(0)!!.close()

        observer2.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
    }

    @Test
    fun create_belowMaxConcurrency_shareConnections() {
        val factory = SharedConnectionFactory(connectionFactory!!, 2)

        val observer1: TestObserver<SharedConnection?> = factory.create().test()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
        observer1.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()

        val observer2: TestObserver<SharedConnection?> = factory.create().test()
        observer2.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
    }

    @Test
    @Throws(InterruptedException::class)
    fun create_concurrentCreate_shareConnections() {
        val maxConcurrency = 10
        val factory =
            SharedConnectionFactory(connectionFactory!!, maxConcurrency)
        val error = AtomicReference<Throwable?>(null)
        val runnable =
            Runnable {
                try {
                    val observer: TestObserver<SharedConnection?> = factory.create().test()

                    observer
                        .assertNoErrors()
                        .assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
                        .assertComplete()
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        val threads: Array<Thread> = arrayOfNulls<Thread>(maxConcurrency)
        for (i in threads.indices) {
            threads[i] = Thread(runnable)
        }

        for (thread in threads) {
            thread.start()
        }
        for (thread in threads) {
            thread.join()
        }

        Truth.assertThat(error.get()).isNull()
        Mockito.verify<ConnectionFactory?>(connectionFactory, Mockito.times(1)).create()
    }

    private class FatalIOException : IOException("fatal")

    @Test
    @Throws(IOException::class)
    fun create_belowMaxConcurrency_fatalErrorPreventsReuse() {
        val brokenConnection: Connection =
            object : Connection {
                override fun <ReqT, RespT> call(
                    method: MethodDescriptor<ReqT?, RespT?>?, options: CallOptions?
                ): ClientCall<ReqT?, RespT?>? {
                    val call: ClientCall<*, *>? = Mockito.mock<ClientCall<*, *>?>(ClientCall::class.java)
                    Mockito.doAnswer(
                        Answer { invocationOnMock: InvocationOnMock? ->
                            (invocationOnMock!!.getArgument<Any?>(0) as ClientCall.Listener<*>)
                                .onClose(Status.fromThrowable(FatalIOException()), Metadata())
                            null
                        })
                        .`when`<ClientCall<*, *>?>(call)
                        .start(ArgumentMatchers.any<ClientCall.Listener<*>?>(), ArgumentMatchers.any<Metadata?>())
                    return call
                }

                override fun close() {}
            }
        val newConnection: Connection? = Mockito.mock<Connection?>(Connection::class.java)
        val connectionsToCreate: Queue<Connection?> =
            ArrayDeque<Connection?>(ImmutableList.of<Connection?>(brokenConnection, newConnection))
        Mockito.`when`<Any?>(connectionFactory!!.create())
            .thenAnswer(Answer { invocation: InvocationOnMock? -> Single.just<Connection?>(connectionsToCreate.remove()) })

        val factory =
            SharedConnectionFactory(
                connectionFactory,
                2,
                java.util.function.Predicate { t: Throwable? -> t is FatalIOException })

        val observer1: TestObserver<SharedConnection> = factory.create().test()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
        observer1
            .assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === brokenConnection })
            .assertComplete()

        // Submit a call on the first connection and have it fail.
        val nullMarshaller: Marshaller<ByteArray?> =
            object : Marshaller<ByteArray?> {
                override fun stream(bytes: ByteArray?): InputStream? {
                    return null
                }

                override fun parse(inputStream: InputStream?): ByteArray? {
                    return null
                }
            }
        observer1.values().getFirst().use { firstConnection ->
            val call: ClientCall<ByteArray?, ByteArray?>? =
                firstConnection.call<ByteArray?, ByteArray?>(
                    MethodDescriptor.newBuilder<ByteArray?, ByteArray?>(nullMarshaller, nullMarshaller)
                        .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                        .setFullMethodName("testMethod")
                        .build(),
                    CallOptions.DEFAULT
                )
            val listener: ClientCall.Listener<ByteArray?> = object : ClientCall.Listener<ByteArray?>() {}
            call!!.start(listener, Metadata())
            listener.onClose(Status.fromThrowable(FatalIOException()), Metadata())
        }
        // Validate that the connection is not reused.
        val observer2: TestObserver<SharedConnection?> = factory.create().test()
        observer2.assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === newConnection })
            .assertComplete()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
    }

    @Test
    fun create_afterLastFailed_success() {
        val times = AtomicInteger(0)
        val connectionFactory: ConnectionFactory = Mockito.mock<ConnectionFactory>(ConnectionFactory::class.java)
        Mockito.`when`<Any?>(connectionFactory.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    if (times.getAndIncrement() == 0) {
                        return@thenAnswer Single.error<Any?>(IllegalStateException("error"))
                    }
                    Single.just<Connection?>(connection)
                })
        val factory = SharedConnectionFactory(connectionFactory, 1)
        val connectionSingle: Single<SharedConnection?> = factory.create()

        connectionSingle
            .test()
            .assertError(IllegalStateException::class.java)
            .assertError(Predicate { e: Throwable? -> e!!.message.contains("error") })
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
        connectionSingle
            .test()
            .assertValue(Predicate { conn: SharedConnection? -> conn!!.underlyingConnection === connection })
            .assertComplete()

        Truth.assertThat(times.get()).isEqualTo(2)
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)
    }

    @Test
    @Throws(InterruptedException::class)
    fun create_disposeWhenWaitingForConnectionCreation_doNotCancelCreation() {
        val canceled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val disposed = Semaphore(0)
        val terminated = Semaphore(0)
        val connectionFactory: ConnectionFactory = Mockito.mock<ConnectionFactory>(ConnectionFactory::class.java)
        Mockito.`when`<Any?>(connectionFactory.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    Single.create<Any?>(
                        SingleOnSubscribe { emitter: SingleEmitter<Any?>? ->
                            Thread(
                                Runnable {
                                    try {
                                        disposed.acquire()
                                        finished.set(true)
                                        emitter!!.onSuccess(connection)
                                    } catch (e: InterruptedException) {
                                        emitter!!.onError(e)
                                    }
                                    terminated.release()
                                })
                                .start()
                        })
                        .doOnDispose(Action { canceled.set(true) })
                })
        val factory = SharedConnectionFactory(connectionFactory, 1)
        val observer: TestObserver<SharedConnection?> = factory.create().test()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)

        observer.assertEmpty().dispose()
        disposed.release()

        terminated.acquire()
        Truth.assertThat(canceled.get()).isFalse()
        Truth.assertThat(finished.get()).isTrue()
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
    }

    @Test
    @Throws(InterruptedException::class)
    fun create_interrupt_terminate() {
        val finished = AtomicBoolean(false)
        val interrupted = AtomicBoolean(true)
        val threadTerminatedSemaphore = Semaphore(0)
        val connectionCreationSemaphore = Semaphore(0)
        val connectionFactory: ConnectionFactory = Mockito.mock<ConnectionFactory>(ConnectionFactory::class.java)
        Mockito.`when`<Any?>(connectionFactory.create())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    Single.create<Any?>(
                        SingleOnSubscribe { emitter: SingleEmitter<Any?>? ->
                            Thread(
                                Runnable {
                                    try {
                                        Thread.sleep(Int.Companion.MAX_VALUE.toLong())
                                        finished.set(true)
                                        emitter!!.onSuccess(connectionFactory)
                                    } catch (e: InterruptedException) {
                                        emitter!!.onError(e)
                                    }
                                })
                                .start()
                        })
                })
        val factory = SharedConnectionFactory(connectionFactory, 2)
        factory.create().test().assertEmpty()
        val t =
            Thread(
                Runnable {
                    try {
                        val observer: TestObserver<SharedConnection?> = factory.create().test()
                        connectionCreationSemaphore.release()
                        observer.await()
                    } catch (e: InterruptedException) {
                        interrupted.set(true)
                    }
                    threadTerminatedSemaphore.release()
                })
        t.start()

        connectionCreationSemaphore.acquire()
        t.interrupt()
        threadTerminatedSemaphore.acquire()

        Truth.assertThat(finished.get()).isFalse()
        Truth.assertThat(interrupted.get()).isTrue()
    }

    @Test
    @Throws(IOException::class)
    fun closeConnection_connectionBecomeAvailable() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        val observer: TestObserver<SharedConnection> = factory.create().test()
        observer.assertComplete()
        val conn = observer.values().get(0)
        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(0)

        conn.close()

        Truth.assertThat(factory.numAvailableConnections()).isEqualTo(1)
        Mockito.verify<Connection?>(connection, Mockito.times(0)).close()
    }

    @Test
    @Throws(IOException::class)
    fun closeFactory_closeUnderlyingConnection() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        val observer: TestObserver<SharedConnection?> = factory.create().test()
        observer.assertComplete()

        factory.close()

        Mockito.verify<Connection?>(connection, Mockito.times(1)).close()
    }

    @Test
    @Throws(IOException::class)
    fun closeFactory_noNewConnectionAllowed() {
        val factory = SharedConnectionFactory(connectionFactory!!, 1)
        factory.close()

        val observer: TestObserver<SharedConnection?> = factory.create().test()

        observer
            .assertError(IllegalStateException::class.java)
            .assertError(Predicate { e: Throwable? -> e!!.message.contains("closed") })
    }

    @Test
    @Throws(IOException::class, InterruptedException::class)
    fun closeFactory_pendingConnectionCreation_closedError() {
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
                                            emitter!!.onSuccess(connection)
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
        val factory = SharedConnectionFactory(connectionFactory, 1)
        val observer: TestObserver<SharedConnection?> = factory.create().test()
        observer.assertEmpty()

        Truth.assertThat(canceled.get()).isFalse()
        factory.close()

        terminated.acquire()
        observer
            .assertError(IllegalStateException::class.java)
            .assertError(Predicate { e: Throwable? -> e!!.message.contains("closed") })
        Truth.assertThat(canceled.get()).isTrue()
        Truth.assertThat(finished.get()).isFalse()
    }
}
