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

import com.google.common.annotations.VisibleForTesting
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.netty.channel.unix.Errors
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.functions.Function
import java.util.function.Predicate
import javax.annotation.concurrent.GuardedBy
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * A [ConnectionPool] that creates one connection using provided [ConnectionFactory] and
 * shares the connection upto `maxConcurrency`.
 * 
 * 
 * This is useful if underlying connection maintains a connection pool internally. (such as
 * `Channel` in gRPC)
 * 
 * 
 * Connections must be closed with [Connection.close] in order to be reused later.
 */
@ThreadSafe
class SharedConnectionFactory @VisibleForTesting internal constructor(
    private val factory: ConnectionFactory,
    maxConcurrency: Int,
    fatalErrorPredicate: Predicate<Throwable?>
) : ConnectionPool {
    private val tokenBucket: TokenBucket<Int?>
    private val fatalErrorPredicate: Predicate<Throwable?>

    @GuardedBy("this")
    private var connectionAsyncSubject: AsyncSubject<Connection?>? = null

    private val connectionCreationDisposable: AtomicReference<Disposable?> = AtomicReference<Disposable?>(null)

    constructor(factory: ConnectionFactory, maxConcurrency: Int) : this(
        factory,
        maxConcurrency,
        Predicate { t: Throwable? -> isFatalError(t) })

    init {
        val initialTokens: MutableList<Int?> = ArrayList<Int?>(maxConcurrency)
        for (i in 0..<maxConcurrency) {
            initialTokens.add(i)
        }
        this.tokenBucket = TokenBucket<Int?>(initialTokens)
        this.fatalErrorPredicate = fatalErrorPredicate
    }

    @Throws(IOException::class)
    override fun close() {
        tokenBucket.close()

        val d: Disposable? = connectionCreationDisposable.getAndSet(null)
        if (d != null && !d.isDisposed()) {
            d.dispose()
        }

        synchronized(this) {
            if (connectionAsyncSubject != null) {
                val connection = connectionAsyncSubject.getValue()
                if (connection != null) {
                    connection.close()
                }

                // If it still has observers, it means the subject hasn't completed. Complete it now.
                if (connectionAsyncSubject.hasObservers()) {
                    connectionAsyncSubject.onError(IllegalStateException("closed"))
                }
            }
        }
    }

    private fun createUnderlyingConnectionIfNot(): AsyncSubject<Connection?>? {
        synchronized(this) {
            if (connectionAsyncSubject == null || connectionAsyncSubject.hasThrowable()) {
                connectionAsyncSubject =
                    factory
                        .create()
                        .doOnSubscribe(Consumer { newValue: V? -> connectionCreationDisposable.set(newValue) })
                        .toObservable()
                        .subscribeWith<AsyncSubject<Connection?>>(AsyncSubject.create<Connection?>())
            }
            return connectionAsyncSubject
        }
    }

    private fun acquireConnection(): Single<out Connection?>? {
        return Single.fromObservable<Connection?>(createUnderlyingConnectionIfNot())
    }

    /**
     * Reuses the underlying [Connection] and wait for it to be released if is exceeding `maxConcurrency`.
     */
    override fun create(): Single<SharedConnection?>? {
        return tokenBucket
            .acquireToken()
            .flatMap<SharedConnection?>(
                Function { token: Int? ->
                    acquireConnection()
                        .doOnError(Consumer { ignored: Throwable? -> tokenBucket.addToken(token) })
                        .doOnDispose(Action { tokenBucket.addToken(token) })
                        .map<SharedConnection?>(
                            { conn: Connection? ->
                                SharedConnection(
                                    conn!!,  /* onClose= */
                                    Action { tokenBucket.addToken(token) },
                                    fatalErrorPredicate,  /* onFatalError= */
                                    Runnable {
                                        synchronized(this) {
                                            connectionAsyncSubject = null
                                        }
                                    })
                            })
                })
    }

    /** Returns current number of available connections.  */
    fun numAvailableConnections(): Int {
        return tokenBucket.size()
    }

    /** A [Connection] which wraps an underlying connection and is shared between consumers.  */
    class SharedConnection(
        /** Returns the underlying connection this shared connection built on  */
        val underlyingConnection: Connection,
        private val onClose: Action,
        private val fatalErrorPredicate: Predicate<Throwable?>,
        private val onFatalError: Runnable
    ) : Connection {
        override fun <ReqT, RespT> call(
            method: MethodDescriptor<ReqT?, RespT?>?, options: CallOptions?
        ): ClientCall<ReqT?, RespT?> {
            return object :
                SimpleForwardingClientCall<ReqT?, RespT?>(underlyingConnection.call<ReqT?, RespT?>(method, options)) {
                override fun start(responseListener: ClientCall.Listener<RespT?>?, headers: Metadata?) {
                    super.start(
                        object : SimpleForwardingClientCallListener<RespT?>(responseListener) {
                            override fun onClose(status: Status, trailers: Metadata?) {
                                if (fatalErrorPredicate.test(status.getCause())) {
                                    onFatalError.run()
                                }
                                super.onClose(status, trailers)
                            }
                        },
                        headers
                    )
                }
            }
        }

        @Throws(IOException::class)
        override fun close() {
            try {
                onClose.run()
            } catch (t: Throwable) {
                throw IOException(t)
            }
        }
    }

    companion object {
        private fun isFatalError(t: Throwable?): Boolean {
            // A low-level netty error indicates that the connection is fundamentally broken
            // and should not be reused for retries.
            return t is Errors.NativeIoException
        }
    }
}
