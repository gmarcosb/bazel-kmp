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
 * Creates an execRoot for a Spawn that contains input files as copies from their original source.
 */
class CopyingSandboxedSpawn(
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
    successCallback: java.lang.Runnable,
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
    private val successCallback: java.lang.Runnable

    init {
        this.successCallback = successCallback
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun copyOutputs(execRoot: com.google.devtools.build.lib.vfs.Path?) {
        successCallback.run()
        super.copyOutputs(execRoot)
    }

    @Throws(IOException::class)
    override fun copyFile(
        source: com.google.devtools.build.lib.vfs.Path,
        target: com.google.devtools.build.lib.vfs.Path
    ) {
        val stat: FileStatus = source.stat(Symlinks.NOFOLLOW)
        if (stat.isSymbolicLink() || stat.isFile()) {
            com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(source, target)
        } else if (stat.isDirectory()) {
            target.createDirectory()
            com.google.devtools.build.lib.vfs.FileSystemUtils.copyTreesBelow(source, target)
        }
    }
}
