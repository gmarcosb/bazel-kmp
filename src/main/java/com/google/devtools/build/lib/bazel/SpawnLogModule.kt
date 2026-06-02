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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.actions.Spawn

/** Module providing on-demand spawn logging.  */
class SpawnLogModule : BlazeModule() {
    private var spawnLogContext: SpawnLogContext? = null
    private var outputPath: com.google.devtools.build.lib.vfs.Path? = null
    private var uriFuture: com.google.common.util.concurrent.ListenableFuture<String?>? = null
    private var logName: String? = null

    private var abruptExit: AbruptExitException? = null

    private fun clear() {
        spawnLogContext = null
        outputPath = null
        uriFuture = null
        logName = null
        abruptExit = null
    }

    @Throws(IOException::class)
    private fun initOutputs(env: CommandEnvironment) {
        clear()
        try {
            val executionOptions: ExecutionOptions? =
                env.getOptions().getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
            if (executionOptions == null) {
                return
            }

            val bepOptions: BuildEventProtocolOptions =
                com.google.common.base.Preconditions.checkNotNull<BuildEventProtocolOptions>(
                    env.getOptions().getOptions<BuildEventProtocolOptions?>(BuildEventProtocolOptions::class.java)
                )

            val numFormats: Int =
                com.google.common.primitives.Booleans.countTrue(
                    executionOptions.getExecutionLogCompactFile() != null,
                    executionOptions.getExecutionLogBinaryFile() != null,
                    executionOptions.getExecutionLogJsonFile() != null
                )

            if (numFormats == 0) {
                // No logging requested.
                return
            }

            if (numFormats > 1) {
                val message =
                    ("Must specify at most one of --execution_log_binary_file, --execution_log_json_file and"
                            + " --execution_log_compact_file")
                env.getBlazeModuleEnvironment()
                    .exit(
                        AbruptExitException(
                            DetailedExitCode.of(
                                FailureDetail.newBuilder()
                                    .setMessage(message)
                                    .setExecutionOptions(
                                        FailureDetails.ExecutionOptions.newBuilder()
                                            .setCode(
                                                FailureDetails.ExecutionOptions.Code
                                                    .MULTIPLE_EXECUTION_LOG_FORMATS
                                            )
                                    )
                                    .build()
                            )
                        )
                    )
                return
            }

            val outputBase: com.google.devtools.build.lib.vfs.Path = env.getOutputBase()
            val logSpawnPredicate: java.util.function.Predicate<Spawn?> =
                java.util.function.Predicate { spawn: Spawn? ->
                    executionOptions.getExecutionLogMnemonicFilter().test(spawn.getMnemonic())
                }

            var outputStream: BufferedOutputStream? = null
            var displayName: String? = null
            var logPath: PathFragment? = null
            if (executionOptions.getExecutionLogCompactFile() != null) {
                logName = EXEC_LOG_COMPACT_FILENAME
                logPath = executionOptions.getExecutionLogCompactFile()
            } else if (executionOptions.getExecutionLogBinaryFile() != null) {
                logName = EXEC_LOG_BINARY_FILENAME
                logPath = executionOptions.getExecutionLogBinaryFile()
            } else {
                logName = EXEC_LOG_JSON_FILENAME
                logPath = executionOptions.getExecutionLogJsonFile()
            }
            com.google.common.base.Preconditions.checkNotNull<PathFragment?>(logPath)

            if (!logPath.isEmpty()) {
                // Log path is specified, write to local file.
                outputPath = getAbsolutePath(logPath, env)
                outputStream = BufferedOutputStream(outputPath.getOutputStream(), OUTPUT_BUFFER_SIZE)
                displayName = outputPath.toString()
            } else if (bepOptions.getStreamingLogFileUploads()) {
                // Path is empty but streaming is enabled.
                val uploader: BuildEventArtifactUploader =
                    env.getRuntime()
                        .getBuildEventArtifactUploaderFactoryMap()
                        .select(bepOptions.getBuildEventUploadStrategy())
                        .create(env)
                val uploadContext: UploadContext = uploader.startUpload(LocalFileType.LOG, null)
                outputStream =
                    BufferedOutputStream(uploadContext.getOutputStream(), OUTPUT_BUFFER_SIZE)
                uriFuture = uploadContext.uriFuture()
                displayName = logName + "-stream"
            } else {
                // Path is empty but streaming is not enabled. Disable logging.
                env.getBlazeModuleEnvironment()
                    .exit(
                        AbruptExitException(
                            DetailedExitCode.of(
                                FailureDetail.newBuilder()
                                    .setMessage(
                                        ("--execution_log_{compact,binary,json}_file is empty, but"
                                                + " --experimental_stream_log_file_uploads is not enabled."
                                                + " Execution log will not be uploaded to the BEP.")
                                    )
                                    .setExecutionOptions(
                                        FailureDetails.ExecutionOptions.newBuilder()
                                            .setCode(
                                                FailureDetails.ExecutionOptions.Code
                                                    .EXECUTION_LOG_STREAMING_DISABLED
                                            )
                                    )
                                    .build()
                            )
                        )
                    )
                return
            }

            if (outputStream == null) {
                // Null output stream from UploadContext - disable logging.
                env.getReporter()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            "Execution log streaming is not enabled. Execution log will not be"
                                    + " generated."
                        )
                    )
                return
            }

            com.google.common.base.Preconditions.checkNotNull<String?>(displayName)

            if (executionOptions.getExecutionLogCompactFile() != null) {
                spawnLogContext =
                    CompactSpawnLogContext(
                        outputStream,
                        displayName,
                        env.getExecRoot().asFragment(),
                        env.getWorkspaceName(),
                        env.getOptions()
                            .getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                            .getExperimentalSiblingRepositoryLayout(),
                        env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java),
                        env.getRuntime().getFileSystem().getDigestFunction(),
                        env.getXattrProvider(),
                        env.getCommandId(),
                        env.getReporter(),
                        logSpawnPredicate
                    )
            } else {
                val binaryElseJson = executionOptions.getExecutionLogBinaryFile() != null
                // Use a well-known temporary path to avoid accumulation of potentially large files in /tmp
                // due to abnormally terminated invocations (e.g., when running out of memory).
                val tempPath: com.google.devtools.build.lib.vfs.Path? = outputBase.getRelative(logName)

                spawnLogContext =
                    ExpandedSpawnLogContext(
                        outputStream,
                        displayName,
                        outputPath,
                        tempPath,
                        if (binaryElseJson) ExpandedSpawnLogContext.Encoding.BINARY else ExpandedSpawnLogContext.Encoding.JSON,  /* sorted= */
                        executionOptions.getExecutionLogSort(),
                        env.getExecRoot().asFragment(),
                        env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java),
                        env.getRuntime().getFileSystem().getDigestFunction(),
                        env.getXattrProvider(),
                        uriFuture != null,
                        logSpawnPredicate
                    )
            }
        } catch (e: java.lang.InterruptedException) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.error("Error while setting up the execution log: " + e.getMessage()))
        }
    }

    /**
     * If the given path is absolute path, leave it as it is. If the given path is a relative path, it
     * is relative to the current working directory. If the given path starts with '%workspace%, it is
     * relative to the workspace root, which is the output of `bazel info workspace`.
     * 
     * @return Absolute Path
     */
    private fun getAbsolutePath(path: PathFragment, env: CommandEnvironment): com.google.devtools.build.lib.vfs.Path? {
        var pathString: String = path.getPathString()
        if (env.getWorkspace() != null) {
            pathString = pathString.replace("%workspace%", env.getWorkspace().getPathString())
        }
        if (!PathFragment.isAbsolute(pathString)) {
            return env.getWorkingDirectory().getRelative(pathString)
        }

        return env.getRuntime().getFileSystem().getPath(pathString)
    }

    override fun registerActionContexts(
        registryBuilder: com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder,
        env: CommandEnvironment?,
        buildRequest: BuildRequest?
    ) {
        if (spawnLogContext != null) {
            registryBuilder.register<T?>(SpawnLogContext::class.java, spawnLogContext)
        }
    }

    override fun executorInit(env: CommandEnvironment, request: BuildRequest?, builder: ExecutorBuilder?) {
        env.getEventBus().register(this)

        try {
            initOutputs(env)
        } catch (e: IOException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            env.getBlazeModuleEnvironment()
                .exit(
                    AbruptExitException(
                        createDetailedExitCode(
                            java.lang.String.format("Error initializing execution log: %s", e.getMessage()),
                            Code.EXECUTION_LOG_INITIALIZATION_FAILURE
                        )
                    )
                )
        }
    }

    @com.google.common.eventbus.Subscribe
    fun buildComplete(event: BuildCompleteEvent) {
        // The log must be finalized in buildComplete() instead of afterCommand(), because it's our
        // last chance to publish it to the build event protocol.

        if (spawnLogContext == null) {
            // No logging requested.
            clear()
            return
        }

        try {
            spawnLogContext.close()
            if (spawnLogContext.shouldPublish()) {
                com.google.common.base.Preconditions.checkNotNull<String?>(logName)
                if (uriFuture != null) {
                    event.getResult().getBuildToolLogCollection().addUriFuture(logName, uriFuture)
                } else {
                    event.getResult().getBuildToolLogCollection().addLocalFile(logName, outputPath)
                }
            }
        } catch (e: IOException) {
            abruptExit =
                AbruptExitException(
                    createDetailedExitCode(
                        java.lang.String.format("Error writing execution log: %s", e.getMessage()),
                        Code.EXECUTION_LOG_WRITE_FAILURE
                    ),
                    e
                )
        } finally {
            clear()
        }
    }

    @Throws(AbruptExitException::class)
    override fun afterCommand() {
        if (abruptExit != null) {
            throw abruptExit
        }
    }

    companion object {
        private val OUTPUT_BUFFER_SIZE = 100 * 1024
        private const val EXEC_LOG_COMPACT_FILENAME = "execution_log.binpb.zst"
        private const val EXEC_LOG_BINARY_FILENAME = "execution_log.binpb"
        private const val EXEC_LOG_JSON_FILENAME = "execution_log.json"

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(Execution.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
