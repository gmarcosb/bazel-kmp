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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.actions.Action

/**
 * An OutputService retains control over the Blaze output tree, and provides a higher level of
 * abstraction compared to the VFS layer.
 * 
 * 
 * Higher-level facilities include batch statting, cleaning the output tree, creating symlink
 * trees, and out-of-band insertion of metadata into the tree.
 */
interface OutputService {
    /** Properties of the action file system implementation provided by this output service.  */
    enum class ActionFileSystemType {
        /** The action file system is disabled.  */
        DISABLED,

        /**
         * The action file system implementation is purely in-memory, taking full control of the output
         * base. It's not able to stage remote outputs accessed as inputs by local actions, but is able
         * to do input discovery. Used by Blaze.
         */
        IN_MEMORY_ONLY_FILE_SYSTEM,

        /**
         * The action file system implementation mixes an in-memory and a local file system. It uses the
         * in-memory filesystem for in-process and remote actions, but is also aware of outputs from
         * local actions. It's able to stage remote outputs accessed as inputs by local actions and to
         * do input discovery. Used by Blaze.
         */
        STAGE_REMOTE_FILES_FILE_SYSTEM,

        /**
         * Similar to STAGE_REMOTE_FILES_FILES_SYSTEM, but only constructs output directories as needed
         * by local actions. Used by Blaze.
         */
        STAGE_REMOTE_FILES_ON_DEMAND_FILE_SYSTEM,

        /**
         * The action file system implementation mixes an in-memory and a local file system. It uses the
         * in-memory filesystem for in-process and remote actions, but is also aware of outputs from
         * local actions. It's able to stage remote outputs accessed as inputs by local actions, but
         * unable to do input discovery. Used by Bazel.
         */
        REMOTE_FILE_SYSTEM;

        fun inMemoryFileSystem(): Boolean {
            return this != ActionFileSystemType.DISABLED
        }

        /**
         * Returns true if this service should early prepare the underlying filesystem for every action.
         * This involves deleting old output files and creating directories for the newly-created output
         * files. If false, the output service must handle such tasks itself as needed.
         */
        fun shouldDoEagerActionPrep(): Boolean {
            return this != ActionFileSystemType.IN_MEMORY_ONLY_FILE_SYSTEM && this != ActionFileSystemType.STAGE_REMOTE_FILES_ON_DEMAND_FILE_SYSTEM
        }

        /**
         * Returns true if this service supports execution of local actions. This is used to determine
         * whether to create [ ][com.google.devtools.build.lib.runtime.CommandEnvironment.getActionTempsDirectory].
         */
        fun supportsLocalActions(): Boolean {
            return this != ActionFileSystemType.IN_MEMORY_ONLY_FILE_SYSTEM
        }

        fun supportsInputDiscovery(): Boolean {
            return this != ActionFileSystemType.REMOTE_FILE_SYSTEM
        }

        val isEnabled: Boolean
            get() = this != ActionFileSystemType.DISABLED
    }

    /**
     * Returns the name of the filesystem used by this output service, akin to what you might see in
     * /proc/mounts.
     * 
     * @param outputBaseFileSystemName from [     ][com.google.devtools.build.lib.runtime.BlazeWorkspace.getOutputBaseFilesystemTypeName]
     */
    fun getFileSystemName(outputBaseFileSystemName: String?): String?

    val isLocalOnly: Boolean
        /** Whether actions can only be executed locally.  */
        get() = false

    /** Returns true if remote output metadata should be stored in action cache.  */
    fun shouldStoreRemoteOutputMetadataInActionCache(): Boolean {
        return false
    }

    val outputChecker: OutputChecker
        get() = OutputChecker.TRUST_ALL

    val proxyMetadataFactory: ProxyMetadataFactory
        get() = ProxyMetadataFactory.NO_PROXIES

    /**
     * Starts the build.
     * 
     * @param buildId the build identifier
     * @param workspaceName the name of the workspace in which the build is running
     * @param eventHandler an [EventHandler] to inform of events
     * @param finalizeActions whether this build is finalizing actions so that the output service can
     * track output tree modifications
     * @return a ModifiedFileSet of changed output files.
     * @throws BuildFailedException if build preparation failed
     */
    @Throws(BuildFailedException::class, AbruptExitException::class, java.lang.InterruptedException::class)
    fun startBuild(
        buildId: UUID?, workspaceName: String?, eventHandler: EventHandler?, finalizeActions: Boolean
    ): ModifiedFileSet?

    /** Flush and wait for in-progress downloads.  */
    @Throws(java.lang.InterruptedException::class)
    fun flushOutputTree() {
    }

    /**
     * Finish the build.
     * 
     * @param buildSuccessful iff build was successful
     * @throws BuildFailedException on failure
     */
    @Throws(BuildFailedException::class, AbruptExitException::class, java.lang.InterruptedException::class)
    fun finalizeBuild(buildSuccessful: Boolean)

    /** Notify the output service of a completed action.  */
    @Throws(IOException::class, EnvironmentalExecException::class, java.lang.InterruptedException::class)
    fun finalizeAction(action: Action?, outputMetadataStore: OutputMetadataStore?)

    val batchStatter: BatchStat?

    /** Returns true iff [.createSymlinkTree] is available.  */
    fun canCreateSymlinkTree(): Boolean

    /**
     * Creates a symlink tree.
     * 
     * @param symlinks map from `symlinkTreeRoot`-relative path to symlink target; may contain
     * null values to represent an empty file instead of a symlink (can happen with `__init__.py` files, see [     ])
     * @param symlinkTreeRoot the symlink tree root, relative to the exec root
     * @throws ExecException on failure
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun createSymlinkTree(symlinks: MutableMap<PathFragment?, PathFragment?>?, symlinkTreeRoot: PathFragment?)

    /**
     * Cleans the entire output tree.
     * 
     * @throws ExecException on failure
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun clean()

    fun actionFileSystemType(): ActionFileSystemType {
        return ActionFileSystemType.DISABLED
    }

    /**
     * Returns an action-scoped filesystem if [.actionFileSystemType] is enabled.
     * 
     * @param delegateFileSystem the actual underlying filesystem
     * @param execRootFragment absolute path fragment pointing to the execution root
     * @param relativeOutputPath execution root relative path to output
     * @param sourceRoots list of directories on the package path (from [     ])
     * @param inputArtifactData information about required inputs to the action
     * @param outputArtifacts required outputs of the action
     * @param rewindingEnabled whether to track failed remote reads to enable action rewinding
     */
    fun createActionFileSystem(
        delegateFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        execRootFragment: PathFragment?,
        relativeOutputPath: String?,
        sourceRoots: com.google.common.collect.ImmutableList<Root?>?,
        inputArtifactData: InputMetadataProvider?,
        outputArtifacts: Iterable<Artifact?>?,
        rewindingEnabled: Boolean
    ): com.google.devtools.build.lib.vfs.FileSystem? {
        return null
    }

    /**
     * Updates the context used by the filesystem returned by [.createActionFileSystem].
     * 
     * 
     * Should be called as context changes throughout action execution.
     * 
     * @param actionFileSystem must be a filesystem returned by [.createActionFileSystem].
     */
    fun updateActionFileSystemContext(
        action: ActionExecutionMetadata?,
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        outputMetadataStore: OutputMetadataStore?
    ) {
    }

    /**
     * Checks the filesystem returned by [.createActionFileSystem] for errors attributable to
     * lost inputs.
     */
    @Throws(LostInputsActionExecutionException::class)
    fun checkActionFileSystemForLostInputs(
        actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        action: Action?
    ) {
    }

    fun supportsPathResolverForArtifactValues(): Boolean {
        return false
    }

    fun createPathResolverForArtifactValues(
        execRoot: PathFragment?,
        relativeOutputPath: String?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        pathEntries: com.google.common.collect.ImmutableList<Root?>?,
        actionInputMap: ActionInputMap?
    ): ArtifactPathResolver? {
        throw java.lang.IllegalStateException("Path resolver not supported by this class")
    }

    fun bulkDeleter(): BulkDeleter? {
        return null
    }

    fun getXattrProvider(delegate: XattrProvider?): XattrProvider? {
        return delegate
    }

    fun stagesTopLevelRunfiles(): Boolean {
        return false
    }

    val rewoundActionSynchronizer: RewoundActionSynchronizer
        get() = RewoundActionSynchronizer.Companion.NOOP

    /**
     * Provides synchronization for actions in the presence of action rewinding.
     * 
     * 
     * If an action discovers that some of its inputs have been lost, action rewinding will select
     * actions that need to be re-executed to recover the lost inputs. Without synchronization, such
     * actions may run concurrently with actions that consume their non-lost outputs. Depending on the
     * particular output service and action filesystem implementation, this may lead to races, which
     * this interface aims to prevent.
     */
    interface RewoundActionSynchronizer {
        /**
         * Guards an action from the beginning of its [preparation][Action.prepare] until the end
         * of its [execution][Action.execute].
         */
        @Throws(java.lang.InterruptedException::class)
        fun enterActionPreparation(action: Action?, wasRewound: Boolean): SilentCloseable?

        /** Guards an action from the beginning to the end of its [execution][Action.execute].  */
        @Throws(java.lang.InterruptedException::class)
        fun enterActionExecution(action: Action?, metadataProvider: InputMetadataProvider?): SilentCloseable?

        companion object {
            /**
             * A no-op implementation of [RewoundActionSynchronizer], suitable for action filesystems
             * that support racy access to action outputs.
             */
            @kotlin.jvm.JvmField
            val NOOP: RewoundActionSynchronizer = object : RewoundActionSynchronizer {
                override fun enterActionPreparation(action: Action?, wasRewound: Boolean): SilentCloseable {
                    return SilentCloseable {}
                }

                override fun enterActionExecution(
                    action: Action?, metadataProvider: InputMetadataProvider?
                ): SilentCloseable {
                    return SilentCloseable {}
                }
            }
        }
    }
}
