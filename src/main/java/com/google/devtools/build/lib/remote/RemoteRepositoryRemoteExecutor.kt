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

import build.bazel.remote.execution.v2.Action

/** The remote package's implementation of [RepositoryRemoteExecutor].  */
class RemoteRepositoryRemoteExecutor(
    remoteCache: RemoteExecutionCache,
    remoteExecutor: RemoteExecutionClient,
    digestUtil: DigestUtil,
    buildRequestId: String?,
    commandId: String?,
    workspaceName: String?,
    remoteInstanceName: String?,
    acceptCached: Boolean
) : RepositoryRemoteExecutor {
    private val remoteCache: RemoteExecutionCache
    private val remoteExecutor: RemoteExecutionClient
    private val digestUtil: DigestUtil
    private val buildRequestId: String?
    private val commandId: String?
    private val workspaceName: String?

    private val remoteInstanceName: String?
    private val acceptCached: Boolean

    init {
        this.remoteCache = remoteCache
        this.remoteExecutor = remoteExecutor
        this.digestUtil = digestUtil
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.workspaceName = workspaceName
        this.remoteInstanceName = remoteInstanceName
        this.acceptCached = acceptCached
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun downloadOutErr(context: RemoteActionExecutionContext?, result: ActionResult): ExecutionResult {
        Profiler.instance().profile(ProfilerTask.REMOTE_DOWNLOAD, "download stdout/stderr").use { c ->
            var stdout: ByteArray? = ByteArray(0)
            if (!result.getStdoutRaw().isEmpty()) {
                stdout = result.getStdoutRaw().toByteArray()
            } else if (result.hasStdoutDigest()) {
                stdout =
                    com.google.devtools.build.lib.remote.util.Utils.getFromFuture<ByteArray?>(
                        remoteCache.downloadBlob(
                            context, "<stdout>",  /* execPath= */null, result.getStdoutDigest()
                        )
                    )
            }

            var stderr: ByteArray? = ByteArray(0)
            if (!result.getStderrRaw().isEmpty()) {
                stderr = result.getStderrRaw().toByteArray()
            } else if (result.hasStderrDigest()) {
                stderr =
                    com.google.devtools.build.lib.remote.util.Utils.getFromFuture<ByteArray?>(
                        remoteCache.downloadBlob(
                            context, "<stderr>",  /* execPath= */null, result.getStderrDigest()
                        )
                    )
            }
            return ExecutionResult(result.getExitCode(), stdout, stderr)
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun execute(
        arguments: com.google.common.collect.ImmutableList<String?>?,
        inputFiles: com.google.common.collect.ImmutableSortedMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?>,
        executionProperties: com.google.common.collect.ImmutableMap<String?, String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>,
        workingDirectory: String?,
        timeout: java.time.Duration
    ): ExecutionResult {
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "repository_rule", null)
        val context: RemoteActionExecutionContext = RemoteActionExecutionContext.Companion.create(metadata)

        val platform: Platform? = PlatformUtils.buildPlatformProto(executionProperties)

        val commandBuilder: Command.Builder = Command.newBuilder().addAllArguments(arguments)
        // Sorting the environment pairs by variable name.
        val variables: TreeSet<String?> = TreeSet<String?>(environment.keySet())
        for (`var` in variables) {
            commandBuilder.addEnvironmentVariablesBuilder().setName(`var`).setValue(environment.get(`var`))
        }
        if (platform != null) {
            commandBuilder.setPlatform(platform)
        }
        if (workingDirectory != null) {
            commandBuilder.setWorkingDirectory(workingDirectory)
        }

        val command: Command? = commandBuilder.build()
        val commandHash: Digest? = digestUtil.compute(command)
        val merkleTree: Uploadable =
            MerkleTreeComputer(
                digestUtil,  /* remoteExecutionCache= */
                null,
                buildRequestId,
                commandId,
                workspaceName
            )
                .buildForFiles(inputFiles)
        val action: Action? =
            com.google.devtools.build.lib.remote.util.Utils.buildAction(
                commandHash, merkleTree.digest(), platform, timeout, acceptCached,  /* salt= */null
            )
        val actionDigest: Digest? = digestUtil.compute(action)
        val actionKey: ActionKey = ActionKey(actionDigest)
        val cachedActionResult: CachedActionResult?
        Profiler.instance().profile(ProfilerTask.REMOTE_CACHE_CHECK, "check cache hit").use { c ->
            cachedActionResult =
                remoteCache.downloadActionResult(
                    context,
                    actionKey,  /* inlineOutErr= */
                    true,  /* inlineOutputFiles= */
                    com.google.common.collect.ImmutableSet.of<String?>()
                )
        }
        var actionResult: ActionResult? = null
        if (cachedActionResult != null) {
            actionResult = cachedActionResult.actionResult
        }
        if (actionResult == null || actionResult.getExitCode() !== 0) {
            Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload missing inputs").use { c ->
                val additionalInputs: MutableMap<Digest?, Message?> =
                    com.google.common.collect.Maps.newHashMapWithExpectedSize<Digest?, Message?>(2)
                additionalInputs.put(actionDigest, action)
                additionalInputs.put(commandHash, command)
                remoteCache.ensureInputsPresent(
                    context,
                    merkleTree,
                    additionalInputs,  /* force= */
                    true,  /* remotePathResolver= */
                    null
                )
            }
            Profiler.instance().profile(ProfilerTask.REMOTE_EXECUTION, "execute remotely").use { c ->
                val executeRequest: ExecuteRequest? =
                    ExecuteRequest.newBuilder()
                        .setActionDigest(actionDigest)
                        .setInstanceName(remoteInstanceName)
                        .setDigestFunction(digestUtil.getDigestFunction())
                        .setSkipCacheLookup(!acceptCached)
                        .build()
                val response: ExecuteResponse =
                    remoteExecutor.executeRemotely(context, executeRequest, OperationObserver.Companion.NO_OP)
                actionResult = response.getResult()
            }
        }
        return downloadOutErr(context, actionResult)
    }
}
