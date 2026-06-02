// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.ActionInput

/**
 * Helper methods that are shared by the different sandboxing strategies.
 * 
 * 
 * All sandboxed strategies within a build should share the same instance of this object.
 */
object SandboxHelpers {
    const val INACCESSIBLE_HELPER_DIR: String = "inaccessibleHelperDir"
    const val INACCESSIBLE_HELPER_FILE: String = "inaccessibleHelperFile"

    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    private val warnedAboutMovesBeingCopies: AtomicBoolean = AtomicBoolean(false)

    private val VISITOR_POOL: ExecutorService = Executors.newThreadPerTaskExecutor(
        java.lang.Thread.ofVirtual().name("sandbox-directory-visitor-").factory()
    )

    /**
     * Moves or copies all given outputs from a root to another.
     * 
     * 
     * Moves if possible, otherwise makes a copy. It is unspecified whether the source files still
     * exist after this method returns.
     * 
     * @param outputs outputs to move/copy as relative paths to a root
     * @param sourceRoot root directory to copy from
     * @param targetRoot root directory to copy to
     * @throws IOException if moving/copying fails
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun moveOutputs(
        outputs: SandboxOutputs,
        sourceRoot: com.google.devtools.build.lib.vfs.Path,
        targetRoot: com.google.devtools.build.lib.vfs.Path
    ) {
        for (output in com.google.common.collect.Iterables.concat<MutableMap.MutableEntry<PathFragment?, PathFragment?>>(
            outputs.files.entries,
            outputs.dirs.entries
        )) {
            val source: com.google.devtools.build.lib.vfs.Path = sourceRoot.getRelative(output.value)
            val target: com.google.devtools.build.lib.vfs.Path = targetRoot.getRelative(output.key)

            val stat: FileStatus? = source.statIfFound(Symlinks.NOFOLLOW)
            if (stat == null) {
                // The correct thing to do here would be to delete the target path.
                // Unfortunately, this breaks streamed test output, which causes the test log to be written
                // directly to the target path even when sandboxing is enabled. Until we either fix streamed
                // test output or create a way to reliably detect it, just skip the deletion.
                continue
            }

            // Delete the target if it already exists.
            // Some test spawn outputs aren't action outputs, so they aren't deleted before action
            // execution.
            target.deleteTree()

            // Create the target's parent directory if it doesn't already exist.
            // Some test spawn outputs aren't action outputs, so their parent directories aren't created
            // before action execution.
            target.getParentDirectory().createDirectoryAndParents()

            try {
                // Prefer to move outputs through a rename, avoiding a more expensive copy.
                source.renameTo(target)
            } catch (unused: IOException) {
                // Assume that the rename failed because it was cross-device.
                // TODO(tjgq): Distinguish a cross-device rename from other errors.
                if (warnedAboutMovesBeingCopies.compareAndSet(false, true)) {
                    logger.atWarning().log(
                        ("Moving files out of the sandbox (e.g. from %s to %s) had to be done with a file"
                                + " copy, which is detrimental to performance; are the two trees in different"
                                + " file systems?"),
                        source, target
                    )
                }

                // Make a copy.
                // Do as little work as possible, as any overhead adds up for large trees. In particular,
                // avoid FileSystemUtils, which spends time deleting preexisting files and preserving
                // attributes: we know output directories start out empty, and don't care about attributes.
                // Speed up copying of large directory trees by parallelizing over files.
                // Don't delete the original; leave it to the sandbox to clean up after itself.
                if (stat.isFile()) {
                    copyFile(source, target)
                } else if (stat.isDirectory()) {
                    val copier = DirectoryCopier(source, target)
                    copier.run()
                } else if (stat.isSymbolicLink()) {
                    copySymlink(source, target)
                } else {
                    throw IOException(
                        "Don't know how to copy %s into %s because it has an unsupported type"
                            .formatted(source, target)
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        try {
            source.getInputStream().use { `in` ->
                target.getOutputStream().use { out ->
                    com.google.common.io.ByteStreams.copy(`in`, out)
                }
            }
        } catch (e: FileAccessException) {
            // Actions may create unreadable output files.
            // Make the source file readable and try again (but only once).
            // Don't check the permissions upfront to optimize for the typical case.
            source.chmod(420)
            source.getInputStream().use { `in` ->
                target.getOutputStream().use { out ->
                    com.google.common.io.ByteStreams.copy(`in`, out)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copySymlink(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        target.createSymbolicLink(source.readSymbolicLink())
    }

    /**
     * Cleans the existing sandbox at `root` to match the `inputs`, updating `inputsToCreate` and `dirsToCreate` to not contain existing inputs and dir. Existing
     * directories or files that are either not needed `inputs` or doesn't have the right
     * content or symlink target path are removed.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun cleanExisting(
        root: com.google.devtools.build.lib.vfs.Path,
        inputs: SandboxInputs,
        inputsToCreate: MutableSet<PathFragment?>,
        dirsToCreate: MutableSet<PathFragment>,
        workDir: com.google.devtools.build.lib.vfs.Path,
        treeDeleter: TreeDeleter
    ) {
        cleanExisting(
            root,
            inputs,
            inputsToCreate,
            dirsToCreate,
            workDir,
            treeDeleter,  /* sandboxContents= */
            null
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun cleanExisting(
        root: com.google.devtools.build.lib.vfs.Path,
        inputs: SandboxInputs,
        inputsToCreate: MutableSet<PathFragment?>,
        dirsToCreate: MutableSet<PathFragment>,
        workDir: com.google.devtools.build.lib.vfs.Path,
        treeDeleter: TreeDeleter,
        sandboxContents: SandboxContents?
    ) {
        val inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path = workDir.getRelative(INACCESSIBLE_HELPER_DIR)
        // Setting the permissions is necessary when we are using an asynchronous tree deleter in order
        // to move the directory first. This is not necessary for a synchronous tree deleter because the
        // permissions are only needed in the parent directory in that case.
        if (inaccessibleHelperDir.exists()) {
            inaccessibleHelperDir.setExecutable(true)
            inaccessibleHelperDir.setWritable(true)
            inaccessibleHelperDir.setReadable(true)
        }

        // To avoid excessive scanning of dirsToCreate for prefix dirs, we prepopulate this set of
        // prefixes.
        val prefixDirs: MutableSet<PathFragment?> = HashSet<PathFragment?>()
        for (dir in dirsToCreate) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            var parent: PathFragment? = dir.getParentDirectory()
            while (parent != null && !prefixDirs.contains(parent)) {
                prefixDirs.add(parent)
                parent = parent.getParentDirectory()
            }
        }
        if (sandboxContents == null) {
            cleanRecursively(
                root, inputs, inputsToCreate, dirsToCreate, workDir, prefixDirs, treeDeleter
            )
        } else {
            cleanRecursivelyWithInMemoryContents(
                root,
                inputs,
                inputsToCreate,
                dirsToCreate,
                workDir,
                prefixDirs,
                treeDeleter,
                sandboxContents
            )
        }
    }

    /**
     * Deletes unnecessary files/directories and updates the sets if something on disk is already
     * correct and doesn't need any changes.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun cleanRecursivelyWithInMemoryContents(
        root: com.google.devtools.build.lib.vfs.Path,
        inputs: SandboxInputs,
        inputsToCreate: MutableSet<PathFragment?>,
        dirsToCreate: MutableSet<PathFragment>,
        workDir: com.google.devtools.build.lib.vfs.Path,
        prefixDirs: MutableSet<PathFragment?>,
        treeDeleter: TreeDeleter,
        stashContents: SandboxContents?
    ) {
        val execroot: com.google.devtools.build.lib.vfs.Path? = workDir.getParentDirectory()
        com.google.common.base.Preconditions.checkNotNull<SandboxContents?>(stashContents)
        for (dirent in stashContents!!.symlinkMap!!.entries) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            val absPath: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.key)
            val pathRelativeToWorkDir: PathFragment? = getPathRelativeToWorkDir(absPath, workDir, execroot)
            val targetPath: java.util.Optional<PathFragment?> =
                getExpectedSymlinkTargetPath(pathRelativeToWorkDir, inputs)
            if (targetPath.isPresent() && dirent.value == targetPath.get()) {
                com.google.common.base.Preconditions.checkState(inputsToCreate.remove(pathRelativeToWorkDir))
            } else {
                absPath.delete()
            }
        }
        for (dirent in stashContents.dirMap!!.entries) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            val absPath: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.key)
            val pathRelativeToWorkDir: PathFragment? = getPathRelativeToWorkDir(absPath, workDir, execroot)
            if (dirsToCreate.contains(pathRelativeToWorkDir)
                || prefixDirs.contains(pathRelativeToWorkDir)
            ) {
                cleanRecursivelyWithInMemoryContents(
                    absPath,
                    inputs,
                    inputsToCreate,
                    dirsToCreate,
                    workDir,
                    prefixDirs,
                    treeDeleter,
                    dirent.value
                )
                dirsToCreate.remove(pathRelativeToWorkDir)
            } else {
                treeDeleter.deleteTree(absPath)
            }
        }
    }

    /**
     * Deletes unnecessary files/directories and updates the sets if something on disk is already
     * correct and doesn't need any changes.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun cleanRecursively(
        root: com.google.devtools.build.lib.vfs.Path,
        inputs: SandboxInputs,
        inputsToCreate: MutableSet<PathFragment?>,
        dirsToCreate: MutableSet<PathFragment>,
        workDir: com.google.devtools.build.lib.vfs.Path,
        prefixDirs: MutableSet<PathFragment?>,
        treeDeleter: TreeDeleter?
    ) {
        val execroot: com.google.devtools.build.lib.vfs.Path? = workDir.getParentDirectory()
        for (dirent in root.readdir(Symlinks.NOFOLLOW)) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            val absPath: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.getName())
            val pathRelativeToWorkDir: PathFragment?
            if (absPath.startsWith(workDir)) {
                // path is under workDir, i.e. execroot/<workspace name>. Simply get the relative path.
                pathRelativeToWorkDir = absPath.relativeTo(workDir)
            } else {
                // path is not under workDir, which means it belongs to one of external repositories
                // symlinked directly under execroot. Get the relative path based on there and prepend it
                // with the designated prefix, '../', so that it's still a valid relative path to workDir.
                pathRelativeToWorkDir =
                    LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX.getRelative(
                        absPath.relativeTo(execroot)
                    )
            }
            val targetPath: java.util.Optional<PathFragment?> =
                getExpectedSymlinkTargetPath(pathRelativeToWorkDir, inputs)
            if (targetPath.isPresent()) {
                if (com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK == dirent.getType()
                    && absPath.readSymbolicLink() == targetPath.get()
                ) {
                    inputsToCreate.remove(pathRelativeToWorkDir)
                } else if (com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY == dirent.getType()) {
                    if (treeDeleter == null) {
                        // TODO(bazel-team): Use async tree deleter for workers too
                        absPath.deleteTree()
                    } else {
                        treeDeleter.deleteTree(absPath)
                    }
                } else {
                    absPath.delete()
                }
            } else if (com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY == dirent.getType()) {
                if (dirsToCreate.contains(pathRelativeToWorkDir)
                    || prefixDirs.contains(pathRelativeToWorkDir)
                ) {
                    cleanRecursively(
                        absPath, inputs, inputsToCreate, dirsToCreate, workDir, prefixDirs, treeDeleter
                    )
                    dirsToCreate.remove(pathRelativeToWorkDir)
                } else {
                    if (treeDeleter == null) {
                        // TODO(bazel-team): Use async tree deleter for workers too
                        absPath.deleteTree()
                    } else {
                        treeDeleter.deleteTree(absPath)
                    }
                }
            } else if (!inputsToCreate.contains(pathRelativeToWorkDir)) {
                absPath.delete()
            }
        }
    }

    private fun getPathRelativeToWorkDir(
        absPath: com.google.devtools.build.lib.vfs.Path,
        workDir: com.google.devtools.build.lib.vfs.Path,
        execroot: com.google.devtools.build.lib.vfs.Path?
    ): PathFragment? {
        if (absPath.startsWith(workDir)) {
            // path is under workDir, i.e. execroot/<workspace name>. Simply get the relative path.
            return absPath.relativeTo(workDir)
        } else {
            // path is not under workDir, which means it belongs to one of external repositories
            // symlinked directly under execroot. Get the relative path based on there and prepend it
            // with the designated prefix, '../', so that it's still a valid relative path to workDir.
            return LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX.getRelative(
                absPath.relativeTo(execroot)
            )
        }
    }

    /**
     * Returns what the target path of the symlink `path` should be according to `inputs`.
     */
    private fun getExpectedSymlinkTargetPath(
        path: PathFragment?, inputs: SandboxInputs
    ): java.util.Optional<PathFragment?> {
        val file: com.google.devtools.build.lib.vfs.Path? = inputs.getFiles().get(path)
        if (file != null) {
            return java.util.Optional.of<PathFragment?>(file.asFragment())
        }
        return java.util.Optional.ofNullable<PathFragment?>(inputs.getSymlinks().get(path))
    }

    /** Populates the provided sets with the inputs and directories that need to be created.  */
    fun populateInputsAndDirsToCreate(
        writableDirs: MutableSet<PathFragment?>?,
        inputsToCreate: MutableSet<PathFragment?>,
        dirsToCreate: MutableSet<PathFragment?>,
        inputFiles: Iterable<PathFragment>,
        outputs: SandboxOutputs
    ) {
        // Add all worker files, input files, and the parent directories.
        for (input in inputFiles) {
            inputsToCreate.add(input)
            dirsToCreate.add(input.getParentDirectory())
        }

        // And all parent directories of output files. Note that we don't add the files themselves --
        // any pre-existing files that have the same path as an output should get deleted.
        for (file in outputs.files.values) {
            dirsToCreate.add(file.getParentDirectory())
        }

        // Add all output directories.
        dirsToCreate.addAll(outputs.dirs.values)

        // Add some directories that should be writable, and thus exist.
        dirsToCreate.addAll(writableDirs)
    }

    /**
     * Creates directory and all ancestors for it at a given path.
     * 
     * 
     * This method uses (and updates) the set of already known directories in order to minimize the
     * I/O involved with creating directories. For example a path of `1/2/3/4` created after
     * `1/2/3/5` only calls for creating `1/2/3/5`. We can use the set of known
     * directories to discover that `1/2/3` already exists instead of deferring to the
     * filesystem for it.
     */
    @Throws(IOException::class)
    fun createDirectoryAndParentsInSandboxRoot(
        path: com.google.devtools.build.lib.vfs.Path,
        knownDirectories: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path?
    ) {
        if (knownDirectories.contains(path)) {
            return
        }
        createDirectoryAndParentsInSandboxRoot(
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                path.getParentDirectory(),
                "Path %s is not under/siblings of sandboxExecRoot: %s",
                path,
                sandboxExecRoot
            ),
            knownDirectories,
            sandboxExecRoot
        )
        path.createDirectory()
        knownDirectories.add(path)
    }

    /**
     * Creates all directories needed for the sandbox.
     * 
     * 
     * No input can be a child of another input, because otherwise we might try to create a symlink
     * below another symlink we created earlier - which means we'd actually end up writing somewhere
     * in the workspace.
     * 
     * 
     * If all inputs were regular files, this situation could naturally not happen - but
     * unfortunately, we might get the occasional action that has directories in its inputs.
     * 
     * 
     * Creating all parent directories first ensures that we can safely create symlinks to
     * directories, too, because we'll get an IOException with EEXIST if inputs happen to be nested
     * once we start creating the symlinks for all inputs.
     * 
     * @param strict If true, absolute directories or directories with multiple up-level references
     * are disallowed, for stricter sandboxing.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createDirectories(
        dirsToCreate: Iterable<PathFragment>, dir: com.google.devtools.build.lib.vfs.Path, strict: Boolean
    ) {
        val knownDirectories: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
            HashSet<com.google.devtools.build.lib.vfs.Path?>()
        // Add sandboxExecRoot and it's parent -- all paths must fall under the parent of
        // sandboxExecRoot and we know that sandboxExecRoot exists. This stops the recursion in
        // createDirectoryAndParentsInSandboxRoot.
        knownDirectories.add(dir)
        knownDirectories.add(dir.getParentDirectory())
        knownDirectories.add(getTmpDirPath(dir))

        for (path in dirsToCreate) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            if (strict) {
                com.google.common.base.Preconditions.checkArgument(!path.isAbsolute(), path)
                if (path.containsUplevelReferences() && path.isMultiSegment()) {
                    // Allow a single up-level reference to allow inputs from the siblings of the main
                    // repository in the sandbox execution root, but forbid multiple up-level references.
                    // PathFragment is normalized, so up-level references are guaranteed to be at the
                    // beginning.
                    com.google.common.base.Preconditions.checkArgument(
                        !PathFragment.containsUplevelReferences(path.getSegment(1)),
                        "%s escapes the sandbox exec root.",
                        path
                    )
                }
            }

            createDirectoryAndParentsInSandboxRoot(dir.getRelative(path), knownDirectories, dir)
        }
    }

    fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
        return FailureDetail.newBuilder()
            .setMessage(message)
            .setSandbox(Sandbox.newBuilder().setCode(detailedCode))
            .build()
    }

    /** Adds additional bind mounts entries from `paths` to `bindMounts`.  */
    @Throws(UserExecException::class)
    fun mountAdditionalPaths(
        paths: com.google.common.collect.ImmutableMap<String?, String?>,
        sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
        bindMounts: SortedMap<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>
    ) {
        val fs: com.google.devtools.build.lib.vfs.FileSystem = sandboxExecRoot.getFileSystem()
        for (additionalMountPath in paths.entries) {
            try {
                val mountTarget: com.google.devtools.build.lib.vfs.Path? = fs.getPath(additionalMountPath.value)
                // If source path is relative, treat it as a relative path inside the execution root
                val mountSource: com.google.devtools.build.lib.vfs.Path =
                    sandboxExecRoot.getRelative(additionalMountPath.key)
                // If a target has more than one source path, the latter one will take effect.
                bindMounts.put(mountTarget, mountSource)
            } catch (e: java.lang.IllegalArgumentException) {
                throw UserExecException(
                    createFailureDetail(
                        String.format("Error occurred when analyzing bind mount pairs. %s", e.message),
                        Code.BIND_MOUNT_ANALYSIS_FAILURE
                    )
                )
            }
        }
    }

    /**
     * Returns the inputs of a Spawn as a map of PathFragments relative to an execRoot to paths in the
     * host filesystem where the input files can be found.
     * 
     * @param inputMap the map of action inputs and where they should be visible in the action
     * @param execRoot the exec root
     * @throws IOException if processing symlinks fails
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun processInputFiles(
        inputMap: MutableMap<PathFragment?, ActionInput?>, execRoot: com.google.devtools.build.lib.vfs.Path
    ): SandboxInputs {
        val inputFiles: MutableMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?> =
            TreeMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?>()
        val inputSymlinks: MutableMap<PathFragment?, PathFragment?> = TreeMap<PathFragment?, PathFragment?>()
        val virtualInputs: MutableMap<VirtualActionInput?, ByteArray?> = HashMap<VirtualActionInput?, ByteArray?>()

        for (e in inputMap.entries) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
            val pathFragment: PathFragment? = e.key
            val actionInput: ActionInput = e.value
            if (actionInput is VirtualActionInput) {
                val digest: ByteArray? = actionInput.atomicallyWriteRelativeTo(execRoot)
                virtualInputs.put(actionInput, digest)
            }

            if (actionInput.isSymlink()) {
                val inputPath: com.google.devtools.build.lib.vfs.Path = execRoot.getRelative(actionInput.getExecPath())
                inputSymlinks.put(pathFragment, inputPath.readSymbolicLink())
            } else {
                val inputPath: com.google.devtools.build.lib.vfs.Path? =
                    if (actionInput is EmptyActionInput)
                        null
                    else
                        execRoot.getRelative(actionInput.getExecPath())
                inputFiles.put(pathFragment, inputPath)
            }
        }
        return SandboxInputs(inputFiles, virtualInputs, inputSymlinks)
    }

    fun getOutputs(spawn: Spawn): SandboxOutputs {
        val files: com.google.common.collect.ImmutableMap.Builder<PathFragment?, PathFragment?> =
            com.google.common.collect.ImmutableMap.builder<PathFragment?, PathFragment?>()
        val dirs: com.google.common.collect.ImmutableMap.Builder<PathFragment?, PathFragment?> =
            com.google.common.collect.ImmutableMap.builder<PathFragment?, PathFragment?>()
        for (output in spawn.getOutputFiles()) {
            val mappedPath: PathFragment? = spawn.getPathMapper().map(output.getExecPath())
            if (output is Artifact && (output as Artifact).isTreeArtifact()) {
                dirs.put(output.getExecPath(), mappedPath)
            } else {
                files.put(output.getExecPath(), mappedPath)
            }
        }
        return SandboxOutputs.Companion.create(files.build(), dirs.build())
    }

    /**
     * Returns the path to the tmp directory of the given workDir of worker.
     * 
     * 
     * The structure of the worker directories should look like this: <outputBase>/
     * |__bazel-workers/ |__worker-<id>-<mnemonic>/ |__worker-<id>-<mnemonic>-tmp/
    </mnemonic></id></mnemonic></id></outputBase> */
    fun getTmpDirPath(workDir: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
        return workDir
            .getParentDirectory()
            .getParentDirectory()
            .getChild(workDir.getParentDirectory().getBaseName() + "-tmp")
    }

    /**
     * Returns true if the build options are set in a way that requires network access for all
     * actions. This is separate from [ ][com.google.devtools.build.lib.actions.Spawns.requiresNetwork] to avoid having to keep a
     * reference to the full set of build options (and also for performance, since this only needs to
     * be checked once-per-build).
     */
    fun shouldAllowNetwork(buildOptions: com.google.devtools.common.options.OptionsParsingResult): Boolean {
        // Allow network access, when --java_debug is specified, otherwise we can't connect to the
        // remote debug server of the test. This intentionally overrides the "block-network" execution
        // tag.
        return buildOptions
            .getOptions<O?>(TestConfiguration.TestOptions::class.java)
            .getTestArguments()
            .contains("--wrapper_script_flag=--debug")
    }

    /**
     * Computes a [SandboxContents] for the filesystem hierarchy rooted at `workDir`'s
     * parent directory, reflecting the expected inputs and outputs for a spawn.
     * 
     * 
     * This may be used in conjunction with [.updateContentMap] to speed up the sandbox setup
     * for a subsequent execution.
     */
    fun createContentMap(
        workDir: com.google.devtools.build.lib.vfs.Path, inputs: SandboxInputs, outputs: SandboxOutputs
    ): SandboxContents {
        val contentsMap: MutableMap<PathFragment?, SandboxContents?> =
            com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.create()
        for (entry in inputs.getFiles().entries) {
            if (entry.value == null) {
                continue
            }
            val parent: PathFragment? = entry.key.getParentDirectory()
            val parentWasPresent = !addParent(contentsMap, parent)
            contentsMap
                .get(parent)!!
                .symlinkMap!!
                .put(entry.key.getBaseName(), entry.value.asFragment())
            addAllParents(contentsMap, parentWasPresent, parent)
        }
        for (entry in inputs.getSymlinks().entries) {
            if (entry.value == null) {
                continue
            }
            val parent: PathFragment? = entry.key.getParentDirectory()
            val parentWasPresent = !addParent(contentsMap, parent)
            contentsMap.get(parent)!!.symlinkMap!!.put(entry.key.getBaseName(), entry.value)
            addAllParents(contentsMap, parentWasPresent, parent)
        }

        for (outputDir in java.util.stream.Stream.concat<PathFragment?>(
            outputs.files.values.stream().map<PathFragment?> { obj: PathFragment? -> obj.getParentDirectory() },
            outputs.dirs.values.stream()
        )
            .distinct()
            .collect(com.google.common.collect.ImmutableList.toImmutableList<PathFragment?>())) {
            val parent: PathFragment = outputDir
            val parentWasPresent = !addParent(contentsMap, parent)
            addAllParents(contentsMap, parentWasPresent, parent)
        }
        // TODO: Handle the sibling repository layout correctly. Currently, the code below assumes that
        // all paths descend from the main repository.
        val root = SandboxContents()
        root.dirMap!!.put(workDir.getBaseName(), contentsMap.get(PathFragment.EMPTY_FRAGMENT))
        return root
    }

    /**
     * Updates a [SandboxContents] previously created by [.createContentMap] to reflect
     * any filesystem modifications that occurred after the given timestamp.
     * 
     * 
     * This is necessary because an action may delete some of its inputs or create additional
     * declared outputs. We assume that a ctime check on directories is sufficient to detect such
     * modifications and avoid a full filesystem traversal.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun updateContentMap(
        root: com.google.devtools.build.lib.vfs.Path,
        timestamp: Long,
        stashContents: SandboxContents
    ) {
        if (root.stat().getLastChangeTime() > timestamp) {
            val dirsToKeep: MutableSet<String?> = HashSet<String?>()
            val filesAndSymlinksToKeep: MutableSet<String?> = HashSet<String?>()
            for (dirent in root.readdir(Symlinks.NOFOLLOW)) {
                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException()
                }
                val absPath: com.google.devtools.build.lib.vfs.Path = root.getChild(dirent.getName())
                if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
                    if (stashContents.symlinkMap!!.containsKey(dirent.getName())
                        && absPath.stat().getLastChangeTime() <= timestamp
                    ) {
                        filesAndSymlinksToKeep.add(dirent.getName())
                    } else {
                        absPath.delete()
                    }
                } else if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY) {
                    if (stashContents.dirMap!!.containsKey(dirent.getName())) {
                        dirsToKeep.add(dirent.getName())
                        SandboxHelpers.updateContentMap(
                            absPath,
                            timestamp,
                            stashContents.dirMap.get(dirent.getName())!!
                        )
                    } else {
                        absPath.deleteTree()
                        stashContents.dirMap.remove(dirent.getName())
                    }
                } else {
                    absPath.delete()
                }
            }
            stashContents.dirMap!!.keys.retainAll(dirsToKeep)
            stashContents.symlinkMap!!.keys.retainAll(filesAndSymlinksToKeep)
        } else {
            for (entry in stashContents.dirMap!!.entries) {
                val absPath: com.google.devtools.build.lib.vfs.Path = root.getChild(entry.key)
                SandboxHelpers.updateContentMap(absPath, timestamp, entry.value!!)
            }
        }
    }

    @kotlin.jvm.JvmStatic
    @com.google.common.annotations.VisibleForTesting
    fun resetWarnedAboutMovesBeingCopiesForTesting() {
        warnedAboutMovesBeingCopies.set(false)
    }

    private fun addParent(
        contentsMap: MutableMap<PathFragment?, SandboxContents?>, parent: PathFragment?
    ): Boolean {
        var parentWasPresent = true
        if (!contentsMap.containsKey(parent)) {
            contentsMap.put(parent, SandboxContents())
            parentWasPresent = false
        }
        return !parentWasPresent
    }

    private fun addAllParents(
        contentsMap: MutableMap<PathFragment?, SandboxContents?>,
        parentWasPresent: Boolean,
        parent: PathFragment
    ) {
        var parentWasPresent = parentWasPresent
        var parent: PathFragment = parent
        var grandparent: PathFragment?
        while (!parentWasPresent && (parent.getParentDirectory().also { grandparent = it }) != null) {
            var grandparentContents = contentsMap.get(grandparent)
            if (grandparentContents != null) {
                parentWasPresent = true
            } else {
                grandparentContents = SandboxContents()
                contentsMap.put(grandparent, grandparentContents)
            }
            grandparentContents.dirMap.putIfAbsent(parent.getBaseName(), contentsMap.get(parent))
            parent = grandparent
        }
    }

    private class DirectoryCopier(
        sourceRoot: com.google.devtools.build.lib.vfs.Path?,
        targetRoot: com.google.devtools.build.lib.vfs.Path?
    ) : AbstractQueueVisitor(
        VISITOR_POOL,
        ExecutorOwnership.SHARED,
        ExceptionHandlingMode.FAIL_FAST,
        ErrorClassifier.DEFAULT
    ) {
        private val sourceRoot: com.google.devtools.build.lib.vfs.Path
        private val targetRoot: com.google.devtools.build.lib.vfs.Path

        init {
            this.sourceRoot =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(sourceRoot)
            this.targetRoot =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(targetRoot)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun run() {
            try {
                visitDirectory(sourceRoot, targetRoot)
                awaitQuiescence(true)
            } catch (e: UncheckedIOException) {
                throw e.cause
            }
        }

        fun visitDirectory(
            sourceDir: com.google.devtools.build.lib.vfs.Path,
            targetDir: com.google.devtools.build.lib.vfs.Path
        ) {
            var dirents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent>
            try {
                try {
                    dirents = sourceDir.readdir(Symlinks.NOFOLLOW)
                } catch (e: FileAccessException) {
                    // Make the source directory readable and try again (but only once).
                    // Don't check the permissions upfront to optimize for the typical case.
                    sourceDir.chmod(493)
                    dirents = sourceDir.readdir(Symlinks.NOFOLLOW)
                }
                targetDir.createDirectory()
                for (dirent in dirents) {
                    val sourceChild: com.google.devtools.build.lib.vfs.Path = sourceDir.getChild(dirent.getName())
                    val targetChild: com.google.devtools.build.lib.vfs.Path = targetDir.getChild(dirent.getName())
                    when (dirent.getType()) {
                        com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY -> execute({
                            visitDirectory(
                                sourceChild,
                                targetChild
                            )
                        })

                        com.google.devtools.build.lib.vfs.Dirent.Type.FILE -> execute({
                            visitFile(
                                sourceChild,
                                targetChild
                            )
                        })

                        com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK -> execute({
                            visitSymlink(
                                sourceChild,
                                targetChild
                            )
                        })

                        com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN -> throw IOException(
                            "Don't know how to copy %s to %s".formatted(sourceChild, targetChild)
                        )
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }

        fun visitFile(
            sourceFile: com.google.devtools.build.lib.vfs.Path,
            targetFile: com.google.devtools.build.lib.vfs.Path
        ) {
            try {
                copyFile(sourceFile, targetFile)
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }

        fun visitSymlink(
            sourceSymlink: com.google.devtools.build.lib.vfs.Path,
            targetSymlink: com.google.devtools.build.lib.vfs.Path
        ) {
            try {
                copySymlink(sourceSymlink, targetSymlink)
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }

    /** Wrapper class for the inputs of a sandbox.  */
    class SandboxInputs(
        files: MutableMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?>,
        virtualInputs: MutableMap<VirtualActionInput?, ByteArray?>,
        symlinks: MutableMap<PathFragment?, PathFragment?>
    ) {
        private val files: MutableMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?>
        private val virtualInputs: MutableMap<VirtualActionInput?, ByteArray?>
        private val symlinks: MutableMap<PathFragment?, PathFragment?>

        init {
            this.files = files
            this.virtualInputs = virtualInputs
            this.symlinks = symlinks
        }

        fun getFiles(): MutableMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?> {
            return files
        }

        fun getSymlinks(): MutableMap<PathFragment?, PathFragment?> {
            return symlinks
        }

        val virtualInputDigests: com.google.common.collect.ImmutableMap<VirtualActionInput?, ByteArray?>
            get() = com.google.common.collect.ImmutableMap.copyOf<VirtualActionInput?, ByteArray?>(virtualInputs)

        /**
         * Returns a new SandboxInputs instance with only the inputs/symlinks listed in `allowed`
         * included.
         */
        fun limitedCopy(allowed: MutableSet<PathFragment?>): SandboxInputs {
            return SandboxInputs(
                com.google.common.collect.Maps.filterKeys<PathFragment?, com.google.devtools.build.lib.vfs.Path?>(
                    files,
                    com.google.common.base.Predicate { o: PathFragment? -> allowed.contains(o) }),
                com.google.common.collect.ImmutableMap.of<VirtualActionInput?, ByteArray?>(),
                com.google.common.collect.Maps.filterKeys<PathFragment?, PathFragment?>(
                    symlinks,
                    com.google.common.base.Predicate { o: PathFragment? -> allowed.contains(o) })
            )
        }

        override fun toString(): String {
            return "Files: " + files + "\nVirtualInputs: " + virtualInputs + "\nSymlinks: " + symlinks
        }

        companion object {
            val emptyInputs: SandboxInputs = SandboxInputs(
                com.google.common.collect.ImmutableMap.of<PathFragment?, com.google.devtools.build.lib.vfs.Path?>(),
                com.google.common.collect.ImmutableMap.of<VirtualActionInput?, ByteArray?>(),
                com.google.common.collect.ImmutableMap.of<PathFragment?, PathFragment?>()
            )
        }
    }

    /**
     * The file and directory outputs of a sandboxed spawn.
     * 
     * @param files A map from output file exec paths to paths in the sandbox.
     * @param dirs A map from output directory exec paths to paths in the sandbox.
     */
    class SandboxOutputs(
        files: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?,
        dirs: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?
    ) {
        val files: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?
        val dirs: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?

        init {
            this.dirs = dirs
            this.files = files
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?>(
                files,
                "files"
            )
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?>(
                dirs,
                "dirs"
            )
        }

        companion object {
            val emptyInstance: SandboxOutputs = Companion.create(
                com.google.common.collect.ImmutableMap.of<PathFragment?, PathFragment?>(),
                com.google.common.collect.ImmutableMap.of<PathFragment?, PathFragment?>()
            )

            fun create(
                files: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?,
                dirs: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?
            ): SandboxOutputs {
                return SandboxOutputs(files, dirs)
            }

            fun create(
                files: com.google.common.collect.ImmutableSet<PathFragment?>,
                dirs: com.google.common.collect.ImmutableSet<PathFragment?>
            ): SandboxOutputs {
                return SandboxOutputs(
                    files.stream().collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<PathFragment?, PathFragment?, PathFragment?>(
                            java.util.function.Function { f: PathFragment? -> f },
                            java.util.function.Function { f: PathFragment? -> f })
                    ),
                    dirs.stream().collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<PathFragment?, PathFragment?, PathFragment?>(
                            java.util.function.Function { d: PathFragment? -> d },
                            java.util.function.Function { d: PathFragment? -> d })
                    )
                )
            }
        }
    }

    /**
     * In-memory representation of the set of paths known to be present in a sandbox directory.
     * 
     * 
     * Used to minimize the amount of I/O required to prepare a sandbox for reuse.
     * 
     * 
     * The map keys are individual path segments.
     * 
     * @param symlinkMap maps names of known symlinks to their target path
     * @param dirMap maps names of known subdirectories to their contents
     */
    class SandboxContents(
        symlinkMap: MutableMap<String?, PathFragment?>?,
        dirMap: MutableMap<String?, SandboxContents?>?
    ) {
        constructor() : this(
            com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.create(),
            com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap.create()
        )

        val symlinkMap: MutableMap<String?, PathFragment?>?
        val dirMap: MutableMap<String?, SandboxContents?>?

        init {
            this.symlinkMap = symlinkMap
            this.dirMap = dirMap
        }
    }
}
