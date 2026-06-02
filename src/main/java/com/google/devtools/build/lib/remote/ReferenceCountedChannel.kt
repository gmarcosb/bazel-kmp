// Copyright 2018 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.ServerCapabilities

/**
 * A wrapper around a [DynamicConnectionPool] exposing [Channel] and a reference count.
 * When instantiated the reference count is 1. [DynamicConnectionPool.close] will be called
 * on the wrapped channel when the reference count reaches 0.
 * 
 * 
 * See [ReferenceCounted] for more information about reference counting.
 */
class ReferenceCountedChannel @kotlin.jvm.JvmOverloads constructor(
    connectionFactory: ChannelConnectionWithServerCapabilitiesFactory,
    maxConnections: Int = 0
) : io.netty.util.ReferenceCounted {
    private val dynamicConnectionPool: DynamicConnectionPool
    private val referenceCounted: io.netty.util.AbstractReferenceCounted =
        object : io.netty.util.AbstractReferenceCounted() {
            override fun deallocate() {
                try {
                    dynamicConnectionPool.close()
                } catch (e: IOException) {
                    throw java.lang.AssertionError(e.getMessage(), e)
                }
            }

            override fun touch(o: Any?): io.netty.util.ReferenceCounted {
                return this
            }
        }

    init {
        this.dynamicConnectionPool =
            DynamicConnectionPool(
                connectionFactory, connectionFactory.maxConcurrency(), maxConnections
            )
    }

    @get:Throws(IOException::class)
    val serverCapabilities: ServerCapabilities?
        get() {
            try {
                Profiler.instance().profile("getServerCapabilities").use { s ->
                    return blockingGet<ServerCapabilities?>(
                        withChannelConnection<ServerCapabilities?>(io.reactivex.rxjava3.functions.Function { obj: ChannelConnectionWithServerCapabilities? -> obj.getServerCapabilities() })
                    )
                }
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                throw IOException(e)
            }
        }

    val isShutdown: Boolean
        get() = dynamicConnectionPool.isClosed()

    /**
     * A specialized [Function] that can only throw [IOException] and [ ].
     */
    @java.lang.FunctionalInterface
    interface IOFunction<T, R> : io.reactivex.rxjava3.functions.Function<T?, R?> {
        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun apply(t: T?): R?
    }

    @io.reactivex.rxjava3.annotations.CheckReturnValue
    fun <T> withChannelFuture(
        source: IOFunction<io.grpc.Channel?, out com.google.common.util.concurrent.ListenableFuture<T?>?>
    ): com.google.common.util.concurrent.ListenableFuture<T?> {
        return RxFutures.toListenableFuture<T?>(
            withChannel<T?>(io.reactivex.rxjava3.functions.Function { channel: io.grpc.Channel? ->
                RxFutures.toSingle<T?>(
                    io.reactivex.rxjava3.functions.Supplier { source.apply(channel) },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            })
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun <T> withChannelBlocking(source: IOFunction<io.grpc.Channel?, T?>): T? {
        return blockingGet<T?>(withChannel<T?>(io.reactivex.rxjava3.functions.Function { channel: io.grpc.Channel? ->
            Single.just<T?>(
                source.apply(channel)
            )
        }))
    }

    // prevents rxjava silent possible wrap of RuntimeException and misinterpretation
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun <T> blockingGet(single: Single<T?>): T? {
        val future: com.google.common.util.concurrent.SettableFuture<T?> =
            com.google.common.util.concurrent.SettableFuture.create<T?>()
        single.subscribe(
            object : SingleObserver<T?>() {
                override fun onError(t: Throwable) {
                    future.setException(t)
                }

                override fun onSuccess(t: T?) {
                    future.set(t)
                }

                override fun onSubscribe(d: Disposable) {
                    future.addListener(
                        java.lang.Runnable {
                            if (future.isCancelled()) {
                                d.dispose()
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
            })

        try {
            return future.get()
        } catch (e: ExecutionException) {
            val cause: Throwable = e.getCause()
            com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                cause,
                java.lang.InterruptedException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(cause)
            throw java.lang.IllegalStateException("Unexpected exception type", cause)
        }
    }

    @io.reactivex.rxjava3.annotations.CheckReturnValue
    fun <T> withChannel(source: io.reactivex.rxjava3.functions.Function<io.grpc.Channel?, out SingleSource<out T?>?>): Single<T?>? {
        return withChannelConnection<T?>(io.reactivex.rxjava3.functions.Function { channelConnection: ChannelConnectionWithServerCapabilities? ->
            source.apply(
                channelConnection.getChannel()
            )
        })
    }

    private fun <T> withChannelConnection(
        source: io.reactivex.rxjava3.functions.Function<ChannelConnectionWithServerCapabilities?, out SingleSource<out T?>?>
    ): Single<T?>? {
        return dynamicConnectionPool
            .create()
            .flatMap<T?>(
                io.reactivex.rxjava3.functions.Function { sharedConnection: SharedConnection? ->
                    Single.using(
                        io.reactivex.rxjava3.functions.Supplier { sharedConnection },
                        io.reactivex.rxjava3.functions.Function { conn: SharedConnection? ->
                            val connection: ChannelConnectionWithServerCapabilities? =
                                sharedConnection.getUnderlyingConnection() as ChannelConnectionWithServerCapabilities?
                            source.apply(connection)
                        },
                        io.reactivex.rxjava3.functions.Consumer { obj: SharedConnection? -> obj.close() })
                })
    }

    override fun refCnt(): Int {
        return referenceCounted.refCnt()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun retain(): ReferenceCountedChannel {
        referenceCounted.retain()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun retain(increment: Int): ReferenceCountedChannel {
        referenceCounted.retain(increment)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun touch(): io.netty.util.ReferenceCounted {
        referenceCounted.touch()
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    override fun touch(hint: Any?): io.netty.util.ReferenceCounted {
        referenceCounted.touch(hint)
        return this
    }

    override fun release(): Boolean {
        return referenceCounted.release()
    }

    override fun release(decrement: Int): Boolean {
        return referenceCounted.release(decrement)
    }
}
