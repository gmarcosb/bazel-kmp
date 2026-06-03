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

import com.google.devtools.build.lib.actions.VirtualActionInput

/**
 * Helper class that sets up a sandbox in a more comprehensible way. Handles setting up
 * SandboxInputs and SandboxOutputs as well as creating related files.
 */
internal class SandboxHelper(execRoot: Path, workDir: Path) {
    /** Map from workdir-relative input path to optional real file path.  */
    private val inputs: MutableMap<PathFragment?, Path?> = HashMap<PathFragment?, Path?>()

    private val virtualInputs: MutableMap<VirtualActionInput?, ByteArray?> = HashMap<VirtualActionInput?, ByteArray?>()
    private val symlinks: MutableMap<PathFragment?, PathFragment?> = HashMap<PathFragment?, PathFragment?>()
    private val workerFiles: MutableMap<PathFragment?, Path?> = HashMap<PathFragment?, Path?>()
    private val outputFiles: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()
    private val outputDirs: MutableList<PathFragment?> = java.util.ArrayList<PathFragment?>()

    /** The global execRoot.  */
    val execRoot: Path

    /** The worker process's sandbox root.  */
    val workDir: Path

    init {
        this.execRoot = execRoot
        this.workDir = workDir
    }

    /**
     * Adds a regular input file at relativePath under `workDir`, with the real file at `workspacePath` under `execRoot`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addInputFile(relativePath: String?, workspacePath: String?): SandboxHelper {
        inputs.put(
            PathFragment.create(relativePath),
            if (workspacePath != null) execRoot.getRelative(workspacePath) else null
        )
        return this
    }

    /**
     * Adds a regular input file at relativePath under the `workDir`, with the real file at
     * `workspacePath` under `execRoot`. The real file gets created immediately and filled
     * with `contents`, which is assumed to be ASCII text.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun addAndCreateInputFile(
        relativePath: String?, workspacePath: String?, contents: String?
    ): SandboxHelper {
        addInputFile(relativePath, workspacePath)
        val absPath: Path = execRoot.getRelative(workspacePath)
        absPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(absPath, contents)
        return this
    }

    /** Adds a virtual input with some contents, which is assumed to be ASCII text.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addAndCreateVirtualInput(relativePath: String?, contents: String?): SandboxHelper {
        val input: VirtualActionInput? = ActionsTestUtil.createVirtualActionInput(relativePath, contents)
        val digest: ByteArray? =
            execRoot
                .getRelative(relativePath)
                .getFileSystem()
                .getDigestFunction()
                .getHashFunction()
                .hashString(contents, java.nio.charset.StandardCharsets.UTF_8)
                .asBytes()
        virtualInputs.put(input, digest)
        return this
    }

    /** Adds a symlink to the inputs.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addSymlink(relativePath: String?, linkTo: String?): SandboxHelper {
        symlinks.put(PathFragment.create(relativePath), PathFragment.create(linkTo))
        return this
    }

    /** Adds an output file without creating it.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addOutput(relativePath: String?): SandboxHelper {
        outputFiles.add(PathFragment.create(relativePath))
        return this
    }

    /** Adds an output directory without creating it.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addOutputDir(relativePath: String): SandboxHelper {
        outputDirs.add(
            PathFragment.create(if (relativePath.endsWith("/")) relativePath else relativePath + "/")
        )
        return this
    }

    /**
     * Adds a worker file that is created under `execRoot` and referenced under the `workDir`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addWorkerFile(relativePath: String?): SandboxHelper {
        val absPath: Path? = execRoot.getRelative(relativePath)
        workerFiles.put(PathFragment.create(relativePath), absPath)
        return this
    }

    /**
     * Adds a worker file that is created under `execRoot` and referenced under the `workDir`. Writes the content, which is assumed to be ASCII text, under `execRoot`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun addAndCreateWorkerFile(relativePath: String?, contents: String?): SandboxHelper {
        addWorkerFile(relativePath)
        val absPath: Path = execRoot.getRelative(relativePath)
        absPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(absPath, contents)
        return this
    }

    /**
     * Creates a file with `contents`, which is assumed to be ASCII text, at `relPath`
     * under the `workDir`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun createExecRootFile(relativePath: String?, contents: String?): SandboxHelper {
        val absPath: Path = workDir.getRelative(relativePath)
        absPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(absPath, contents)
        return this
    }

    /**
     * Creates a file with `contents`, which is assumed to be ASCII text, at `relPath`
     * under the `workDir`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun createWorkspaceDirFile(workspaceDirPath: String?, contents: String?): SandboxHelper {
        val absPath: Path = execRoot.getRelative(workspaceDirPath)
        absPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(absPath, contents)
        return this
    }

    /**
     * Creates a symlink from within the `workDir`. The destination is just what's written into
     * the symlink and thus relative to the created symlink.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun createSymlink(relativePath: String?, relativeDestination: String?): SandboxHelper {
        val fromPath: Path? = workDir.getRelative(relativePath)
        FileSystemUtils.ensureSymbolicLink(fromPath, relativeDestination)
        return this
    }

    val sandboxInputs: SandboxInputs?
        get() = SandboxInputs(inputs, virtualInputs, symlinks)

    val sandboxOutputs: SandboxOutputs
        get() = SandboxOutputs.create(
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (this.outputFiles),
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (this.outputDirs)
        )

    fun getWorkerFiles(): com.google.common.collect.ImmutableSet<PathFragment?> {
        return com.google.common.collect.ImmutableSet.copyOf<PathFragment?>(workerFiles.keys)
    }
}
