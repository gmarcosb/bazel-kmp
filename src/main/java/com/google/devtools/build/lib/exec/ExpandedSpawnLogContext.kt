// Copyright 2023 The Bazel Authors. All rights reserved.
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

/** A [SpawnLogContext] implementation that produces a log in expanded format.  */
class ExpandedSpawnLogContext(
    outputStream: BufferedOutputStream,
    displayName: String?,
    outputPath: com.google.devtools.build.lib.vfs.Path?,
    tempPath: com.google.devtools.build.lib.vfs.Path,
    private val encoding: Encoding?,
    private val sorted: Boolean,
    execRoot: PathFragment,
    remoteOptions: RemoteOptions?,
    digestHashFunction: DigestHashFunction?,
    xattrProvider: XattrProvider?,
    shouldPublish: Boolean,
    logSpawnPredicate: java.util.function.Predicate<Spawn?>?
) : SpawnLogContext(logSpawnPredicate) {
    /** The log encoding.  */
    enum class Encoding {
        /** Length-delimited binary protos.  */
        BINARY,

        /** Newline-delimited JSON messages.  */
        JSON
    }

    private val tempPath: com.google.devtools.build.lib.vfs.Path
    private val outputStream: java.io.OutputStream

    private val execRoot: PathFragment
    private val remoteOptions: RemoteOptions?
    private val digestHashFunction: DigestHashFunction?
    private val xattrProvider: XattrProvider?
    private val shouldPublish: Boolean

    /** Output stream to write directly into during execution.  */
    private val rawOutputStream: MessageOutputStream<SpawnExec?>

    init {
        this.tempPath = tempPath
        this.execRoot = execRoot
        this.shouldPublish = shouldPublish
        this.remoteOptions = remoteOptions
        this.digestHashFunction = digestHashFunction
        this.xattrProvider = xattrProvider
        this.outputStream = outputStream

        if (needsConversion()) {
            // Write the unsorted binary format into a temporary path first, then convert into the output
            // format after execution.
            rawOutputStream = getRawOutputStream(tempPath)
        } else {
            // The unsorted binary format can be written directly into the output stream during execution.
            rawOutputStream = AsynchronousMessageOutputStream<SpawnExec?>(displayName, outputStream)
        }
    }

    private fun needsConversion(): Boolean {
        return encoding != com.google.devtools.build.lib.exec.ExpandedSpawnLogContext.Encoding.BINARY || sorted
    }

    private fun getConvertedOutputStream(out: java.io.OutputStream?): MessageOutputStream<SpawnExec?> {
        return when (encoding) {
            com.google.devtools.build.lib.exec.ExpandedSpawnLogContext.Encoding.BINARY -> BinaryOutputStreamWrapper<SpawnExec?>(
                out
            )

            com.google.devtools.build.lib.exec.ExpandedSpawnLogContext.Encoding.JSON -> JsonOutputStreamWrapper<SpawnExec?>(
                out
            )
        }
    }

    override fun shouldPublish(): Boolean {
        return shouldPublish
    }

    @Throws(IOException::class, ExecException::class)
    override fun logSpawn(
        spawn: Spawn,
        inputMetadataProvider: InputMetadataProvider,
        inputMap: java.util.function.Supplier<SortedMap<PathFragment?, ActionInput?>?>,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        timeout: java.time.Duration,
        result: SpawnResult
    ) {
        if (!shouldLog(spawn)) {
            return
        }
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("logSpawn").use { c ->
            val builder: SpawnExec.Builder = SpawnExec.newBuilder()
            builder.addAllCommandArgs(spawn.getArguments())
            builder.addAllEnvironmentVariables(getEnvironmentVariables(spawn))

            val toolFiles: com.google.common.collect.ImmutableSet<out ActionInput?> = spawn.getToolFiles().toSet()
            val toolRunfilesDirectories: com.google.common.collect.ImmutableList<PathFragment?> =
                toolFiles.stream()
                    .filter { actionInput: ActionInput? -> actionInput is Artifact && actionInput.isRunfilesTree() }
                    .map<Any?>(inputMetadataProvider::getRunfilesMetadata)
                    .map<Any?>(RunfilesArtifactValue::getRunfilesTree)
                    .map<Any?>(RunfilesTree::getExecPath)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("logSpawn/inputs").use { c1 ->
                    for (e in inputMap.get().entrySet()) {
                        val displayPath: PathFragment = e.getKey()
                        val input: ActionInput = e.getValue()

                        if (input is VirtualActionInput.EmptyActionInput) {
                            // Do not include a digest, as it's a waste of space.
                            builder
                                .addInputsBuilder()
                                .setPath(displayPath.getPathString())
                                .setIsTool(
                                    toolRunfilesDirectories.stream()
                                        .anyMatch(java.util.function.Predicate { other: PathFragment? ->
                                            displayPath.startsWith(other)
                                        })
                                )
                            continue
                        }

                        val isTool =
                            toolFiles.contains(input)
                                    || (input is TreeFileArtifact
                                    && toolFiles.contains(input.getParent()))
                                    || toolRunfilesDirectories.stream()
                                .anyMatch(java.util.function.Predicate { other: PathFragment? ->
                                    displayPath.startsWith(other)
                                })

                        val contentPath: com.google.devtools.build.lib.vfs.Path =
                            fileSystem.getPath(execRoot.getRelative(input.getExecPathString()))

                        if (isInputDirectory(input, inputMetadataProvider)) {
                            listDirectoryContents(
                                displayPath, contentPath, builder::addInputs, inputMetadataProvider, isTool
                            )
                            continue
                        }

                        if (input.isSymlink()) {
                            val metadata: FileArtifactValue = inputMetadataProvider.getInputMetadata(input)
                            checkState(metadata.getType().isSymlink(), metadata)
                            builder
                                .addInputsBuilder()
                                .setPath(displayPath.getPathString())
                                .setSymlinkTargetPath(metadata.getUnresolvedSymlinkTarget())
                                .setIsTool(isTool)
                            continue
                        }

                        val digest: Digest? =
                            computeDigest(
                                input,
                                contentPath,
                                inputMetadataProvider,
                                xattrProvider,
                                digestHashFunction,  /* includeHashFunctionName= */
                                true
                            )

                        builder
                            .addInputsBuilder()
                            .setPath(displayPath.getPathString())
                            .setDigest(digest)
                            .setIsTool(isTool)
                    }
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Error computing spawn input properties")
            }
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("logSpawn/outputs").use { c1 ->
                val outputPaths: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
                for (output in spawn.getOutputFiles()) {
                    outputPaths.add(output.getExecPathString())
                }
                Collections.sort<String?>(outputPaths)
                builder.addAllListedOutputs(outputPaths)
                try {
                    for (output in spawn.getOutputFiles()) {
                        val path: com.google.devtools.build.lib.vfs.Path =
                            fileSystem.getPath(execRoot.getRelative(output.getExecPathString()))
                        if (!output.isDirectory() && !output.isSymlink() && path.isFile()) {
                            builder
                                .addActualOutputsBuilder()
                                .setPath(output.getExecPathString())
                                .setDigest(
                                    computeDigest(
                                        output,
                                        path,
                                        inputMetadataProvider,
                                        xattrProvider,
                                        digestHashFunction,  /* includeHashFunctionName= */
                                        true
                                    )
                                )
                        } else if (output.isDirectory() && path.isDirectory()) {
                            listDirectoryContents(
                                output.getExecPath(),
                                path,
                                builder::addActualOutputs,
                                inputMetadataProvider,  /* isTool= */
                                false
                            )
                        } else if (output.isSymlink() && path.isSymbolicLink()) {
                            builder
                                .addActualOutputsBuilder()
                                .setPath(output.getExecPathString())
                                .setSymlinkTargetPath(path.readSymbolicLink().getPathString())
                        }
                    }
                } catch (ex: IOException) {
                    logger.atWarning().withCause(ex).log("Error computing spawn output properties")
                }
            }
            builder.setRemotable(Spawns.mayBeExecutedRemotely(spawn))

            val platform: Platform? = getPlatform(spawn, remoteOptions)
            if (platform != null) {
                builder.setPlatform(platform)
            }
            if (result.status() !== SpawnResult.Status.SUCCESS) {
                builder.setStatus(result.status().toString())
            }
            if (!timeout.isZero()) {
                builder.setTimeoutMillis(timeout.toMillis())
            }
            builder.setCacheable(Spawns.mayBeCached(spawn))
            builder.setRemoteCacheable(Spawns.mayBeCachedRemotely(spawn))
            builder.setExitCode(result.exitCode())
            builder.setCacheHit(result.isCacheHit())
            builder.setRunner(result.getRunnerName())

            if (result.getDigest() != null) {
                builder.setDigest(result.getDigest())
            }

            builder.setMnemonic(spawn.getMnemonic())

            if (spawn.getTargetLabel() != null) {
                builder.setTargetLabel(spawn.getTargetLabel().toString())
            }

            builder.setMetrics(SpawnLogContext.Companion.getSpawnMetricsProto(result))
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("logSpawn/write").use { c1 ->
                rawOutputStream.write(builder.build())
            }
        }
    }

    override fun logSymlinkAction(action: AbstractAction?) {
        // The expanded log does not report symlink actions.
    }

    @Throws(IOException::class)
    override fun close() {
        rawOutputStream.close()

        if (!needsConversion()) {
            outputStream.close()
            return
        }

        try {
            BinaryInputStreamWrapper<T?>(
                tempPath.getInputStream(), SpawnExec.getDefaultInstance()
            ).use { rawInputStream ->
                getConvertedOutputStream(outputStream).use { convertedOutputStream ->
                    if (sorted) {
                        StableSort.stableSort(rawInputStream, convertedOutputStream)
                    } else {
                        var ex: SpawnExec?
                        while ((rawInputStream.read().also { ex = it }) != null) {
                            convertedOutputStream.write(ex)
                        }
                    }
                }
            }
        } finally {
            try {
                tempPath.delete()
            } catch (e: IOException) {
                // Intentionally ignored.
            }
        }
    }

    /**
     * Expands a directory into its contents.
     * 
     * 
     * Note the difference between `displayPath` and `contentPath`: the first is where
     * the spawn can find the directory, while the second is where Bazel can find it. They're not the
     * same for a directory appearing in a runfiles or fileset tree.
     */
    @Throws(IOException::class)
    private fun listDirectoryContents(
        displayPath: PathFragment,
        contentPath: com.google.devtools.build.lib.vfs.Path,
        addFile: java.util.function.Consumer<File?>,
        inputMetadataProvider: InputMetadataProvider?,
        isTool: Boolean
    ) {
        val sortedDirent: MutableList<com.google.devtools.build.lib.vfs.Dirent> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Dirent>(contentPath.readdir(Symlinks.NOFOLLOW))
        sortedDirent.sort(java.util.Comparator.comparing<com.google.devtools.build.lib.vfs.Dirent?, String?>(java.util.function.Function { obj: com.google.devtools.build.lib.vfs.Dirent? -> obj.getName() }))

        for (dirent in sortedDirent) {
            val name: String? = dirent.getName()
            val childDisplayPath: PathFragment = displayPath.getChild(name)
            val childContentPath: com.google.devtools.build.lib.vfs.Path = contentPath.getChild(name)

            if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                listDirectoryContents(
                    childDisplayPath, childContentPath, addFile, inputMetadataProvider, isTool
                )
                continue
            }

            addFile.accept(
                File.newBuilder()
                    .setPath(childDisplayPath.getPathString())
                    .setDigest(
                        computeDigest(
                            null,
                            childContentPath,
                            inputMetadataProvider,
                            xattrProvider,
                            digestHashFunction,  /* includeHashFunctionName= */
                            true
                        )
                    )
                    .setIsTool(isTool)
                    .build()
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(IOException::class)
        private fun getRawOutputStream(path: com.google.devtools.build.lib.vfs.Path): MessageOutputStream<SpawnExec?> {
            // Use an AsynchronousMessageOutputStream so that writes occur in a separate thread.
            // This ensures concurrent writes don't tear and avoids blocking execution.
            return AsynchronousMessageOutputStream<SpawnExec?>(path)
        }
    }
}
