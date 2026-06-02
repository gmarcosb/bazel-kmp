// Copyright 2024 The Bazel Authors. All rights reserved.
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

/** Output service implementation for the remote build with local output service daemon.  */
class BazelOutputService(
    outputBase: com.google.devtools.build.lib.vfs.Path,
    execRootSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path>,
    outputPathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path>,
    digestFunction: DigestFunction.Value?,
    remoteCache: String?,
    remoteInstanceName: String?,
    remoteOutputServiceOutputPathPrefix: String?,
    verboseFailures: Boolean,
    retrier: RemoteRetrier,
    channel: ReferenceCountedChannel,
    lastBuildId: String?
) : OutputService {
    private val outputBaseId: String
    private val execRootSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path>
    private val outputPathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path>
    private val digestFunction: DigestFunction.Value?
    private val remoteCache: String?
    private val remoteInstanceName: String?
    private val remoteOutputServiceOutputPathPrefix: String?
    private val verboseFailures: Boolean
    private val retrier: RemoteRetrier
    private val channel: ReferenceCountedChannel
    private val lastBuildId: String?

    private var buildId: String? = null
    private var outputPathTarget: PathFragment? = null

    init {
        this.outputBaseId = DigestUtil.hashCodeToString(
            com.google.common.hash.Hashing.md5()
                .hashString(outputBase.toString(), java.nio.charset.StandardCharsets.UTF_8)
        )
        this.execRootSupplier = execRootSupplier
        this.outputPathSupplier = outputPathSupplier
        this.digestFunction = digestFunction
        this.remoteCache = remoteCache
        this.remoteInstanceName = remoteInstanceName
        this.remoteOutputServiceOutputPathPrefix = remoteOutputServiceOutputPathPrefix
        this.verboseFailures = verboseFailures
        this.retrier = retrier
        this.channel = channel
        this.lastBuildId = lastBuildId
    }

    fun shutdown() {
        channel.release()
    }

    override fun getFileSystemName(outputBaseFileSystemName: String?): String {
        return "BazelOutputService"
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    public override fun startBuild(
        buildId: UUID,
        workspaceName: String?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        finalizeActions: Boolean
    ): ModifiedFileSet {
        com.google.common.base.Preconditions.checkState(this.buildId == null, "this.buildId must be null")
        this.buildId = buildId.toString()
        val outputPathPrefix: PathFragment = PathFragment.create(remoteOutputServiceOutputPathPrefix)
        if (!outputPathPrefix.isEmpty() && !outputPathPrefix.isAbsolute()) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            java.lang.String.format(
                                "--experimental_remote_output_service_path_prefix must be an absolute"
                                        + " path, got '%s'",
                                outputPathPrefix
                            )
                        )
                        .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                        .build()
                )
            )
        }
        val outputPath: com.google.devtools.build.lib.vfs.Path = outputPathSupplier.get()

        // Notify the remote output service that the build is about to start. The remote output service
        // will return the directory in which it wants us to let the build take place.
        val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            StartBuildRequest.newBuilder()
                .setVersion(1)
                .setOutputBaseId(outputBaseId)
                .setBuildId(this.buildId)
                .setArgs(
                    Any.pack(
                        StartBuildArgs.newBuilder()
                            .setRemoteCache(remoteCache)
                            .setInstanceName(remoteInstanceName)
                            .setDigestFunction(digestFunction)
                            .build()
                    )
                )
                .setOutputPathPrefix(outputPathPrefix.toString())
                .putOutputPathAliases(outputPath.toString(), ".")
                .build()

        val response: StartBuildResponse
        try {
            response = startBuild(request)
        } catch (e: IOException) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            java.lang.String.format(
                                "StartBuild failed: %s",
                                com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(
                                    e,
                                    verboseFailures
                                )
                            )
                        )
                        .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                        .build()
                )
            )
        }

        com.google.common.base.Preconditions.checkState(outputPathTarget == null, "outputPathTarget must be null")
        outputPathTarget = constructOutputPathTarget(outputPathPrefix, response)
        prepareOutputPath(outputPath, outputPathTarget)

        if (finalizeActions && response.hasInitialOutputPathContents()) {
            val initialOutputPathContents: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                response.getInitialOutputPathContents()
            if (!initialOutputPathContents.getBuildId().equals(lastBuildId)) {
                return ModifiedFileSet.EVERYTHING_DELETED
            }

            // TODO(chiwang): Handle StartBuildResponse.initial_output_path_contents
        }

        return ModifiedFileSet.EVERYTHING_MODIFIED
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun startBuild(request: StartBuildRequest?): StartBuildResponse {
        return retrier.execute<StartBuildResponse, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.StartBuild").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .startBuild(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun stageArtifacts(files: MutableList<FileMetadata>) {
        val outputPath: com.google.devtools.build.lib.vfs.Path? = outputPathSupplier.get()
        val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            StageArtifactsRequest.newBuilder()
        request.setBuildId(buildId)
        for (file in files) {
            request.addArtifacts(
                StageArtifactsRequest.Artifact.newBuilder()
                    .setPath(file.path().relativeTo(outputPath).toString())
                    .setLocator(
                        Any.pack(FileArtifactLocator.newBuilder().setDigest(file.digest()).build())
                    )
                    .build()
            )
        }
        val response: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            stageArtifacts(request.build())
        if (response.getResponsesCount() !== files.size()) {
            throw IOException(
                java.lang.String.format(
                    "StageArtifacts failed: expect %s responses from StageArtifactsResponse, got %s",
                    files.size(), response.getResponsesCount()
                )
            )
        }

        for (i in files.indices) {
            val fileResponse: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                response.getResponses(i)
            if (fileResponse.getStatus().getCode() !== io.grpc.Status.Code.OK.value()) {
                throw IOException(
                    java.lang.String.format(
                        "Failed to stage %s, code: %s",
                        files.get(i).path().relativeTo(outputPath), fileResponse.getStatus()
                    )
                )
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun stageArtifacts(request: StageArtifactsRequest?): StageArtifactsResponse? {
        return retrier.execute<StageArtifactsResponse?, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.StageArtifacts").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .stageArtifacts(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    override fun finalizeBuild(buildSuccessful: Boolean) {
        val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FinalizeBuildRequest.newBuilder()
                .setBuildId(com.google.common.base.Preconditions.checkNotNull<T?>(buildId))
                .setBuildSuccessful(buildSuccessful)
                .build()
        try {
            val unused: FinalizeBuildResponse? = finalizeBuild(request)
        } catch (e: IOException) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            java.lang.String.format(
                                "FinalizeBuild failed: %s",
                                com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage(
                                    e,
                                    verboseFailures
                                )
                            )
                        )
                        .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                        .build()
                )
            )
        } finally {
            this.buildId = null
            this.outputPathTarget = null
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun finalizeBuild(request: FinalizeBuildRequest?): FinalizeBuildResponse? {
        return retrier.execute<FinalizeBuildResponse?, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.FinalizeBuild").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .finalizeBuild(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun finalizeAction(action: Action, outputMetadataStore: OutputMetadataStore) {
        val execRoot: com.google.devtools.build.lib.vfs.Path = execRootSupplier.get()
        val outputPath: com.google.devtools.build.lib.vfs.Path? = outputPathSupplier.get()

        val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FinalizeArtifactsRequest.newBuilder()
        request.setBuildId(buildId)
        for (output in action.getOutputs()) {
            if (outputMetadataStore.artifactOmitted(output)) {
                continue
            }

            if (output.isTreeArtifact()) {
                // TODO(chiwang): Use TreeArtifactLocator
                val children: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    outputMetadataStore.getTreeArtifactValue(output as SpecialArtifact).getChildren()
                for (child in children) {
                    addArtifact(outputMetadataStore, execRoot, outputPath, request, child)
                }
            } else {
                addArtifact(outputMetadataStore, execRoot, outputPath, request, output)
            }
        }

        val unused: FinalizeArtifactsResponse? = finalizeArtifacts(request.build())
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun finalizeArtifacts(request: FinalizeArtifactsRequest?): FinalizeArtifactsResponse? {
        return retrier.execute<FinalizeArtifactsResponse?, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.FinalizeArtifacts").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .finalizeArtifacts(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    private class BazelOutputServiceFile(digest: Digest?) : FileStatusWithDigest {
        val isFile: Boolean
            get() = true

        val isDirectory: Boolean
            get() = false

        val isSymbolicLink: Boolean
            get() = false

        val isSpecialFile: Boolean
            get() = false

        val size: Long
            get() = digest.getSizeBytes()

        val lastModifiedTime: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get last modified time")
            }

        val lastChangeTime: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get last change time")
            }

        val nodeId: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get node id")
            }

        override fun getDigest(): ByteArray? {
            return DigestUtil.toBinaryDigest(digest)
        }

        val digest: Digest?

        init {
            this.digest = digest
        }
    }

    @kotlin.jvm.JvmRecord
    private data class BazelOutputServiceSymlink(val target: String?) : FileStatusWithDigest {
        val isFile: Boolean
            get() = false

        val isDirectory: Boolean
            get() = false

        val isSymbolicLink: Boolean
            get() = true

        val isSpecialFile: Boolean
            get() = false

        val size: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get size")
            }

        val lastModifiedTime: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get last modified time")
            }

        val lastChangeTime: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get last change time")
            }

        val nodeId: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get node id")
            }

        val digest: ByteArray?
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get digest")
            }
    }

    private class BazelOutputServiceDirectory : FileStatusWithDigest {
        val isFile: Boolean
            get() = false

        val isDirectory: Boolean
            get() = true

        val isSymbolicLink: Boolean
            get() = false

        val isSpecialFile: Boolean
            get() = false

        val size: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get size")
            }

        val lastModifiedTime: Long
            get() = 0

        val lastChangeTime: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get last change time")
            }

        val nodeId: Long
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get node id")
            }

        val digest: ByteArray?
            get() {
                throw java.lang.UnsupportedOperationException("Cannot get digest")
            }
    }

    val batchStatter: BatchStat?
        get() = BatchStat { paths: Iterable<PathFragment?>? ->
            val outputPath: PathFragment = outputPathSupplier.get().asFragment()
            val execRoot: com.google.devtools.build.lib.vfs.Path = execRootSupplier.get()

            val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                BatchStatRequest.newBuilder()
            request.setBuildId(com.google.common.base.Preconditions.checkNotNull<T?>(buildId))

            val unsupportedPathIndexSet: HashSet<Int?> = HashSet<Int?>()
            var index = 0
            for (execPath in paths!!) {
                var pathString: String? = null
                val path: PathFragment = execRoot.getRelative(execPath).asFragment()
                if (path.startsWith(outputPath)) {
                    pathString = path.relativeTo(outputPath).toString()
                } else if (path.startsWith(
                        com.google.common.base.Preconditions.checkNotNull<PathFragment?>(
                            outputPathTarget
                        )
                    )
                ) {
                    pathString = path.relativeTo(outputPathTarget).toString()
                }

                if (pathString == null) {
                    unsupportedPathIndexSet.add(index)
                } else {
                    request.addPaths(pathString)
                }
                ++index
            }

            val response: BatchStatResponse = this@BazelOutputService.batchStat(request.build())
            if (response.getResponsesCount() !== request.getPathsCount()) {
                throw IOException(
                    java.lang.String.format(
                        "BatchStat failed: expect %s responses, got %s",
                        request.getPathsCount(), response.getResponsesCount()
                    )
                )
            }

            val result: java.util.ArrayList<FileStatusWithDigest?> = java.util.ArrayList<FileStatusWithDigest?>(index)
            for (i in 0..<index) {
                if (unsupportedPathIndexSet.contains(i)) {
                    result.add(null)
                    continue
                }

                val statResponse: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    response.getResponses(i)
                if (!statResponse.hasStat()) {
                    result.add(null)
                    continue
                }

                val stat: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    statResponse.getStat()
                if (stat.hasFile() && stat.getFile().hasLocator()) {
                    val locator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        stat.getFile().getLocator()
                    result.add(
                        BazelOutputServiceFile(locator.unpack(FileArtifactLocator::class.java).getDigest())
                    )
                } else if (stat.hasSymlink()) {
                    // TODO(chiwang): The target is currently unused by the call site, instead it resolves the
                    //  symlink manually. Optimize it.
                    result.add(BazelOutputServiceSymlink(stat.getSymlink().getTarget()))
                } else if (stat.hasDirectory()) {
                    result.add(BazelOutputServiceDirectory())
                } else {
                    result.add(null)
                }
            }
            result
        }

    override fun canCreateSymlinkTree(): Boolean {
        return false
    }

    override fun createSymlinkTree(
        symlinks: MutableMap<PathFragment?, PathFragment?>?, symlinkTreeRoot: PathFragment?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    override fun clean() {
        val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CleanRequest.newBuilder().setOutputBaseId(outputBaseId).build()
        try {
            val unused: CleanResponse? = clean(request)
        } catch (e: IOException) {
            throw EnvironmentalExecException(e, Code.UNEXPECTED_EXCEPTION)
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun clean(request: CleanRequest?): CleanResponse? {
        return retrier.execute<CleanResponse?, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.Clean").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .clean(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun batchStat(request: BatchStatRequest?): BatchStatResponse {
        return retrier.execute<BatchStatResponse, java.lang.RuntimeException?>(
            RetryableCallable {
                channel.withChannelBlocking<Any?>(
                    com.google.devtools.build.lib.remote.ReferenceCountedChannel.IOFunction { channel: io.grpc.Channel? ->
                        try {
                            com.google.devtools.build.lib.profiler.Profiler.instance()
                                .profile("BazelOutputService.BatchStat").use { sc ->
                                    return@withChannelBlocking BazelOutputServiceGrpc.newBlockingStub(channel)
                                        .batchStat(request)
                                }
                        } catch (e: StatusRuntimeException) {
                            throw IOException(e)
                        }
                    })
            })
    }

    override fun getXattrProvider(delegate: XattrProvider?): XattrProvider {
        return object : DelegatingXattrProvider(delegate) {
            @Throws(IOException::class)
            override fun getFastDigest(path: com.google.devtools.build.lib.vfs.Path): ByteArray? {
                val outputPath: com.google.devtools.build.lib.vfs.Path = outputPathSupplier.get()
                val buildId: String =
                    com.google.common.base.Preconditions.checkNotNull<String>(this@BazelOutputService.buildId)
                val outputPathTarget: PathFragment =
                    com.google.common.base.Preconditions.checkNotNull<PathFragment>(this@BazelOutputService.outputPathTarget)

                var pathString: String? = null
                if (path.startsWith(outputPath)) {
                    pathString = path.relativeTo(outputPath).toString()
                } else if (path.startsWith(outputPathTarget)) {
                    pathString = path.asFragment().relativeTo(outputPathTarget).toString()
                }
                if (pathString == null) {
                    return super.getFastDigest(path)
                }

                val request: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    BatchStatRequest.newBuilder().setBuildId(buildId).addPaths(pathString).build()
                val response: BatchStatResponse
                try {
                    response = batchStat(request)
                } catch (e: java.lang.InterruptedException) {
                    throw IOException(e)
                }

                if (response.getResponsesCount() !== 1) {
                    throw IOException(
                        java.lang.String.format(
                            "BatchStat failed: expect 1 response, got %s", response.getResponsesCount()
                        )
                    )
                }

                val statResponse: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    response.getResponses(0)
                if (!statResponse.hasStat()) {
                    throw FileNotFoundException(path.getPathString())
                }

                val stat: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    statResponse.getStat()
                if (stat.hasFile()) {
                    val file: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        stat.getFile()
                    if (file.hasLocator()) {
                        val locator: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            file.getLocator().unpack(FileArtifactLocator::class.java)
                        return DigestUtil.toBinaryDigest(locator.getDigest())
                    }
                }

                return null
            }
        }
    }

    companion object {
        @Throws(AbruptExitException::class)
        private fun prepareOutputPath(outputPath: com.google.devtools.build.lib.vfs.Path, target: PathFragment?) {
            // Plant a symlink at bazel-out pointing to the target returned from the remote output service.
            try {
                if (!outputPath.isSymbolicLink()) {
                    outputPath.deleteTree()
                }
                com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(outputPath, target)
            } catch (e: IOException) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format("Failed to plant output path symlink: %s", e.getMessage())
                            )
                            .setExecution(
                                Execution.newBuilder().setCode(Code.LOCAL_OUTPUT_DIRECTORY_SYMLINK_FAILURE)
                            )
                            .build()
                    ),
                    e
                )
            }
        }

        @Throws(AbruptExitException::class)
        private fun constructOutputPathTarget(
            outputPathPrefix: PathFragment, response: StartBuildResponse
        ): PathFragment {
            val outputPathSuffix: PathFragment = PathFragment.create(response.getOutputPathSuffix())
            if (outputPathPrefix.isEmpty() && !outputPathSuffix.isAbsolute()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format(
                                    "Expect StartBuildResponse.output_path_suffix to be an absolute path"
                                            + " (because StartBuildRequest.output_path_prefix is empty), got %s.",
                                    if (outputPathSuffix.isEmpty())
                                        "an empty string"
                                    else
                                        response.getOutputPathSuffix()
                                )
                            )
                            .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                            .build()
                    )
                )
            } else if (outputPathSuffix.isAbsolute()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format(
                                    "Expect StartBuildResponse.output_path_suffix to be a relative path, got"
                                            + " %s.",
                                    response.getOutputPathSuffix()
                                )
                            )
                            .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                            .build()
                    )
                )
            } else if (outputPathSuffix.containsUplevelReferences()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format(
                                    "Expect normalized StartBuildResponse.output_path_suffix to not contain"
                                            + " uplevel references, got %s.",
                                    outputPathSuffix
                                )
                            )
                            .setExecution(Execution.newBuilder().setCode(Code.EXECUTION_UNKNOWN))
                            .build()
                    )
                )
            }

            val outputPathTarget: PathFragment = outputPathPrefix.getRelative(outputPathSuffix)
            com.google.common.base.Preconditions.checkState(outputPathTarget.isAbsolute())
            return outputPathTarget
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun addArtifact(
            outputMetadataStore: OutputMetadataStore,
            execRoot: com.google.devtools.build.lib.vfs.Path,
            outputPath: com.google.devtools.build.lib.vfs.Path?,
            builder: FinalizeArtifactsRequest.Builder,
            output: Artifact
        ) {
            com.google.common.base.Preconditions.checkState(!output.isTreeArtifact())
            val metadata: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                outputMetadataStore.getOutputMetadata(output)
            if (metadata.getType().isFile()) {
                val digest: Digest? = DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize())
                val path: String? = execRoot.getRelative(output.getExecPath()).relativeTo(outputPath).toString()
                builder.addArtifacts(
                    FinalizeArtifactsRequest.Artifact.newBuilder()
                        .setPath(path)
                        .setLocator(Any.pack(FileArtifactLocator.newBuilder().setDigest(digest).build()))
                        .build()
                )
            }
        }
    }
}
