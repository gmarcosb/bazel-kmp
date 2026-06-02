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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Creates an execRoot for a Spawn that contains input files as symlinks to their original
 * destination.
 */
open class SymlinkedSandboxedSpawn(
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
    interactiveDebugArguments: com.google.common.collect.ImmutableList<String?>?,
    mnemonic: String?,
    targetLabel: Label?
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
    /** Mnemonic of the action running in this spawn.  */
    private val mnemonic: String

    private val targetLabel: Label?

    private val interactiveDebugArguments: com.google.common.collect.ImmutableList<String?>?

    init {
        this.mnemonic = (if (com.google.common.base.Strings.isNullOrEmpty(mnemonic)) "_NoMnemonic_" else mnemonic)!!
        this.interactiveDebugArguments = interactiveDebugArguments
        this.targetLabel = targetLabel
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    public override fun filterInputsAndDirsToCreate(
        inputsToCreate: MutableSet<PathFragment?>?, dirsToCreate: MutableSet<PathFragment?>?
    ) {
        if (!SandboxStash.Companion.gotInstance()) {
            return
        }
        val sandboxContents: java.util.Optional<SandboxContents?>? =
            SandboxStash.Companion.takeStashedSandbox(
                sandboxPath, mnemonic, getEnvironment(), outputs, targetLabel
            )
        sandboxExecRoot.createDirectoryAndParents()

        if (sandboxContents != null) {
            // Delete anything unnecessary, and update `inputsToCreate`/`dirsToCreate` if something can
            // be left without changes (e.g., a, symlink that already points to the right destination).
            // We're traversing from sandboxExecRoot's parent directory because external repositories can
            // now be symlinked as siblings of sandboxExecRoot when
            // --experimental_sibling_repository_layout is set.
            if (sandboxContents.isPresent()) {
                SandboxHelpers.cleanExisting(
                    sandboxExecRoot.getParentDirectory(),
                    inputs,
                    inputsToCreate,
                    dirsToCreate,
                    sandboxExecRoot,
                    treeDeleter,
                    sandboxContents.get()
                )
            } else {
                // No in-memory stashes enabled but there is a stash.
                // When reusing an old sandbox, we do a full traversal of the parent directory of
                // `sandboxExecRoot`.
                SandboxHelpers.cleanExisting(
                    sandboxExecRoot.getParentDirectory(),
                    inputs,
                    inputsToCreate,
                    dirsToCreate,
                    sandboxExecRoot,
                    treeDeleter
                )
                return
            }
        }

        if (SandboxStash.Companion.useInMemoryStashes()) {
            SandboxStash.Companion.setPathContents(
                sandboxPath, SandboxHelpers.createContentMap(sandboxExecRoot, inputs, outputs)
            )
        }
    }

    @Throws(IOException::class)
    override fun copyFile(
        source: com.google.devtools.build.lib.vfs.Path?,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        target.createSymbolicLink(source)
    }

    override fun delete() {
        SandboxStash.Companion.stashSandbox(
            sandboxPath, mnemonic, getEnvironment(), outputs, treeDeleter, targetLabel
        )
        super.delete()
    }

    val interactiveDebugInstructions: java.util.Optional<String?>?
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
}
