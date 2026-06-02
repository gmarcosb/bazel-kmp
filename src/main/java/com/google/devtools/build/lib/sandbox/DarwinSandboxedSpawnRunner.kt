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

import com.google.devtools.build.lib.actions.Spawn

/** Spawn runner that uses Darwin (macOS) sandboxing to execute a process.  */
internal class DarwinSandboxedSpawnRunner(
    cmdEnv: CommandEnvironment,
    sandboxBase: com.google.devtools.build.lib.vfs.Path,
    treeDeleter: TreeDeleter?
) : AbstractSandboxSpawnRunner(cmdEnv) {
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val allowNetwork: Boolean
    private val processWrapper: ProcessWrapper
    private val sandboxBase: com.google.devtools.build.lib.vfs.Path
    private val treeDeleter: TreeDeleter?

    /**
     * The set of directories that always should be writable, independent of the Spawn itself.
     * 
     * 
     * We cache this, because creating it involves executing `getconf`, which is expensive.
     */
    private val alwaysWritableDirs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>

    private val localEnvProvider: LocalEnvProvider

    /**
     * Creates a sandboxed spawn runner that uses the `process-wrapper` tool and the MacOS
     * `sandbox-exec` binary.
     * 
     * @param cmdEnv the command environment to use
     * @param sandboxBase path to the sandbox base directory
     */
    init {
        this.execRoot = cmdEnv.getExecRoot()
        this.allowNetwork = SandboxHelpers.shouldAllowNetwork(cmdEnv.getOptions())
        this.alwaysWritableDirs = getAlwaysWritableDirs(cmdEnv.getRuntime().getFileSystem())
        this.processWrapper = ProcessWrapper.fromCommandEnvironment(cmdEnv)
        this.localEnvProvider = LocalEnvProvider.forCurrentOs(cmdEnv.getClientEnv())
        this.sandboxBase = sandboxBase
        this.treeDeleter = treeDeleter
    }

    @Throws(IOException::class)
    private fun getAlwaysWritableDirs(fs: com.google.devtools.build.lib.vfs.FileSystem): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        val writableDirs: HashSet<com.google.devtools.build.lib.vfs.Path?> =
            HashSet<com.google.devtools.build.lib.vfs.Path?>()

        addPathToSetIfExists(fs, writableDirs, "/dev")
        addPathToSetIfExists(fs, writableDirs, "/tmp")
        addPathToSetIfExists(fs, writableDirs, "/private/tmp")
        addPathToSetIfExists(fs, writableDirs, "/private/var/tmp")

        // On macOS, processes may write to not only $TMPDIR but also to two other temporary
        // directories. We get their values from from getconf from the client. This comes
        // from the client instead of being computed here because after logging out and back
        // in getconf no longer works when run from a server process from a previous user
        // session. See https://github.com/bazelbuild/bazel/issues/7692.
        addPathToSetIfExists(fs, writableDirs, clientEnv.get("DARWIN_USER_TEMP_DIR"))
        addPathToSetIfExists(fs, writableDirs, clientEnv.get("DARWIN_USER_CACHE_DIR"))

        // We don't add any value for $TMPDIR here, instead we compute its value later in
        // {@link #actuallyExec} and add it as a writable directory in
        // {@link AbstractSandboxSpawnRunner#getWritableDirs}.

        // ~/Library/Caches and ~/Library/Logs need to be writable (cf. issue #2231).
        val homeDir: com.google.devtools.build.lib.vfs.Path =
            fs.getPath(StringEncoding.platformToInternal(java.lang.System.getProperty("user.home")))
        addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Caches"))
        addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Logs"))

        // Certain Xcode tools expect to be able to write to this path.
        addPathToSetIfExists(writableDirs, homeDir.getRelative("Library/Developer"))

        return com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.vfs.Path?>(writableDirs)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepareSpawn(spawn: Spawn, context: SpawnExecutionContext): SandboxedSpawn {
        // Each invocation of "exec" gets its own sandbox base.
        // Note that the value returned by context.getId() is only unique inside one given SpawnRunner,
        // so we have to prefix our name to turn it into a globally unique value.
        val sandboxPath: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(this.name).getRelative(context.id.toString())
        sandboxPath.getParentDirectory().createDirectory()
        sandboxPath.createDirectory()

        // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
        // the normal execroot does.
        val workspaceName: String? = execRoot.getBaseName()
        val sandboxExecRoot: com.google.devtools.build.lib.vfs.Path =
            sandboxPath.getRelative("execroot").getRelative(workspaceName)
        sandboxExecRoot.getParentDirectory().createDirectory()
        sandboxExecRoot.createDirectory()

        val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp")

        val writableDirs: HashSet<com.google.devtools.build.lib.vfs.Path> =
            HashSet<com.google.devtools.build.lib.vfs.Path>(alwaysWritableDirs)
        val extraWritableDirs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>? =
            getWritableDirs(sandboxExecRoot, environment)
        writableDirs.addAll(extraWritableDirs)

        val inputs: SandboxInputs =
            SandboxHelpers.processInputFiles(
                context.getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true),
                execRoot
            )
        val outputs: SandboxOutputs = SandboxHelpers.getOutputs(spawn)

        val sandboxConfigPath: com.google.devtools.build.lib.vfs.Path = sandboxPath.getRelative("sandbox.sb")
        val timeout: java.time.Duration? = context.timeout

        val processWrapperCommandLineBuilder: CommandLineBuilder =
            processWrapper
                .commandLineBuilder(spawn.getArguments())
                .addExecutionInfo(spawn.getExecutionInfo())
                .setTimeout(timeout)

        val statisticsPath: com.google.devtools.build.lib.vfs.Path = sandboxPath.getRelative("stats.out")
        processWrapperCommandLineBuilder.setStatisticsPath(statisticsPath.asFragment())

        val commandLine: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add(sandboxExecBinary)
                .add("-f")
                .add(sandboxConfigPath.getPathString())
                .addAll(processWrapperCommandLineBuilder.build())
                .build()

        val allowNetworkForThisSpawn =
            allowNetwork
                    || Spawns.requiresNetwork(spawn, getSandboxOptions().getDefaultSandboxAllowNetwork())

        return object : SymlinkedSandboxedSpawn(
            sandboxPath,
            sandboxExecRoot,
            commandLine,
            environment,
            inputs,
            outputs,
            writableDirs,
            treeDeleter,  /* sandboxDebugPath= */
            null,
            statisticsPath,  /* interactiveDebugArguments= */
            null,
            spawn.getMnemonic(),
            spawn.getTargetLabel()
        ) {
            @Throws(IOException::class, java.lang.InterruptedException::class)
            override fun createFileSystem() {
                super.createFileSystem()
                writeConfig(
                    sandboxConfigPath,
                    writableDirs,
                    getInaccessiblePaths(),
                    allowNetworkForThisSpawn,
                    statisticsPath
                )
            }
        }
    }

    val name: String
        get() = "darwin-sandbox"

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Path to the `sandbox-exec` system tool to use.  */
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        var sandboxExecBinary: String = "/usr/bin/sandbox-exec"

        // Since checking if sandbox is supported is expensive, we remember what we've checked.
        private var isSupported: Boolean? = null

        /**
         * Returns whether the darwin sandbox is supported on the local machine by running a small command
         * in it.
         */
        @Throws(java.lang.InterruptedException::class)
        fun isSupported(cmdEnv: CommandEnvironment): Boolean {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN) {
                return false
            }
            if (ProcessWrapper.fromCommandEnvironment(cmdEnv) == null) {
                return false
            }
            if (isSupported == null) {
                isSupported = computeIsSupported(cmdEnv.getClientEnv())
            }
            return isSupported!!
        }

        @Throws(java.lang.InterruptedException::class)
        private fun computeIsSupported(clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?): Boolean {
            val args: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>(
                    sandboxExecBinary,
                    "-p",
                    "(version 1) (allow default)",
                    "/usr/bin/true"
                )

            val env: com.google.common.collect.ImmutableMap<String?, String?> =
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            val cwd: java.io.File = java.io.File("/usr/bin")

            val cmd: com.google.devtools.build.lib.shell.Command =
                com.google.devtools.build.lib.shell.Command(args, env, cwd, clientEnv)
            try {
                cmd.execute(
                    com.google.common.io.ByteStreams.nullOutputStream(),
                    com.google.common.io.ByteStreams.nullOutputStream()
                )
            } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                logger.atWarning().withCause(e).log(
                    "Checking for darwin sandbox support failed: %s", e.message
                )
                return false
            }

            return true
        }

        @Throws(IOException::class)
        private fun addPathToSetIfExists(
            fs: com.google.devtools.build.lib.vfs.FileSystem,
            paths: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
            path: String?
        ) {
            if (path != null) {
                addPathToSetIfExists(paths, fs.getPath(path))
            }
        }

        @Throws(IOException::class)
        private fun addPathToSetIfExists(
            paths: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
            path: com.google.devtools.build.lib.vfs.Path
        ) {
            if (path.exists()) {
                paths.add(path.resolveSymbolicLinks())
            }
        }

        @Throws(IOException::class)
        private fun writeConfig(
            sandboxConfigPath: com.google.devtools.build.lib.vfs.Path,
            writableDirs: MutableSet<com.google.devtools.build.lib.vfs.Path>,
            inaccessiblePaths: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
            allowNetwork: Boolean,
            statisticsPath: com.google.devtools.build.lib.vfs.Path?
        ) {
            PrintWriter(
                BufferedWriter(
                    OutputStreamWriter(sandboxConfigPath.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)
                )
            ).use { out ->
                // Note: In Apple's sandbox configuration language, the *last* matching rule wins.
                out.println("(version 1)")
                out.println("(debug deny)")
                out.println("(allow default)")
                out.println("(allow process-exec (with no-sandbox) (literal \"/bin/ps\"))")

                if (!allowNetwork) {
                    out.println("(deny network*)")
                    out.println("(allow network-inbound (local ip \"localhost:*\"))")
                    out.println("(allow network* (remote ip \"localhost:*\"))")
                    out.println("(allow network* (remote unix-socket))")
                }

                // By default, everything is read-only.
                out.println("(deny file-write*)")

                out.println("(allow file-write*")
                for (path in writableDirs) {
                    out.println("    (subpath \"" + path.getPathString() + "\")")
                }
                if (statisticsPath != null) {
                    out.println("    (literal \"" + statisticsPath.getPathString() + "\")")
                }
                out.println(")")
                if (!inaccessiblePaths.isEmpty()) {
                    out.println("(deny file-read*")
                    // The sandbox configuration file is not part of a cache key and sandbox-exec doesn't care
                    // about ordering of paths in expressions, so it's fine if the iteration order is random.
                    for (inaccessiblePath in inaccessiblePaths) {
                        out.println("    (subpath \"" + inaccessiblePath + "\")")
                    }
                    out.println(")")
                }
            }
        }
    }
}
