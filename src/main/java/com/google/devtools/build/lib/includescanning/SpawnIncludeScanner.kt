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
package com.google.devtools.build.lib.includescanning

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.AbstractAction
import com.google.devtools.build.lib.vfs.Path
import java.io.InputStream

/**
 * C include scanner. Scans C/C++ source files using spawns to determine the bounding set of
 * transitively referenced include files. Has lifetime of a single build.
 */
class SpawnIncludeScanner(
    private val execRoot: Path,
    private val remoteExtractionThreshold: Int,
    syscallCache: SyscallCache
) {
    private var outputService: OutputService? = null
    private var inMemoryOutput = false
    private val syscallCache: SyscallCache

    /** Constructs a new SpawnIncludeScanner.  */
    init {
        this.syscallCache = syscallCache
    }

    fun setOutputService(outputService: OutputService?) {
        Preconditions.checkState(this.outputService == null)
        this.outputService = outputService
    }

    fun setInMemoryOutput(inMemoryOutput: Boolean) {
        this.inMemoryOutput = inMemoryOutput
    }

    @VisibleForTesting
    fun getIncludesOutput(
        src: Artifact, resolver: ArtifactPathResolver, fileType: GrepIncludesFileType,
        placeNextToFile: Boolean
    ): Path {
        if (placeNextToFile) {
            // If this is an output file, just place the grepped-file next to it. The directory is bound
            // to exist.
            return resolver.toPath(src)
                .getParentDirectory()
                .getRelative(src.getFilename() + ".blaze-grepped_includes_" + fileType)
        }
        return resolver.convertPath(execRoot)
            .getChild("blaze-grepped_includes_" + fileType.getFileType())
            .getRelative(src.getExecPath())
    }

    private fun execPath(path: Path): PathFragment? {
        return path.asFragment().relativeTo(execRoot.asFragment())
    }

    /** Returns whether `file` should be parsed using this include scanner.  */
    @Throws(IOException::class)
    fun shouldParseRemotely(file: Artifact): Boolean {
        // We currently cannot remotely extract inclusions from files that aren't underneath a known
        // Blaze root (e.g. that are in /usr/include). Likely, it's not a good idea to look at those in
        // the first place as it means we have a non-hermetic build.
        // TODO(b/115503807): Fix underlying issue and consider turning this into a precondition check.
        if (file.getRoot().getRoot().isAbsolute()) {
            return false
        }
        // Enable include scanning remotely when explicitly directed to via a flag.
        if (remoteExtractionThreshold == 0) {
            return true
        }
        // Files written remotely that are not locally available should be scanned remotely to avoid the
        // bandwidth and disk space penalty of bringing them across.
        if (!outputService.isLocalOnly() && !file.isSourceArtifact()) {
            return true
        }
        val path: Path = file.getPath()
        // Don't use syscallCache for a derived artifact: it might have been statted before it was
        // regenerated.
        val status: FileStatus? =
            if (file.isSourceArtifact())
                syscallCache.statIfFound(path, Symlinks.FOLLOW)
            else
                path.statIfFound(Symlinks.FOLLOW)
        return status == null || status.getSize() > remoteExtractionThreshold
    }

    /**
     * Action for grepping. Is used basically just for ActionStatusMessages (displaying the action
     * status to the user as it executes).
     */
    private class GrepIncludesAction(
        actionExecutionMetadata: ActionExecutionMetadata?,
        executionPlatform: PlatformInfo?,
        input: PathFragment
    ) : ActionExecutionMetadata {
        /**
         * We don't use this object as the 'resource owner' of the spawn because we want to override the
         * mnemonic (among other things, see additional comments below). However, we do delegate
         * getOwner, and we may delegate other methods (e.g., getProgressMessage, describe) in the
         * future.
         */
        private val actionExecutionMetadata: ActionExecutionMetadata

        private val executionPlatform: PlatformInfo?

        val progressMessage: String

        init {
            this.executionPlatform = executionPlatform
            this.actionExecutionMetadata = Preconditions.checkNotNull<ActionExecutionMetadata>(actionExecutionMetadata)
            this.progressMessage = "Extracting include lines from " + input.getPathString()
        }

        val owner: ActionOwner
            get() = actionExecutionMetadata.getOwner()

        val isShareable: Boolean
            get() = false

        public override fun describe(): String {
            return progressMessage
        }

        public override fun inputsKnown(): Boolean {
            throw UnsupportedOperationException()
        }

        public override fun discoversInputs(): Boolean {
            throw UnsupportedOperationException()
        }

        val tools: NestedSet<Artifact?>?
            get() {
                throw UnsupportedOperationException()
            }

        val inputs: NestedSet<Artifact?>
            get() = actionExecutionMetadata.getInputs()

        val originalInputs: NestedSet<Artifact?>
            get() = actionExecutionMetadata.getInputs()

        val schedulingDependencies: NestedSet<Artifact?>?
            get() {
                throw UnsupportedOperationException()
            }

        val execProperties: ImmutableMap<String?, String?>
            get() {
                if (executionPlatform != null) {
                    return executionPlatform.execProperties()
                }
                return actionExecutionMetadata.getExecProperties()
            }

        public override fun getExecutionPlatform(): PlatformInfo? {
            if (executionPlatform != null) {
                return executionPlatform
            }
            return actionExecutionMetadata.getExecutionPlatform()
        }

        val outputs: ImmutableSet<Artifact>
            get() =// We currently compute orphaned outputs from the Action's list of outputs rather than from
            // the Spawn's list of outputs. If we return something here, we need to update that place as
                // well.
                ImmutableSet.of<Artifact?>()

        val clientEnvironmentVariables: MutableCollection<String?>
            get() = ImmutableSet.of<String?>()

        val primaryInput: Artifact?
            get() {
                throw UnsupportedOperationException()
            }

        val primaryOutput: Artifact?
            get() =// This violates the contract of ActionExecutionMetadata. Classes that call here are working
            // around this returning null. At least some subclasses of CriticalPathComputer are affected.
            // TODO(ulfjack): Either fix this or change the contract. See b/111583707 for
                // CriticalPathComputer.
                null

        val mandatoryInputs: NestedSet<Artifact?>?
            get() {
                throw UnsupportedOperationException()
            }

        public override fun getKey(
            actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
        ): String? {
            throw UnsupportedOperationException()
        }

        public override fun describeKey(): String? {
            throw UnsupportedOperationException()
        }

        public override fun prettyPrint(): String {
            // This is called when running with -s (printing all subcommands).
            return "(include scanning)"
        }

        public override fun getInputFilesForExtraAction(
            actionExecutionContext: ActionExecutionContext?
        ): NestedSet<Artifact?>? {
            throw UnsupportedOperationException()
        }

        val mandatoryOutputs: ImmutableSet<Artifact>
            get() =// This is called to compute orphaned outputs. See getOutputs.
                ImmutableSet.of<Artifact?>()

        companion object {
            val mnemonic: String = "GrepIncludes"
                get() = Companion.field
        }
    }

    /** Extracts and returns inclusions from "file" using a spawn.  */
    @Throws(IOException::class, ExecException::class, InterruptedException::class)
    fun extractInclusions(
        file: Artifact,
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecutionContext: ActionExecutionContext,
        grepIncludes: Artifact,
        grepIncludesExecutionPlatform: PlatformInfo?,
        fileType: GrepIncludesFileType,
        isOutputFile: Boolean
    ): MutableCollection<Inclusion?> {
        val placeNextToFile = isOutputFile && !file.hasParent()
        val output = getIncludesOutput(
            file, actionExecutionContext.getPathResolver(), fileType,
            placeNextToFile
        )
        if (!inMemoryOutput) {
            AbstractAction.deleteOutput(
                output,
                if (placeNextToFile)
                    actionExecutionContext.getPathResolver().transformRoot(file.getRoot().getRoot())
                else
                    null
            )
            if (!placeNextToFile) {
                output.getParentDirectory()!!.createDirectoryAndParents()
            }
        }

        val dotIncludeStream: InputStream? =
            spawnGrep(
                file,
                execPath(output),
                inMemoryOutput,  // We use {@link GrepIncludesAction} primarily to overwrite {@link Action#getMnemonic}.
                // You might be tempted to use a custom mnemonic on the Spawn instead, but rest assured
                // that _this does not work_. We call Spawn.getResourceOwner().getMnemonic() in a lot of
                // places, some of which are downstream from here, and doing so would cause the Spawn
                // and its owning ActionExecutionMetadata to be inconsistent with each other.
                GrepIncludesAction(
                    actionExecutionMetadata, grepIncludesExecutionPlatform, file.getExecPath()
                ),
                actionExecutionContext,
                grepIncludes,
                fileType
            )
        return if (dotIncludeStream == null)
            IncludeParser.Companion.processIncludes(output)
        else
            IncludeParser.Companion.processIncludes(output, dotIncludeStream)
    }

    private class GrepIncludesSpawn(
        arguments: ImmutableList<String?>?,
        executionInfo: ImmutableMap<String?, String?>?,
        action: ActionExecutionMetadata?,
        grepIncludes: Artifact?,
        input: Artifact?,
        output: ActionInput
    ) : BaseSpawn(
        arguments,  /* environment= */ImmutableMap.of<K?, V?>(), executionInfo, action, LOCAL_RESOURCES
    ) {
        private val inputs: NestedSet<Artifact?>?
        private val outputs: ImmutableSet<ActionInput?>

        init {
            this.inputs = NestedSetBuilder.create(Order.STABLE_ORDER, grepIncludes, input)
            this.outputs = ImmutableSet.of<ActionInput?>(output)
        }

        val inputFiles: NestedSet<Artifact?>?
            get() = inputs

        val outputFiles: ImmutableSet<ActionInput>
            get() = outputs

        val toolFiles: NestedSet<Artifact?>
            get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        val outputEdgesForExecutionGraph: ImmutableList<ActionInput>
            get() =// The output of GrepIncludesSpawn is read by the include scanner itself and is not an input
            // to any other spawn. Hiding it from the execution graph can save a considerable amount of
                // memory, see b/445300504.
                ImmutableList.of<ActionInput?>()
    }

    companion object {
        /** The grep-includes tool is very lightweight, so don't use the default from AbstractAction.  */
        private val LOCAL_RESOURCES: ResourceSet? = ResourceSet.createWithRamCpu( /* memoryMb= */10,  /* cpu= */1)

        /**
         * Executes grep-includes.
         * 
         * @param input the file to parse
         * @param outputExecPath the output file (exec path)
         * @param inMemoryOutput if true, return the contents of the output in the return value instead of
         * to the given Path
         * @param resourceOwner the resource owner
         * @param actionExecutionContext services in the scope of the action. Like the Err/Out stream
         * outputs.
         * @param fileType Either "c++" or "swig", passed verbatim to grep-includes.
         * @return The InputStream of the .includes file if inMemoryOutput feature retrieved it directly.
         * Otherwise "null"
         * @throws ExecException if scanning fails
         */
        @Throws(ExecException::class, InterruptedException::class)
        private fun spawnGrep(
            input: Artifact,
            outputExecPath: PathFragment,
            inMemoryOutput: Boolean,
            resourceOwner: ActionExecutionMetadata,
            actionExecutionContext: ActionExecutionContext,
            grepIncludes: Artifact,
            fileType: GrepIncludesFileType
        ): InputStream? {
            val output: ActionInput = ActionInputHelper.fromPath(outputExecPath)
            val command: ImmutableList<String?> =
                ImmutableList.of<E?>(
                    grepIncludes.getExecPathString(),
                    input.getExecPath().getPathString(),
                    outputExecPath.getPathString(),
                    fileType.getFileType()
                )

            val execInfoBuilder = ImmutableMap.builder<String?, String?>()
            execInfoBuilder.putAll(resourceOwner.getExecutionInfo())
            if (inMemoryOutput) {
                execInfoBuilder.put(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS, outputExecPath.getPathString()
                )
                // grep-includes writes output file to disk. If in-memory output is requested, no-local should
                // also be added, otherwise, grep-includes could be executed locally resulting output be
                // written to local disk.
                execInfoBuilder.put(ExecutionRequirements.NO_LOCAL, "")
            }
            execInfoBuilder.put(ExecutionRequirements.DO_NOT_REPORT, "")

            val spawn: Spawn =
                GrepIncludesSpawn(
                    command, execInfoBuilder.buildOrThrow(), resourceOwner, grepIncludes, input, output
                )

            actionExecutionContext.maybeReportSubcommand(spawn,  /* spawnRunner= */null)

            // Don't share the originalOutErr across spawnGrep calls. Doing so would not be thread-safe.
            val originalOutErr: FileOutErr = actionExecutionContext.getFileOutErr()
            val grepOutErr: FileOutErr? = originalOutErr.childOutErr()
            val spawnStrategyResolver: SpawnStrategyResolver =
                actionExecutionContext.getContext(SpawnStrategyResolver::class.java)
            val spawnContext: ActionExecutionContext = actionExecutionContext.withFileOutErr(grepOutErr)
            val results: MutableList<SpawnResult>
            try {
                results = spawnStrategyResolver.exec(spawn, spawnContext)
                dump(spawnContext, actionExecutionContext)
            } catch (e: ExecException) {
                dump(spawnContext, actionExecutionContext)
                throw e
            }

            val result: SpawnResult = results.getFirst()
            val includesContent: ByteString? = result.getInMemoryOutput(output)
            if (includesContent != null) {
                return includesContent.newInput()
            }
            return null
        }

        private fun dump(fromContext: ActionExecutionContext, toContext: ActionExecutionContext) {
            if (fromContext.getFileOutErr().hasRecordedOutput()) {
                synchronized(toContext) {
                    FileOutErr.dump(fromContext.getFileOutErr(), toContext.getFileOutErr())
                }
            }
        }
    }
}
