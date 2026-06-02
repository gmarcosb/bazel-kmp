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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.DigestFunction

/**
 * A [ChannelConnectionFactory] which creates [ChannelConnection] using [ ].
 */
class GoogleChannelConnectionFactory
    (
    channelFactory: com.google.devtools.build.lib.remote.ChannelFactory,
    target: String?,
    proxy: String?,
    remoteOptions: RemoteOptions?,
    options: AuthAndTLSOptions?,
    interceptors: MutableList<ClientInterceptor?>?,
    maxConcurrency: Int,
    verboseFailures: Boolean,
    reporter: Reporter,
    remoteServerCapabilities: RemoteServerCapabilities?,
    digestFunction: Value?,
    requirement: ServerCapabilitiesRequirement?
) : ChannelConnectionWithServerCapabilitiesFactory {
    private val getAndVerifyServerCapabilitiesStarted: AtomicBoolean = AtomicBoolean(false)
    private val serverCapabilities: com.google.common.util.concurrent.SettableFuture<ServerCapabilities?> =
        com.google.common.util.concurrent.SettableFuture.create<ServerCapabilities?>()

    private val channelFactory: com.google.devtools.build.lib.remote.ChannelFactory
    private val target: String?
    private val proxy: String?
    private val options: AuthAndTLSOptions?
    private val interceptors: MutableList<ClientInterceptor?>?
    private val maxConcurrency: Int
    private val verboseFailures: Boolean
    private val reporter: Reporter
    private val remoteServerCapabilities: RemoteServerCapabilities?
    private val remoteOptions: RemoteOptions?
    private val digestFunction: DigestFunction.Value?
    private val requirement: ServerCapabilitiesRequirement?

    init {
        if (requirement != ServerCapabilitiesRequirement.NONE) {
            com.google.common.base.Preconditions.checkNotNull<RemoteServerCapabilities?>(remoteServerCapabilities)
        }

        this.channelFactory = channelFactory
        this.target = target
        this.proxy = proxy
        this.options = options
        this.interceptors = interceptors
        this.maxConcurrency = maxConcurrency
        this.verboseFailures = verboseFailures
        this.reporter = reporter
        this.remoteServerCapabilities = remoteServerCapabilities
        this.remoteOptions = remoteOptions
        this.digestFunction = digestFunction
        this.requirement = requirement
    }

    override fun create(): Single<ChannelConnectionWithServerCapabilities?>? {
        return Single.fromCallable<ManagedChannel?>(
            java.util.concurrent.Callable { channelFactory.newChannel(target, proxy, options, interceptors) })
            .flatMap<ChannelConnectionWithServerCapabilities?>(
                io.reactivex.rxjava3.functions.Function { channel: ManagedChannel? ->
                    val serverCapabilitiesSingle: Single<ServerCapabilities?> =
                        RxFutures.toSingle<ServerCapabilities?>(
                            io.reactivex.rxjava3.functions.Supplier { getAndVerifyServerCapabilities(channel) },
                            com.google.common.util.concurrent.MoreExecutors.directExecutor()
                        )
                    // Don't issue GetCapabilities calls if the requirement is NONE because the endpoint,
                    // e.g. Remote Asset API, might not implement the API. See #20342.
                    if (requirement == ServerCapabilitiesRequirement.NONE) {
                        return@flatMap Single.just<ChannelConnectionWithServerCapabilities?>(
                            ChannelConnectionWithServerCapabilities(channel, serverCapabilitiesSingle)
                        )
                    }
                    serverCapabilitiesSingle.map<ChannelConnectionWithServerCapabilities?>(
                        io.reactivex.rxjava3.functions.Function { sc: ServerCapabilities? ->
                            ChannelConnectionWithServerCapabilities(
                                channel,
                                Single.just<ServerCapabilities?>(sc)
                            )
                        })
                })
    }

    private fun getAndVerifyServerCapabilities(
        channel: ManagedChannel?
    ): com.google.common.util.concurrent.ListenableFuture<ServerCapabilities?> {
        if (getAndVerifyServerCapabilitiesStarted.compareAndSet(false, true)) {
            val s: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                Profiler.instance().profile("getAndVerifyServerCapabilities")
            val future: com.google.common.util.concurrent.ListenableFuture<Any?> =
                com.google.common.util.concurrent.Futures.transformAsync<ServerCapabilities?, Any?>(
                    com.google.common.base.Preconditions.checkNotNull<RemoteServerCapabilities?>(
                        remoteServerCapabilities
                    ).get(channel),
                    com.google.common.util.concurrent.AsyncFunction { serverCapabilities: ServerCapabilities? ->
                        val result: ClientServerCompatibilityStatus =
                            RemoteServerCapabilities.Companion.checkClientServerCompatibility(
                                serverCapabilities, remoteOptions, digestFunction, requirement
                            )
                        for (warning in result.getWarnings()) {
                            reporter.handle(Event.warn(warning))
                        }
                        val errors: MutableList<String?> = result.getErrors()
                        for (i in 0..<errors.size() - 1) {
                            reporter.handle(Event.error(errors.get(i)))
                        }
                        if (!errors.isEmpty()) {
                            val lastErrorMessage = errors.get(errors.size() - 1)
                            return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                                IOException(lastErrorMessage)
                            )
                        }
                        com.google.common.util.concurrent.Futures.immediateFuture<Any?>(serverCapabilities)
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            future.addListener(s::close, com.google.common.util.concurrent.MoreExecutors.directExecutor())
            com.google.common.util.concurrent.Futures.addCallback<V?>(
                future,
                object : com.google.common.util.concurrent.FutureCallback<ServerCapabilities?> {
                    override fun onSuccess(result: ServerCapabilities?) {
                        serverCapabilities.set(result)
                    }

                    override fun onFailure(error: Throwable) {
                        val cause: Throwable?
                        if (error !is IOException && error.getCause() is IOException) {
                            cause = ioException
                        } else {
                            cause = error
                        }
                        serverCapabilities.setException(RemoteExecutionCapabilitiesException(cause))
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
        return serverCapabilities
    }

    override fun maxConcurrency(): Int {
        return maxConcurrency
    }
}
