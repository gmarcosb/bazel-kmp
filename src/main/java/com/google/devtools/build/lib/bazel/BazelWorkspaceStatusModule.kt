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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Provides information about the workspace (e.g. source control context, current machine, current
 * user, etc).
 */
class BazelWorkspaceStatusModule : BlazeModule() {
    internal class BazelWorkspaceStatusAction(
        stableStatus: Artifact,
        volatileStatus: Artifact,
        username: String?,
        hostname: String?
    ) : WorkspaceStatusAction(
        ActionOwner.SYSTEM_ACTION_OWNER,
        NestedSetBuilder.Companion.emptySet<E?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
        com.google.common.collect.ImmutableSet.of<E?>(stableStatus, volatileStatus),
        "workspace status"
    ) {
        private val stableStatus: Artifact
        private val volatileStatus: Artifact
        private val username: String?
        private val hostname: String?

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        private fun getAdditionalWorkspaceStatus(
            options: Options, actionExecutionContext: ActionExecutionContext
        ): String? {
            val getWorkspaceStatusCommand: com.google.devtools.build.lib.shell.Command? =
                actionExecutionContext.getContext(WorkspaceStatusAction.Context::class.java).getCommand()
            try {
                if (getWorkspaceStatusCommand != null) {
                    actionExecutionContext
                        .getEventHandler()
                        .handle(
                            com.google.devtools.build.lib.events.Event.progress(
                                "Getting additional workspace status by running "
                                        + options.workspaceStatusCommand
                            )
                        )
                    val stdoutStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                    try {
                        actionExecutionContext.getFileOutErr().getErrorPath().getOutputStream().use { errStream ->
                            getWorkspaceStatusCommand.execute(stdoutStream, errStream)
                        }
                    } catch (e: IOException) {
                        throw createExecutionException(e, Code.STDERR_IO_EXCEPTION)
                    }
                    return stdoutStream.toString(java.nio.charset.StandardCharsets.UTF_8)
                }
            } catch (e: BadExitStatusException) {
                throw createExecutionException(e, Code.NON_ZERO_EXIT)
            } catch (e: AbnormalTerminationException) {
                throw createExecutionException(e, Code.ABNORMAL_TERMINATION)
            } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                throw createExecutionException(e, Code.EXEC_FAILED)
            }
            return ""
        }

        init {
            this.stableStatus = stableStatus
            this.volatileStatus = volatileStatus
            this.username = username
            this.hostname = hostname
        }

        @Throws(IOException::class)
        public override fun prepare(
            execRoot: com.google.devtools.build.lib.vfs.Path?,
            pathResolver: ArtifactPathResolver?,
            bulkDeleter: BulkDeleter?,
            cleanupArchivedArtifacts: Boolean
        ) {
            // The default implementation of this method deletes all output files; override it to keep
            // the old stableStatus around. This way we can reuse the existing file (preserving its mtime)
            // if the contents haven't changed.
            deleteOutput(volatileStatus, pathResolver)
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            val context: WorkspaceStatusAction.Context =
                actionExecutionContext.getContext(WorkspaceStatusAction.Context::class.java)
            val options: Options = context.options
            val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> = context.clientEnv
            val volatileMap: MutableMap<String?, String?> = TreeMap<String?, String?>()
            val stableMap: MutableMap<String?, String?> = TreeMap<String?, String?>()

            stableMap.put(BuildInfo.BUILD_EMBED_LABEL, options.embedLabel)
            stableMap.put(BuildInfo.BUILD_HOST, hostname)
            stableMap.put(BuildInfo.BUILD_USER, username)
            val currentTimeMillis = getCurrentTimeMillis(clientEnv)
            volatileMap.put(BuildInfo.BUILD_TIMESTAMP, (currentTimeMillis / 1000).toString())
            volatileMap.put("FORMATTED_DATE", format(currentTimeMillis / 1000 * 1000))
            try {
                val statusMap =
                    Companion.parseWorkspaceStatus(getAdditionalWorkspaceStatus(options, actionExecutionContext)!!)
                for (entry in statusMap.entries) {
                    if (Companion.isStableKey(entry.key!!)) {
                        stableMap.put(entry.key, entry.value)
                    } else {
                        volatileMap.put(entry.key, entry.value)
                    }
                }

                val overallMap: MutableMap<String?, String?> = TreeMap<String?, String?>()
                overallMap.putAll(volatileMap)
                overallMap.putAll(stableMap)
                actionExecutionContext.getEventHandler().post(BuildInfoEvent(overallMap))

                // Only update the stableStatus contents if they are different than what we have on disk.
                // This is to preserve the old file's mtime so that we do not generate an unnecessary dirty
                // file on each incremental build.
                com.google.devtools.build.lib.vfs.FileSystemUtils.maybeUpdateContent(
                    actionExecutionContext.getInputPath(stableStatus), printStatusMap(stableMap)
                )

                // Contrary to the stableStatus, write the contents of volatileStatus unconditionally
                // because we know it will be different. This output file is marked as "constant metadata"
                // so its dirtiness will be ignored anyway.
                com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                    actionExecutionContext.getInputPath(volatileStatus), printStatusMap(volatileMap)
                )
            } catch (e: IOException) {
                val message: String? =
                    java.lang.String.format(
                        "Failed to run workspace status command %s: %s",
                        options.workspaceStatusCommand, e.message
                    )
                val code: DetailedExitCode = createDetailedCode(message, Code.CONTENT_UPDATE_IO_EXCEPTION)
                throw ActionExecutionException(message, e, this, true, code)
            }
            return ActionResult.EMPTY
        }

        val mnemonic: String
            get() = "BazelWorkspaceStatusAction"

        public override fun getVolatileStatus(): Artifact {
            return volatileStatus
        }

        public override fun getStableStatus(): Artifact {
            return stableStatus
        }

        companion object {
            private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy MMM dd HH mm ss EEE")

            private fun format(timestamp: Long): String {
                return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).format(TIME_FORMAT)
            }

            private val SPECIAL_STABLE_KEYS: com.google.common.collect.ImmutableSet<String?> =
                com.google.common.collect.ImmutableSet.of<String?>(
                    BuildInfo.BUILD_EMBED_LABEL,
                    BuildInfo.BUILD_HOST,
                    BuildInfo.BUILD_USER
                )

            private fun isStableKey(key: String): Boolean {
                return key.startsWith("STABLE_") || SPECIAL_STABLE_KEYS.contains(key)
            }

            private fun parseWorkspaceStatus(input: String): MutableMap<String?, String?> {
                val result: TreeMap<String?, String?> = TreeMap<String?, String?>()
                for (line in input.trim { it <= ' ' }.split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()) {
                    val splitLine: Array<String?> = line.split(" ".toRegex(), limit = 2).toTypedArray()
                    if (splitLine.size >= 2) {
                        result.put(splitLine[0], splitLine[1].trim { it <= ' ' })
                    }
                }

                return result
            }

            private fun printStatusMap(map: MutableMap<String?, String?>): ByteArray? {
                var s: String? =
                    map.entries.stream()
                        .map<String?> { entry: MutableMap.MutableEntry<String?, String?>? -> entry!!.key + " " + entry.value }
                        .collect(Collectors.joining("\n"))
                s += "\n"
                return s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            }

            /**
             * This method returns the current time for stamping, using SOURCE_DATE_EPOCH
             * (https://reproducible-builds.org/specs/source-date-epoch/) if provided.
             */
            private fun getCurrentTimeMillis(clientEnv: com.google.common.collect.ImmutableMap<String?, String?>): Long {
                if (clientEnv.containsKey("SOURCE_DATE_EPOCH")) {
                    val value: String = clientEnv.get("SOURCE_DATE_EPOCH").trim { it <= ' ' }
                    if (!value.isEmpty()) {
                        try {
                            return value.toLong() * 1000
                        } catch (ex: java.lang.NumberFormatException) {
                            // Fall-back to use the current time if SOURCE_DATE_EPOCH is not a long.
                        }
                    }
                }
                return java.lang.System.currentTimeMillis()
            }
        }
    }

    private class BazelStatusActionFactory : WorkspaceStatusAction.Factory {
        public override fun createDummyWorkspaceStatus(
            workspaceInfoFromDiff: WorkspaceInfoFromDiff?
        ): com.google.common.collect.ImmutableSortedMap<String?, String?> {
            return com.google.common.collect.ImmutableSortedMap.of<String?, String?>()
        }

        public override fun createWorkspaceStatusAction(
            env: WorkspaceStatusAction.Environment
        ): WorkspaceStatusAction {
            val stableArtifact: Artifact = env.createStableArtifact("stable-status.txt")
            val volatileArtifact: Artifact = env.createVolatileArtifact("volatile-status.txt")
            return BazelWorkspaceStatusAction(
                stableArtifact,
                volatileArtifact,
                com.google.common.base.StandardSystemProperty.USER_NAME.value(),
                com.google.devtools.build.lib.util.NetUtil.getCachedShortHostName()
            )
        }
    }

    private class BazelWorkspaceStatusActionContext
        (env: CommandEnvironment) : WorkspaceStatusAction.Context {
        private val env: CommandEnvironment

        init {
            this.env = env
        }

        val options: WorkspaceStatusAction.Options?
            get() = env.getOptions().getOptions<O?>(WorkspaceStatusAction.Options::class.java)

        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
            get() = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(env.getClientEnv())

        val command: com.google.devtools.build.lib.shell.Command?
            get() {
                val options: WorkspaceStatusAction.Options? =
                    env.getOptions().getOptions<O?>(WorkspaceStatusAction.Options::class.java)
                return if (options.workspaceStatusCommand.equals(PathFragment.EMPTY_FRAGMENT))
                    null
                else
                    CommandBuilder(env.getClientEnv())
                        .addArgs(options.workspaceStatusCommand.toString()) // Pass client env to allow SCM clients (like git) relying on environment variables to
                        // work correctly.
                        .setEnv(env.getClientEnv())
                        .setWorkingDir(env.getWorkspace())
                        .useShell(true)
                        .build()
            }
    }

    override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<E?>(WorkspaceStatusAction.Options::class.java)
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    override fun workspaceInit(
        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
    ) {
        builder.setWorkspaceStatusActionFactory(BazelStatusActionFactory())
    }

    override fun registerActionContexts(
        registryBuilder: com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder,
        env: CommandEnvironment,
        buildRequest: BuildRequest?
    ) {
        registryBuilder.register<T?>(
            WorkspaceStatusAction.Context::class.java, BazelWorkspaceStatusActionContext(env)
        )
    }

    companion object {
        private fun createDetailedCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setWorkspaceStatus(WorkspaceStatus.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
