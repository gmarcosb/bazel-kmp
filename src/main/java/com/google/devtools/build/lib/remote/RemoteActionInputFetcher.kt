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

import build.bazel.remote.execution.v2.Digest

/**
 * Stages output files that are stored remotely to the local filesystem.
 * 
 * 
 * This is used to ensure that the inputs to a local action are present, even when they are
 * provided by a remote action when building without the bytes, or by an external repository when
 * building with a remote repository cache enabled.
 */
class RemoteActionInputFetcher internal constructor(
    reporter: com.google.devtools.build.lib.events.Reporter?,
    buildRequestId: String?,
    commandId: String?,
    combinedCache: CombinedCache?,
    execRoot: com.google.devtools.build.lib.vfs.Path?,
    tempPathGenerator: TempPathGenerator?,
    remoteOutputChecker: RemoteOutputChecker?,
    outputDirectoryHelper: ActionOutputDirectoryHelper?,
    outputPermissions: OutputPermissions?
) : AbstractActionInputPrefetcher(
    reporter,
    execRoot,
    tempPathGenerator,
    remoteOutputChecker,
    outputDirectoryHelper,
    outputPermissions
) {
    private val buildRequestId: String
    private val commandId: String
    private val combinedCache: CombinedCache
    private val rewoundActionOutputs: ConcurrentArtifactPathTrie = ConcurrentArtifactPathTrie()

    init {
        this.buildRequestId = com.google.common.base.Preconditions.checkNotNull<String>(buildRequestId)
        this.commandId = com.google.common.base.Preconditions.checkNotNull<String>(commandId)
        this.combinedCache = com.google.common.base.Preconditions.checkNotNull<CombinedCache>(combinedCache)
    }

    @Throws(IOException::class)
    override fun prefetchVirtualActionInput(input: VirtualActionInput) {
        input.atomicallyWriteRelativeTo(execRoot)
    }

    override fun canDownloadFile(path: com.google.devtools.build.lib.vfs.Path, metadata: FileArtifactValue): Boolean {
        // When action rewinding is enabled, an action that had remote metadata at some point during the
        // build may have been re-executed locally to regenerate lost inputs, but may then be rewound
        // again and thus have its (now local) outputs deleted. In this case, we need to download the
        // outputs again, even if they are now considered local.
        return metadata.isRemote() || (forceRefetch(path) && !path.exists(Symlinks.NOFOLLOW))
    }

    override fun forceRefetch(path: com.google.devtools.build.lib.vfs.Path): Boolean {
        // Caches for download operations and output directory creation need to be disregarded for the
        // outputs of rewound actions as they may have been deleted after they were first created.
        return path.startsWith(execRoot) && rewoundActionOutputs.contains(path.relativeTo(execRoot))
    }

    @Throws(IOException::class)
    protected override fun doDownloadFile(
        action: ActionExecutionMetadata?,
        reporter: com.google.devtools.build.lib.events.Reporter?,
        input: ActionInput,
        tempPath: com.google.devtools.build.lib.vfs.Path,
        metadata: FileArtifactValue,
        priority: Priority?,
        reason: Reason
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val requestMetadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                buildRequestId,
                commandId,
                when (reason) {
                    INPUTS -> "input"
                    OUTPUTS -> "output"
                },
                action
            )
        val context: RemoteActionExecutionContext = RemoteActionExecutionContext.Companion.create(requestMetadata)

        val digest: Digest = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())

        // Treat other download error as CacheNotFoundException so that Bazel can
        // correctly rewind the action/build.
        // Intentionally, do not transform IOExceptions directly thrown by downloadFile rather than in
        // the returned future, as those are likely to be caused by local FS issues.
        return com.google.common.util.concurrent.Futures.catchingAsync<java.lang.Void?, IOException?>(
            combinedCache.downloadFile(
                context,
                input.getExecPathString(),
                input.getExecPath(),
                tempPath.forHostFileSystem(),
                digest,
                DownloadProgressReporter(
                    ProgressStatusListener { progress: SpawnProgressEvent? ->
                        if (action != null) {
                            progress.postTo(reporter, action)
                        }
                    },
                    input.getExecPathString(),
                    digest.getSizeBytes()
                )
            ),
            IOException::class.java,
            com.google.common.util.concurrent.AsyncFunction { e: IOException? ->
                com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                    when (e) {
                        -> cacheNotFoundException
                        else -> {
                            val cacheNotFoundException: CacheNotFoundException =
                                CacheNotFoundException(digest, input.getExecPath())
                            cacheNotFoundException.addSuppressed(e)
                            cacheNotFoundException
                        }
                    }
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    fun handleRewoundActionOutputs(outputs: MutableCollection<Artifact>) {
        // SkyframeActionExecutor#prepareForRewinding does *not* call this method because the
        // RemoteActionFileSystem corresponds to an ActionFileSystemType with inMemoryFileSystem() ==
        // true. While it is true that resetting outputDirectoryHelper isn't necessary to undo the
        // caching of output directory creation during action preparation, we still need to reset here
        // since outputDirectoryHelper is also used by AbstractActionInputPrefetcher.
        outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(outputs)
        for (output in outputs) {
            // Action templates have TreeFileArtifacts as outputs, which isn't supported by the trie. We
            // only need to track the tree artifacts themselves.
            if (output is Artifact.TreeFileArtifact) {
                rewoundActionOutputs.add(output.getParent())
            } else {
                rewoundActionOutputs.add(output)
            }
        }
    }
}
