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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Artifact

/**
 * Handler provided by [ActionExecutionFunction] which allows the execution engine to obtain
 * [metadata][FileArtifactValue] about outputs and to store metadata about them for
 * purposes of creating the final [ActionExecutionValue].
 * 
 * 
 * The handler can be in one of two modes. After construction, it acts as a cache for output
 * metadata while [com.google.devtools.build.lib.actions.ActionCacheChecker] determines
 * whether the action needs to be executed. If the action needs to be executed (i.e. no action cache
 * hit), [.prepareForActionExecution] is called. This call switches the handler to a mode
 * where it accepts [ injected output data][com.google.devtools.build.lib.actions.cache.OutputMetadataStore], or otherwise obtains metadata from the filesystem. Freshly created output
 * files are set read-only and executable *before* statting them to ensure that the stat's
 * ctime is up to date.
 * 
 * 
 * After action execution, [.getOutputMetadata] or [.getTreeArtifactValue] should be
 * called on each of the action's outputs (except those that were [ omitted][.artifactOmitted]) to ensure that declared outputs were in fact created and are valid.
 */
internal class ActionOutputMetadataStore private constructor(
    private val archivedTreeArtifactsEnabled: Boolean,
    outputPermissions: OutputPermissions,
    outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
    xattrProvider: XattrProvider?,
    tsgm: TimestampGranularityMonitor?,
    artifactPathResolver: ArtifactPathResolver?
) : OutputMetadataStore {
    private val outputPermissions: OutputPermissions

    private val xattrProvider: XattrProvider?
    private val tsgm: TimestampGranularityMonitor
    private val artifactPathResolver: ArtifactPathResolver

    private val executionMode: AtomicBoolean = AtomicBoolean(false)

    private val outputs: com.google.common.collect.ImmutableSet<Artifact?>
    private val omittedOutputs: MutableSet<Artifact?> = com.google.common.collect.Sets.newConcurrentHashSet<Artifact?>()
    private val artifactData: ConcurrentMap<Artifact?, FileArtifactValue?> =
        ConcurrentHashMap<Artifact?, FileArtifactValue?>()
    private val treeArtifactData: ConcurrentMap<SpecialArtifact?, TreeArtifactValue?> =
        ConcurrentHashMap<SpecialArtifact?, TreeArtifactValue?>()

    init {
        this.outputPermissions = outputPermissions
        this.outputs =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<Artifact?>>(outputs)
        this.xattrProvider = xattrProvider
        this.tsgm = com.google.common.base.Preconditions.checkNotNull<TimestampGranularityMonitor>(tsgm)
        this.artifactPathResolver =
            com.google.common.base.Preconditions.checkNotNull<ArtifactPathResolver>(artifactPathResolver)
    }

    private fun putArtifactData(artifact: Artifact, value: FileArtifactValue?) {
        com.google.common.base.Preconditions.checkArgument(
            !artifact.isTreeArtifact() && !artifact.isChildOfDeclaredDirectory(),
            "%s should be stored in a TreeArtifactValue",
            artifact
        )
        artifactData.put(artifact, value)
    }

    val allArtifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>
        get() = com.google.common.collect.ImmutableMap.copyOf<Artifact?, FileArtifactValue?>(artifactData)

    val allTreeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>
        /**
         * Returns data for TreeArtifacts that was computed during execution. May contain copies of [ ][TreeArtifactValue.MISSING_TREE_ARTIFACT].
         */
        get() = com.google.common.collect.ImmutableMap.< K

                private

    fun isKnownOutput(artifact: Artifact): Boolean {
        return outputs.contains(artifact)
                || (artifact.hasParent() && outputs.contains(artifact.getParent()))
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    public override fun getOutputMetadata(artifact: Artifact): FileArtifactValue? {
        if (!isKnownOutput(artifact)) {
            return null
        }

        if (artifact.isRunfilesTree()) {
            // Runfiles trees get a placeholder value, see the Javadoc of RUNFILES_TREE_MARKER as to why
            val value: FileArtifactValue? = artifactData.get(artifact)
            if (value != null) {
                return Companion.checkExists(value, artifact)
            }
            putArtifactData(artifact, FileArtifactValue.RUNFILES_TREE_MARKER)
            return FileArtifactValue.RUNFILES_TREE_MARKER
        }

        if (artifact.isTreeArtifact()) {
            val tree: TreeArtifactValue = getTreeArtifactValue(artifact as SpecialArtifact)
            return tree.getMetadata()
        }

        if (artifact.isChildOfDeclaredDirectory()) {
            val tree: TreeArtifactValue = getTreeArtifactValue(artifact.getParent())
            val value: FileArtifactValue? =
                tree.getChildValues().getOrDefault(artifact, FileArtifactValue.MISSING_FILE_MARKER)
            return Companion.checkExists(value, artifact)
        }

        var value: FileArtifactValue? = artifactData.get(artifact)
        if (value != null) {
            return Companion.checkExists(value, artifact)
        }

        // No existing metadata; this can happen if the output metadata is not injected after a spawn
        // is executed. SkyframeActionExecutor.checkOutputs calls this method for every output file of
        // the action, which hits this code path. Another possibility is that an action runs multiple
        // spawns, and a subsequent spawn requests the metadata of an output of a previous spawn.

        // If necessary, we first call chmod the output file. The FileArtifactValue may use a
        // FileContentsProxy, which is based on ctime (affected by chmod).
        if (executionMode.get()) {
            setPathPermissionsIfFile(artifactPathResolver.toPath(artifact))
        }

        value = constructFileArtifactValueFromFilesystem(artifact)
        putArtifactData(artifact, value)
        return Companion.checkExists(value, artifact)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    public override fun getTreeArtifactValue(artifact: SpecialArtifact): TreeArtifactValue {
        checkState(artifact.isTreeArtifact(), "%s is not a tree artifact", artifact)

        var value: TreeArtifactValue? = treeArtifactData.get(artifact)
        if (value != null) {
            return checkExists(value, artifact)
        }

        value = constructTreeArtifactValueFromFilesystem(artifact)
        treeArtifactData.put(artifact, value)
        return checkExists(value, artifact)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun constructTreeArtifactValueFromFilesystem(parent: SpecialArtifact?): TreeArtifactValue? {
        val treeDir: com.google.devtools.build.lib.vfs.Path = artifactPathResolver.toPath(parent)
        val chmod: Boolean = executionMode.get()

        val stat: FileStatus? = treeDir.statIfFound(Symlinks.FOLLOW)

        // Make sure the tree artifact root exists and is a regular directory. Note that this is how the
        // action is initialized, so this should hold unless the action itself has deleted the root.
        if (stat == null || !stat.isDirectory()) {
            if (chmod) {
                setPathPermissionsIfFile(treeDir)
            }
            return TreeArtifactValue.MISSING_TREE_ARTIFACT
        }

        val tree: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)

        TreeArtifactValue.visitTree(
            treeDir,
            TreeArtifactVisitor { parentRelativePath: PathFragment?, type: com.google.devtools.build.lib.vfs.Dirent.Type?, traversedSymlink: Boolean ->
                com.google.common.base.Preconditions.checkState(type == com.google.devtools.build.lib.vfs.Dirent.Type.FILE || type == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY)
                // Set the output permissions when the execution mode requires it, unless at least one
                // symlink was traversed on the way to this entry, as it might have led outside of the
                // root directory.
                if (chmod && !traversedSymlink) {
                    setPathPermissions(treeDir.getRelative(parentRelativePath))
                }
                if (type == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                    return@visitTree  // The final TreeArtifactValue does not contain child directories.
                }
                val child: TreeFileArtifact = TreeFileArtifact.createTreeOutput(parent, parentRelativePath)
                val metadata: FileArtifactValue = constructFileArtifactValueFromFilesystem(child)
                // visitTree() uses multiple threads and putChild() is not thread-safe
                synchronized(tree) {
                    tree.putChild(child, metadata)
                }
            })

        if (archivedTreeArtifactsEnabled) {
            val archivedTreeArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(parent)
            val archivedStatNoFollow: FileStatus? =
                artifactPathResolver.toPath(archivedTreeArtifact).statIfFound(Symlinks.NOFOLLOW)
            if (archivedStatNoFollow != null) {
                tree.setArchivedRepresentation(
                    archivedTreeArtifact,
                    constructFileArtifactValue(
                        archivedTreeArtifact,
                        FileStatusWithDigestAdapter.maybeAdapt(archivedStatNoFollow)
                    )
                )
            } else {
                logger.atInfo().atMostEvery(5, TimeUnit.MINUTES).log(
                    "Archived tree artifact: %s not created", archivedTreeArtifact
                )
            }
        }

        // If the artifact was materialized in the filesystem as as symlink to another artifact, record
        // the real path in the metadata so that it can be recreated as such later.
        // See {@link FileArtifactValue#getResolvedPath} for why this is useful.
        // TODO(tjgq): Actually check whether the path matches one of the action inputs. The presence
        // of a FileStatusWithMetadata happens to coincide, but seems a little brittle.
        if (stat is FileStatusWithMetadata && treeDir.isSymbolicLink()) {
            val metadata: FileArtifactValue = stat.getMetadata()
            val resolvedPath: PathFragment? = metadata.getResolvedPath()
            if (resolvedPath != null) {
                tree.setResolvedPath(resolvedPath)
            } else {
                tree.setResolvedPath(treeDir.resolveSymbolicLinks().asFragment())
            }
        }

        return tree.build()
    }

    public override fun injectFile(output: Artifact, metadata: FileArtifactValue?) {
        com.google.common.base.Preconditions.checkArgument(
            isKnownOutput(output),
            "%s is not a declared output of this action",
            output
        )
        com.google.common.base.Preconditions.checkArgument(
            !output.isTreeArtifact() && !output.isChildOfDeclaredDirectory(),
            "Tree artifacts and their children must be injected via injectTree: %s",
            output
        )

        putArtifactData(output, metadata)
    }

    public override fun injectTree(output: SpecialArtifact, tree: TreeArtifactValue?) {
        com.google.common.base.Preconditions.checkArgument(
            isKnownOutput(output),
            "%s is not a declared output of this action",
            output
        )
        checkArgument(output.isTreeArtifact(), "Output must be a tree artifact: %s", output)
        treeArtifactData.put(output, tree)
    }

    public override fun markOmitted(output: Artifact?) {
        com.google.common.base.Preconditions.checkState(
            executionMode.get(),
            "Tried to mark %s omitted outside of execution",
            output
        )
        omittedOutputs.add(output)
    }

    public override fun artifactOmitted(artifact: Artifact?): Boolean {
        return omittedOutputs.contains(artifact)
    }

    public override fun resetOutputs(outputs: Iterable<out Artifact>) {
        com.google.common.base.Preconditions.checkState(
            executionMode.get(), "resetOutputs() should only be called from within a running action."
        )
        for (output in outputs) {
            omittedOutputs.remove(output)
            if (output.isTreeArtifact()) {
                treeArtifactData.remove(output)
            } else {
                artifactData.remove(output)
            }
        }
    }

    /**
     * Informs this handler that the action is about to be executed.
     * 
     * 
     * Any stale metadata cached from action cache checking is cleared.
     */
    fun prepareForActionExecution() {
        com.google.common.base.Preconditions.checkState(!executionMode.getAndSet(true), "Already in execution mode")
        artifactData.clear()
        treeArtifactData.clear()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("outputs", outputs)
            .add("artifactData", artifactData)
            .add("treeArtifactData", treeArtifactData)
            .toString()
    }

    /** Constructs a new [FileArtifactValue] by reading from the file system.  */
    @Throws(IOException::class)
    private fun constructFileArtifactValueFromFilesystem(artifact: Artifact): FileArtifactValue {
        return constructFileArtifactValue(artifact,  /* statNoFollow= */null)
    }

    /** Constructs a new [FileArtifactValue], optionally taking a known stat.  */
    @Throws(IOException::class)
    private fun constructFileArtifactValue(
        artifact: Artifact, statNoFollow: FileStatusWithDigest?
    ): FileArtifactValue {
        com.google.common.base.Preconditions.checkState(!artifact.isTreeArtifact(), "%s is a tree artifact", artifact)

        val statAndValue =
            fileArtifactValueFromArtifact(
                artifact,
                artifactPathResolver,
                statNoFollow,
                xattrProvider,  // Prevent constant metadata artifacts from notifying the timestamp granularity monitor
                // and potentially delaying the build for no reason.
                if (artifact.isConstantMetadata()) null else tsgm
            )
        val value: FileArtifactValue = statAndValue.fileArtifactValue

        // Ensure that we don't have both an injected digest and a digest from the filesystem.
        val fileDigest: ByteArray? = value.getDigest()

        val type: FileStateType = value.getType()

        if (!type.exists()) {
            // Nonexistent files should only occur before executing an action.
            throw FileNotFoundException(artifact.prettyPrint() + " does not exist")
        }

        if (type.isSymlink()) {
            // We always create a FileArtifactValue for an unresolved symlink with a digest (calling
            // readlink() is easy, unlike checksumming a potentially huge file).
            com.google.common.base.Preconditions.checkNotNull<ByteArray?>(fileDigest, "%s missing digest", value)
            return value
        }

        if (type.isFile() && fileDigest != null) {
            // The digest is in the file value and that is all that is needed for this file's metadata.
            return value
        }

        if (type.isDirectory()) {
            // This branch is taken when the output of an action is a directory:
            //   - A Fileset (in this case, Blaze is correct)
            //   - A directory someone created in a local action (in this case, changes under the
            //     directory may not be detected since we use the mtime of the directory for
            //     up-to-dateness checks)
            //   - A symlink to a source directory due to Filesets
            return FileArtifactValue.createForDirectoryWithMtime(
                artifactPathResolver.toPath(artifact).getLastModifiedTime()
            )
        }

        var digest: ByteArray? = null
        if (type.isFile()) {
            // We don't have an injected digest and there is no digest in the file value (which attempts a
            // fast digest). Manually compute the digest instead.
            var path: com.google.devtools.build.lib.vfs.Path? = statAndValue.pathNoFollow
            if (statAndValue.statNoFollow != null && statAndValue.statNoFollow.isSymbolicLink()
                && statAndValue.realPath != null
            ) {
                // If the file is a symlink, we compute the digest using the target path so that it's
                // possible to hit the digest cache - we probably already computed the digest for the
                // target during previous action execution.
                path = statAndValue.realPath
            }

            digest = com.google.devtools.build.lib.vfs.DigestUtils.manuallyComputeDigest(path)
        }
        return FileArtifactValue.createFromInjectedDigest(value, digest)
    }

    internal class FileArtifactStatAndValue(
        pathNoFollow: com.google.devtools.build.lib.vfs.Path?,
        realPath: com.google.devtools.build.lib.vfs.Path?,
        statNoFollow: FileStatusWithDigest?,
        fileArtifactValue: FileArtifactValue
    ) {
        val pathNoFollow: com.google.devtools.build.lib.vfs.Path?
        val realPath: com.google.devtools.build.lib.vfs.Path?
        val statNoFollow: FileStatusWithDigest?
        val fileArtifactValue: FileArtifactValue

        init {
            this.fileArtifactValue = fileArtifactValue
            this.statNoFollow = statNoFollow
            this.realPath = realPath
            this.pathNoFollow = pathNoFollow
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.vfs.Path?>(pathNoFollow, "pathNoFollow")
            java.util.Objects.requireNonNull<Any?>(fileArtifactValue, "fileArtifactValue")
        }

        companion object {
            fun create(
                pathNoFollow: com.google.devtools.build.lib.vfs.Path?,
                realPath: com.google.devtools.build.lib.vfs.Path?,
                statNoFollow: FileStatusWithDigest?,
                fileArtifactValue: FileArtifactValue
            ): FileArtifactStatAndValue {
                return FileArtifactStatAndValue(pathNoFollow, realPath, statNoFollow, fileArtifactValue)
            }
        }
    }

    @Throws(IOException::class)
    private fun setPathPermissionsIfFile(path: com.google.devtools.build.lib.vfs.Path) {
        val stat: FileStatus? = path.statIfFound(Symlinks.NOFOLLOW)
        if (stat != null && stat.isFile()
            && stat.getPermissions() != outputPermissions.getPermissionsMode()
        ) {
            setPathPermissions(path)
        }
    }

    @Throws(IOException::class)
    private fun setPathPermissions(path: com.google.devtools.build.lib.vfs.Path) {
        path.chmod(outputPermissions.getPermissionsMode())
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Creates a new metadata handler.  */
        fun create(
            archivedTreeArtifactsEnabled: Boolean,
            outputPermissions: OutputPermissions,
            outputs: com.google.common.collect.ImmutableSet<Artifact?>?,
            xattrProvider: XattrProvider?,
            tsgm: TimestampGranularityMonitor?,
            artifactPathResolver: ArtifactPathResolver?
        ): ActionOutputMetadataStore {
            return ActionOutputMetadataStore(
                archivedTreeArtifactsEnabled,
                outputPermissions,
                outputs,
                xattrProvider,
                tsgm,
                artifactPathResolver
            )
        }

        /**
         * If `value` represents an existing file, returns it as is, otherwise throws [ ].
         */
        @Throws(FileNotFoundException::class)
        private fun checkExists(value: FileArtifactValue?, artifact: Artifact?): FileArtifactValue? {
            if (FileArtifactValue.MISSING_FILE_MARKER.equals(value)) {
                throw FileNotFoundException(artifact.toString() + " does not exist")
            }
            return com.google.common.base.Preconditions.checkNotNull<FileArtifactValue?>(value, artifact)
        }

        /**
         * If `value` represents an existing tree artifact, returns it as is, otherwise throws
         * [FileNotFoundException].
         */
        @Throws(FileNotFoundException::class)
        private fun checkExists(value: TreeArtifactValue?, artifact: Artifact?): TreeArtifactValue {
            if (TreeArtifactValue.MISSING_TREE_ARTIFACT == value) {
                throw FileNotFoundException(artifact.toString() + " does not exist")
            }
            return com.google.common.base.Preconditions.checkNotNull<TreeArtifactValue>(value, artifact)
        }

        /**
         * Constructs a [FileArtifactValue] for a regular (non-tree, non-runfiles tree) artifact for
         * the purpose of determining whether an existing [FileArtifactValue] is still valid.
         * 
         * 
         * The returned metadata may be compared with metadata present in an [ ] using [FileArtifactValue.couldBeModifiedSince] to check for
         * inter-build modifications.
         */
        @Throws(IOException::class)
        fun fileArtifactValueFromArtifact(
            artifact: Artifact,
            statNoFollow: FileStatusWithDigest?,
            xattrProvider: XattrProvider?,
            tsgm: TimestampGranularityMonitor?
        ): FileArtifactValue {
            return fileArtifactValueFromArtifact(
                artifact, ArtifactPathResolver.IDENTITY, statNoFollow, xattrProvider, tsgm
            )
                .fileArtifactValue
        }

        @Throws(IOException::class)
        private fun fileArtifactValueFromArtifact(
            artifact: Artifact,
            artifactPathResolver: ArtifactPathResolver,
            statNoFollow: FileStatusWithDigest?,
            xattrProvider: XattrProvider?,
            tsgm: TimestampGranularityMonitor?
        ): FileArtifactStatAndValue {
            var statNoFollow: FileStatusWithDigest? = statNoFollow
            com.google.common.base.Preconditions.checkState(
                !artifact.isTreeArtifact() && !artifact.isRunfilesTree(),
                artifact
            )

            val pathNoFollow: com.google.devtools.build.lib.vfs.Path = artifactPathResolver.toPath(artifact)
            // If we expect a symlink, we can readlink it directly and handle errors appropriately - there
            // is no need for the stat below.
            if (artifact.isSymlink()) {
                val fileArtifactValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    FileArtifactValue.createForUnresolvedSymlink(pathNoFollow)
                return FileArtifactStatAndValue.Companion.create(
                    pathNoFollow,  /* realPath= */null, statNoFollow, fileArtifactValue
                )
            }

            val rootedPathNoFollow: RootedPath? =
                RootedPath.toRootedPath(
                    artifactPathResolver.transformRoot(artifact.getRoot().getRoot()),
                    artifact.getRootRelativePath()
                )
            if (statNoFollow == null) {
                // Stat the file. All output artifacts of an action are deleted before execution, so if a file
                // exists, it was most likely created by the current action. There is a race condition here if
                // an external process creates (or modifies) the file between the deletion and this stat,
                // which we cannot solve.
                statNoFollow =
                    FileStatusWithDigestAdapter.maybeAdapt(pathNoFollow.statIfFound(Symlinks.NOFOLLOW))
            }

            if (statNoFollow == null || !statNoFollow.isSymbolicLink()) {
                val fileArtifactValue: FileArtifactValue =
                    fileArtifactValueFromStat(rootedPathNoFollow, statNoFollow, xattrProvider, tsgm)
                return FileArtifactStatAndValue.Companion.create(
                    pathNoFollow,  /* realPath= */null, statNoFollow, fileArtifactValue
                )
            }

            // We use FileStatus#isSymbolicLink over Path#isSymbolicLink to avoid the unnecessary stat
            // done by the latter.  We need to protect against symlink cycles since
            // ArtifactFileMetadata#value assumes it's dealing with a file that's not in a symlink cycle.
            val realPath: com.google.devtools.build.lib.vfs.Path = pathNoFollow.resolveSymbolicLinks()
            if (realPath == pathNoFollow) {
                throw IOException("symlink cycle")
            }

            val realRootedPath: RootedPath =
                RootedPath.toRootedPathMaybeUnderRoot(
                    realPath,
                    com.google.common.collect.ImmutableList.of<E?>(
                        artifactPathResolver.transformRoot(
                            artifact.getRoot().getRoot()
                        )
                    )
                )

            // TODO(bazel-team): consider avoiding a 'stat' here when the symlink target hasn't changed
            // and is a source file (since changes to those are checked separately).
            val realStat: FileStatus? = realRootedPath.asPath().statIfFound(Symlinks.NOFOLLOW)
            val realStatWithDigest: FileStatusWithDigest? = FileStatusWithDigestAdapter.maybeAdapt(realStat)
            var fileArtifactValue: FileArtifactValue =
                fileArtifactValueFromStat(realRootedPath, realStatWithDigest, xattrProvider, tsgm)

            // If the artifact was materialized in the filesystem as as symlink to another artifact, record
            // the real path in the metadata so that it can be recreated as such later.
            // See {@link FileArtifactValue#getResolvedPath} for why this is useful.
            // TODO(tjgq): Actually check whether the path matches one of the action inputs. The presence
            // of a FileStatusWithMetadata happens to coincide, but seems a little brittle.
            if (realStat is FileStatusWithMetadata && fileArtifactValue.getResolvedPath() == null) {
                fileArtifactValue =
                    FileArtifactValue.createFromExistingWithResolvedPath(
                        fileArtifactValue, realRootedPath.asPath().asFragment()
                    )
            }

            return FileArtifactStatAndValue.Companion.create(pathNoFollow, realPath, statNoFollow, fileArtifactValue)
        }

        @Throws(IOException::class)
        private fun fileArtifactValueFromStat(
            rootedPath: RootedPath?,
            stat: FileStatusWithDigest?,
            xattrProvider: XattrProvider?,
            tsgm: TimestampGranularityMonitor?
        ): FileArtifactValue {
            if (stat == null) {
                return FileArtifactValue.MISSING_FILE_MARKER
            }

            if (stat.isDirectory()) {
                return FileArtifactValue.createForDirectoryWithMtime(stat.getLastModifiedTime())
            }

            if (stat is FileStatusWithMetadata) {
                return stat.getMetadata()
            }

            val fileStateValue: FileStateValue =
                FileStateValue.createWithStatNoFollow(rootedPath, stat, xattrProvider, tsgm)

            return FileArtifactValue.createForNormalFile(
                fileStateValue.getDigest(), fileStateValue.getContentsProxy(), stat.getSize()
            )
        }
    }
}
