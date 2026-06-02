// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.runtime.ProcessWrapper

internal class DockerCommandLineBuilder {
    private var processWrapper: ProcessWrapper? = null
    private var dockerClient: com.google.devtools.build.lib.vfs.Path? = null
    private var imageName: String? = null
    private var commandArguments: MutableList<String?>? = null
    private var sandboxExecRoot: com.google.devtools.build.lib.vfs.Path? = null
    private var environmentVariables: MutableMap<String?, String?>? = null
    private var timeout: java.time.Duration? = null
    private var createNetworkNamespace = false
    private var uuid: UUID? = null
    private var uid = 0
    private var gid = 0
    private var commandId: String? = null
    private var privileged = false
    private var additionalMounts: MutableList<MutableMap.MutableEntry<String?, String?>>? = null

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setProcessWrapper(processWrapper: ProcessWrapper): DockerCommandLineBuilder {
        this.processWrapper = processWrapper
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setDockerClient(dockerClient: com.google.devtools.build.lib.vfs.Path): DockerCommandLineBuilder {
        this.dockerClient = dockerClient
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setImageName(imageName: String): DockerCommandLineBuilder {
        this.imageName = imageName
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCommandArguments(commandArguments: MutableList<String?>): DockerCommandLineBuilder {
        this.commandArguments = commandArguments
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSandboxExecRoot(sandboxExecRoot: com.google.devtools.build.lib.vfs.Path): DockerCommandLineBuilder {
        this.sandboxExecRoot = sandboxExecRoot
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setEnvironmentVariables(
        environmentVariables: MutableMap<String?, String?>
    ): DockerCommandLineBuilder {
        this.environmentVariables = environmentVariables
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setTimeout(timeout: java.time.Duration?): DockerCommandLineBuilder {
        this.timeout = timeout
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCreateNetworkNamespace(createNetworkNamespace: Boolean): DockerCommandLineBuilder {
        this.createNetworkNamespace = createNetworkNamespace
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUuid(uuid: UUID?): DockerCommandLineBuilder {
        this.uuid = uuid
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUid(uid: Int): DockerCommandLineBuilder {
        this.uid = uid
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setGid(gid: Int): DockerCommandLineBuilder {
        this.gid = gid
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCommandId(commandId: String): DockerCommandLineBuilder {
        this.commandId = commandId
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setPrivileged(privileged: Boolean): DockerCommandLineBuilder {
        this.privileged = privileged
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAdditionalMounts(
        additionalMounts: MutableList<MutableMap.MutableEntry<String?, String?>>
    ): DockerCommandLineBuilder {
        this.additionalMounts = additionalMounts
        return this
    }

    fun build(): com.google.common.collect.ImmutableList<String?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
            sandboxExecRoot,
            "sandboxExecRoot must be set"
        )
        com.google.common.base.Preconditions.checkState(!imageName.isEmpty(), "imageName must be set")
        com.google.common.base.Preconditions.checkState(!commandArguments!!.isEmpty(), "commandArguments must be set")

        val dockerCmdLine: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()

        dockerCmdLine.add(dockerClient.getPathString())
        dockerCmdLine.add("run")
        dockerCmdLine.add("--rm")
        if (createNetworkNamespace) {
            dockerCmdLine.add("--network=none")
        } else {
            dockerCmdLine.add("--network=host")
        }
        if (privileged) {
            dockerCmdLine.add("--privileged")
        }
        environmentVariables.forEach { (k: String?, v: String?) -> dockerCmdLine.add("-e", k + "=" + v) }
        val execRootInsideDocker: PathFragment =
            PathFragment.create("/execroot/").getRelative(sandboxExecRoot.getBaseName())
        dockerCmdLine.add(
            "-v", sandboxExecRoot.getPathString() + ":" + execRootInsideDocker.getPathString()
        )
        dockerCmdLine.add("-w", execRootInsideDocker.getPathString())

        for (additionalMountPath in additionalMounts!!) {
            val mountTarget = additionalMountPath.value
            val mountSource = additionalMountPath.key
            dockerCmdLine.add("-v", mountSource + ":" + mountTarget)
        }

        val uidGidFlagBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
        if (uid != 0) {
            uidGidFlagBuilder.append(uid)
        }
        if (gid != 0) {
            uidGidFlagBuilder.append(":")
            uidGidFlagBuilder.append(gid)
        }
        val uidGidFlag = uidGidFlagBuilder.toString()
        if (!uidGidFlag.isEmpty()) {
            dockerCmdLine.add("-u", uidGidFlagBuilder.toString())
        }

        if (!commandId.isEmpty()) {
            dockerCmdLine.add("-l", "command_id=" + commandId)
        }
        if (uuid != null) {
            dockerCmdLine.add("--name", uuid.toString())
        }
        dockerCmdLine.add(imageName)
        dockerCmdLine.addAll(commandArguments)

        val processWrapperCmdLine: CommandLineBuilder =
            processWrapper.commandLineBuilder(dockerCmdLine.build())
        if (timeout != null) {
            processWrapperCmdLine.setTimeout(timeout)
        }
        return processWrapperCmdLine.build()
    }
}
