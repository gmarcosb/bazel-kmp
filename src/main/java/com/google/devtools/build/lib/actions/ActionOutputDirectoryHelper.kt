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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.vfs.FileStatus

/**
 * Helper class to create directories for [ action][com.google.devtools.build.lib.actions.Action] outputs.
 */
class ActionOutputDirectoryHelper(cacheBuilderSpec: CaffeineSpec) {
    // Directories which are known to be created as regular directories within this invocation. This
    // implies parent directories are also regular directories.
    private val knownDirectories: MutableMap<PathFragment?, DirectoryState?>

    private enum class DirectoryState {
        FOUND,
        CREATED
    }

    init {
        knownDirectories =
            Caffeine.from(cacheBuilderSpec)
                .initialCapacity(java.lang.Runtime.getRuntime().availableProcessors())
                .build<PathFragment?, DirectoryState?>()
                .asMap()
    }

    /**
     * Creates output directories, including missing ancestor directories, for the given set of action
     * outputs.
     * 
     * 
     * This method should only be used with an action filesystem ([ ]). Otherwise, please use
     * call [.createOutputDirectories] to avoid recreating output directories shared by multiple
     * actions.
     * 
     * @throws CreateOutputDirectoryException if one of the output directories or one of its ancestor
     * directories fails to be created
     */
    @Throws(CreateOutputDirectoryException::class)
    fun createActionFsOutputDirectories(
        actionOutputs: MutableCollection<Artifact>, artifactPathResolver: ArtifactPathResolver
    ) {
        val done: MutableSet<Path?> = HashSet<Path?>() // avoid redundant calls for the same directory.
        for (outputFile in actionOutputs) {
            val outputDir: Path
            if (outputFile.isTreeArtifact()) {
                outputDir = artifactPathResolver.toPath(outputFile)
            } else {
                outputDir = artifactPathResolver.toPath(outputFile).getParentDirectory()
            }

            if (done.add(outputDir)) {
                try {
                    outputDir.createDirectoryAndParents()
                    outputDir.setWritable(true)
                    continue
                } catch (e: IOException) {
                    /* Fall through to plan B. */
                }

                val rootPath: Path = artifactPathResolver.convertPath(outputFile.getRoot().getRoot().asPath())
                forceCreateDirectoryAndParents(outputDir, rootPath)
            }
        }
    }

    /**
     * Invalidates the cached creation of tree artifact directories when an action is going to be
     * rewound.
     * 
     * 
     * We use [.knownDirectories] to only create an output directory once per build. With
     * rewinding, actions that output tree artifacts need to recreate the directories because they are
     * deleted as part of the [com.google.devtools.build.lib.actions.Action.prepare] step.
     * 
     * 
     * Note that this does not need to be called if using an in-memory action file system ([ ][com.google.devtools.build.lib.vfs.OutputService.ActionFileSystemType.inMemoryFileSystem]).
     */
    fun invalidateTreeArtifactDirectoryCreation(actionOutputs: MutableCollection<Artifact>) {
        for (output in actionOutputs) {
            if (output.isTreeArtifact()) {
                knownDirectories.remove(output.getPath().asFragment())
            }
        }
    }

    /**
     * Creates output directories, including missing ancestor directories, for the given set of action
     * outputs.
     * 
     * 
     * For a non-tree output, the parent directory is created; for a tree output, the root
     * directory for the tree is created.
     * 
     * 
     * If a path to be created already exists but is not a directory, it is recursively deleted and
     * an empty directory is created in its place. If the path exists but is a non-writable directory,
     * it is made writable.
     * 
     * 
     * Already created directories are recorded in [.knownDirectories] to avoid recreating
     * them; calling this method a second time for the same directory is a no-op. For this reason,
     * this method should not be used with an action file system ([ ]), as an output directory
     * shared by multiple actions would only be created in the action filesystem for one of them.
     * Please use [.createActionFsOutputDirectories] instead.
     * 
     * @throws CreateOutputDirectoryException if one of the output directories or one of its ancestor
     * directories fails to be created
     */
    @Throws(CreateOutputDirectoryException::class)
    fun createOutputDirectories(actionOutputs: MutableCollection<Artifact>) {
        val done: MutableSet<Path?> = HashSet<Path?>() // avoid redundant calls for the same directory.
        for (outputFile in actionOutputs) {
            val outputDir: Path
            // Given we know that we are not using action file system, we can get safely get paths
            // directly from the artifacts.
            if (outputFile.isTreeArtifact()) {
                outputDir = outputFile.getPath()
            } else {
                outputDir = outputFile.getPath().getParentDirectory()
            }

            if (done.add(outputDir)) {
                createOutputDirectory(outputDir, outputFile.getRoot().getRoot().asPath())
            }
        }
    }

    /**
     * Creates a writable output directory, including missing ancestor directories.
     * 
     * 
     * If a path to be created already exists but is not a directory, it is recursively deleted and
     * an empty directory is created in its place. If the path exists but is a non-writable directory,
     * it is made writable.
     * 
     * 
     * Already created directories are recorded in [.knownDirectories] to avoid recreating
     * them; calling this method a second time for the same directory is a no-op. For this reason,
     * this method should not be used with an action file system, as an output directory shared across
     * actions would only be created in the action filesystem for one of them.
     * 
     * @throws CreateOutputDirectoryException if the output directory or one of its ancestor
     * directories fails to be created
     */
    @Throws(CreateOutputDirectoryException::class)
    fun createOutputDirectory(outputDir: Path, rootPath: Path) {
        try {
            createAndCheckForSymlinks(outputDir, rootPath)
        } catch (e: IOException) {
            /* Fall through to plan B. */
            forceCreateDirectoryAndParents(outputDir, rootPath)
        }
    }

    @Throws(CreateOutputDirectoryException::class)
    private fun forceCreateDirectoryAndParents(outputDir: Path, rootPath: Path) {
        // Possibly some direct ancestors are not directories.  In that case, we traverse the
        // ancestors downward, deleting any non-directories. This handles the case where a file
        // becomes a directory. The traversal is done downward because otherwise we may delete
        // files through a symlink in a parent directory. Since Blaze never creates such
        // directories within a build, we have no idea where on disk we're actually deleting.
        //
        // Symlinks should not be followed so in order to clean up symlinks pointing to Fileset
        // outputs from previous builds. See bug [incremental build of Fileset fails if
        // Fileset.out was changed to be a subdirectory of the old value].
        try {
            var p: Path = rootPath
            for (segment in outputDir.relativeTo(p).segments()) {
                p = p.getRelative(segment)

                // This lock ensures that the only thread that observes a filesystem transition in
                // which the path p first exists and then does not is the thread that calls
                // p.delete() and causes the transition.
                //
                // If it were otherwise, then some thread A could test p.exists(), see that it does,
                // then test p.isDirectory(), see that p isn't a directory (because, say, thread
                // B deleted it), and then call p.delete(). That could result in two different kinds
                // of failures:
                //
                // 1) In the time between when thread A sees that p is not a directory and when thread
                // A calls p.delete(), thread B may reach the call to createDirectoryAndParents
                // and create a directory at p, which thread A then deletes. Thread B would then try
                // adding outputs to the directory it thought was there, and fail.
                //
                // 2) In the time between when thread A sees that p is not a directory and when thread
                // A calls p.delete(), thread B may create a directory at p, and then either create a
                // subdirectory beneath it or add outputs to it. Then when thread A tries to delete p,
                // it would fail.
                val lock: java.util.concurrent.locks.Lock = outputDirectoryDeletionLock.get(p)
                lock.lock()
                try {
                    val stat: FileStatus? = p.statIfFound(Symlinks.NOFOLLOW)
                    if (stat == null) {
                        // Missing entry: Break out and create expected directories.
                        break
                    }
                    if (stat.isDirectory()) {
                        // If this directory used to be a tree artifact it won't be writable.
                        p.setWritable(true)
                        knownDirectories.put(p.asFragment(), DirectoryState.FOUND)
                    } else {
                        // p may be a file or symlink (possibly from a Fileset in a previous build).
                        p.delete() // throws IOException
                        break
                    }
                } finally {
                    lock.unlock()
                }
            }
            outputDir.createDirectoryAndParents()
        } catch (e: IOException) {
            throw CreateOutputDirectoryException(outputDir.asFragment(), e)
        }
    }

    /**
     * Create an output directory and ensure that no symlinks exists between the output root and the
     * output file. These are all expected to be regular directories. Violations of this expectations
     * can only come from state left behind by previous invocations or external filesystem mutation.
     * 
     * @throws IOException if any of the path components between the output root and the output file
     * already exists but is not a directory
     */
    @Throws(IOException::class)
    private fun createAndCheckForSymlinks(dir: Path, rootPath: Path) {
        var dir: Path = dir
        val root: PathFragment? = rootPath.asFragment()

        // If the output root has not been created yet, do so now.
        if (!knownDirectories.containsKey(root)) {
            val stat: FileStatus? = rootPath.statNullable(Symlinks.NOFOLLOW)
            if (stat == null) {
                rootPath.createDirectoryAndParents()
                knownDirectories.put(root, DirectoryState.CREATED)
            } else {
                knownDirectories.put(root, DirectoryState.FOUND)
            }
        }

        // Walk up until the first known directory is found (must be root or below).
        val checkDirs: MutableList<Path?> = java.util.ArrayList<Path?>()
        while (!dir.equals(rootPath) && !knownDirectories.containsKey(dir.asFragment())) {
            checkDirs.add(dir)
            dir = dir.getParentDirectory()
        }

        // Check in reverse order (parent directory first).
        val parentCreated = knownDirectories.get(dir.asFragment()) == DirectoryState.CREATED
        for (path in com.google.common.collect.Lists.reverse<Path>(checkDirs)) {
            if (parentCreated) {
                // If we have created this directory's parent, we know that it doesn't exist or else we
                // would know about it already. Even if a parallel thread has created it in the meantime,
                // createDirectory() will succeed and we can assume that a regular directory exists
                // afterwards.
                path.createDirectory()
                knownDirectories.put(path.asFragment(), DirectoryState.CREATED)
                continue
            }
            // Otherwise, check whether the directory already exists.
            // Note: while we could also optimistically try to create the directory upfront, benchmarks
            // indicate that doing it this way is faster.
            val stat: FileStatus? = path.statIfFound(Symlinks.NOFOLLOW)
            if (stat != null) {
                // Already exists, but make sure it's a directory.
                if (!stat.isDirectory()) {
                    throw IOException(path.toString() + " (File exists)")
                }
                // Adjust permissions on the directory if necessary.
                // Avoid touching permissions for group/other, which may have been overridden by umask(2)
                // when this directory was originally created.
                val perms: Int = stat.getPermissions()
                if (perms == -1) {
                    path.chmod(511)
                } else if ((perms and 448) != 448) {
                    path.chmod(perms or 448)
                }
                knownDirectories.put(path.asFragment(), DirectoryState.FOUND)
            } else {
                // Create the directory. Even if a parallel thread has created it in the meantime,
                // createDirectory() will succeed and we can assume that a regular directory exists
                // afterwards.
                path.createDirectory()
                knownDirectories.put(path.asFragment(), DirectoryState.CREATED)
            }
        }
    }

    /** An exception that occurred while attempting to create an output directory.  */
    class CreateOutputDirectoryException private constructor(directoryPath: PathFragment?, cause: IOException) :
        IOException(cause.message, cause) {
        private val directoryPath: PathFragment?

        init {
            this.directoryPath = directoryPath
        }

        /** Returns the path to the output directory for which the exception occurred.  */
        fun getDirectoryPath(): PathFragment? {
            return directoryPath
        }
    }

    companion object {
        // Used to prevent check-then-act races in #createOutputDirectories. See the comment there for
        // more detail.
        private val outputDirectoryDeletionLock: com.google.common.util.concurrent.Striped<java.util.concurrent.locks.Lock> =
            com.google.common.util.concurrent.Striped.lock(64)

        @com.google.common.annotations.VisibleForTesting
        fun createForTesting(): ActionOutputDirectoryHelper {
            // Matches the --directory_creation_cache default.
            return ActionOutputDirectoryHelper(CaffeineSpec.parse("maximumSize=10000"))
        }
    }
}
