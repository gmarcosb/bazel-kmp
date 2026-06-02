// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.AbstractAction

/** An [ActionContext] providing the ability to log executed spawns.  */
abstract class SpawnLogContext protected constructor(logSpawnPredicate: java.util.function.Predicate<Spawn?>) :
    ActionContext {
    protected val logSpawnPredicate: java.util.function.Predicate<Spawn?>

    init {
        this.logSpawnPredicate = logSpawnPredicate
    }

    /** Returns true if the spawn should be logged.  */
    protected fun shouldLog(spawn: Spawn?): Boolean {
        return logSpawnPredicate.test(spawn)
    }

    /**
     * Logs an executed spawn.
     * 
     * 
     * May be called concurrently.
     * 
     * @param spawn the spawn to log
     * @param inputMetadataProvider provides metadata for the spawn inputs
     * @param inputMap the mapping from input paths to action inputs (built lazily)
     * @param fileSystem the filesystem containing the spawn inputs and outputs, which might be an
     * action filesystem when building without the bytes
     * @param timeout the timeout the spawn was run under
     * @param result the spawn result
     */
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    abstract fun logSpawn(
        spawn: Spawn?,
        inputMetadataProvider: InputMetadataProvider?,
        inputMap: java.util.function.Supplier<SortedMap<PathFragment?, ActionInput?>?>?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        timeout: java.time.Duration?,
        result: SpawnResult?
    )

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecException::class)
    fun logSpawn(
        spawn: Spawn?,
        inputMetadataProvider: InputMetadataProvider?,
        inputMap: SortedMap<PathFragment?, ActionInput?>?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        timeout: java.time.Duration?,
        result: SpawnResult?
    ) {
        logSpawn(spawn, inputMetadataProvider, java.util.function.Supplier { inputMap }, fileSystem, timeout, result)
    }

    /**
     * Logs an internal symlink action, which is not backed by a spawn.
     * 
     * 
     * May be called concurrently.
     * 
     * @param action the action to log
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    abstract fun logSymlinkAction(action: AbstractAction?)

    /** Finishes writing the log and performs any required post-processing.  */
    @Throws(IOException::class)
    abstract fun close()

    /** Whether the log should be published to the build event protocol.  */
    abstract fun shouldPublish(): Boolean

    /** Computes the environment variables.  */
    protected fun getEnvironmentVariables(spawn: Spawn): com.google.common.collect.ImmutableList<EnvironmentVariable?> {
        val environment: com.google.common.collect.ImmutableMap<String?, String?> = spawn.getEnvironment()
        val builder: com.google.common.collect.ImmutableList.Builder<EnvironmentVariable?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<EnvironmentVariable?>(environment.size())
        for (entry in com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(environment).entrySet()) {
            builder.add(
                EnvironmentVariable.newBuilder()
                    .setName(StringEncoding.internalToUnicode(entry.getKey()))
                    .setValue(StringEncoding.internalToUnicode(entry.getValue()))
                    .build()
            )
        }
        return builder.build()
    }

    /** Computes the execution platform.  */
    @Throws(UserExecException::class)
    protected fun getPlatform(spawn: Spawn?, remoteOptions: RemoteOptions?): Platform? {
        val execPlatform: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            PlatformUtils.getPlatformProto(spawn, remoteOptions)
        if (execPlatform == null) {
            return null
        }
        val builder: Platform.Builder = Platform.newBuilder()
        for (p in execPlatform.getPropertiesList()) {
            builder.addPropertiesBuilder().setName(p.getName()).setValue(p.getValue())
        }
        return builder.build()
    }

    /**
     * Determines whether an action input is a directory, avoiding I/O if possible.
     * 
     * 
     * Do not call for action outputs.
     */
    @Throws(IOException::class)
    protected fun isInputDirectory(input: ActionInput, inputMetadataProvider: InputMetadataProvider): Boolean {
        if (input.isDirectory()) {
            return true
        }
        if (input.isSymlink()) {
            return false
        }
        // Virtual action inputs are always files.
        if (input is VirtualActionInput) {
            return false
        }
        // Source artifacts are always of file type, but may be directories in the filesystem.
        if (input is SourceArtifact) {
            val metadata: FileArtifactValue? = inputMetadataProvider.getInputMetadata(input)
            return metadata != null && metadata.getType().isDirectory()
        }
        return false
    }

    /**
     * Computes the digest of an ActionInput or its path.
     * 
     * 
     * Will try to obtain the digest from cached metadata first, falling back to digesting the
     * contents manually.
     */
    @Throws(IOException::class)
    protected fun computeDigest(
        input: ActionInput?,
        path: com.google.devtools.build.lib.vfs.Path,
        inputMetadataProvider: InputMetadataProvider?,
        xattrProvider: XattrProvider,
        digestHashFunction: DigestHashFunction,
        includeHashFunctionName: Boolean
    ): Digest {
        val builder: Digest.Builder = Digest.newBuilder()

        if (includeHashFunctionName) {
            builder.setHashFunctionName(digestHashFunction.toString())
        }

        if (input != null) {
            if (input is VirtualActionInput) {
                val digest: build.bazel.remote.execution.v2.Digest =
                    DigestUtil.compute(input, digestHashFunction.getHashFunction())
                return builder.setHash(digest.getHash()).setSizeBytes(digest.getSizeBytes()).build()
            }

            if (inputMetadataProvider != null) {
                // Try to obtain a digest from the input metadata.
                try {
                    val metadata: FileArtifactValue? = inputMetadataProvider.getInputMetadata(input)
                    if (metadata != null && metadata.getDigest() != null) {
                        return builder
                            .setHash(com.google.common.hash.HashCode.fromBytes(metadata.getDigest()).toString())
                            .setSizeBytes(metadata.getSize())
                            .build()
                    }
                } catch (e: IOException) {
                    // Pass through to local computation.
                } catch (e: java.lang.IllegalStateException) {
                }
            }
        }

        // Obtain a digest from the filesystem.
        val status: FileStatus = path.stat()
        return builder
            .setHash(
                com.google.common.hash.HashCode.fromBytes(
                    com.google.devtools.build.lib.vfs.DigestUtils.getDigestWithManualFallback(
                        path,
                        xattrProvider,
                        status
                    )
                )
                    .toString()
            )
            .setSizeBytes(status.getSize())
            .build()
    }

    companion object {
        protected fun getSpawnMetricsProto(result: SpawnResult): Protos.SpawnMetrics {
            val metrics: SpawnMetrics = result.getMetrics()
            val builder: Protos.SpawnMetrics.Builder = Protos.SpawnMetrics.newBuilder()
            if (metrics.totalTimeInMs() !== 0L) {
                builder.setTotalTime(millisToProto(metrics.totalTimeInMs()))
            }
            if (metrics.parseTimeInMs() !== 0L) {
                builder.setParseTime(millisToProto(metrics.parseTimeInMs()))
            }
            if (metrics.networkTimeInMs() !== 0L) {
                builder.setNetworkTime(millisToProto(metrics.networkTimeInMs()))
            }
            if (metrics.fetchTimeInMs() !== 0L) {
                builder.setFetchTime(millisToProto(metrics.fetchTimeInMs()))
            }
            if (metrics.queueTimeInMs() !== 0L) {
                builder.setQueueTime(millisToProto(metrics.queueTimeInMs()))
            }
            if (metrics.setupTimeInMs() !== 0L) {
                builder.setSetupTime(millisToProto(metrics.setupTimeInMs()))
            }
            if (metrics.uploadTimeInMs() !== 0L) {
                builder.setUploadTime(millisToProto(metrics.uploadTimeInMs()))
            }
            if (metrics.executionWallTimeInMs() !== 0L) {
                builder.setExecutionWallTime(millisToProto(metrics.executionWallTimeInMs()))
            }
            if (metrics.processOutputsTimeInMs() !== 0L) {
                builder.setProcessOutputsTime(millisToProto(metrics.processOutputsTimeInMs()))
            }
            if (metrics.retryTimeInMs() !== 0L) {
                builder.setRetryTime(millisToProto(metrics.retryTimeInMs()))
            }
            builder.setInputBytes(metrics.inputBytes())
            builder.setInputFiles(metrics.inputFiles())
            builder.setMemoryEstimateBytes(metrics.memoryEstimate())
            builder.setInputBytesLimit(metrics.inputBytesLimit())
            builder.setInputFilesLimit(metrics.inputFilesLimit())
            builder.setOutputBytesLimit(metrics.outputBytesLimit())
            builder.setOutputFilesLimit(metrics.outputFilesLimit())
            builder.setMemoryBytesLimit(metrics.memoryLimit())
            if (metrics.timeLimitInMs() !== 0L) {
                builder.setTimeLimit(millisToProto(metrics.timeLimitInMs()))
            }
            if (result.getStartTime() != null) {
                builder.setStartTime(Timestamps.fromMillis(result.getStartTime().toEpochMilli()))
            }
            return builder.build()
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun millisToProto(t: Int): com.google.protobuf.Duration {
            return Durations.fromMillis(t)
        }
    }
}
