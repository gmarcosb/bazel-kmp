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

import com.google.devtools.build.lib.actions.Action

/** Output service implementation for the remote build without local output service daemon.  */
class RemoteOutputService internal constructor(directories: BlazeDirectories?, private val rewindLostInputs: Boolean) :
    OutputService {
    private val directories: BlazeDirectories

    private var rewoundActionSynchronizer: RewoundActionSynchronizer = RewoundActionSynchronizer.NOOP

    private var remoteOutputChecker: RemoteOutputChecker? = null
    private var actionInputFetcher: RemoteActionInputFetcher? = null
    private var leaseService: LeaseService? = null

    init {
        this.directories = com.google.common.base.Preconditions.checkNotNull<BlazeDirectories>(directories)
    }

    fun setRemoteOutputChecker(remoteOutputChecker: RemoteOutputChecker?) {
        this.remoteOutputChecker = remoteOutputChecker
    }

    fun setActionInputFetcher(actionInputFetcher: RemoteActionInputFetcher?) {
        this.actionInputFetcher = com.google.common.base.Preconditions.checkNotNull<RemoteActionInputFetcher?>(
            actionInputFetcher,
            "actionInputFetcher"
        )
        if (rewindLostInputs) {
            this.rewoundActionSynchronizer = RemoteRewoundActionSynchronizer(actionInputFetcher)
        }
    }

    fun setLeaseService(leaseService: LeaseService?) {
        this.leaseService = leaseService
    }

    override fun actionFileSystemType(): ActionFileSystemType {
        return if (actionInputFetcher != null)
            ActionFileSystemType.REMOTE_FILE_SYSTEM
        else
            ActionFileSystemType.DISABLED
    }

    override fun createActionFileSystem(
        delegateFileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        execRootFragment: PathFragment?,
        relativeOutputPath: String?,
        sourceRoots: com.google.common.collect.ImmutableList<Root?>?,
        inputArtifactData: InputMetadataProvider?,
        outputArtifacts: Iterable<Artifact?>?,
        rewindingEnabled: Boolean
    ): com.google.devtools.build.lib.vfs.FileSystem? {
        com.google.common.base.Preconditions.checkNotNull<RemoteActionInputFetcher?>(
            actionInputFetcher,
            "actionInputFetcher"
        )
        return RemoteActionFileSystem(
            delegateFileSystem,
            execRootFragment,
            relativeOutputPath,
            inputArtifactData,
            actionInputFetcher
        )
    }

    override fun updateActionFileSystemContext(
        action: ActionExecutionMetadata?,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        outputMetadataStore: OutputMetadataStore?
    ) {
        (actionFileSystem as RemoteActionFileSystem).updateContext(action)
    }

    override fun getFileSystemName(outputBaseFileSystemName: String?): String {
        return "remoteActionFS"
    }

    @Throws(AbruptExitException::class)
    public override fun startBuild(
        buildId: UUID?,
        workspaceName: String?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        finalizeActions: Boolean
    ): ModifiedFileSet {
        // One of the responsibilities of OutputService.startBuild() is that it ensures the output path
        // is valid. If the previous OutputService redirected the output path to a remote location, we
        // must undo this.
        val outputPath: com.google.devtools.build.lib.vfs.Path = directories.getOutputPath(workspaceName)
        if (outputPath.isSymbolicLink()) {
            try {
                outputPath.delete()
            } catch (e: IOException) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                java.lang.String.format("Couldn't remove output path symlink: %s", e.getMessage())
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
        return ModifiedFileSet.EVERYTHING_MODIFIED
    }

    @Throws(java.lang.InterruptedException::class)
    override fun flushOutputTree() {
        if (actionInputFetcher != null) {
            actionInputFetcher.flushOutputTree()
        }
    }

    override fun finalizeBuild(buildSuccessful: Boolean) {
        // Intentionally left empty.
    }

    @com.google.common.eventbus.Subscribe
    fun onExecutionPhaseCompleteEvent(event: ExecutionPhaseCompleteEvent?) {
        if (leaseService != null) {
            leaseService.finalizeExecution()
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun finalizeAction(action: Action, outputMetadataStore: OutputMetadataStore?) {
        if (actionInputFetcher != null) {
            actionInputFetcher.finalizeAction(action, outputMetadataStore)
        }

        if (leaseService != null) {
            leaseService.finalizeAction()
        }
    }

    override fun shouldStoreRemoteOutputMetadataInActionCache(): Boolean {
        return true
    }

    val outputChecker: OutputChecker?
        get() = com.google.common.base.Preconditions.checkNotNull<RemoteOutputChecker?>(
            remoteOutputChecker,
            "remoteOutputChecker must not be null"
        )

    val batchStatter: BatchStat?
        get() = null

    override fun canCreateSymlinkTree(): Boolean {
        /* TODO(buchgr): Optimize symlink creation for remote execution */
        return false
    }

    override fun createSymlinkTree(
        symlinks: MutableMap<PathFragment?, PathFragment?>?, symlinkTreeRoot: PathFragment?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    override fun clean() {
        // Intentionally left empty.
    }

    override fun supportsPathResolverForArtifactValues(): Boolean {
        return actionFileSystemType() != ActionFileSystemType.DISABLED
    }

    override fun createPathResolverForArtifactValues(
        execRoot: PathFragment?,
        relativeOutputPath: String?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
        pathEntries: com.google.common.collect.ImmutableList<Root?>?,
        actionInputMap: ActionInputMap?
    ): ArtifactPathResolver {
        val remoteFileSystem: com.google.devtools.build.lib.vfs.FileSystem =
            RemoteActionFileSystem(
                fileSystem, execRoot, relativeOutputPath, actionInputMap, actionInputFetcher
            )
        return ArtifactPathResolver.createPathResolver(remoteFileSystem, fileSystem.getPath(execRoot))
    }

    @Throws(LostInputsActionExecutionException::class)
    override fun checkActionFileSystemForLostInputs(
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        action: Action?
    ) {
        if (actionFileSystem is RemoteActionFileSystem) {
            actionFileSystem.checkForLostInputs(action)
        }
    }

    override fun getRewoundActionSynchronizer(): RewoundActionSynchronizer {
        return rewoundActionSynchronizer
    }
}
