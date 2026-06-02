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

import build.bazel.remote.execution.v2.Platform

/** Spawn runner that uses Docker to execute a local subprocess.  */
internal class DockerSandboxedSpawnRunner(
    cmdEnv: CommandEnvironment,
    dockerClient: com.google.devtools.build.lib.vfs.Path,
    sandboxBase: com.google.devtools.build.lib.vfs.Path,
    defaultImage: String?,
    useCustomizedImages: Boolean,
    treeDeleter: TreeDeleter?
) : AbstractSandboxSpawnRunner(cmdEnv) {
    private val execRoot: com.google.devtools.build.lib.vfs.Path
    private val allowNetwork: Boolean
    private val dockerClient: com.google.devtools.build.lib.vfs.Path
    private val processWrapper: ProcessWrapper?
    private val sandboxBase: com.google.devtools.build.lib.vfs.Path
    private val defaultImage: String?
    private val localEnvProvider: LocalEnvProvider
    private val commandId: String?
    private val reporter: com.google.devtools.build.lib.events.Reporter
    private val useCustomizedImages: Boolean
    private val treeDeleter: TreeDeleter?
    private val uid: Int
    private val gid: Int
    private val containersToCleanup: MutableSet<UUID>?
    private val cmdEnv: CommandEnvironment

    /**
     * Creates a sandboxed spawn runner that uses the `linux-sandbox` tool.
     * 
     * @param cmdEnv the command environment to use
     * @param dockerClient path to the `docker` executable
     * @param sandboxBase path to the sandbox base directory
     * @param defaultImage the Docker image to use if the platform doesn't specify one
     * @param useCustomizedImages whether to use customized images for execution
     * @param treeDeleter scheduler for tree deletions
     */
    init {
        this.execRoot = cmdEnv.getExecRoot()
        this.allowNetwork = SandboxHelpers.shouldAllowNetwork(cmdEnv.getOptions())
        this.dockerClient = dockerClient
        this.processWrapper = ProcessWrapper.fromCommandEnvironment(cmdEnv)
        this.sandboxBase = sandboxBase
        this.defaultImage = defaultImage
        this.localEnvProvider = LocalEnvProvider.forCurrentOs(cmdEnv.getClientEnv())
        this.commandId = cmdEnv.getCommandId().toString()
        this.reporter = cmdEnv.getReporter()
        this.useCustomizedImages = useCustomizedImages
        this.treeDeleter = treeDeleter
        this.cmdEnv = cmdEnv
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX) {
            this.uid = ProcessUtilsService.getService().getuid()
            this.gid = ProcessUtilsService.getService().getgid()
        } else {
            this.uid = -1
            this.gid = -1
        }
        this.containersToCleanup = Collections.synchronizedSet<UUID?>(HashSet<UUID?>())

        cmdEnv.getEventBus().register(this)
    }

    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    override fun prepareSpawn(spawn: Spawn, context: SpawnExecutionContext): SandboxedSpawn {
        // Each invocation of "exec" gets its own sandbox base, execroot and temporary directory.
        val sandboxPath: com.google.devtools.build.lib.vfs.Path =
            sandboxBase.getRelative(this.name).getRelative(context.id.toString())
        sandboxPath.getParentDirectory().createDirectory()
        sandboxPath.createDirectory()

        // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
        // the normal execroot does.
        val sandboxExecRoot: com.google.devtools.build.lib.vfs.Path =
            sandboxPath.getRelative("execroot").getRelative(execRoot.getBaseName())
        sandboxExecRoot.getParentDirectory().createDirectory()
        sandboxExecRoot.createDirectory()

        val environment: com.google.common.collect.ImmutableMap<String?, String?>? =
            localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp")

        val inputs: SandboxInputs =
            SandboxHelpers.processInputFiles(
                context.getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true),
                execRoot
            )
        val outputs: SandboxOutputs = SandboxHelpers.getOutputs(spawn)

        val timeout: java.time.Duration = context.timeout

        val uuid: UUID = UUID.randomUUID()

        val baseImageName: String = dockerContainerFromSpawn(spawn).orElse(this.defaultImage)
        if (baseImageName.isEmpty()) {
            throw UserExecException(
                SandboxHelpers.createFailureDetail(
                    java.lang.String.format(
                        ("Cannot execute %s mnemonic with Docker, because no image could be found in the"
                                + " exec_properties of the platform and no default image was set via"
                                + " --experimental_docker_image"),
                        spawn.getMnemonic()
                    ),
                    Code.NO_DOCKER_IMAGE
                )
            )
        }

        val customizedImageName = getOrCreateCustomizedImage(baseImageName)

        val cmdLine: DockerCommandLineBuilder = DockerCommandLineBuilder()
        cmdLine
            .setProcessWrapper(processWrapper)
            .setDockerClient(dockerClient)
            .setImageName(customizedImageName)
            .setCommandArguments(spawn.getArguments())
            .setSandboxExecRoot(sandboxExecRoot)
            .setAdditionalMounts(getSandboxOptions().getSandboxAdditionalMounts())
            .setPrivileged(getSandboxOptions().getDockerPrivileged())
            .setEnvironmentVariables(environment)
            .setCreateNetworkNamespace(
                !(allowNetwork
                        || Spawns.requiresNetwork(
                    spawn, getSandboxOptions().getDefaultSandboxAllowNetwork()
                ))
            )
            .setCommandId(commandId)
            .setUuid(uuid)
        // If uid / gid are -1, we are on an operating system that doesn't require us to set them on the
        // Docker invocation. If they're 0, it means we are running as root and don't need to set them.
        if (uid > 0) {
            cmdLine.setUid(uid)
        }
        if (gid > 0) {
            cmdLine.setGid(gid)
        }
        if (!timeout.isZero()) {
            cmdLine.setTimeout(timeout)
        }

        // If we were interrupted, it is possible that "docker run" gets killed in exactly the moment
        // between the create and the start call, leaving behind a container that is created but never
        // ran. This means that Docker won't automatically clean it up (as --rm only affects the start
        // phase and has no effect on the create phase of "docker run").
        // We register the container UUID for cleanup, but remove the UUID if the process ran
        // successfully.
        containersToCleanup!!.add(uuid)
        return CopyingSandboxedSpawn(
            sandboxPath,
            sandboxExecRoot,
            cmdLine.build(),
            cmdEnv.getClientEnv(),
            inputs,
            outputs,
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.vfs.Path?>(),
            treeDeleter,  /* sandboxDebugPath= */
            null,  /* statisticsPath= */
            null,
            java.lang.Runnable { containersToCleanup.remove(uuid) },
            spawn.getMnemonic()
        )
    }

    @Throws(UserExecException::class, java.lang.InterruptedException::class)
    private fun getOrCreateCustomizedImage(baseImage: String?): String? {
        // TODO(philwo) docker run implicitly does a docker pull if the image does not exist locally.
        // Pulling an image can take a long time and a user might not be aware of that. We could check
        // if the image exists locally (docker images -q name:tag) and if not, do a docker pull and
        // notify the user in a similar way as when we download a http_archive.
        //
        // This is mostly relevant for the case where we don't build a customized image, as that prints
        // a message when it runs.

        if (!useCustomizedImages) {
            return baseImage
        }

        // If we're running as root, we can skip this step, as it's safe to assume that every image
        // already has a built-in root user and group.
        if (uid == 0 && gid == 0) {
            return baseImage
        }

        // We only need to create a customized image, if we're running on Linux, as Docker on macOS
        // and Windows doesn't map users from the host into the container anyway.
        if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
            return baseImage
        }

        val thrownUserExecException: AtomicReference<UserExecException?> = AtomicReference<UserExecException?>()
        val thrownInterruptedException: AtomicReference<java.lang.InterruptedException?> =
            AtomicReference<java.lang.InterruptedException?>()
        val result: String? =
            imageMap.computeIfAbsent(
                baseImage
            ) { image: String? ->
                reporter.handle(com.google.devtools.build.lib.events.Event.info("Preparing Docker image " + image + " for use..."))
                val workDir: String? =
                    PathFragment.create("/execroot")
                        .getRelative(execRoot.getBaseName())
                        .getPathString()
                val dockerfile: java.lang.StringBuilder = java.lang.StringBuilder()
                dockerfile.append("ARG image\n")
                dockerfile.append("FROM \$image\n")
                dockerfile.append("ARG work_dir\n")
                dockerfile.append("RUN mkdir --parents \$work_dir\n") // could this be a VOLUME?
                dockerfile.append("ARG group_name\n")
                dockerfile.append("ARG gid\n")
                dockerfile.append("RUN groupadd --non-unique --gid \$gid \$group_name\n")
                dockerfile.append("ARG user_name\n")
                dockerfile.append("ARG uid\n")
                dockerfile.append(
                    ("RUN useradd --non-unique --no-log-init --create-home --gid \$gid --home-dir"
                            + " \$work_dir --no-user-group --uid"
                            + " \$uid"
                            + " \$user_name\n")
                ) // we've already created home above?
                dockerfile.append(
                    "RUN chown --recursive \$uid:\$gid \$work_dir\n"
                ) // if we create home with useradd
                // it'd already have the right
                // ownership
                dockerfile.append("USER \$user_name:\$group_name\n")
                dockerfile.append("ENV HOME \$work_dir\n")
                dockerfile.append("ENV USER \$user_name\n")
                dockerfile.append("WORKDIR \$work_dir\n")
                try {
                    return@computeIfAbsent executeCommand(
                        com.google.common.collect.ImmutableList.of<String?>(
                            dockerClient.getPathString(),
                            "build",
                            "--build-arg",
                            String.format("image=%s", image),
                            "--build-arg",
                            String.format("work_dir=%s", workDir),
                            "--build-arg",
                            String.format("group_name=%s", "bazelbuild"),
                            "--build-arg",
                            String.format("gid=%d", gid),
                            "--build-arg",
                            String.format("user_name=%s", "bazelbuild"),
                            "--build-arg",
                            String.format("uid=%d", uid),
                            "-q",
                            "-"
                        ),
                        ByteArrayInputStream(
                            dockerfile.toString().toByteArray(java.nio.charset.Charset.defaultCharset())
                        )
                    )
                } catch (e: UserExecException) {
                    thrownUserExecException.set(e)
                    return@computeIfAbsent null
                } catch (e: java.lang.InterruptedException) {
                    thrownInterruptedException.set(e)
                    return@computeIfAbsent null
                }
            }
        if (thrownUserExecException.get() != null) {
            throw thrownUserExecException.get()
        }
        if (thrownInterruptedException.get() != null) {
            throw thrownInterruptedException.get()
        }
        return result
    }

    @Throws(UserExecException::class, java.lang.InterruptedException::class)
    private fun executeCommand(
        cmdLine: com.google.common.collect.ImmutableList<String?>?,
        stdIn: java.io.InputStream?
    ): String {
        val stdOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val stdErr: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        // Docker might need the $HOME and $PATH variables in order to be able to use advanced
        // authentication mechanisms (e.g. for Google Cloud), thus we pass in the client env.
        val cmd: com.google.devtools.build.lib.shell.Command =
            com.google.devtools.build.lib.shell.Command(
                cmdLine,
                cmdEnv.getClientEnv(),
                execRoot.getPathFile(),
                cmdEnv.getClientEnv()
            )
        try {
            cmd.executeAsync(
                stdIn,
                stdOut,
                stdErr,
                com.google.devtools.build.lib.shell.Command.Companion.KILL_SUBPROCESS_ON_INTERRUPT
            ).get()
        } catch (e: com.google.devtools.build.lib.shell.CommandException) {
            val message: String? = String.format("Running command %s failed: %s", cmd.toDebugString(), stdErr)
            throw UserExecException(
                e, SandboxHelpers.createFailureDetail(message, Code.DOCKER_COMMAND_FAILURE)
            )
        }
        return stdOut.toString().trim { it <= ' ' }
    }

    @Throws(ExecException::class)
    private fun dockerContainerFromSpawn(spawn: Spawn): java.util.Optional<String>? {
        val platform: Platform? =
            PlatformUtils.getPlatformProto(spawn, cmdEnv.getOptions().getOptions(RemoteOptions::class.java))

        if (platform != null) {
            try {
                return platform.getPropertiesList().stream()
                    .filter({ p -> p.getName().equals(CONTAINER_IMAGE_ENTRY_NAME) })
                    .map({ p -> p.getValue() })
                    .filter({ r -> r.startsWith(DOCKER_IMAGE_PREFIX) })
                    .map({ r -> r.substring(DOCKER_IMAGE_PREFIX.length) })
                    .collect(com.google.common.collect.MoreCollectors.toOptional<T?>())
            } catch (e: java.lang.IllegalArgumentException) {
                throw java.lang.IllegalArgumentException(
                    java.lang.String.format(
                        "Platform %s contained multiple container-image entries, but only one is allowed.",
                        spawn.getExecutionPlatform().label()
                    ),
                    e
                )
            }
        } else {
            return java.util.Optional.empty<String?>()
        }
    }

    // Remove all Docker containers that might be stuck in "Created" state and weren't automatically
    // cleaned up by Docker itself.
    @Throws(java.lang.InterruptedException::class)
    fun cleanup() {
        if (containersToCleanup == null || containersToCleanup.isEmpty()) {
            return
        }

        val cmdLine: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        cmdLine.add(dockerClient.getPathString())
        cmdLine.add("rm")
        cmdLine.add("-fv")
        for (uuid in containersToCleanup) {
            cmdLine.add(uuid.toString())
        }

        val cmd: com.google.devtools.build.lib.shell.Command =
            com.google.devtools.build.lib.shell.Command(
                cmdLine,
                cmdEnv.getClientEnv(),
                execRoot.getPathFile(),
                cmdEnv.getClientEnv()
            )

        try {
            cmd.execute()
        } catch (e: com.google.devtools.build.lib.shell.CommandException) {
            // This is to be expected, as not all UUIDs that we pass to "docker rm" will still be alive
            // when this method is called. However, it will successfully remove all the containers that
            // *are* still there, even when the command exits with an error.
        }

        containersToCleanup.clear()
    }

    @com.google.common.eventbus.Subscribe
    fun commandComplete(@Suppress("unused") event: CommandCompleteEvent?) {
        try {
            cleanup()
        } catch (e: java.lang.InterruptedException) {
            cmdEnv.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.error("Interrupted while cleaning up docker sandbox"))
            java.lang.Thread.currentThread().interrupt()
        }
    }

    val name: String
        get() = "docker"

    companion object {
        // The name of the container image entry in the Platform proto
        // (see third_party/googleapis/devtools/remoteexecution/*/remote_execution.proto and
        // remote_default_exec_properties in
        // src/main/java/com/google/devtools/build/lib/remote/RemoteOptions.java)
        private const val CONTAINER_IMAGE_ENTRY_NAME = "container-image"
        private const val DOCKER_IMAGE_PREFIX = "docker://"

        /**
         * Returns whether the darwin sandbox is supported on the local machine by running docker info.
         * This is expensive, and we have also reports of docker hanging for a long time!
         */
        @Throws(java.lang.InterruptedException::class)
        fun isSupported(cmdEnv: CommandEnvironment, dockerClient: com.google.devtools.build.lib.vfs.Path): Boolean {
            val verbose: Boolean = cmdEnv.getOptions().getOptions(SandboxOptions::class.java).getDockerVerbose()

            if (ProcessWrapper.fromCommandEnvironment(cmdEnv) == null) {
                if (verbose) {
                    cmdEnv
                        .getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.error(
                                "Docker sandboxing is disabled because ProcessWrapper is not supported. "
                                        + "This should never happen - is your Bazel binary corrupted?"
                            )
                        )
                }
                return false
            }

            // On Linux we need to know the UID and GID that we're running as, because otherwise Docker will
            // create files as 'root' and we can't move them to the execRoot.
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX) {
                try {
                    var unused: Int = ProcessUtilsService.getService().getuid()
                    unused = ProcessUtilsService.getService().getgid()
                } catch (e: java.lang.UnsatisfiedLinkError) {
                    if (verbose) {
                        cmdEnv
                            .getReporter()
                            .handle(
                                com.google.devtools.build.lib.events.Event.error(
                                    ("Docker sandboxing is disabled, because"
                                            + " ProcessUtilsService.getService().getuid/getgid threw an"
                                            + " UnsatisfiedLinkError. This means that you're running a Bazel version"
                                            + " that doesn't have JNI libraries - did you build it correctly?\n"
                                            + com.google.common.base.Throwables.getStackTraceAsString(e))
                                )
                            )
                    }
                    return false
                }
            }

            val cmd: com.google.devtools.build.lib.shell.Command =
                com.google.devtools.build.lib.shell.Command(
                    com.google.common.collect.ImmutableList.of<String?>(dockerClient.getPathString(), "info"),
                    cmdEnv.getClientEnv(),
                    cmdEnv.getExecRoot().getPathFile(),
                    cmdEnv.getClientEnv()
                )
            try {
                cmd.execute(
                    com.google.common.io.ByteStreams.nullOutputStream(),
                    com.google.common.io.ByteStreams.nullOutputStream()
                )
            } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                if (verbose) {
                    cmdEnv
                        .getReporter()
                        .handle(
                            com.google.devtools.build.lib.events.Event.error(
                                "Docker sandboxing is disabled, because running 'docker info' failed: "
                                        + com.google.common.base.Throwables.getStackTraceAsString(e)
                            )
                        )
                }
                return false
            }

            if (verbose) {
                cmdEnv.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.info("Docker sandboxing is supported"))
            }

            return true
        }

        private val imageMap: ConcurrentHashMap<String?, String?> = ConcurrentHashMap<String?, String?>()
    }
}
