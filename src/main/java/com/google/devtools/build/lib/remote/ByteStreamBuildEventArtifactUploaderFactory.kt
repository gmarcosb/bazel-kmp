// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader

/** A factory for [ByteStreamBuildEventArtifactUploader].  */
internal class ByteStreamBuildEventArtifactUploaderFactory(
    executor: java.util.concurrent.Executor?,
    reporter: ExtendedEventHandler?,
    verboseFailures: Boolean,
    combinedCache: CombinedCache,
    remoteInstanceName: String?,
    remoteBytestreamUriPrefix: String?,
    buildRequestId: String?,
    commandId: String?,
    remoteBuildEventUploadMode: RemoteBuildEventUploadMode?
) : BuildEventArtifactUploaderFactory {
    private val executor: java.util.concurrent.Executor?
    private val reporter: ExtendedEventHandler?
    private val verboseFailures: Boolean
    private val combinedCache: CombinedCache
    private val remoteInstanceName: String?
    private val remoteBytestreamUriPrefix: String?
    private val buildRequestId: String?
    private val commandId: String?
    private val remoteBuildEventUploadMode: RemoteBuildEventUploadMode?

    private var uploader: ByteStreamBuildEventArtifactUploader? = null

    init {
        this.executor = executor
        this.reporter = reporter
        this.verboseFailures = verboseFailures
        this.combinedCache = combinedCache
        this.remoteInstanceName = remoteInstanceName
        this.remoteBytestreamUriPrefix = remoteBytestreamUriPrefix
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.remoteBuildEventUploadMode = remoteBuildEventUploadMode
    }

    override fun create(env: CommandEnvironment): BuildEventArtifactUploader? {
        com.google.common.base.Preconditions.checkState(uploader == null, "Already created")
        uploader =
            ByteStreamBuildEventArtifactUploader(
                executor,
                reporter,
                verboseFailures,
                combinedCache.retain(),
                remoteInstanceName,
                remoteBytestreamUriPrefix,
                buildRequestId,
                commandId,
                env.getXattrProvider(),
                remoteBuildEventUploadMode
            )
        env.getEventBus().register(uploader)
        return uploader
    }

    fun get(): ByteStreamBuildEventArtifactUploader? {
        return uploader
    }
}
