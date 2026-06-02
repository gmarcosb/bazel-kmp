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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Factory for [RemoteRepositoryRemoteExecutor] and [RemoteRepoContentsCacheImpl].  */
internal class RepositoryRemoteHelpersFactoryImpl(
    directories: BlazeDirectories?,
    cache: CombinedCache,
    remoteExecutor: RemoteExecutionClient?,
    buildRequestId: String?,
    commandId: String?,
    workspaceName: String?,
    remoteInstanceName: String?,
    acceptCached: Boolean,
    uploadLocalResults: Boolean,
    verboseFailures: Boolean
) : RepositoryRemoteHelpersFactory {
    private val directories: BlazeDirectories?
    private val cache: CombinedCache
    private val remoteExecutor: RemoteExecutionClient?
    private val buildRequestId: String?
    private val commandId: String?
    private val workspaceName: String?

    private val remoteInstanceName: String?
    private val acceptCached: Boolean
    private val uploadLocalResults: Boolean
    private val verboseFailures: Boolean

    init {
        this.directories = directories
        this.cache = cache
        this.remoteExecutor = remoteExecutor
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.workspaceName = workspaceName
        this.remoteInstanceName = remoteInstanceName
        this.acceptCached = acceptCached
        this.uploadLocalResults = uploadLocalResults
        this.verboseFailures = verboseFailures
    }

    override fun createExecutor(): RepositoryRemoteExecutor? {
        if (remoteExecutor == null) {
            return null
        }
        return RemoteRepositoryRemoteExecutor(
            cache as RemoteExecutionCache?,
            remoteExecutor,
            cache.digestUtil,
            buildRequestId,
            commandId,
            workspaceName,
            remoteInstanceName,
            acceptCached
        )
    }

    override fun createRepoContentsCache(): RemoteRepoContentsCache? {
        return RemoteRepoContentsCacheImpl(
            directories,
            cache,
            buildRequestId,
            commandId,
            acceptCached,
            uploadLocalResults,
            verboseFailures
        )
    }
}
