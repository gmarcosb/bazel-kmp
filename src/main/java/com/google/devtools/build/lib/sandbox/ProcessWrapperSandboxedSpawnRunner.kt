// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Spawn

/** Strategy that uses sandboxing to execute a process.  */
internal class ProcessWrapperSandboxedSpawnRunner(
    cmdEnv: CommandEnvironment,
    sandboxBase: com.google.devtools.build.lib.vfs.Path,
    treeDeleter: TreeDeleter?
) : AbstractSandboxSpawnRunner(cmdEnv) {
    private val processWrapper: ProcessWrapper
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val sandboxBase: com.google.devtools.build.lib.vfs.Path
    private val localEnvProvider: LocalEnvProvider
    private val treeDeleter: TreeDeleter?

    /**
     * Creates a sandboxed spawn runner that uses the `process-wrapper` tool.
     * 
     * @param cmdEnv the command environment to use
     * @param sandboxBase path to the sandbox base directory
     */
    init {
        this.processWrapper = ProcessWrapper.fromCommandEnvironment(cmdEnv)
        this.execRoot = cmdEnv.getExecRoot()
        this.localEnvProvider = LocalEnvProvider.forCurrentOs(cmdEnv.getClientEnv())
        this.sandboxBase = sandboxBase
        this.treeDeleter = treeDeleter
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepareSpawn(spawn: Spawn, context: SpawnExecutionContext): SandboxedSpawn {
        // Each invocation of "exec" gets its own sandbox base.
        // Note that the value returned by context.getId() is only unique inside one given SpawnRunner,
        // so we have to prefix our name to turn it into a globally unique value.
        val sandboxPath: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(this.name).getRelative(context.id.toString())
        sandboxPath.createDirectoryAndParents()

        // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
        // the normal execroot does.
        val workspaceName: String? = execRoot.getBaseName()
        val sandboxExecRoot: com.google.devtools.build.lib.vfs.Path =
            sandboxPath.getRelative("execroot").getRelative(workspaceName)
        sandboxExecRoot.createDirectoryAndParents()

        val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp")

        val timeout: java.time.Duration? = context.timeout
        val commandLineBuilder: CommandLineBuilder =
            processWrapper
                .commandLineBuilder(spawn.getArguments())
                .addExecutionInfo(spawn.getExecutionInfo())
                .setTimeout(timeout)

        val statisticsPath: com.google.devtools.build.lib.vfs.Path = sandboxPath.getRelative("stats.out")
        commandLineBuilder.setStatisticsPath(statisticsPath.asFragment())

        val inputs: SandboxInputs =
            SandboxHelpers.processInputFiles(
                context.getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true),
                execRoot
            )
        val outputs: SandboxOutputs = SandboxHelpers.getOutputs(spawn)

        return SymlinkedSandboxedSpawn(
            sandboxPath,
            sandboxExecRoot,
            commandLineBuilder.build(),
            environment,
            inputs,
            outputs,
            getWritableDirs(sandboxExecRoot, environment),
            treeDeleter,  /* sandboxDebugPath= */
            null,
            statisticsPath,  /* interactiveDebugArguments= */
            null,
            spawn.getMnemonic(),
            spawn.getTargetLabel()
        )
    }

    val name: String
        get() = "processwrapper-sandbox"

    companion object {
        fun isSupported(cmdEnv: CommandEnvironment?): Boolean {
            return com.google.devtools.build.lib.util.OS.isPosixCompatible() && ProcessWrapper.fromCommandEnvironment(
                cmdEnv
            ) != null
        }
    }
}
