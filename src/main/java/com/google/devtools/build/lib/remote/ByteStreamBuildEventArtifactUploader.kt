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

import build.bazel.remote.execution.v2.Digest

/** A [BuildEventArtifactUploader] backed by [CombinedCache].  */
internal class ByteStreamBuildEventArtifactUploader(
    executor: java.util.concurrent.Executor?,
    reporter: ExtendedEventHandler,
    verboseFailures: Boolean,
    combinedCache: CombinedCache,
    remoteInstanceName: String?,
    remoteBytestreamUriPrefix: String?,
    buildRequestId: String?,
    commandId: String?,
    xattrProvider: XattrProvider?,
    remoteBuildEventUploadMode: RemoteBuildEventUploadMode?
) : io.netty.util.AbstractReferenceCounted(), BuildEventArtifactUploader {
    private val executor: java.util.concurrent.Executor?
    private val reporter: ExtendedEventHandler
    private val verboseFailures: Boolean
    private val combinedCache: CombinedCache
    private val buildRequestId: String?
    private val commandId: String?
    private val remoteInstanceName: String?
    private val remoteBytestreamUriPrefix: String?

    private val shutdown: AtomicBoolean = AtomicBoolean()
    private val scheduler: io.reactivex.rxjava3.core.Scheduler

    private val xattrProvider: XattrProvider?
    private val remoteBuildEventUploadMode: RemoteBuildEventUploadMode?

    init {
        this.executor = executor
        this.reporter = reporter
        this.verboseFailures = verboseFailures
        this.combinedCache = combinedCache
        this.buildRequestId = buildRequestId
        this.commandId = commandId
        this.remoteInstanceName = remoteInstanceName
        this.remoteBytestreamUriPrefix = remoteBytestreamUriPrefix
        this.scheduler = Schedulers.from(executor)
        this.xattrProvider = xattrProvider
        this.remoteBuildEventUploadMode = remoteBuildEventUploadMode
    }

    private class PathMetadata(
        path: com.google.devtools.build.lib.vfs.Path?,
        digest: Digest,
        directory: Boolean,
        symlink: Boolean,
        remote: Boolean,
        isBuildToolLog: Boolean,
        digestFunction: DigestFunction.Value
    ) {
        private val path: com.google.devtools.build.lib.vfs.Path?
        private val digest: Digest
        val isDirectory: Boolean
        val isSymlink: Boolean
        val isRemote: Boolean
        val isBuildToolLog: Boolean
        private val digestFunction: DigestFunction.Value

        init {
            this.path = path
            this.digest = digest
            this.isDirectory = directory
            this.isSymlink = symlink
            this.isRemote = remote
            this.isBuildToolLog = isBuildToolLog
            this.digestFunction = digestFunction
        }

        fun getPath(): com.google.devtools.build.lib.vfs.Path? {
            return path
        }

        fun getDigest(): Digest {
            return digest
        }

        fun getDigestFunction(): DigestFunction.Value {
            return digestFunction
        }
    }

    /**
     * Collects metadata for `file`. Depending on the underlying filesystem used this method
     * might do I/O.
     */
    @Throws(IOException::class)
    private fun readPathMetadata(path: com.google.devtools.build.lib.vfs.Path, file: LocalFile): PathMetadata {
        val digestUtil: DigestUtil = DigestUtil(xattrProvider, path.getFileSystem().getDigestFunction())

        if (file.type === LocalFileType.OUTPUT_DIRECTORY
            || ((file.type === LocalFileType.SUCCESSFUL_TEST_OUTPUT
                    || file.type === LocalFileType.FAILED_TEST_OUTPUT)
                    && path.isDirectory())
        ) {
            return PathMetadata(
                path,  /* digest= */
                null,  /* directory= */
                true,  /* symlink= */
                false,  /* remote= */
                false,  /* isBuildToolLog= */
                false,  /* digestFunction= */
                digestUtil.getDigestFunction()
            )
        }
        if (file.type === LocalFileType.OUTPUT_SYMLINK) {
            return PathMetadata(
                path,  /* digest= */
                null,  /* directory= */
                false,  /* symlink= */
                true,  /* remote= */
                false,  /* isBuildToolLog= */
                false,  /* digestFunction= */
                digestUtil.getDigestFunction()
            )
        }

        val digest: Digest = digestUtil.compute(path)
        val isBuildToolLog =
            file.type === LocalFileType.LOG || file.type === LocalFileType.PERFORMANCE_LOG
        return PathMetadata(
            path,
            digest,  /* directory= */
            false,  /* symlink= */
            false,
            isRemoteFile(path),
            isBuildToolLog,
            digestUtil.getDigestFunction()
        )
    }

    private fun shouldUpload(path: PathMetadata): Boolean {
        var result =
            path.getDigest() != null && !path.isRemote && !path.isDirectory && !path.isSymlink

        if (remoteBuildEventUploadMode == RemoteBuildEventUploadMode.MINIMAL) {
            result = result && (path.isBuildToolLog || isBuildOrTestLog(path))
        }

        return result
    }

    private fun isBuildOrTestLog(path: PathMetadata): Boolean {
        return TEST_LOG_PATTERN.matcher(path.getPath().getPathString()).matches()
                || BUILD_LOG_PATTERN.matcher(path.getPath().getPathString()).matches()
    }

    private fun queryCombinedCache(
        combinedCache: CombinedCache, context: RemoteActionExecutionContext?, paths: MutableList<PathMetadata>
    ): Single<MutableList<PathMetadata?>?>? {
        val knownPaths: MutableList<PathMetadata?> = java.util.ArrayList<PathMetadata?>(paths.size())
        val filesToQuery: MutableList<PathMetadata> = java.util.ArrayList<PathMetadata>()
        val digestsToQuery: MutableSet<Digest?> = HashSet<Digest?>()
        for (path in paths) {
            if (shouldUpload(path)) {
                filesToQuery.add(path)
                digestsToQuery.add(path.getDigest())
            } else {
                knownPaths.add(path)
            }
        }

        if (digestsToQuery.isEmpty()) {
            return Single.just<MutableList<PathMetadata?>?>(knownPaths)
        }
        return RxFutures.toSingle<com.google.common.collect.ImmutableSet<Digest?>?>(io.reactivex.rxjava3.functions.Supplier {
            combinedCache.findMissingDigests(
                context,
                digestsToQuery
            )
        }, executor)
            .onErrorResumeNext(
                io.reactivex.rxjava3.functions.Function { error: Throwable? ->
                    reportUploadError(error, null, null)
                    Single.just<com.google.common.collect.ImmutableSet<Digest?>?>(
                        com.google.common.collect.ImmutableSet.copyOf<Digest?>(
                            digestsToQuery
                        )
                    )
                })
            .map<MutableList<PathMetadata?>?>(
                io.reactivex.rxjava3.functions.Function { missingDigests: com.google.common.collect.ImmutableSet<Digest?>? ->
                    processQueryResult(missingDigests, filesToQuery, knownPaths)
                    knownPaths
                })
    }

    private fun reportUploadError(error: Throwable?, path: com.google.devtools.build.lib.vfs.Path?, digest: Digest?) {
        if (error is CancellationException) {
            return
        }

        var errorMessage = "Uploading BEP referenced local file"
        if (path != null) {
            errorMessage += " " + path
        }
        if (digest != null) {
            errorMessage += " " + digest
        }
        errorMessage += ": " + com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(
            error,
            verboseFailures
        )

        reporter.handle(com.google.devtools.build.lib.events.Event.warn(errorMessage))
    }

    private fun uploadLocalFiles(
        combinedCache: CombinedCache, context: RemoteActionExecutionContext?, paths: MutableList<PathMetadata?>
    ): Single<MutableList<PathMetadata?>?>? {
        return Flowable.fromIterable<PathMetadata?>(paths)
            .flatMapSingle<PathMetadata?>(
                io.reactivex.rxjava3.functions.Function { path: PathMetadata? ->
                    if (!shouldUpload(path!!)) {
                        return@flatMapSingle Single.just<PathMetadata?>(path)
                    }
                    RxFutures.toCompletable(
                        io.reactivex.rxjava3.functions.Supplier {
                            combinedCache.uploadFile(
                                context,
                                path.getDigest(),
                                path.getPath()
                            )
                        },
                        executor
                    )
                        .toSingle<PathMetadata?>(
                            io.reactivex.rxjava3.functions.Supplier {
                                PathMetadata(
                                    path.getPath(),
                                    path.getDigest(),
                                    path.isDirectory,
                                    path.isSymlink,  // set remote to true so the PathConverter will use bytestream://
                                    // scheme to convert the URI for this file
                                    /* remote= */
                                    true,
                                    path.isBuildToolLog,
                                    path.getDigestFunction()
                                )
                            })
                        .onErrorResumeNext(
                            io.reactivex.rxjava3.functions.Function { error: Throwable? ->
                                reportUploadError(error, path.getPath(), path.getDigest())
                                Single.just<PathMetadata?>(path)
                            })
                })
            .collect<MutableList<PathMetadata?>?, Any?>(Collectors.toList())
    }

    private fun getRemoteServerInstanceName(combinedCache: CombinedCache): Single<String?>? {
        if (!com.google.common.base.Strings.isNullOrEmpty(remoteBytestreamUriPrefix)) {
            return Single.just<String?>(remoteBytestreamUriPrefix)
        }

        return RxFutures.toSingle<String?>(
            io.reactivex.rxjava3.functions.Supplier { combinedCache.getRemoteAuthority() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
            .map<String?>(
                io.reactivex.rxjava3.functions.Function { a: String? ->
                    if (!com.google.common.base.Strings.isNullOrEmpty(remoteInstanceName)) {
                        return@map a + "/" + remoteInstanceName
                    }
                    a
                })
    }

    private fun doUpload(files: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>): Single<PathConverter?>? {
        if (files.isEmpty()) {
            return Single.just<PathConverter?>(PathConverter.NO_CONVERSION)
        }

        val metadata: RequestMetadata? =
            TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "bes-upload", null)
        val context: RemoteActionExecutionContext? =
            RemoteActionExecutionContext.Companion.create(metadata)
                .withWriteCachePolicy(CachePolicy.REMOTE_CACHE_ONLY)

        return Single.using<T?, CombinedCache?>(
            io.reactivex.rxjava3.functions.Supplier { combinedCache.retain() },
            io.reactivex.rxjava3.functions.Function { combinedCache: CombinedCache? ->
                Flowable.fromIterable<MutableMap.MutableEntry<com.google.devtools.build.lib.vfs.Path, LocalFile>?>(files.entrySet())
                    .map<PathMetadata?>(
                        io.reactivex.rxjava3.functions.Function { entry: MutableMap.MutableEntry<com.google.devtools.build.lib.vfs.Path, LocalFile>? ->
                            val path: com.google.devtools.build.lib.vfs.Path = entry.getKey()
                            val file: LocalFile = entry.getValue()
                            try {
                                return@map readPathMetadata(path, file)
                            } catch (e: IOException) {
                                reportUploadError(e, path, null)
                                return@map PathMetadata(
                                    path,  /* digest= */
                                    null,  /* directory= */
                                    false,  /* symlink= */
                                    false,  /* remote= */
                                    false,  /* isBuildToolLog= */
                                    false,
                                    DigestFunction.Value.SHA256
                                )
                            }
                        })
                    .collect<MutableList<PathMetadata?>?, Any?>(Collectors.toList())
                    .flatMap<MutableList<PathMetadata?>?>(io.reactivex.rxjava3.functions.Function { paths: MutableList<PathMetadata>? ->
                        queryCombinedCache(
                            combinedCache,
                            context,
                            paths!!
                        )
                    })
                    .flatMap<MutableList<PathMetadata?>?>(io.reactivex.rxjava3.functions.Function { paths: MutableList<PathMetadata?>? ->
                        uploadLocalFiles(
                            combinedCache,
                            context,
                            paths!!
                        )
                    })
                    .flatMap<PathConverterImpl?>(
                        io.reactivex.rxjava3.functions.Function { paths: MutableList<PathMetadata?>? ->
                            getRemoteServerInstanceName(combinedCache)
                                .map<PathConverterImpl?>(
                                    io.reactivex.rxjava3.functions.Function { remoteServerInstanceName: String? ->
                                        PathConverterImpl(
                                            remoteServerInstanceName,
                                            paths,
                                            remoteBuildEventUploadMode
                                        )
                                    })
                        })
            },
            io.reactivex.rxjava3.functions.Consumer { obj: CombinedCache? -> obj.release() })
    }

    public override fun upload(files: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>): com.google.common.util.concurrent.ListenableFuture<PathConverter?> {
        return RxFutures.toListenableFuture<PathConverter?>(doUpload(files).subscribeOn(scheduler))
    }

    public override fun mayBeSlow(): Boolean {
        return true
    }

    override fun deallocate() {
        if (shutdown.getAndSet(true)) {
            return
        }
        combinedCache.release()
    }

    override fun touch(o: Any?): io.netty.util.ReferenceCounted {
        return this
    }

    private class PathConverterImpl(
        remoteServerInstanceName: String?,
        uploads: MutableList<PathMetadata>?,
        remoteBuildEventUploadMode: RemoteBuildEventUploadMode?
    ) : PathConverter {
        private val remoteServerInstanceName: String?
        private val pathToMetadata: MutableMap<com.google.devtools.build.lib.vfs.Path?, PathMetadata?>
        private val skippedPaths: MutableSet<com.google.devtools.build.lib.vfs.Path?>
        private val localPaths: MutableSet<com.google.devtools.build.lib.vfs.Path?>

        init {
            com.google.common.base.Preconditions.checkNotNull<MutableList<PathMetadata?>?>(uploads)
            this.remoteServerInstanceName = remoteServerInstanceName
            pathToMetadata =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<com.google.devtools.build.lib.vfs.Path?, PathMetadata?>(
                    uploads.size()
                )
            val skippedPaths: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.vfs.Path?>()
            val localPaths: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?> =
                com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.vfs.Path?>()
            for (metadata in uploads!!) {
                val path: com.google.devtools.build.lib.vfs.Path? = metadata.getPath()
                val digest: Digest? = metadata.getDigest()
                if (digest != null) {
                    // Always use bytestream:// in MINIMAL mode
                    if (remoteBuildEventUploadMode == RemoteBuildEventUploadMode.MINIMAL) {
                        pathToMetadata.put(path, metadata)
                    } else if (metadata.isRemote) {
                        pathToMetadata.put(path, metadata)
                    } else {
                        localPaths.add(path)
                    }
                } else {
                    skippedPaths.add(path)
                }
            }
            this.skippedPaths = skippedPaths.build()
            this.localPaths = localPaths.build()
        }

        public override fun apply(path: com.google.devtools.build.lib.vfs.Path?): String? {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(path)

            if (localPaths.contains(path)) {
                return java.lang.String.format("file://%s", path.getPathString())
            }

            val metadata = pathToMetadata.get(path)
            if (metadata == null) {
                if (skippedPaths.contains(path)) {
                    return null
                }
                // It's a programming error to reference a file that has not been uploaded.
                throw java.lang.IllegalStateException(
                    java.lang.String.format("Illegal file reference: '%s'", path.getPathString())
                )
            }

            val digest: Digest = metadata.getDigest()
            val digestFunction: DigestFunction.Value = metadata.getDigestFunction()
            val out: String?
            if (DigestUtil.isOldStyleDigestFunction(digestFunction)) {
                out =
                    java.lang.String.format(
                        "bytestream://%s/blobs/%s/%d",
                        remoteServerInstanceName, digest.getHash(), digest.getSizeBytes()
                    )
            } else {
                out =
                    java.lang.String.format(
                        "bytestream://%s/blobs/%s/%s/%d",
                        remoteServerInstanceName,
                        com.google.common.base.Ascii.toLowerCase(digestFunction.getValueDescriptor().getName()),
                        digest.getHash(),
                        digest.getSizeBytes()
                    )
            }
            return out
        }
    }

    companion object {
        private val TEST_LOG_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(".*/bazel-out/[^/]*/testlogs/.*")
        private val BUILD_LOG_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(".*/bazel-out/_tmp/actions/std(err|out)-.*")

        /** Returns `true` if Bazel knows that the file is stored on a remote system.  */
        @Throws(IOException::class)
        private fun isRemoteFile(file: com.google.devtools.build.lib.vfs.Path): Boolean {
            return file.getFileSystem() is RemoteActionFileSystem
                    && (file.getFileSystem() as RemoteActionFileSystem).isRemote(file)
        }

        private fun processQueryResult(
            missingDigests: com.google.common.collect.ImmutableSet<Digest?>,
            filesToQuery: MutableList<PathMetadata>,
            knownRemotePaths: MutableList<PathMetadata?>
        ) {
            for (file in filesToQuery) {
                if (missingDigests.contains(file.getDigest())) {
                    knownRemotePaths.add(file)
                } else {
                    val remotePathMetadata =
                        PathMetadata(
                            file.getPath(),
                            file.getDigest(),
                            file.isDirectory,
                            file.isSymlink,  /* remote= */
                            true,
                            file.isBuildToolLog,
                            file.getDigestFunction()
                        )
                    knownRemotePaths.add(remotePathMetadata)
                }
            }
        }
    }
}
