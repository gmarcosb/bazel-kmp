// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.exec.TreeDeleter

/**
 * Implements the general flow of a sandboxed spawn that uses a container directory to build an
 * execution root for a spawn.
 */
abstract class AbstractContainerizingSandboxedSpawn(
    sandboxPath: com.google.devtools.build.lib.vfs.Path?,
    sandboxExecRoot: com.google.devtools.build.lib.vfs.Path,
    arguments: com.google.common.collect.ImmutableList<String?>?,
    environment: com.google.common.collect.ImmutableMap<String?, String?>?,
    inputs: SandboxInputs,
    outputs: SandboxOutputs,
    writableDirs: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
    treeDeleter: TreeDeleter,
    sandboxDebugPath: com.google.devtools.build.lib.vfs.Path?,
    statisticsPath: com.google.devtools.build.lib.vfs.Path?,
    mnemonic: String?
) : SandboxedSpawn {
    val sandboxPath: com.google.devtools.build.lib.vfs.Path?
    val sandboxExecRoot: com.google.devtools.build.lib.vfs.Path
    private val arguments: com.google.common.collect.ImmutableList<String?>?
    private val environment: com.google.common.collect.ImmutableMap<String?, String?>?
    val inputs: SandboxInputs
    val outputs: SandboxOutputs
    private val writableDirs: MutableSet<com.google.devtools.build.lib.vfs.Path?>
    protected val treeDeleter: TreeDeleter
    private val sandboxDebugPath: com.google.devtools.build.lib.vfs.Path?
    private val statisticsPath: com.google.devtools.build.lib.vfs.Path?
    val mnemonic: String?

    init {
        this.sandboxPath = sandboxPath
        this.sandboxExecRoot = sandboxExecRoot
        this.arguments = arguments
        this.environment = environment
        this.inputs = inputs
        this.outputs = outputs
        this.writableDirs = writableDirs
        this.treeDeleter = treeDeleter
        this.sandboxDebugPath = sandboxDebugPath
        this.statisticsPath = statisticsPath
        this.mnemonic = mnemonic
    }

    override fun getSandboxExecRoot(): com.google.devtools.build.lib.vfs.Path {
        return sandboxExecRoot
    }

    override fun getArguments(): com.google.common.collect.ImmutableList<String?>? {
        return arguments
    }

    override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return environment
    }

    override fun getSandboxDebugPath(): com.google.devtools.build.lib.vfs.Path? {
        return sandboxDebugPath
    }

    override fun getStatisticsPath(): com.google.devtools.build.lib.vfs.Path? {
        return statisticsPath
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun createFileSystem() {
        // First compute all the inputs and directories that we need. This is based only on
        // `workerFiles`, `inputs` and `outputs` and won't do any I/O.
        val inputsToCreate: MutableSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val dirsToCreate: MutableSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val writableSandboxDirs: MutableSet<PathFragment?> =
            writableDirs.stream()
                .filter { p: com.google.devtools.build.lib.vfs.Path? -> p.startsWith(sandboxExecRoot) }
                .map<PathFragment?> { p: com.google.devtools.build.lib.vfs.Path? -> p.relativeTo(sandboxExecRoot) }
                .collect(Collectors.toSet())
        Profiler.instance().profile("sandbox.populateInputsAndDirsToCreate").use { c ->
            SandboxHelpers.populateInputsAndDirsToCreate(
                writableSandboxDirs,
                inputsToCreate,
                dirsToCreate,
                com.google.common.collect.Iterables.concat<PathFragment?>(
                    com.google.common.collect.ImmutableSet.of<PathFragment?>(),
                    inputs.getFiles().keys,
                    inputs.getSymlinks().keys
                ),
                outputs
            )
        }
        Profiler.instance().profile("sandbox.filterInputsAndDirsToCreate").use { c ->
            // Allow subclasses to filter out inputs and dirs that don't need to be created.
            filterInputsAndDirsToCreate(inputsToCreate, dirsToCreate)
        }
        Profiler.instance().profile("sandbox.createDirectories").use { c ->
            SandboxHelpers.createDirectories(dirsToCreate, sandboxExecRoot,  /* strict= */true)
        }
        Profiler.instance().profile("sandbox.createInputs").use { c ->
            createInputs(inputsToCreate, inputs)
        }
        SandboxStash.Companion.setLastModified(sandboxPath, java.lang.System.currentTimeMillis())
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    protected open fun filterInputsAndDirsToCreate(
        inputsToCreate: MutableSet<PathFragment?>?, dirsToCreate: MutableSet<PathFragment?>?
    ) {
    }

    /**
     * Creates all inputs needed for this spawn's sandbox.
     * 
     * @param inputsToCreate The inputs that actually need to be created. Some inputs may already
     * exist if we're reusing a previously existing sandbox.
     * @param inputs All the inputs for this spawn.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createInputs(inputsToCreate: Iterable<PathFragment?>, inputs: SandboxInputs) {
        for (fragment in inputsToCreate) {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException("Interrupted creating inputs")
            }
            val key: com.google.devtools.build.lib.vfs.Path = sandboxExecRoot.getRelative(fragment)
            if (inputs.getFiles().containsKey(fragment)) {
                val fileDest: com.google.devtools.build.lib.vfs.Path? = inputs.getFiles().get(fragment)
                if (fileDest != null) {
                    copyFile(fileDest, key)
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

    @Throws(IOException::class)
    protected abstract fun copyFile(
        source: com.google.devtools.build.lib.vfs.Path?,
        target: com.google.devtools.build.lib.vfs.Path?
    )

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun copyOutputs(execRoot: com.google.devtools.build.lib.vfs.Path?) {
        SandboxHelpers.moveOutputs(outputs, sandboxExecRoot, execRoot)
    }

    override fun delete() {
        try {
            treeDeleter.deleteTree(sandboxPath)
        } catch (e: IOException) {
            // This usually means that the Spawn itself exited, but still has children running that
            // we couldn't wait for, which now block deletion of the sandbox directory. On Linux this
            // should never happen, as we use PID namespaces and where they are not available the
            // subreaper feature to make sure all children have been reliably killed before returning,
            // but on other OS this might not always work. The SandboxModule will try to delete them
            // again when the build is all done, at which point it hopefully works, so let's just go
            // on here.
        }
    }
}
