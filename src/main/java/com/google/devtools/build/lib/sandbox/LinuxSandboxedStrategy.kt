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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.exec.AbstractSpawnStrategy

/** Strategy that uses sandboxing to execute a process.  */
class LinuxSandboxedStrategy internal constructor(spawnRunner: SpawnRunner?, executionOptions: ExecutionOptions?) :
    AbstractSpawnStrategy(spawnRunner, executionOptions) {
    override fun toString(): String {
        return "linux-sandbox"
    }

    companion object {
        /**
         * Creates a sandboxed spawn runner that uses the `linux-sandbox` tool.
         * 
         * @param cmdEnv the command environment to use
         * @param sandboxBase path to the sandbox base directory
         * @param timeoutKillDelay additional grace period before killing timing out commands
         */
        fun create(
            cmdEnv: CommandEnvironment,
            sandboxBase: com.google.devtools.build.lib.vfs.Path,
            timeoutKillDelay: java.time.Duration?,
            treeDeleter: TreeDeleter?,
            options: SandboxOptions
        ): LinuxSandboxedSpawnRunner {
            var inaccessibleHelperFile: com.google.devtools.build.lib.vfs.Path? = null
            var inaccessibleHelperDir: com.google.devtools.build.lib.vfs.Path? = null
            if (!options.getSandboxBlockPath().isEmpty()) {
                try {
                    inaccessibleHelperFile = LinuxSandboxUtil.getInaccessibleHelperFile(sandboxBase)
                    inaccessibleHelperDir = LinuxSandboxUtil.getInaccessibleHelperDir(sandboxBase)
                } catch (e: IOException) {
                    cmdEnv
                        .getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "Could not block access to: "
                                        + com.google.common.base.Joiner.on(",").join(options.getSandboxBlockPath())
                            )
                        )
                }
            }
            return LinuxSandboxedSpawnRunner(
                cmdEnv,
                sandboxBase,
                inaccessibleHelperFile,
                inaccessibleHelperDir,
                timeoutKillDelay,
                treeDeleter
            )
        }
    }
}
