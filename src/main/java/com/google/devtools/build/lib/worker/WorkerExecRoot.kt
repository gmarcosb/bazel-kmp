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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.exec.TreeDeleter

/** Creates and manages the contents of a working directory of a persistent worker.  */
internal class WorkerExecRoot(
    workDir: com.google.devtools.build.lib.vfs.Path,
    extraDirs: MutableList<PathFragment?>,
    useInMemoryTracking: Boolean
) {
    private val workDir: com.google.devtools.build.lib.vfs.Path
    private val extraDirs: MutableList<PathFragment?>

    private val useInMemoryTracking: Boolean
    private var sandboxContents: SandboxContents? = null
    private var sandboxContentsTimestamp: Long = 0

    /**
     * Creates a new WorkerExecRoot.
     * 
     * @param workDir The directory (workspace dir) that the worker will be executing in.
     * @param extraDirs Directories that must survive sandbox cleanup, e.g. for things that are
     * bind-mounted.
     */
    init {
        this.workDir = workDir
        this.extraDirs = extraDirs
        this.useInMemoryTracking = useInMemoryTracking
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createFileSystem(
        workerFiles: MutableSet<PathFragment?>?,
        inputs: SandboxInputs,
        outputs: SandboxOutputs?,
        treeDeleter: TreeDeleter?
    ) {
        workDir.createDirectoryAndParents()

        // First compute all the inputs and directories that we need. This is based only on
        // `workerFiles`, `inputs` and `outputs` and won't do any I/O.
        val inputsToCreate: MutableSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val dirsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>(extraDirs)
        SandboxHelpers.populateInputsAndDirsToCreate(
            com.google.common.collect.ImmutableSet.of<E?>(),
            inputsToCreate,
            dirsToCreate,
            com.google.common.collect.Iterables.concat(
                workerFiles,
                inputs.getFiles().keySet(),
                inputs.getSymlinks().keySet()
            ),
            outputs
        )

        // If we have information about the previous contents of the sandbox, update it to reflect
        // filesystem changes that have happened in the interim, to speed up the cleanup process below.
        // TODO(tjgq): Consider doing this asynchronously in between worker invocations.
        if (sandboxContents != null) {
            SandboxHelpers.updateContentMap(
                workDir.getParentDirectory(), sandboxContentsTimestamp, sandboxContents
            )
        }

        // Then do a full traversal of the parent directory of `workDir`. This will use what we computed
        // above, delete anything unnecessary and update `inputsToCreate`/`dirsToCreate` if something is
        // can be left without changes (e.g., a symlink that already points to the right destination).
        // We're traversing from workDir's parent directory because external repositories can now be
        // symlinked as siblings of workDir when --experimental_sibling_repository_layout is in effect.
        SandboxHelpers.cleanExisting(
            workDir.getParentDirectory(),
            inputs,
            inputsToCreate,
            dirsToCreate,
            workDir,
            treeDeleter,
            sandboxContents
        )

        // Finally, create anything that is still missing. This is non-strict only for historical
        // reasons, we haven't seen what would break if we make it strict.
        SandboxHelpers.createDirectories(dirsToCreate, workDir,  /* strict= */false)
        createInputs(inputsToCreate, inputs, workDir)

        // Track the sandbox contents in memory. This makes the cleanup faster in subsequent runs.
        if (useInMemoryTracking) {
            sandboxContents = SandboxHelpers.createContentMap(workDir, inputs, outputs)
            sandboxContentsTimestamp = java.lang.System.currentTimeMillis()
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun copyOutputs(execRoot: com.google.devtools.build.lib.vfs.Path?, outputs: SandboxOutputs?) {
        SandboxHelpers.moveOutputs(outputs, workDir, execRoot)
    }

    companion object {
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun createInputs(
            inputsToCreate: Iterable<PathFragment?>,
            inputs: SandboxInputs,
            dir: com.google.devtools.build.lib.vfs.Path
        ) {
            for (fragment in inputsToCreate) {
                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException()
                }
                val key: com.google.devtools.build.lib.vfs.Path = dir.getRelative(fragment)
                if (inputs.getFiles().containsKey(fragment)) {
                    val fileDest: com.google.devtools.build.lib.vfs.Path? = inputs.getFiles().get(fragment)
                    if (fileDest != null) {
                        key.createSymbolicLink(fileDest)
                    } else {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.createEmptyFile(key)
                    }
                } else if (inputs.getSymlinks().containsKey(fragment)) {
                    val symlinkDest: PathFragment? = inputs.getSymlinks().get(fragment)
                    if (symlinkDest != null) {
                        key.createSymbolicLink(symlinkDest)
                    }
                }
            }
        }
    }
}
