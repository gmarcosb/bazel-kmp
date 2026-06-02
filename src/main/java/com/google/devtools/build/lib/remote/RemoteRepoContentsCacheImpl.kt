// Copyright 2025 The Bazel Authors. All rights reserved.
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

/**
 * A cache for the contents of external repositories that is backed by an ordinary remote cache.
 * 
 * 
 * Upon a cache hit, the metadata of the files comprising the repository is downloaded and
 * injected into a [RemoteExternalOverlayFileSystem]. Downloads of file contents only occur
 * when Bazel needs to read a file (e.g., a BUILD or .bzl file) or if a file is an input to an
 * action executed locally. This can save both time taken to execute repo rules and compute file
 * digests and disk space required to store the contents of external repositories.
 * 
 * 
 * Repositories are cached as AC entries for a synthetic command with a special hash as the salt.
 * The contents are represented as an output file for the marker file and an output directory for
 * the contents.
 * 
 * 
 * If a repo rule does not record any [RepoRecordedInput]s during its execution, this hash
 * is just the predeclared inputs hash [DigestWriter]. Otherwise, the AC entry for the
 * predeclared inputs hash will be an intermediate entry that lists one or more sets of [ ]s that a previously cached repo consumed during the evaluation of its rule. The
 * cache requests the current values of these inputs and computes the next hash to look up by a
 * rolling construction that combines the previous hash with the string representations of the
 * [RepoRecordedInput.WithValue]. This process is repeated until a final entry with the repo
 * contents is found or no matching entry exists.
 * 
 * 
 * By representing repos with recorded inputs as DAGs of AC entries, lookups are efficient (they
 * don't scale with the number of cached repos per predeclared inputs hash) and regular LRU eviction
 * policies remain effective for the most part. If a repo rule often requests different inputs even
 * with the same predeclared inputs hash and previously requested inputs and values, it could result
 * in large action results that grow over time. This is considered an acceptable trade-off for
 * simplicity for now and could be mitigated in the future by an explicit GC mechanism such as
 * "least recently added" eviction when the size of action result exceeds a certain threshold.
 */
class RemoteRepoContentsCacheImpl(
    directories: BlazeDirectories?,
    cache: CombinedCache,
    buildRequestId: String?,
    commandId: String?,
    acceptCached: Boolean,
    uploadLocalResults: Boolean,
    verboseFailures: Boolean
) : RemoteRepoContentsCache {
    private val directories: BlazeDirectories?
    private val cache: CombinedCache
    private val buildRequestId: String?
    private val commandId: String?
    private val acceptCached: Boolean
    private val uploadLocalResults: Boolean
    private val verboseFailures: Boolean
    private val digestUtil: DigestUtil
    private val baseAction: Action
    private val commandDigest: Digest?

    init {
        this.directories = directories
        this.cache = cache
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.acceptCached = acceptCached
        this.uploadLocalResults = uploadLocalResults
        this.verboseFailures = verboseFailures
        this.digestUtil = cache.digestUtil
        this.baseAction =
            Action.newBuilder()
                .setCommandDigest(digestUtil.compute(COMMAND))
                .setInputRootDigest(digestUtil.compute(INPUT_ROOT))
                .setPlatform(Platform.getDefaultInstance())
                .build()
        this.commandDigest = digestUtil.compute(COMMAND)
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun addToCache(
        repoName: RepositoryName,
        fetchedRepoDir: com.google.devtools.build.lib.vfs.Path,
        fetchedRepoMarkerFile: com.google.devtools.build.lib.vfs.Path?,
        predeclaredInputHash: String?,
        reporter: ExtendedEventHandler
    ) {
        if (fetchedRepoDir.getFileSystem() !is RemoteExternalOverlayFileSystem) {
            return
        }
        val context: RemoteActionExecutionContext = buildContext(repoName, CacheOp.UPLOAD)
        if (!context.getWriteCachePolicy().allowRemoteCache()) {
            return
        }
        val recordedInputValues: MutableList<WithValue?>
        try {
            val maybeRecordedInputValues: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                DigestWriter.readMarkerFile(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                        fetchedRepoMarkerFile,
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    ), predeclaredInputHash
                )
            if (maybeRecordedInputValues.isEmpty()) {
                return
            }
            recordedInputValues = maybeRecordedInputValues.get()
        } catch (e: IOException) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "Failed to read marker file for repo %s, skipping: %s"
                        .formatted(repoName, maybeGetStackTrace(e))
                )
            )
            return
        }
        try {
            // TODO: Consider uploading asynchronously.
            val finalHash =
                uploadIntermediateActionResults(context, predeclaredInputHash, recordedInputValues)
            val action: Action = buildAction(finalHash)
            val actionKey: ActionKey = ActionKey(digestUtil.compute(action))
            val remotePathResolver = RepoRemotePathResolver(fetchedRepoMarkerFile, fetchedRepoDir)
            val unused: ActionResult? =
                UploadManifest.Companion.create(
                    cache.getRemoteCacheCapabilities(),
                    digestUtil,
                    remotePathResolver,
                    actionKey,
                    action,
                    COMMAND,
                    com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.vfs.Path?>(
                        fetchedRepoMarkerFile,
                        fetchedRepoDir
                    ),  /* outErr= */
                    null,  /* exitCode= */
                    0,  /* startTime= */
                    Instant.now(),  /* wallTimeInMs= */
                    0,  /* preserveExecutableBit= */
                    true
                )
                    .upload(context, cache, reporter)
        } catch (e: ExecException) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "Failed to upload repo contents to remote cache for repo %s: %s"
                        .formatted(repoName, maybeGetStackTrace(e))
                )
            )
        } catch (e: IOException) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "Failed to upload repo contents to remote cache for repo %s: %s"
                        .formatted(repoName, maybeGetStackTrace(e))
                )
            )
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun lookupCache(
        repoName: RepositoryName,
        repoDir: com.google.devtools.build.lib.vfs.Path,
        predeclaredInputHash: String,
        env: com.google.devtools.build.skyframe.SkyFunction.Environment
    ): Boolean {
        try {
            return doLookupCache(repoName, repoDir, predeclaredInputHash, env)
        } catch (e: IOException) {
            throw IOException(
                "Failed to look up repo %s in the remote repo contents cache: %s"
                    .formatted(repoName, maybeGetStackTrace(e)),
                e
            )
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun doLookupCache(
        repoName: RepositoryName,
        repoDir: com.google.devtools.build.lib.vfs.Path,
        predeclaredInputHash: String,
        env: com.google.devtools.build.skyframe.SkyFunction.Environment
    ): Boolean {
        if (repoDir.getFileSystem() !is RemoteExternalOverlayFileSystem) {
            return false
        }

        val context: RemoteActionExecutionContext = buildContext(repoName, CacheOp.DOWNLOAD)
        if (!context.getReadCachePolicy().allowRemoteCache()) {
            return false
        }
        val finalEntry: Final? = fetchFinalCacheEntry(env, context, predeclaredInputHash)
        if (env.valuesMissing() || finalEntry == null) {
            return false
        }

        val markerFileContentFuture: com.google.common.util.concurrent.ListenableFuture<ByteArray?>?
        val markerFile: OutputFile = finalEntry.markerFile
        // Inlining is an optional feature, so we have to be prepared to download the marker file.
        if (markerFile.getContents().isEmpty()) {
            markerFileContentFuture =
                cache.downloadBlob(
                    context, MARKER_FILE_PATH,  /* execPath= */null, markerFile.getDigest()
                )
        } else {
            markerFileContentFuture =
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(markerFile.getContents().toByteArray())
        }
        val repoDirectory: OutputDirectory = finalEntry.repoDirectory
        val repoDirectoryContentFuture: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */?
        Object > com.google.common.util.concurrent.Futures.transformAsync<I?, O?>(
            cache.downloadBlob(
                context, REPO_DIRECTORY_PATH,  /* execPath= */null, repoDirectory.getTreeDigest()
            ),
            { treeBytes -> }<V> com . google . common . util . concurrent . Futures . immediateFuture < V ? > (Tree.parseFrom(
                treeBytes
            )),
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(
            com.google.common.collect.ImmutableList.of<com.google.common.util.concurrent.ListenableFuture<out Any?>?>(
                markerFileContentFuture,
                repoDirectoryContentFuture
            )
        )

        val markerFileContent =
            String(markerFileContentFuture.resultNow(), java.nio.charset.StandardCharsets.ISO_8859_1)
        val maybeRecordedInputs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            DigestWriter.readMarkerFile(markerFileContent, predeclaredInputHash)
        if (maybeRecordedInputs.isEmpty()) {
            return false
        }
        val outdatedReason: java.util.Optional<String?> =
            RepoRecordedInput.isAnyValueOutdated(env, directories, maybeRecordedInputs.get())
        if (env.valuesMissing() || outdatedReason.isPresent()) {
            env.getListener()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        "Unexpectedly outdated cached repo %s: %s"
                            .formatted(repoName, outdatedReason.orElse("unknown reason"))
                    )
                )
            return false
        }

        return remoteFs.injectRemoteRepo(
            repoName, repoDirectoryContentFuture.resultNow(), markerFileContent
        )
    }

    private enum class CacheOp {
        DOWNLOAD,
        UPLOAD,
    }

    private fun buildContext(repoName: RepositoryName, cacheOp: CacheOp): RemoteActionExecutionContext {
        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(
                buildRequestId, commandId, repoName.name,  /* actionMetadata= */null
            )
        // Don't upload local repo contents to the disk cache as the (local) `--repo_contents_cache` is
        // a better alternative for local caching. Do write through the disk cache for downloads from
        // the remote cache to speed up future usage.
        return RemoteActionExecutionContext.Companion.create(metadata)
            .withReadCachePolicy(if (acceptCached) CachePolicy.ANY_CACHE else CachePolicy.NO_CACHE)
            .withWriteCachePolicy(
                when (cacheOp) {
                    CacheOp.DOWNLOAD -> CachePolicy.ANY_CACHE
                    CacheOp.UPLOAD -> if (uploadLocalResults) CachePolicy.REMOTE_CACHE_ONLY else CachePolicy.NO_CACHE
                }
            )
    }

    private fun buildAction(inputHash: String?): Action {
        // We choose to embed the hash into the salt simply because that results in a constant Command
        // message.
        return baseAction.toBuilder()
            .setSalt(ByteString.copyFrom(StringUnsafe.getByteArray(inputHash)))
            .build()
    }

    /**
     * Uploads the intermediate action results representing the inputs recorded at runtime and returns
     * the input hash to use for the final action result.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun uploadIntermediateActionResults(
        context: RemoteActionExecutionContext,
        predeclaredInputHash: String?,
        recordedInputValues: MutableList<WithValue?>
    ): String? {
        // The command is shared by all action results and small enough that FindMissingBlobs is not
        // worthwhile. The REAPI spec requires the command to be uploaded before an action result that
        // references it.
        com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(
            com.google.common.collect.ImmutableSet.of<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(
                cache.uploadBlob(context, commandDigest, COMMAND_BYTES)
            )
        )

        var rollingHash = predeclaredInputHash
        val batches: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<WithValue>> =
            RepoRecordedInput.WithValue.splitIntoBatches(recordedInputValues)
        val futures: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(batches.size())
        for (batch in batches) {
            futures.add(
                addToActionResult(
                    context,
                    buildAction(rollingHash),
                    com.google.common.collect.Collections2.transform<WithValue?, RepoRecordedInput?>(
                        batch,
                        RepoRecordedInput.WithValue::input
                    )
                )
            )
            for (recordedInputValue in batch) {
                rollingHash = rollForwardHash(rollingHash, recordedInputValue)
            }
        }
        com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(futures)
        return rollingHash
    }

    /**
     * Adds the given set of recorded inputs as one of the alternative paths to the action result for
     * the given action, if not already present.
     * 
     * 
     * Most repo rule evaluations with a fixed previous batch of hashes (in particular, the same
     * predeclared inputs hash) will request a fixed set of inputs in the next batch. Thus, most
     * intermediate action results will only contain a single set of recorded inputs.
     */
    private fun addToActionResult(
        context: RemoteActionExecutionContext,
        action: Action,
        newInputs: MutableCollection<RepoRecordedInput?>
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        val actionKey: ActionKey = digestUtil.computeActionKey(action)
        val currentInputsFuture: com.google.common.util.concurrent.ListenableFuture<String?> =
            com.google.common.util.concurrent.Futures.transformAsync<CachedActionResult?, String?>(
                cache.downloadActionResultAsync(
                    context, actionKey,  /* inlineOutErr= */true, com.google.common.collect.ImmutableSet.of<String?>()
                ),
                com.google.common.util.concurrent.AsyncFunction { currentResult: CachedActionResult? ->
                    if (currentResult == null
                        || currentResult.actionResult.getStdoutDigest().getSizeBytes() === 0
                    ) {
                        return@transformAsync com.google.common.util.concurrent.Futures.immediateFuture<String?>("")
                    }
                    fetchStdout(context, currentResult.actionResult)
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        return com.google.common.util.concurrent.Futures.transformAsync<String, java.lang.Void?>(
            currentInputsFuture,
            com.google.common.util.concurrent.AsyncFunction { currentInputsString: String ->
                // RepoRecordedInput.toString() is guaranteed to return a string that doesn't contain
                // spaces or newlines. We can thus safely use spaces to separate inputs within a batch
                // and newlines to separate different batches.
                val newInputString: String =
                    newInputs.stream()
                        .map<String?>(java.util.function.Function { obj: RepoRecordedInput? -> obj.toString() })
                        .collect(Collectors.joining(" "))
                if (currentInputsString.lines()
                        .anyMatch(java.util.function.Predicate { anObject: String? -> newInputString.equals(anObject) })
                ) {
                    // The current batch of inputs is already present, no need to update the action result.
                    return@transformAsync com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(
                        null
                    )
                }
                // Add the new inputs to the top so that the most recently added inputs stay at the top.
                // This could be used to implement a simple "least recently added" eviction strategy in
                // the future in case the size of action results becomes a concern.
                //
                // Note that this update is inherently racy: multiple clients may add inputs concurrently,
                // resulting in some added inputs being lost since the REAPI does not provide a way to
                // update action results atomically. However, since different batches of inputs are
                // already rare and them being added concurrently even more so, the temporary loss of a
                // cache entry is an acceptable trade-off for simplicity.
                val newInputsString = newInputString + '\n' + currentInputsString
                val stdoutBytes: ByteArray = StringUnsafe.getInternalStringBytes(newInputsString)
                val stdoutDigest: Digest? = digestUtil.compute(stdoutBytes)
                val actionResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    ActionResult.newBuilder().setExitCode(0).setStdoutDigest(stdoutDigest).build()
                com.google.common.util.concurrent.Futures.whenAllSucceed<V?>(
                    cache.uploadBlob(context, actionKey.digest, action.toByteString()),
                    cache.uploadBlob(context, stdoutDigest, ByteString.copyFrom(stdoutBytes))
                )
                    .callAsync<java.lang.Void?>(
                        com.google.common.util.concurrent.AsyncCallable {
                            cache.uploadActionResult(
                                context,
                                actionKey,
                                actionResult
                            )
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /** Represents a single AC entry in the internal format used by the remote repo contents cache.  */
    private interface CacheEntry {
        /**
         * A final cache entry containing the contents of a repository.
         * 
         * 
         * Represented as an ActionResult with one output directory and one output file.
         * 
         * @param repoDirectory the contents of the repository directory
         * @param markerFile the contents of the repository's marker file
         */
        class Final(repoDirectory: OutputDirectory, markerFile: OutputFile) : CacheEntry {
            val repoDirectory: OutputDirectory
            val markerFile: OutputFile

            init {
                this.repoDirectory = repoDirectory
                this.markerFile = markerFile
            }
        }

        /**
         * An intermediate cache entry that points to the keys of any number of further AC entries,
         * which can themselves be intermediate or final entries. The remote repo contents cache will
         * try them in order.
         * 
         * @param nextInputHashes the keys under which the next AC entries should be looked up
         */
        class Intermediate(nextInputHashes: com.google.common.collect.ImmutableList<String?>?) : CacheEntry {
            val nextInputHashes: com.google.common.collect.ImmutableList<String?>?

            init {
                this.nextInputHashes = nextInputHashes
            }
        }

        /**
         * The cache entry didn't match any of the formats expected by this version of the remote repo
         * contents cache for the given human-readable reason.
         */
        @kotlin.jvm.JvmRecord
        data class Invalid(val reason: String?) : CacheEntry
    }

    /**
     * Fetches a final cache entry for the given predeclared input hash by recursively following
     * intermediate entries if needed or returns null if no final entry could be found or a Skyframe
     * restart is needed.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun fetchFinalCacheEntry(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        context: RemoteActionExecutionContext,
        predeclaredInputHash: String
    ): Final? {
        var currentHashes: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(predeclaredInputHash)
        while (!currentHashes.isEmpty()) {
            val nextHashes: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (hash in currentHashes) {
                when (fetchCacheEntry(env, context, hash)) {
                    -> {
                        return finalEntry
                    }

                    -> nextHashes.addAll(nextInputHashes)
                    -> env.getListener().handle(com.google.devtools.build.lib.events.Event.warn(reason))
                    null -> {
                        // Keep checking hashes to batch missing values in fewer restarts.
                        com.google.common.base.Preconditions.checkState(env.valuesMissing())
                    }
                }
            }
            if (env.valuesMissing()) {
                return null
            }
            currentHashes = nextHashes.build()
        }
        return null
    }

    // Returns null if and only if values are missing.
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun fetchCacheEntry(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        context: RemoteActionExecutionContext,
        inputHash: String
    ): CacheEntry? {
        val actionKey: ActionKey = ActionKey(digestUtil.compute(buildAction(inputHash)))
        // The marker file is read right after and thus requested to be inlined. If the action result
        // is an intermediate node, the full result will be contained in the stdout, which should thus
        // also be inlined.
        val cachedActionResult: CachedActionResult? =
            cache.downloadActionResult(
                context, actionKey,  /* inlineOutErr= */true, com.google.common.collect.ImmutableSet.of<String?>(
                    MARKER_FILE_PATH
                )
            )
        if (cachedActionResult == null) {
            return Intermediate(com.google.common.collect.ImmutableList.of<String?>())
        }
        val actionResult: ActionResult = cachedActionResult.actionResult

        if (actionResult.getExitCode() !== 0) {
            return com.google.devtools.build.lib.remote.RemoteRepoContentsCacheImpl.CacheEntry.Invalid(
                "Unexpected exit code in action result for remotely cached repo %s:\n%s"
                    .formatted(context.getRequestMetadata().getActionId(), actionResult)
            )
        }
        if (actionResult.getOutputFilesCount() === 1 && actionResult.getOutputDirectoriesCount() === 1 && actionResult.getOutputSymlinksCount() === 0) {
            return Final(
                actionResult.getOutputDirectories(0), actionResult.getOutputFiles(0)
            )
        }
        if (!(actionResult.getOutputFilesCount() === 0 && actionResult.getOutputDirectoriesCount() === 0 && actionResult.getOutputSymlinksCount() === 0 && actionResult.getStdoutDigest()
                .getSizeBytes() > 0)
        ) {
            return com.google.devtools.build.lib.remote.RemoteRepoContentsCacheImpl.CacheEntry.Invalid(
                "Unexpected intermediate action result for remotely cached repo %s:\n%s"
                    .formatted(context.getRequestMetadata().getActionId(), actionResult)
            )
        }
        val stdoutFuture: com.google.common.util.concurrent.ListenableFuture<String?> =
            fetchStdout(context, actionResult)
        com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer(
            com.google.common.collect.ImmutableList.of<com.google.common.util.concurrent.ListenableFuture<String?>?>(
                stdoutFuture
            )
        )

        // The action result's stdout contains multiple lines, each representing a batch of
        // RepoRecordedInputs separated by spaces. A given batch is valid only if all inputs in the
        // batch are, but separate batches are tried independently.
        val nextInputBatches: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<RepoRecordedInput>?> =
            stdoutFuture
                .resultNow()
                .lines()
                .map<com.google.common.collect.ImmutableList<RepoRecordedInput?>?>(
                    java.util.function.Function { line: String? ->
                        SPLIT_ON_SPACE
                            .splitToStream(line)
                            .map<RepoRecordedInput?>(java.util.function.Function { s: String? ->
                                RepoRecordedInput.parse(
                                    s
                                )
                            })
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<RepoRecordedInput?>())
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.common.collect.ImmutableList<RepoRecordedInput>?>())
        val uniqueNextInputs: com.google.common.collect.ImmutableSet<RepoRecordedInput?> =
            nextInputBatches.stream()
                .flatMap<RepoRecordedInput?>(java.util.function.Function { obj: com.google.common.collect.ImmutableList<RepoRecordedInput>? -> obj.stream() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<RepoRecordedInput?>())
        RepoRecordedInput.prefetch(env, directories, uniqueNextInputs)
        if (env.valuesMissing()) {
            return null
        }
        val nextHashes: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        nextBatch@ for (batch in nextInputBatches) {
            var rollingHash = inputHash
            for (input in batch) {
                val value: MaybeValue? = input.getValue(env, directories)
                // Values have been prefetched above.
                com.google.common.base.Preconditions.checkState(!env.valuesMissing())
                if (value !is) {
                    continue@nextBatch
                }
                rollingHash =
                    rollForwardHash(rollingHash, WithValue(input, valueString))
            }
            nextHashes.add(rollingHash)
        }
        return Intermediate(nextHashes.build())
    }

    private fun rollForwardHash(hash: String?, inputWithValue: WithValue): String {
        return Fingerprint()
            .addString(hash)
            .addString(inputWithValue.toString())
            .hexDigestAndReset()
    }

    private fun fetchStdout(
        context: RemoteActionExecutionContext?, actionResult: ActionResult
    ): com.google.common.util.concurrent.ListenableFuture<String?> {
        if (!actionResult.getStdoutRaw().isEmpty()) {
            return com.google.common.util.concurrent.Futures.immediateFuture<String?>(
                StringUnsafe.newInstance(actionResult.getStdoutRaw().toByteArray(), StringUnsafe.LATIN1)
            )
        }
        return com.google.common.util.concurrent.Futures.transform<ByteArray?, String?>(
            cache.downloadBlob(context, actionResult.getStdoutDigest()),
            com.google.common.base.Function { stdout: ByteArray? ->
                StringUnsafe.newInstance(
                    stdout,
                    StringUnsafe.LATIN1
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun maybeGetStackTrace(e: java.lang.Exception): String? {
        return if (verboseFailures) com.google.common.base.Throwables.getStackTraceAsString(e) else e.getMessage()
    }

    private class RepoRemotePathResolver(
        fetchedRepoMarkerFile: com.google.devtools.build.lib.vfs.Path?,
        fetchedRepoDir: com.google.devtools.build.lib.vfs.Path?
    ) : RemotePathResolver {
        override fun localPathToOutputPath(path: com.google.devtools.build.lib.vfs.Path): String {
            // Map repo marker file and contents to fixed locations under the fake remote exec root.
            if (path == fetchedRepoMarkerFile) {
                return MARKER_FILE_PATH
            }
            if (path == fetchedRepoDir) {
                return REPO_DIRECTORY_PATH
            }
            return REPO_DIRECTORY_PATH + "/" + path.relativeTo(fetchedRepoDir).getPathString()
        }

        override fun localPathToOutputPath(execPath: PathFragment?): String? {
            throw java.lang.UnsupportedOperationException("Not used")
        }

        val workingDirectory: PathFragment?
            get() {
                throw java.lang.UnsupportedOperationException("Not used")
            }

        override fun outputPathToLocalPath(outputPath: String?): com.google.devtools.build.lib.vfs.Path? {
            throw java.lang.UnsupportedOperationException("Not used")
        }

        override fun localPathToExecPath(localPath: PathFragment?): PathFragment? {
            throw java.lang.UnsupportedOperationException("Not used")
        }

        override fun getInputMapping(
            context: SpawnRunner.SpawnExecutionContext?, willAccessRepeatedly: Boolean
        ): SortedMap<PathFragment?, ActionInput?>? {
            throw java.lang.UnsupportedOperationException("Not used")
        }

        val fetchedRepoMarkerFile: com.google.devtools.build.lib.vfs.Path?
        val fetchedRepoDir: com.google.devtools.build.lib.vfs.Path?

        init {
            this.fetchedRepoMarkerFile = fetchedRepoMarkerFile
            this.fetchedRepoDir = fetchedRepoDir
        }
    }

    companion object {
        private val GUID: UUID = UUID.fromString("f4a165a9-5557-45a7-bf25-230b6d42393a")
        private const val MARKER_FILE_PATH = ".recorded_inputs"
        private const val REPO_DIRECTORY_PATH = "repo_contents"
        private val SPLIT_ON_SPACE: com.google.common.base.Splitter = com.google.common.base.Splitter.on(' ')

        private val COMMAND: Command =
            Command.newBuilder() // A unique but nonsensical command that is valid on all platforms. It is never executed,
                // but should pass all checks that an RE backend may apply to commands.
                .addArguments(GUID.toString())
                .addOutputPaths(MARKER_FILE_PATH)
                .addOutputPaths(REPO_DIRECTORY_PATH)
                .addOutputFiles(MARKER_FILE_PATH)
                .addOutputDirectories(REPO_DIRECTORY_PATH)
                .setPlatform(Platform.getDefaultInstance())
                .build()
        private val COMMAND_BYTES: ByteString = COMMAND.toByteString()
        private val INPUT_ROOT: Directory? = Directory.getDefaultInstance()
    }
}
