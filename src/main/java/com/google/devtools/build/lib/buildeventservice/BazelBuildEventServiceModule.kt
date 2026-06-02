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
package com.google.devtools.build.lib.buildeventservice

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Bazel's BES module.  */
open class BazelBuildEventServiceModule

    : BuildEventServiceModule<BuildEventServiceOptions?>() {
    internal class BackendConfig(
        besBackend: String?,
        besProxy: String?,
        besHeaders: com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, String?>>?,
        authAndTLSOptions: AuthAndTLSOptions?
    ) {
        val besBackend: String?
        val besProxy: String?
        val besHeaders: com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, String?>>?
        val authAndTLSOptions: AuthAndTLSOptions?

        init {
            this.authAndTLSOptions = authAndTLSOptions
            this.besHeaders = besHeaders
            this.besProxy = besProxy
            this.besBackend = besBackend
            String > java.util.Objects.requireNonNull<String?>(besBackend, "besBackend")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, String?>?>?>(
                besHeaders,
                "besHeaders"
            )
            Object > java.util.Objects.requireNonNull<Any?>(authAndTLSOptions, "authAndTLSOptions")
        }

        companion object {
            fun create(
                besOptions: BuildEventServiceOptions, authAndTLSOptions: AuthAndTLSOptions?
            ): BackendConfig {
                return BackendConfig(
                    besOptions.getBesBackend(),
                    besOptions.getBesProxy(),
                    com.google.common.collect.ImmutableMap.builder<String?, String?>()
                        .putAll(besOptions.getBesHeaders())
                        .buildKeepingLast()
                        .entrySet()
                        .asList(),
                    authAndTLSOptions
                )
            }
        }
    }

    private var client: BuildEventServiceClient? = null
    private var config: BackendConfig? = null

    private var credentialModule: CredentialModule? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        com.google.common.base.Preconditions.checkState(credentialModule == null, "credentialModule must be null")
        credentialModule = com.google.common.base.Preconditions.checkNotNull<CredentialModule?>(
            runtime.getBlazeModule<CredentialModule?>(CredentialModule::class.java)
        )
    }

    override fun optionsClass(): java.lang.Class<BuildEventServiceOptions?> {
        return BuildEventServiceOptions::class.java
    }

    public override fun getBesKeywords(
        commandName: String?,
        besOptions: BuildEventServiceOptions,
        startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult?
    ): com.google.common.collect.ImmutableSet<String?> {
        val userKeywords: MutableList<String?> = besOptions.getBesKeywords()
        val systemKeywords: MutableList<String?> = besOptions.getBesSystemKeywords()
        val builder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
                .add("protocol_name=BEP")
                .add("command_name=" + commandName)
                .addAll(systemKeywords)
        for (userKeyword in userKeywords) {
            builder.add("user_keyword=" + userKeyword)
        }
        return builder.build()
    }

    @Throws(IOException::class)
    override fun getBesClient(
        env: CommandEnvironment,
        besOptions: BuildEventServiceOptions,
        authAndTLSOptions: AuthAndTLSOptions
    ): BuildEventServiceClient {
        val newConfig = BackendConfig.Companion.create(besOptions, authAndTLSOptions)
        if (client == null || config != newConfig) {
            clearBesClient()
            com.google.common.base.Preconditions.checkState(config == null, "config should be null")
            com.google.common.base.Preconditions.checkState(client == null, "client should be null")

            val credentials: com.google.auth.Credentials? =
                GoogleAuthUtils.newCredentials(
                    CredentialHelperEnvironment.newBuilder()
                        .setEventReporter(env.getReporter())
                        .setWorkspacePath(env.getWorkspace())
                        .setClientEnvironment(env.getClientEnv())
                        .setHelperExecutionTimeout(authAndTLSOptions.credentialHelperTimeout)
                        .build(),
                    credentialModule.getCredentialCache(),
                    env.getCommandLinePathFactory(),
                    env.getRuntime().getFileSystem(),
                    newConfig.authAndTLSOptions
                )

            config = newConfig
            client =
                BuildEventServiceGrpcClient(
                    newGrpcChannel(config!!),
                    if (credentials != null) MoreCallCredentials.from(credentials) else null,
                    Companion.makeGrpcInterceptor(config!!)
                )
        }
        return client
    }

    // newGrpcChannel is only defined so it can be overridden in tests to not use a real network link.
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    protected open fun newGrpcChannel(config: BackendConfig): ManagedChannel {
        return GoogleAuthUtils.newChannel( /* executor= */
            null,
            config.besBackend,
            config.besProxy,
            config.authAndTLSOptions,  /* interceptors= */
            null
        )
    }

    override fun clearBesClient() {
        if (client != null) {
            client.shutdown()
        }
        this.client = null
        this.config = null
    }

    override fun allowedCommands(besOptions: BuildEventServiceOptions?): MutableSet<String?> {
        return ALLOWED_COMMANDS
    }

    val invocationIdPrefix: String?
        get() {
            if (com.google.common.base.Strings.isNullOrEmpty(besOptions.getBesResultsUrl())) {
                return ""
            }
            return if (besOptions.getBesResultsUrl().endsWith("/"))
                besOptions.getBesResultsUrl()
            else
                besOptions.getBesResultsUrl() + "/"
        }

    val buildRequestIdPrefix: String
        get() = ""

    companion object {
        private fun makeGrpcInterceptor(config: BackendConfig): ClientInterceptor? {
            if (config.besHeaders.isEmpty()) {
                return null
            }
            return MetadataUtils.newAttachHeadersInterceptor(makeGrpcMetadata(config))
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun makeGrpcMetadata(config: BackendConfig): io.grpc.Metadata {
            val extraHeaders: io.grpc.Metadata = io.grpc.Metadata()
            for (header in config.besHeaders) {
                extraHeaders.put<String?>(
                    io.grpc.Metadata.Key.of<String?>(header.getKey(), io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                    header.getValue()
                )
            }
            return extraHeaders
        }

        private val ALLOWED_COMMANDS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                "fetch",
                "build",
                "test",
                "run",
                "query",
                "aquery",
                "cquery",
                "coverage",
                "mobile-install"
            )
    }
}
