// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.QueryEnvironmentFactory

/**
 * Builder class to create a [BlazeRuntime] instance. This class is part of the module API,
 * which allows modules to affect how the server is initialized.
 */
class ServerBuilder @com.google.common.annotations.VisibleForTesting constructor() {
    private var queryEnvironmentFactory: QueryEnvironmentFactory? = null
    private val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
    private val commands: com.google.common.collect.ImmutableList.Builder<BlazeCommand?> =
        com.google.common.collect.ImmutableList.builder<BlazeCommand?>()
    private val infoItems: com.google.common.collect.ImmutableMap.Builder<String?, InfoItem?> =
        com.google.common.collect.ImmutableMap.builder<String?, InfoItem?>()
    private val queryFunctions: com.google.common.collect.ImmutableList.Builder<QueryFunction?> =
        com.google.common.collect.ImmutableList.builder<QueryFunction?>()
    private val queryOutputFormatters: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.query2.query.output.OutputFormatter?> =
        com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.query2.query.output.OutputFormatter?>()
    private val buildEventArtifactUploaderFactories: com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactoryMap.Builder =
        com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactoryMap.Builder()
    private var repositoryRemoteHelpersFactory: RepositoryRemoteHelpersFactory? = null
    private val instrumentationOutputFactoryBuilder: com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.Builder =
        com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.Builder()

    fun getQueryEnvironmentFactory(): QueryEnvironmentFactory? {
        return if (queryEnvironmentFactory == null)
            QueryEnvironmentFactory()
        else
            queryEnvironmentFactory
    }

    val invocationPolicy: InvocationPolicy
        get() = invocationPolicyBuilder.build()

    fun getInfoItems(): com.google.common.collect.ImmutableMap<String?, InfoItem?> {
        return infoItems.buildOrThrow()
    }

    fun getQueryFunctions(): com.google.common.collect.ImmutableList<QueryFunction?> {
        return queryFunctions.build()
    }

    fun getQueryOutputFormatters(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.query2.query.output.OutputFormatter?> {
        return queryOutputFormatters.build()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getCommands(): com.google.common.collect.ImmutableList<BlazeCommand?> {
        return commands.build()
    }

    val buildEventArtifactUploaderMap: BuildEventArtifactUploaderFactoryMap?
        get() = buildEventArtifactUploaderFactories.build()

    val repositoryHelpersFactory: RepositoryRemoteHelpersFactory?
        get() = repositoryRemoteHelpersFactory

    /**
     * Merges the given invocation policy into the per-server invocation policy. While this can accept
     * any number of policies, the end result is order-dependent if multiple policies attempt to
     * police the same options, so it's probably a good idea to not have too many modules that call
     * this.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addInvocationPolicy(policy: InvocationPolicy?): ServerBuilder {
        invocationPolicyBuilder.mergeFrom(com.google.common.base.Preconditions.checkNotNull<T?>(policy))
        return this
    }

    /**
     * Sets a factory for creating [ ] instances. Note that
     * only one factory per server is allowed. If none is set, the server uses the default
     * implementation.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setQueryEnvironmentFactory(queryEnvironmentFactory: QueryEnvironmentFactory?): ServerBuilder {
        com.google.common.base.Preconditions.checkState(
            this.queryEnvironmentFactory == null,
            "At most one query environment factory supported. But found two: %s and %s",
            this.queryEnvironmentFactory,
            queryEnvironmentFactory
        )
        this.queryEnvironmentFactory =
            com.google.common.base.Preconditions.checkNotNull<QueryEnvironmentFactory?>(queryEnvironmentFactory)
        return this
    }

    /**
     * Adds the given command to the server. This overload only exists to avoid array object creation
     * in the common case.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addCommands(command: BlazeCommand?): ServerBuilder {
        this.commands.add(com.google.common.base.Preconditions.checkNotNull<BlazeCommand?>(command))
        return this
    }

    /** Adds the given commands to the server.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addCommands(vararg commands: BlazeCommand?): ServerBuilder {
        this.commands.add(*commands)
        return this
    }

    /**
     * Adds the given items as info items to the info command. It is an error to add info items with
     * the same name to the same builder, regardless of whether that happens within the same module or
     * across modules.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addInfoItems(vararg infoItems: InfoItem): ServerBuilder {
        for (item in infoItems) {
            this.infoItems.put(item.getName(), item)
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addQueryFunctions(vararg functions: QueryFunction?): ServerBuilder {
        this.queryFunctions.add(*functions)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addQueryOutputFormatters(vararg formatters: com.google.devtools.build.lib.query2.query.output.OutputFormatter?): ServerBuilder {
        this.queryOutputFormatters.add(*formatters)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addQueryOutputFormatters(formatters: Iterable<com.google.devtools.build.lib.query2.query.output.OutputFormatter?>): ServerBuilder {
        this.queryOutputFormatters.addAll(formatters)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addBuildEventArtifactUploaderFactory(
        uploaderFactory: BuildEventArtifactUploaderFactory?, name: String?
    ): ServerBuilder {
        buildEventArtifactUploaderFactories.add(name, uploaderFactory)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRepositoryHelpersFactory(
        repositoryRemoteHelpersFactory: RepositoryRemoteHelpersFactory?
    ): ServerBuilder {
        this.repositoryRemoteHelpersFactory = repositoryRemoteHelpersFactory
        return this
    }

    /**
     * Returns the builder for [InstrumentationOutputFactory] so that suppliers for different
     * types of [InstrumentationOutputBuilder] can be added.
     */
    fun getInstrumentationOutputFactoryBuilder(): com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.Builder {
        return instrumentationOutputFactoryBuilder
    }

    /**
     * Creates the [InstrumentationOutputFactory] so that user can choose to create the [ ] object.
     */
    fun createInstrumentationOutputFactory(): InstrumentationOutputFactory? {
        return instrumentationOutputFactoryBuilder.build()
    }
}
