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

import com.google.devtools.build.lib.exec.TreeDeleter

/**
 * Creates an execRoot for a Spawn that contains input files as hardlinks to their original
 * destination.
 */
class HardlinkedSandboxedSpawn(
    sandboxPath: com.google.devtools.build.lib.vfs.Path?,
    sandboxExecRoot: com.google.devtools.build.lib.vfs.Path?,
    arguments: com.google.common.collect.ImmutableList<String?>?,
    environment: com.google.common.collect.ImmutableMap<String?, String?>?,
    inputs: SandboxInputs?,
    outputs: SandboxOutputs?,
    writableDirs: MutableSet<com.google.devtools.build.lib.vfs.Path?>?,
    treeDeleter: TreeDeleter?,
    sandboxDebugPath: com.google.devtools.build.lib.vfs.Path?,
    statisticsPath: com.google.devtools.build.lib.vfs.Path?,
    sandboxDebug: Boolean,
    interactiveDebugArguments: com.google.common.collect.ImmutableList<String?>?,
    mnemonic: String?
) : AbstractContainerizingSandboxedSpawn(
    sandboxPath,
    sandboxExecRoot,
    arguments,
    environment,
    inputs,
    outputs,
    writableDirs,
    treeDeleter,
    sandboxDebugPath,
    statisticsPath,
    mnemonic
) {
    private var sandboxDebug = false
    private val interactiveDebugArguments: com.google.common.collect.ImmutableList<String?>?

    init {
        this.sandboxDebug = sandboxDebug
        this.interactiveDebugArguments = interactiveDebugArguments
    }

    @Throws(IOException::class)
    override fun copyFile(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        hardLinkRecursive(source, target)
    }

    /**
     * Recursively creates hardlinks for all files in `source` path, in `target` path.
     * Symlinks are resolved. If files is located on another disk, hardlink will fail and a copy will
     * be made instead. Throws IllegalArgumentException if source path is a subdirectory of target
     * path.
     */
    @Throws(IOException::class)
    private fun hardLinkRecursive(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        var source: com.google.devtools.build.lib.vfs.Path = source
        var stat: FileStatus = source.stat(Symlinks.NOFOLLOW)

        if (stat.isSymbolicLink()) {
            source = source.resolveSymbolicLinks()
            stat = source.stat()
        }

        if (stat.isFile()) {
            try {
                source.createHardLink(target)
            } catch (e: IOException) {
                if (sandboxDebug) {
                    logger.atInfo().log(
                        "File %s could not be hardlinked, file will be copied instead.", source
                    )
                }
                com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(source, target)
            }
        } else if (stat.isDirectory()) {
            require(!source.startsWith(target)) { source.toString() + " is a subdirectory of " + target }
            target.createDirectory()
            val entries: MutableCollection<com.google.devtools.build.lib.vfs.Path> = source.getDirectoryEntries()
            for (entry in entries) {
                val toPath: com.google.devtools.build.lib.vfs.Path = target.getChild(entry.getBaseName())
                hardLinkRecursive(entry, toPath)
            }
        }
    }

    val interactiveDebugInstructions: java.util.Optional<String?>
        get() {
            if (interactiveDebugArguments == null) {
                return java.util.Optional.empty<String?>()
            }
            return java.util.Optional.of<String?>(
                "Run this command to start an interactive shell in an identical sandboxed environment:\n"
                        + CommandFailureUtils.describeCommand(
                    CommandDescriptionForm.COMPLETE,  /* prettyPrintArgs= */
                    false,
                    interactiveDebugArguments,
                    getEnvironment(),  /* environmentVariablesToClear= */
                    null,  /* cwd= */
                    sandboxExecRoot.getPathString(),  /* configurationChecksum= */
                    null,  /* executionPlatformLabel= */
                    null,  /* spawnRunner= */
                    null
                )
            )
        }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
