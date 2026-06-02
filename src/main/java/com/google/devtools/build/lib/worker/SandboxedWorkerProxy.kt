// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.exec.TreeDeleter

/**
 * A multiplex worker proxy with sandboxing. The multiplexer process runs in `workDir`, while
 * each proxy has a fixed subdir where it sets up its files. The subdir is then passed to the worker
 * in [WorkRequest.sandbox_dir]. The worker implementation is responsible for reading from and
 * writing to that subdir only.
 */
class SandboxedWorkerProxy internal constructor(
    workerKey: WorkerKey,
    workerId: Int,
    logFile: com.google.devtools.build.lib.vfs.Path?,
    workerMultiplexer: WorkerMultiplexer,
    workDir: com.google.devtools.build.lib.vfs.Path?,
    treeDeleter: TreeDeleter?
) : WorkerProxy(workerKey, workerId, logFile, workerMultiplexer, workDir) {
    /** The sandbox directory for the current request, inside `workDir`.  */
    private val sandboxDir: com.google.devtools.build.lib.vfs.Path

    private val sandboxName: PathFragment

    private val treeDeleter: TreeDeleter?

    init {
        sandboxName =
            PathFragment.Companion.create(
                com.google.common.base.Joiner.on(PathFragment.Companion.SEPARATOR_CHAR)
                    .join(
                        "__sandbox",
                        java.lang.Integer.toString(workerId),
                        workerKey.getExecRoot().getBaseName()
                    )
            )
        sandboxDir = this.workDir.getRelative(sandboxName)
        this.treeDeleter = treeDeleter
    }

    val isSandboxed: Boolean
        get() = true

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepareExecution(
        inputFiles: SandboxInputs,
        outputs: SandboxOutputs?,
        workerFiles: MutableSet<PathFragment?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        workerMultiplexer.createSandboxedProcess(
            workDir, workerFiles, inputFiles, treeDeleter, clientEnv
        )

        sandboxDir.createDirectoryAndParents()
        val dirsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val inputsToCreate: MutableSet<PathFragment?> = HashSet<PathFragment?>()

        SandboxHelpers.populateInputsAndDirsToCreate(
            com.google.common.collect.ImmutableSet.of<E?>(),
            inputsToCreate,
            dirsToCreate,
            com.google.common.collect.Iterables.concat(
                inputFiles.getFiles().keySet(),
                inputFiles.getSymlinks().keySet()
            ),
            outputs
        )
        SandboxHelpers.cleanExisting(
            sandboxDir.getParentDirectory(),
            inputFiles,
            inputsToCreate,
            dirsToCreate,
            sandboxDir,
            treeDeleter
        )
        // Finally, create anything that is still missing. This is non-strict only for historical
        // reasons, we haven't seen what would break if we make it strict.
        SandboxHelpers.createDirectories(dirsToCreate, sandboxDir,  /* strict= */false)
        WorkerExecRoot.Companion.createInputs(inputsToCreate, inputFiles, sandboxDir)
    }

    /** Send the WorkRequest to multiplexer.  */
    @Throws(IOException::class)
    override fun putRequest(request: WorkRequest) {
        // Modifying the request on the way out is not great. The alternatives are having the
        // spawn runner ask the worker for the dir or making the spawn runner understand the sandbox,
        // dir structure, neither of which are nice either.
        workerMultiplexer.putRequest(
            request.toBuilder().setSandboxDir(sandboxName.getPathString()).build()
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun finishExecution(execRoot: com.google.devtools.build.lib.vfs.Path?, outputs: SandboxOutputs?) {
        super.finishExecution(execRoot, outputs)
        SandboxHelpers.moveOutputs(outputs, sandboxDir, execRoot)
    }

    @kotlin.jvm.Synchronized
    override fun destroy() {
        super.destroy()
        try {
            sandboxDir.deleteTree()
        } catch (e: IOException) {
            logger.atWarning().withCause(e).log("Caught IOException while deleting workdir.")
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
