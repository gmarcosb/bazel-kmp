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
package com.google.devtools.build.lib.authandtls

import com.google.auth.oauth2.GoogleCredentials
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions.CredentialHelperOption
import com.google.devtools.build.lib.authandtls.CallCredentialsProvider
import com.google.devtools.build.lib.authandtls.GoogleAuthCallCredentialsProvider
import com.google.devtools.build.lib.authandtls.Netrc
import com.google.devtools.build.lib.authandtls.NetrcCredentials
import com.google.devtools.build.lib.authandtls.NetrcParser
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperCredentials
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import com.google.devtools.build.lib.runtime.CommandLinePathFactory
import io.grpc.CallCredentials
import io.grpc.ClientInterceptor
import io.grpc.ManagedChannel
import io.grpc.auth.MoreCallCredentials
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NegotiationType
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Utility methods for using [AuthAndTLSOptions] with Google Cloud.  */
object GoogleAuthUtils {
    /**
     * Create a new gRPC [ManagedChannel].
     * 
     * @throws IOException in case the channel can't be constructed.
     */
    @Throws(IOException::class)
    fun newChannel(
        executor: java.util.concurrent.Executor?,
        target: String?,
        proxy: String?,
        options: AuthAndTLSOptions?,
        interceptors: MutableList<ClientInterceptor?>?
    ): ManagedChannel? {
        com.google.common.base.Preconditions.checkNotNull<String?>(target)
        com.google.common.base.Preconditions.checkNotNull<AuthAndTLSOptions?>(options)

        val sslContext: io.netty.handler.ssl.SslContext? =
            if (com.google.devtools.build.lib.authandtls.GoogleAuthUtils.isTlsEnabled(target))
                com.google.devtools.build.lib.authandtls.GoogleAuthUtils.createSSlContext(
                    options.getTlsCertificate(),
                    options.getTlsClientCertificate(),
                    options.getTlsClientKey()
                )
            else
                null

        val targetUrl: String = com.google.devtools.build.lib.authandtls.GoogleAuthUtils.convertTargetScheme(target)
        try {
            val builder: NettyChannelBuilder =
                com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newNettyChannelBuilder(targetUrl, proxy)
                    .executor(executor)
                    .negotiationType(
                        if (com.google.devtools.build.lib.authandtls.GoogleAuthUtils.isTlsEnabled(target)) NegotiationType.TLS else NegotiationType.PLAINTEXT
                    )
            if (options.getGrpcKeepaliveTime() != null && !options.getGrpcKeepaliveTime().isZero()) {
                builder.keepAliveTime(options.getGrpcKeepaliveTime().toNanos(), TimeUnit.NANOSECONDS)
                builder.keepAliveTimeout(options.getGrpcKeepaliveTimeout().toNanos(), TimeUnit.NANOSECONDS)
            }
            if (interceptors != null) {
                builder.intercept(interceptors)
            }
            if (sslContext != null) {
                builder.sslContext(sslContext)
                if (options.getTlsAuthorityOverride() != null) {
                    builder.overrideAuthority(options.getTlsAuthorityOverride())
                }
            }
            return builder.build()
        } catch (e: java.lang.RuntimeException) {
            // gRPC might throw all kinds of RuntimeExceptions: StatusRuntimeException,
            // IllegalStateException, NullPointerException, ...
            val message = "Failed to connect to '%s': %s"
            throw IOException(java.lang.String.format(message, targetUrl, e.getMessage()))
        }
    }

    /**
     * Converts 'grpc(s)' into an empty protocol, because 'grpc(s)' is not a widely supported scheme
     * and is interpreted as 'dns' under the hood.
     * 
     * @return target URL with converted scheme
     */
    private fun convertTargetScheme(target: String): String {
        return target.replace("grpcs://", "").replace("grpc://", "")
    }

    private fun isTlsEnabled(target: String): Boolean {
        // 'grpcs://' or empty prefix => TLS-enabled
        // when no scheme prefix is provided in URL, bazel will treat it as a gRPC request with TLS
        // enabled
        return !target.startsWith("grpc://") && !target.startsWith("unix:")
    }

    @Throws(IOException::class)
    private fun createSSlContext(
        rootCert: String?, clientCert: String?, clientKey: String?
    ): io.netty.handler.ssl.SslContext {
        val sslContextBuilder: io.netty.handler.ssl.SslContextBuilder
        try {
            sslContextBuilder = GrpcSslContexts.forClient()
        } catch (e: java.lang.Exception) {
            val message = "Failed to init TLS infrastructure: " + e.getMessage()
            throw IOException(message, e)
        }
        if (rootCert != null) {
            try {
                sslContextBuilder.trustManager(java.io.File(rootCert))
            } catch (e: java.lang.Exception) {
                var message: String? = "Failed to init TLS infrastructure using '%s' as root certificate: %s"
                message = java.lang.String.format(message, rootCert, e.getMessage())
                throw IOException(message, e)
            }
        }
        if (clientCert != null && clientKey != null) {
            try {
                sslContextBuilder.keyManager(java.io.File(clientCert), java.io.File(clientKey))
            } catch (e: java.lang.Exception) {
                var message: String? = "Failed to init TLS infrastructure using '%s' as client certificate: %s"
                message = java.lang.String.format(message, clientCert, e.getMessage())
                throw IOException(message, e)
            }
        }
        try {
            return sslContextBuilder.build()
        } catch (e: java.lang.Exception) {
            val message = "Failed to init TLS infrastructure: " + e.getMessage()
            throw IOException(message, e)
        }
    }

    private var currentEventLoopGroup: io.netty.channel.EventLoopGroup? = null

    @get:Throws(IOException::class)
    @get:kotlin.jvm.Synchronized
    private val eventLoopGroup: io.netty.channel.EventLoopGroup
        get() {
            if (com.google.devtools.build.lib.authandtls.GoogleAuthUtils.currentEventLoopGroup == null) {
                if (KQueue.isAvailable()) {
                    com.google.devtools.build.lib.authandtls.GoogleAuthUtils.currentEventLoopGroup =
                        KQueueEventLoopGroup()
                } else if (Epoll.isAvailable()) {
                    com.google.devtools.build.lib.authandtls.GoogleAuthUtils.currentEventLoopGroup =
                        EpollEventLoopGroup()
                } else {
                    throw IOException("Creating event loop groups is unsupported on this platform")
                }
            }
            return com.google.devtools.build.lib.authandtls.GoogleAuthUtils.currentEventLoopGroup
        }

    @Throws(IOException::class)
    private fun newUnixNettyChannelBuilder(target: String): NettyChannelBuilder {
        val address: io.netty.channel.unix.DomainSocketAddress =
            io.netty.channel.unix.DomainSocketAddress(target.replaceFirst("^unix:", ""))
        val builder: NettyChannelBuilder =
            NettyChannelBuilder.forAddress(address)
                .eventLoopGroup(com.google.devtools.build.lib.authandtls.GoogleAuthUtils.getEventLoopGroup())
        if (KQueue.isAvailable()) {
            return builder.channelType(KQueueDomainSocketChannel::class.java)
        }
        if (Epoll.isAvailable()) {
            return builder.channelType(EpollDomainSocketChannel::class.java)
        }

        throw IOException("Unix domain sockets are unsupported on this platform")
    }

    @Throws(IOException::class)
    private fun newNettyChannelBuilder(targetUrl: String, proxy: String?): NettyChannelBuilder? {
        if (targetUrl.startsWith("unix:")) {
            return com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newUnixNettyChannelBuilder(targetUrl)
        }

        if (com.google.common.base.Strings.isNullOrEmpty(proxy)) {
            return NettyChannelBuilder.forTarget(targetUrl).defaultLoadBalancingPolicy("round_robin")
        }

        if (!proxy.startsWith("unix:")) {
            throw IOException("Remote proxy unsupported: " + proxy)
        }

        return com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newUnixNettyChannelBuilder(proxy)
            .overrideAuthority(targetUrl)
    }

    /**
     * Create a new [CallCredentials] object from the authentication flags, or null if no flags
     * are set.
     * 
     * @throws IOException in case the credentials can't be constructed.
     */
    @Throws(IOException::class)
    fun newGoogleCallCredentials(options: AuthAndTLSOptions?): CallCredentials? {
        val creds: java.util.Optional<com.google.auth.Credentials?> =
            com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newGoogleCredentials(options)
        if (creds.isPresent()) {
            return MoreCallCredentials.from(creds.get())
        }
        return null
    }

    /**
     * Create a new [CallCredentialsProvider] object from [Credentials] or return [ ][CallCredentialsProvider.NO_CREDENTIALS] if it is `null`.
     */
    fun newCallCredentialsProvider(creds: com.google.auth.Credentials?): CallCredentialsProvider {
        if (creds != null) {
            return GoogleAuthCallCredentialsProvider(creds)
        }
        return CallCredentialsProvider.Companion.NO_CREDENTIALS
    }

    /**
     * Create a new [Credentials] retrieving call credentials in the following order:
     * 
     * 
     *  1. If a Credential Helper is configured for the scope, use the credentials provided by the
     * helper.
     *  1. If (Google) authentication is enabled by flags, use it to create credentials.
     *  1. Use `.netrc` to provide credentials if exists.
     * 
     * 
     * @throws IOException in case the credentials can't be constructed.
     */
    @Throws(IOException::class)
    fun newCredentials(
        credentialHelperEnvironment: CredentialHelperEnvironment?,
        credentialCache: com.github.benmanes.caffeine.cache.Cache<java.net.URI?, GetCredentialsResponse?>?,
        commandLinePathFactory: CommandLinePathFactory?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        authAndTlsOptions: AuthAndTLSOptions?
    ): com.google.auth.Credentials {
        com.google.common.base.Preconditions.checkNotNull<CredentialHelperEnvironment?>(credentialHelperEnvironment)
        com.google.common.base.Preconditions.checkNotNull<CommandLinePathFactory?>(commandLinePathFactory)
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem?>(fileSystem)
        com.google.common.base.Preconditions.checkNotNull<AuthAndTLSOptions?>(authAndTlsOptions)

        var fallbackCredentials: java.util.Optional<com.google.auth.Credentials?> =
            com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newGoogleCredentials(authAndTlsOptions)

        if (fallbackCredentials.isEmpty()) {
            // Fallback to .netrc if it exists.
            try {
                fallbackCredentials =
                    com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newCredentialsFromNetrc(
                        credentialHelperEnvironment.clientEnvironment,
                        fileSystem
                    )
            } catch (e: IOException) {
                // TODO(yannic): Make this fail the build.
                credentialHelperEnvironment.eventReporter.handle(com.google.devtools.build.lib.events.Event.warn(e.getMessage()))
            }
        }

        return CredentialHelperCredentials(
            com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                authAndTlsOptions.getCredentialHelpers()
            ),
            credentialHelperEnvironment,
            credentialCache,
            fallbackCredentials
        )
    }

    /**
     * Create a new [Credentials] object from the authentication flags, or null if no flags are
     * set.
     * 
     * @throws IOException in case the credentials can't be constructed.
     */
    @Throws(IOException::class)
    private fun newGoogleCredentials(options: AuthAndTLSOptions?): java.util.Optional<com.google.auth.Credentials?> {
        com.google.common.base.Preconditions.checkNotNull<AuthAndTLSOptions?>(options)
        if (options.getGoogleCredentials() != null) {
            // Credentials from file
            try {
                FileInputStream(options.getGoogleCredentials()).use { authFile ->
                    return java.util.Optional.of<com.google.auth.Credentials?>(
                        com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newGoogleCredentialsFromFile(
                            authFile,
                            options.getGoogleAuthScopes()
                        )
                    )
                }
            } catch (e: FileNotFoundException) {
                val message: String? =
                    java.lang.String.format(
                        "Could not open auth credentials file '%s': %s",
                        options.getGoogleCredentials(), e.getMessage()
                    )
                throw IOException(message, e)
            }
        } else if (options.getUseGoogleDefaultCredentials()) {
            return java.util.Optional.of<com.google.auth.Credentials?>(
                com.google.devtools.build.lib.authandtls.GoogleAuthUtils.newGoogleCredentialsFromFile(
                    null,  /* Google Application Default Credentials */options.getGoogleAuthScopes()
                )
            )
        }
        return java.util.Optional.empty<com.google.auth.Credentials?>()
    }

    /**
     * Create a new [Credentials] object from credential file and given authentication scopes.
     * 
     * @throws IOException in case the credentials can't be constructed.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun newGoogleCredentialsFromFile(
        credentialsFile: java.io.InputStream?, authScopes: MutableList<String?>
    ): com.google.auth.Credentials {
        try {
            var creds: GoogleCredentials =
                if (credentialsFile == null)
                    GoogleCredentials.getApplicationDefault()
                else
                    GoogleCredentials.fromStream(credentialsFile)
            if (!authScopes.isEmpty()) {
                creds = creds.createScoped(authScopes)
            }
            return creds
        } catch (e: java.lang.Exception) {
            val message = "Failed to init auth credentials: " + e.getMessage()
            throw IOException(message, e)
        }
    }

    /**
     * Create a new [Credentials] object by parsing the .netrc file with following order to
     * search it:
     * 
     * 
     *  1. If environment variable $NETRC exists, use it as the path to the .netrc file
     *  1. Fallback to $HOME/.netrc
     * 
     * 
     * @return the [Credentials] object or `null` if there is no .netrc file.
     * @throws IOException in case the credentials can't be constructed.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun newCredentialsFromNetrc(
        clientEnv: MutableMap<String?, String?>, fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    ): java.util.Optional<com.google.auth.Credentials?> {
        val netrcFileString: java.util.Optional<String?> =
            java.util.Optional.ofNullable<String?>(clientEnv.get("NETRC"))
                .or(java.util.function.Supplier {
                    java.util.Optional.ofNullable<String?>(clientEnv.get("HOME"))
                        .map<String?>(java.util.function.Function { home: String? -> home + "/.netrc" })
                })
        if (netrcFileString.isEmpty()) {
            return java.util.Optional.empty<com.google.auth.Credentials?>()
        }

        val netrcFile: com.google.devtools.build.lib.vfs.Path = fileSystem.getPath(netrcFileString.get())
        if (!netrcFile.exists()) {
            return java.util.Optional.empty<com.google.auth.Credentials?>()
        }

        try {
            val netrc: Netrc = NetrcParser.parseAndClose(netrcFile.getInputStream())
            return java.util.Optional.of<com.google.auth.Credentials?>(NetrcCredentials(netrc))
        } catch (e: IOException) {
            throw IOException(
                "Failed to parse " + netrcFile.getPathString() + ": " + e.getMessage(), e
            )
        }
    }

    @Throws(IOException::class)
    fun newCredentialHelperProvider(
        environment: CredentialHelperEnvironment?,
        pathFactory: CommandLinePathFactory?,
        helpers: MutableList<CredentialHelperOption>?
    ): CredentialHelperProvider {
        com.google.common.base.Preconditions.checkNotNull<CredentialHelperEnvironment?>(environment)
        com.google.common.base.Preconditions.checkNotNull<CommandLinePathFactory?>(pathFactory)
        com.google.common.base.Preconditions.checkNotNull<MutableList<CredentialHelperOption?>?>(helpers)

        val builder: com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperProvider.Builder =
            CredentialHelperProvider.Companion.builder()
        for (helper in helpers!!) {
            val scope: java.util.Optional<String?> = helper.scope
            val path: com.google.devtools.build.lib.vfs.Path? =
                pathFactory.create(environment.clientEnvironment, helper.path)
            if (scope.isPresent()) {
                builder.add(scope.get(), path)
            } else {
                builder.add(path)
            }
        }
        return builder.build()
    }
}
