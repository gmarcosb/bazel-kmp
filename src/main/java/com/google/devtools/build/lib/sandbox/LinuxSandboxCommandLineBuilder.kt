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

import com.google.devtools.build.lib.actions.ExecutionRequirements

/**
 * A builder class for constructing the full command line to run a command using the `linux-sandbox` tool.
 */
class LinuxSandboxCommandLineBuilder private constructor(linuxSandboxPath: com.google.devtools.build.lib.vfs.Path) {
    private val linuxSandboxPath: com.google.devtools.build.lib.vfs.Path
    private var hermeticSandboxPath: com.google.devtools.build.lib.vfs.Path? = null
    private var workingDirectory: com.google.devtools.build.lib.vfs.Path? = null
    private var timeout: java.time.Duration? = null
    private var killDelay: java.time.Duration? = null
    private var persistentProcess = false
    private var stdoutPath: com.google.devtools.build.lib.vfs.Path? = null
    private var stderrPath: com.google.devtools.build.lib.vfs.Path? = null
    private var writableFilesAndDirectories: MutableSet<com.google.devtools.build.lib.vfs.Path>? =
        com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>()
    private var tmpfsDirectories: com.google.common.collect.ImmutableSet<PathFragment> =
        com.google.common.collect.ImmutableSet.of<PathFragment?>()
    private var bindMounts: MutableMap<com.google.devtools.build.lib.vfs.Path, com.google.devtools.build.lib.vfs.Path> =
        com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.vfs.Path?, com.google.devtools.build.lib.vfs.Path?>()
    private var statisticsPath: com.google.devtools.build.lib.vfs.Path? = null
    private var useFakeHostname = false
    private var createNetworkNamespace: NetworkNamespace? = NetworkNamespace.NO_NETNS
    private var useFakeRoot = false
    private var useFakeUsername = false
    private var enablePseudoterminal = false
    private var sandboxDebugPath: String? = null
    private var sigintSendsSigterm = false
    private var cgroupsDirs: MutableSet<java.nio.file.Path> =
        com.google.common.collect.ImmutableSet.of<java.nio.file.Path?>()

    init {
        this.linuxSandboxPath = linuxSandboxPath
    }

    /**
     * Sets the sandbox path to chroot to, required for the hermetic linux sandbox to figure out where
     * the working directory is.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setHermeticSandboxPath(sandboxPath: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        this.hermeticSandboxPath = sandboxPath
        return this
    }

    /** Sets the working directory to use, if any.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setWorkingDirectory(workingDirectory: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        this.workingDirectory = workingDirectory
        return this
    }

    /** Sets the timeout for the command run using the `linux-sandbox` tool.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setTimeout(timeout: java.time.Duration?): LinuxSandboxCommandLineBuilder {
        this.timeout = timeout
        return this
    }

    /**
     * Sets the kill delay for commands run using the `linux-sandbox` tool that exceed their
     * timeout.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setKillDelay(killDelay: java.time.Duration?): LinuxSandboxCommandLineBuilder {
        this.killDelay = killDelay
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setPersistentProcess(persistentProcess: Boolean): LinuxSandboxCommandLineBuilder {
        this.persistentProcess = persistentProcess
        return this
    }

    /** Sets the path to use for redirecting stdout, if any.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setStdoutPath(stdoutPath: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        this.stdoutPath = stdoutPath
        return this
    }

    /** Sets the path to use for redirecting stderr, if any.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setStderrPath(stderrPath: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        this.stderrPath = stderrPath
        return this
    }

    /** Sets the files or directories to make writable for the sandboxed process, if any.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setWritableFilesAndDirectories(
        writableFilesAndDirectories: MutableSet<com.google.devtools.build.lib.vfs.Path>?
    ): LinuxSandboxCommandLineBuilder {
        this.writableFilesAndDirectories = writableFilesAndDirectories
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addWritablePath(writablePath: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        if (this.writableFilesAndDirectories == null) {
            this.writableFilesAndDirectories = HashSet<com.google.devtools.build.lib.vfs.Path>()
        }
        this.writableFilesAndDirectories!!.add(writablePath)
        return this
    }

    /** Sets the directories where to mount an empty tmpfs, if any.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setTmpfsDirectories(
        tmpfsDirectories: com.google.common.collect.ImmutableSet<PathFragment>
    ): LinuxSandboxCommandLineBuilder {
        this.tmpfsDirectories = tmpfsDirectories
        return this
    }

    /**
     * Sets the sources and targets of files or directories to explicitly bind-mount in the sandbox,
     * if any.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setBindMounts(bindMounts: MutableMap<com.google.devtools.build.lib.vfs.Path, com.google.devtools.build.lib.vfs.Path>): LinuxSandboxCommandLineBuilder {
        this.bindMounts = bindMounts
        return this
    }

    /** Sets the path for writing execution statistics (e.g. resource usage).  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setStatisticsPath(statisticsPath: com.google.devtools.build.lib.vfs.Path?): LinuxSandboxCommandLineBuilder {
        this.statisticsPath = statisticsPath
        return this
    }

    /** Sets whether to use a fake 'localhost' hostname inside the sandbox.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUseFakeHostname(useFakeHostname: Boolean): LinuxSandboxCommandLineBuilder {
        this.useFakeHostname = useFakeHostname
        return this
    }

    /** Sets whether and how to create a new network namespace.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCreateNetworkNamespace(
        createNetworkNamespace: NetworkNamespace?
    ): LinuxSandboxCommandLineBuilder {
        this.createNetworkNamespace = createNetworkNamespace
        return this
    }

    /** Sets whether to pretend to be 'root' inside the namespace.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUseFakeRoot(useFakeRoot: Boolean): LinuxSandboxCommandLineBuilder {
        this.useFakeRoot = useFakeRoot
        return this
    }

    /** Sets whether to use a fake 'nobody' username inside the sandbox.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUseFakeUsername(useFakeUsername: Boolean): LinuxSandboxCommandLineBuilder {
        this.useFakeUsername = useFakeUsername
        return this
    }

    /**
     * Sets whether to set group to 'tty' and make /dev/pts writable inside the sandbox in order to
     * enable the use of pseudoterminals.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setEnablePseudoterminal(enablePseudoterminal: Boolean): LinuxSandboxCommandLineBuilder {
        this.enablePseudoterminal = enablePseudoterminal
        return this
    }

    /** Sets the output path for sandbox debugging messages.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSandboxDebugPath(sandboxDebugPath: String?): LinuxSandboxCommandLineBuilder {
        this.sandboxDebugPath = sandboxDebugPath
        return this
    }

    /**
     * Sets the directory to be used for cgroups. Cgroups can be used to set limits on resource usage
     * of a subprocess tree, and to gather statistics. Requires cgroups v2 and systemd. This directory
     * must be under `/sys/fs/cgroup` and the user running Bazel must have write permissions to
     * this directory, its parent directory, and the cgroup directory for the Bazel process.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCgroupsDirs(cgroupsDirs: MutableSet<java.nio.file.Path>): LinuxSandboxCommandLineBuilder {
        this.cgroupsDirs = cgroupsDirs
        return this
    }

    /** Incorporates settings from a spawn's execution info.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addExecutionInfo(executionInfo: MutableMap<String?, String?>): LinuxSandboxCommandLineBuilder {
        if (executionInfo.containsKey(ExecutionRequirements.GRACEFUL_TERMINATION)) {
            sigintSendsSigterm = true
        }
        return this
    }

    /** Builds the command line to invoke a specific command using the `linux-sandbox` tool.  */
    fun buildForCommand(commandArguments: MutableList<String?>): com.google.common.collect.ImmutableList<String?> {
        com.google.common.base.Preconditions.checkState(
            !(this.useFakeUsername && this.useFakeRoot),
            "useFakeUsername and useFakeRoot are exclusive"
        )

        val commandLineBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()

        commandLineBuilder.add(linuxSandboxPath.getPathString())
        if (workingDirectory != null) {
            commandLineBuilder.add("-W", workingDirectory.getPathString())
        }
        if (timeout != null) {
            commandLineBuilder.add("-T", timeout.toSeconds().toString())
        }
        if (killDelay != null) {
            commandLineBuilder.add("-t", killDelay.toSeconds().toString())
        }
        if (stdoutPath != null) {
            commandLineBuilder.add("-l", stdoutPath.getPathString())
        }
        if (stderrPath != null) {
            commandLineBuilder.add("-L", stderrPath.getPathString())
        }
        for (writablePath in writableFilesAndDirectories!!) {
            commandLineBuilder.add("-w", writablePath.getPathString())
        }
        for (tmpfsPath in tmpfsDirectories) {
            commandLineBuilder.add("-e", tmpfsPath.getPathString())
        }
        for (bindMountTarget in bindMounts.keys) {
            val bindMountSource: com.google.devtools.build.lib.vfs.Path = bindMounts.get(bindMountTarget)
            commandLineBuilder.add("-M", bindMountSource.getPathString())
            // The file is mounted in a custom location inside the sandbox.
            if (bindMountSource != bindMountTarget) {
                commandLineBuilder.add("-m", bindMountTarget.getPathString())
            }
        }
        if (statisticsPath != null) {
            commandLineBuilder.add("-S", statisticsPath.getPathString())
        }
        if (hermeticSandboxPath != null) {
            commandLineBuilder.add("-h", hermeticSandboxPath.getPathString())
        }
        if (useFakeHostname) {
            commandLineBuilder.add("-H")
        }
        if (createNetworkNamespace == NetworkNamespace.NETNS_WITH_LOOPBACK) {
            commandLineBuilder.add("-N")
        } else if (createNetworkNamespace == NetworkNamespace.NETNS) {
            commandLineBuilder.add("-n")
        }
        if (useFakeRoot) {
            commandLineBuilder.add("-R")
        }
        if (useFakeUsername) {
            commandLineBuilder.add("-U")
        }
        if (enablePseudoterminal) {
            commandLineBuilder.add("-P")
        }
        if (sandboxDebugPath != null) {
            commandLineBuilder.add("-D", sandboxDebugPath)
        }
        if (sigintSendsSigterm) {
            commandLineBuilder.add("-i")
        }
        if (persistentProcess) {
            commandLineBuilder.add("-p")
        }
        for (dir in cgroupsDirs) {
            commandLineBuilder.add("-C", dir.toString())
        }
        commandLineBuilder.add("--")
        commandLineBuilder.addAll(commandArguments)

        return commandLineBuilder.build()
    }

    /** Enum for the possibilities for creating a network namespace in the sandbox.  */
    enum class NetworkNamespace {
        /** No network namespace will be created, sandboxed processes can access the network freely.  */
        NO_NETNS,

        /** A fresh network namespace will be created.  */
        NETNS,

        /** A fresh network namespace will be created, and a loopback device will be set up in it.  */
        NETNS_WITH_LOOPBACK,
    }

    companion object {
        /** Returns a new command line builder for the `linux-sandbox` tool.  */
        fun commandLineBuilder(linuxSandboxPath: com.google.devtools.build.lib.vfs.Path): LinuxSandboxCommandLineBuilder {
            return LinuxSandboxCommandLineBuilder(linuxSandboxPath)
        }
    }
}
