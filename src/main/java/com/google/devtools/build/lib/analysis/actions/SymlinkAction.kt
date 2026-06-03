// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.unix.UnixMode.S_IXUSR

/**
 * Action to create a symlink to a known-to-exist target with alias semantics similar to a true copy
 * of the input (if any).
 */
class SymlinkAction private constructor(
    owner: ActionOwner?,
    inputPath: PathFragment?,
    primaryInput: Artifact?,
    primaryOutput: Artifact,
    progressMessage: String?,
    targetType: TargetType?
) : AbstractAction(
    owner,
    if (primaryInput != null)
        NestedSetBuilder.create(Order.STABLE_ORDER, primaryInput)
    else
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
    com.google.common.collect.ImmutableSet.of<E?>(primaryOutput)
), RichDataProducingAction {
    /** Null when [.getPrimaryInput] is the target of the symlink.  */
    private val inputPath: PathFragment?

    private val progressMessage: String?

    internal enum class TargetType {
        /**
         * The symlink points into a Fileset.
         * 
         * 
         * If this is set, the action also updates the mtime for its target thus forcing actions
         * depending on it to be re-executed. This would not be necessary in an ideal world, but
         * dependency checking for Filesets output trees is unsound because they are directories, so we
         * need to force them to be considered changed this way. Yet Another Reason why Filests should
         * go away.
         */
        FILESET,

        /**
         * The symlink should point to an executable.
         * 
         * 
         * Blaze will verify that the target is indeed executable.
         */
        EXECUTABLE,

        /** Just a vanilla symlink. Don't do anything else other than creating the symlink.  */
        OTHER,
    }

    private val targetType: TargetType?

    init {
        this.inputPath = inputPath
        this.progressMessage = progressMessage
        this.targetType = targetType
    }

    fun getInputPath(): PathFragment? {
        return if (inputPath == null) getPrimaryInput().getExecPath() else inputPath
    }

    fun getOutputPath(actionExecutionContext: ActionExecutionContext): Path {
        return actionExecutionContext.getInputPath(getPrimaryOutput())
    }

    public override fun reconstructRichDataOnActionCacheHit(
        inputMetadataProvider: InputMetadataProvider
    ): RichArtifactData? {
        return if (targetType == com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.FILESET)
            FilesetOutputTree.Companion.forward(inputMetadataProvider.getFileset(getPrimaryInput()))
        else
            null
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        maybeVerifyTargetIsExecutable(actionExecutionContext)

        val targetPath: Path?
        if (inputPath == null) {
            targetPath = actionExecutionContext.getInputPath(getPrimaryInput())
        } else {
            targetPath = actionExecutionContext.getExecRoot().getRelative(inputPath)
        }

        val outputPath: Path = getOutputPath(actionExecutionContext)

        try {
            // Delete the empty output directory created prior to the action execution when the output is
            // a tree artifact. All other actions that produce tree artifacts expect it to exist prior to
            // their execution. It's not worth complicating ActionOutputDirectoryHelper just to avoid this
            // small amount of overhead.
            outputPath.delete()

            outputPath.createSymbolicLink(targetPath, getSymlinkTargetType(actionExecutionContext))
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "failed to create symbolic link '%s' to '%s' due to I/O error: %s",
                    getPrimaryOutput().getExecPathString(), printInputs(), e.message
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.LINK_CREATION_IO_EXCEPTION)
            throw ActionExecutionException(message, e, this, false, code)
        }

        updateInputMtimeIfNeeded(actionExecutionContext)

        val logContext: SpawnLogContext? = actionExecutionContext.getContext(SpawnLogContext::class.java)
        if (logContext != null) {
            try {
                logContext.logSymlinkAction(this)
            } catch (e: IOException) {
                val message: String? =
                    java.lang.String.format(
                        "failed to log creation of symlink '%s' to '%s' due to I/O error: %s",
                        getPrimaryOutput().getExecPathString(), printInputs(), e.message
                    )
                val code: DetailedExitCode = createDetailedExitCode(message, Code.LINK_LOG_IO_EXCEPTION)
                throw ActionExecutionException(message, e, this, false, code)
            }
        }

        if (targetType == com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.FILESET) {
            // Forward the Fileset metadata to the output artifact of this symlink: the metadata is
            // created in an upstream (Google-specific) action, but the output of this action will appear
            // on the inputs of actions that have the Fileset as an input. The Fileset metadata must be
            // attached to that artifact so that the execution strategies of actions that take it as an
            // input can recreate the Fileset.
            actionExecutionContext.setRichArtifactData(
                FilesetOutputTree.Companion.forward(
                    actionExecutionContext.getInputMetadataProvider().getFileset(getPrimaryInput())
                )
            )
        } else {
            maybeInjectMetadata(this, actionExecutionContext)
        }
        return ActionResult.EMPTY
    }

    @Throws(IOException::class)
    private fun getSymlinkTargetType(actionExecutionContext: ActionExecutionContext): SymlinkTargetType {
        val primaryInput: Artifact? = getPrimaryInput()
        if (primaryInput == null) {
            return SymlinkTargetType.UNSPECIFIED
        }
        val metadata: FileArtifactValue =
            checkNotNull(
                actionExecutionContext.getInputMetadataProvider().getInputMetadata(primaryInput),
                "missing metadata for %s",
                primaryInput
            )
        return if (metadata.getType() == FileStateType.DIRECTORY)
            SymlinkTargetType.DIRECTORY
        else
            SymlinkTargetType.FILE
    }

    @Throws(ActionExecutionException::class)
    private fun maybeVerifyTargetIsExecutable(actionExecutionContext: ActionExecutionContext) {
        if (targetType != com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.EXECUTABLE) {
            return
        }

        val primaryInput: Artifact = getPrimaryInput()
        val inputPath: Path = actionExecutionContext.getInputPath(primaryInput)

        // Source artifacts are probably in the syscall cache. Generated artifacts are probably not.
        val syscallCache: SyscallCache =
            if (primaryInput.isSourceArtifact())
                actionExecutionContext.getSyscallCache()
            else
                SyscallCache.NO_CACHE
        try {
            val stat: FileStatus? = syscallCache.statIfFound(inputPath, Symlinks.FOLLOW)
            if (stat == null || !stat.isFile()) {
                val message: String? = java.lang.String.format("'%s' is not a file", primaryInput.getExecPathString())
                throw ActionExecutionException(
                    message, this, false, createDetailedExitCode(message, Code.EXECUTABLE_INPUT_NOT_FILE)
                )
            }
            val isExecutable: Boolean
            if (stat.getPermissions() !== -1) {
                isExecutable = (stat.getPermissions() and S_IXUSR) !== 0
            } else {
                isExecutable = inputPath.isExecutable()
            }
            if (!isExecutable) {
                val message: String? =
                    java.lang.String.format(
                        "failed to create symbolic link '%s': file '%s' is not executable",
                        getPrimaryOutput().getExecPathString(), primaryInput.getExecPathString()
                    )
                throw ActionExecutionException(
                    message, this, false, createDetailedExitCode(message, Code.EXECUTABLE_INPUT_IS_NOT)
                )
            }
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "failed to create symbolic link '%s' to the '%s' due to I/O error: %s",
                    getPrimaryOutput().getExecPathString(),
                    primaryInput.getExecPathString(),
                    e.message
                )
            val detailedExitCode: DetailedExitCode =
                createDetailedExitCode(message, Code.EXECUTABLE_INPUT_CHECK_IO_EXCEPTION)
            throw ActionExecutionException(message, e, this, false, detailedExitCode)
        }
    }

    @Throws(ActionExecutionException::class)
    private fun updateInputMtimeIfNeeded(actionExecutionContext: ActionExecutionContext) {
        if (targetType != com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.FILESET) {
            return
        }

        try {
            // Update the mtime of the target of the symlink to force downstream re-execution of actions.
            // This is needed because dependency checking of Fileset output trees is unsound (it's a
            // directory).
            // Note that utime() on a symlink actually changes the mtime of its target.
            val linkPath: Path = getOutputPath(actionExecutionContext)
            if (linkPath.exists()) {
                linkPath.setLastModifiedTime(Path.NOW_SENTINEL_TIME)
            } else {
                // Should only happen if the Fileset included no links.
                actionExecutionContext
                    .getExecRoot()
                    .getRelative(getInputPath())
                    .createDirectoryAndParents()
            }
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "failed to touch symbolic link '%s' to the '%s' due to I/O error: %s",
                    getPrimaryOutput().getExecPathString(),
                    getPrimaryInput().getExecPathString(),
                    e.message
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.LINK_TOUCH_IO_EXCEPTION)
            throw ActionExecutionException(message, e, this, false, code)
        }
    }

    private fun printInputs(): String {
        if (getInputs().isEmpty()) {
            return inputPath.getPathString()
        } else if (getInputs().isSingleton()) {
            return getPrimaryInput().getExecPathString()
        } else {
            throw java.lang.IllegalStateException(
                "Inputs unexpectedly contains more than 1 element: " + getInputs()
            )
        }
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        // We don't normally need to add inputs to the key. In this case, however, the inputPath can be
        // different from the actual input artifact.
        if (inputPath != null) {
            fp.addPath(inputPath)
        }
    }

    public override fun describeKey(): String? {
        return String.format("GUID: %s\ninputPath: %s\n", GUID, inputPath)
    }

    public override fun getMnemonic(): String {
        return if (targetType == com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.EXECUTABLE) "ExecutableSymlink" else "Symlink"
    }

    public override fun isVolatile(): Boolean {
        return inputPath != null && inputPath.isAbsolute()
    }

    public override fun executeUnconditionally(): Boolean {
        // If the SymlinkAction points to an absolute path, we can't verify that its output artifact did
        // not change purely by looking at the output tree. Thus, we re-execute the action just to be
        // safe. Change pruning will take care of not re-running dependent actions and this is used only
        // in very rare cases (only C++ FDO and even then, only twice per build at most) anyway.
        return inputPath != null && inputPath.isAbsolute()
    }

    protected override fun getRawProgressMessage(): String? {
        return progressMessage
    }

    public override fun mayInsensitivelyPropagateInputs(): Boolean {
        return true
    }

    public override fun getExecutionPlatform(): PlatformInfo {
        return PlatformInfo.EMPTY_PLATFORM_INFO
    }

    public override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
        // SymlinkAction is platform agnostic.
        return com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    companion object {
        private const val GUID = "7f4fab4d-d0a7-4f0f-8649-1d0337a21fee"

        fun toArtifact(
            owner: ActionOwner?, input: Artifact, output: Artifact, progressMessage: String?
        ): SymlinkAction {
            return toArtifact(owner, input, output, progressMessage,  /* useExecRootForSource= */false)
        }

        /**
         * Creates an action that creates a symlink pointing to an artifact.
         * 
         * @param owner the action owner.
         * @param input the [Artifact] the symlink will point to
         * @param output the [Artifact] that will be created by executing this Action.
         * @param progressMessage the progress message.
         * @param useExecRootForSource whether to link source artifacts to exec root as opposed to the
         * artifact itself. This indirection makes sure that the symlink is always in sync with exec
         * root, which could go out of sync with it when re-planting the symlink tree and the header
         * was unchanged.
         */
        fun toArtifact(
            owner: ActionOwner?,
            input: Artifact,
            output: Artifact,
            progressMessage: String?,
            useExecRootForSource: Boolean
        ): SymlinkAction {
            return SymlinkAction(
                owner,
                if (useExecRootForSource && input.isSourceArtifact()) input.getExecPath() else null,
                input,
                output,
                progressMessage,
                com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.OTHER
            )
        }

        fun toExecutable(
            owner: ActionOwner?, input: Artifact?, output: Artifact, progressMessage: String?
        ): SymlinkAction {
            return SymlinkAction(
                owner,
                null,
                input,
                output,
                progressMessage,
                com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.EXECUTABLE
            )
        }

        /**
         * Creates a symlink to a Fileset.
         * 
         * 
         * This is different from a regular [SymlinkAction] in that the target is in the output
         * tree but not an artifact and that when running this action, the mtime of its target is updated
         * (necessary because dependency checking of Filesets is unsound). For more information, see the
         * Javadoc of `TargetType.FILESET`.
         * 
         * 
         * **WARNING:**Do not use this for anything else other than Filesets. If you do, your
         * correctness will depend on a subtle interaction between various parts of Blaze.
         * 
         * @param owner the action owner.
         * @param execPath where the symlink will point to
         * @param primaryInput the [Artifact] that is required to build the inputPath.
         * @param primaryOutput the [Artifact] that will be created by executing this Action.
         * @param progressMessage the progress message.
         */
        fun toFileset(
            owner: ActionOwner?,
            execPath: PathFragment,
            primaryInput: Artifact?,
            primaryOutput: Artifact,
            progressMessage: String?
        ): SymlinkAction {
            com.google.common.base.Preconditions.checkState(!execPath.isAbsolute())
            return SymlinkAction(
                owner,
                execPath,
                primaryInput,
                primaryOutput,
                progressMessage,
                com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.FILESET
            )
        }

        /**
         * Creates a new SymlinkAction instance, where an input artifact is not present. This is useful
         * when dealing with special cases where input paths that are outside the exec root directory
         * tree. Currently, the only instance where this happens is for FDO builds where the profile file
         * is outside the exec root structure.
         * 
         * 
         * Do **NOT** use this method unless there is no other way; unconditionally executed actions
         * are costly: even if change pruning kicks in and downstream actions are not re-executed, they
         * trigger unconditional Skyframe invalidation of their reverse dependencies.
         * 
         * @param owner the action owner.
         * @param absolutePath where the symlink will point to
         * @param output the Artifact that will be created by executing this Action.
         * @param progressMessage the progress message.
         */
        @Deprecated(
            """This action is not hermetic. To remove it, also the feature using it needs to be
        deprecated."""
        )
        fun toAbsolutePath(
            owner: ActionOwner?, absolutePath: PathFragment, output: Artifact, progressMessage: String?
        ): SymlinkAction {
            com.google.common.base.Preconditions.checkState(absolutePath.isAbsolute())
            return SymlinkAction(
                owner,
                absolutePath,
                null,
                output,
                progressMessage,
                com.google.devtools.build.lib.analysis.actions.SymlinkAction.TargetType.OTHER
            )
        }

        /**
         * Propagates metadata from the input artifact (symlink target) if possible.
         * 
         * 
         * This is an optimization that saves filesystem operations - we know the output is just a
         * symlink to the input, so we may be able to skip constructing its metadata from the filesystem.
         * 
         * 
         * In addition to reducing filesystem operations, this allows us to provide richer information
         * for the symlink metadata. For example, if the input metadata is a [ ], the output
         * metadata will be as well.
         * 
         * 
         * In cases where propagating the input metadata is incorrect ([ directory artifacts][Artifact.isDirectory]) or cases where the input metadata cannot be obtained, this method does
         * nothing. The output symlink will be read back from the filesystem after this action finishes
         * executing.
         */
        fun maybeInjectMetadata(symlinkAction: Action, ctx: ActionExecutionContext) {
            if (ctx.getActionFileSystem() != null) {
                return  // Action filesystems are responsible for their own metadata injection.
            }
            val primaryInput: Artifact? = symlinkAction.getPrimaryInput()
            if (primaryInput == null || primaryInput.isDirectory()) {
                return
            }
            val metadata: FileArtifactValue?
            try {
                metadata = ctx.getInputMetadataProvider().getInputMetadata(primaryInput)
            } catch (e: IOException) {
                return
            }
            if (metadata != null) {
                ctx.getOutputMetadataStore()
                    .injectFile(
                        symlinkAction.getPrimaryOutput(),
                        if (primaryInput is SourceArtifact)
                            FileArtifactValue.Companion.createFromExistingWithResolvedPath(
                                metadata, primaryInput.getPath().asFragment()
                            )
                        else
                            metadata
                    )
            }
        }

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setSymlinkAction(FailureDetails.SymlinkAction.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
