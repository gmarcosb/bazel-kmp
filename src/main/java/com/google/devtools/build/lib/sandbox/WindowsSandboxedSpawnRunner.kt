// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInput

/** Spawn runner that uses BuildXL Sandbox APIs to execute a local subprocess.  */
internal class WindowsSandboxedSpawnRunner(
    cmdEnv: CommandEnvironment,
    timeoutKillDelay: java.time.Duration?,
    windowsSandboxPath: PathFragment?
) : AbstractSandboxSpawnRunner(cmdEnv) {
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val windowsSandbox: PathFragment?
    private val localEnvProvider: LocalEnvProvider
    private val timeoutKillDelay: java.time.Duration?

    /**
     * Creates a sandboxed spawn runner that uses the `windows-sandbox` tool.
     * 
     * @param cmdEnv the command environment to use
     * @param timeoutKillDelay an additional grace period before killing timing out commands
     * @param windowsSandboxPath path to windows-sandbox binary
     */
    init {
        this.execRoot = cmdEnv.getExecRoot()
        this.windowsSandbox = windowsSandboxPath
        this.timeoutKillDelay = timeoutKillDelay
        this.localEnvProvider = WindowsLocalEnvProvider(cmdEnv.getClientEnv())
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepareSpawn(spawn: Spawn, context: SpawnExecutionContext): SandboxedSpawn {
        val tmpDir: com.google.devtools.build.lib.vfs.Path = createActionTemp(execRoot)
        val commandTmpDir: com.google.devtools.build.lib.vfs.Path = tmpDir.getRelative("work")
        commandTmpDir.createDirectory()
        val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(
                spawn.getEnvironment(), binTools, commandTmpDir.getPathString()
            )

        val readablePaths: SandboxInputs =
            SandboxHelpers.processInputFiles(
                context.getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true),
                execRoot
            )

        val writablePaths: com.google.common.collect.ImmutableSet.Builder<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.vfs.Path?>()
        writablePaths.addAll(getWritableDirs(execRoot, environment))
        for (output in spawn.getOutputFiles()) {
            writablePaths.add(execRoot.getRelative(output.getExecPath()))
        }

        val timeout: java.time.Duration = context.timeout

        if (!readablePaths.getSymlinks().isEmpty()) {
            throw IOException(
                ("Windows sandbox does not support unresolved symlinks yet ("
                        + com.google.common.base.Joiner.on(", ").join(readablePaths.getSymlinks().keys)
                        + ")")
            )
        }

        val commandLineBuilder: CommandLineBuilder =
            WindowsSandboxUtil.commandLineBuilder(windowsSandbox, spawn.getArguments())
                .setWritableFilesAndDirectories(writablePaths.build())
                .setReadableFilesAndDirectories(readablePaths.getFiles())
                .setInaccessiblePaths(getInaccessiblePaths())
                .setUseDebugMode(getSandboxOptions().getSandboxDebug())
                .setKillDelay(timeoutKillDelay)

        if (!timeout.isZero()) {
            commandLineBuilder.setTimeout(timeout)
        }

        return WindowsSandboxedSpawn(
            execRoot, environment, commandLineBuilder.build(), spawn.getMnemonic()
        )
    }

    val name: String
        get() = "windows-sandbox"

    companion object {
        @Throws(IOException::class)
        private fun createActionTemp(execRoot: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            return execRoot.createTempDirectory("windows-sandbox.")
        }
    }
}
