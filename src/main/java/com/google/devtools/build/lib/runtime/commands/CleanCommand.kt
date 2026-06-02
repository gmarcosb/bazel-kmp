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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** Implements 'blaze clean'.  */
@Command(
    name = "clean",
    buildPhase = NONE,
    allowResidue = true,
    writeCommandLog = false,
    options = [com.google.devtools.build.lib.runtime.commands.CleanCommand.Options::class],
    help = "resource:clean.txt",
    shortDescription = "Removes output files and optionally stops the server.",
    inheritsOptionsFrom = [BuildCommand::class]
)
class CleanCommand @com.google.common.annotations.VisibleForTesting constructor(os: com.google.devtools.build.lib.util.OS?) :
    BlazeCommand {
    /** An interface for special options for the clean command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "expunge",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
            help = ("If true, clean removes the entire working tree for this %{product} instance, "
                    + "which includes all %{product}-created temporary and build output files, "
                    + "and stops the %{product} server if it is running.")
        )
        abstract val expunge: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "expunge_async",
            defaultValue = "null",
            expansion = ["--expunge", "--async"],
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
            help = ("If specified, clean asynchronously removes the entire working tree for "
                    + "this %{product} instance, which includes all %{product}-created temporary and "
                    + "build output files, and stops the %{product} server if it is running. When "
                    + "this command completes, it will be safe to execute new commands in the same "
                    + "client, even though the deletion may continue in the background.")
        )
        abstract val expungeAsync: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "async",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
            help = ("If true, output cleaning is asynchronous. When this command completes, it will be safe"
                    + " to execute new commands in the same client, even though the deletion may"
                    + " continue in the background.")
        )
        abstract val async: Boolean
    }

    private val os: com.google.devtools.build.lib.util.OS?

    constructor() : this(com.google.devtools.build.lib.util.OS.getCurrent())

    init {
        this.os = os
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        // Assert that there is no residue and warn about Starlark options.
        val residue: MutableList<String?> = options.getResidue()
        if (!residue.isEmpty()) {
            val message = "Unrecognized arguments: " + com.google.common.base.Joiner.on(' ').join(residue)
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.failureDetail(
                createFailureDetail(message, Code.ARGUMENTS_NOT_RECOGNIZED)
            )
        }

        env.getEventBus().post(NoBuildEvent())
        val cleanOptions: Options? =
            options.getOptions<Options?>(com.google.devtools.build.lib.runtime.commands.CleanCommand.Options::class.java)
        val async =
            canUseAsync(cleanOptions!!.async, cleanOptions.expunge, os, env.getReporter())
        env.getEventBus().post(CleanStartingEvent(options))

        try {
            val symlinkPrefix: String? =
                options
                    .getOptions<O?>(BuildRequestOptions::class.java)
                    .getSymlinkPrefix(env.getRuntime().productName)
            return actuallyClean(
                env, env.getOutputBase(), cleanOptions.expunge, async, symlinkPrefix
            )
        } catch (e: CleanException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return BlazeCommandResult.failureDetail(e.failureDetail)
        } catch (e: java.lang.InterruptedException) {
            val message = "clean interrupted"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode(message)
            )
        }
    }

    private class CleanException(detailedCode: FailureDetails.CleanCommand.Code?, e: java.lang.Exception) :
        java.lang.Exception(com.google.common.base.Strings.nullToEmpty(e.message), e) {
        private val detailedCode: FailureDetails.CleanCommand.Code?

        init {
            this.detailedCode = detailedCode
        }

        val failureDetail: FailureDetail
            get() = createFailureDetail(message, detailedCode)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @com.google.common.annotations.VisibleForTesting
        fun canUseAsync(
            async: Boolean,
            expunge: Boolean,
            os: com.google.devtools.build.lib.util.OS?,
            reporter: com.google.devtools.build.lib.events.Reporter
        ): Boolean {
            // TODO(bazel-team): Deactivate expunge_async on Windows or Unknown platforms as support for
            // daemonizing is done in daemonize.c and does not support those platforms.
            var async = async
            val asyncSupportMissing =
                os == com.google.devtools.build.lib.util.OS.WINDOWS || os == com.google.devtools.build.lib.util.OS.UNKNOWN
            if (async && asyncSupportMissing) {
                val fallbackName = if (expunge) "--expunge" else "synchronous clean"
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        null,  /*location*/
                        "--async cannot be used on non-Linux platforms, falling back to " + fallbackName
                    )
                )
                async = false
            }

            val cleanBanner =
                if (async || asyncSupportMissing)
                    "Starting clean."
                else
                    ("Starting clean (this may take a while). "
                            + "Use --async if the clean takes more than several minutes.")
            reporter.handle(com.google.devtools.build.lib.events.Event.info( /* location= */null, cleanBanner))

            return async
        }

        @Throws(
            IOException::class,
            com.google.devtools.build.lib.shell.CommandException::class,
            java.lang.InterruptedException::class
        )
        private fun asyncClean(
            env: CommandEnvironment,
            path: com.google.devtools.build.lib.vfs.Path,
            pathItemName: String?
        ) {
            val tempBaseName =
                path.getBaseName() + "_tmp_" + java.lang.ProcessHandle.current().pid() + "_" + UUID.randomUUID()

            // Keeping tempOutputBase in the same directory ensures it remains in the
            // same file system, and therefore the mv will be atomic and fast.
            val tempPath: com.google.devtools.build.lib.vfs.Path = path.getParentDirectory().getChild(tempBaseName)
            path.renameTo(tempPath)
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        null,
                        pathItemName + " moved to " + tempPath + " for deletion"
                    )
                )

            val command: String? = String.format(
                "/usr/bin/find %s -type d -not -perm -u=rwx -exec /bin/chmod -f u=rwx {} +; /bin/rm"
                        + " -rf %s",
                tempBaseName, tempBaseName
            )
            logger.atInfo().log("Executing daemonic shell command %s", command)

            // Daemonize the shell to ensure that the shell exits even while the "rm
            // -rf" command continues.
            val result: CommandResult =
                CommandBuilder(env.getClientEnv())
                    .addArg(
                        env.getBlazeWorkspace().getBinTools().getEmbeddedPath("daemonize").getPathString()
                    )
                    .addArgs("-l", "/dev/null")
                    .addArgs("-p", "/dev/null")
                    .addArg("--")
                    .addArgs("/bin/sh", "/bin/sh", "-c", command)
                    .setWorkingDir(tempPath.getParentDirectory())
                    .build()
                    .execute()
            logger.atInfo().log("Shell command status: %s", result.terminationStatus)
        }

        @Throws(CleanException::class, java.lang.InterruptedException::class)
        private fun actuallyClean(
            env: CommandEnvironment,
            outputBase: com.google.devtools.build.lib.vfs.Path,
            expunge: Boolean,
            async: Boolean,
            symlinkPrefix: String?
        ): BlazeCommandResult {
            val runtime: BlazeRuntime = env.getRuntime()

            try {
                env.getOutputService().clean()
            } catch (e: ExecException) {
                throw CleanException(Code.OUTPUT_SERVICE_CLEAN_FAILURE, e)
            }

            try {
                env.getBlazeWorkspace().clearCaches()
            } catch (e: IOException) {
                throw CleanException(Code.ACTION_CACHE_CLEAN_FAILURE, e)
            }

            com.google.devtools.build.lib.vfs.DigestUtils.clearCache()

            if (expunge && !async) {
                logger.atInfo().log("Expunging...")
                runtime.prepareForAbruptShutdown()
                // Close java.log.
                java.util.logging.LogManager.getLogManager().reset()
                // Close the default stdout/stderr.
                try {
                    if (java.io.FileDescriptor.out.valid()) {
                        FileOutputStream(java.io.FileDescriptor.out).close()
                    }
                    if (java.io.FileDescriptor.err.valid()) {
                        FileOutputStream(java.io.FileDescriptor.err).close()
                    }
                } catch (e: IOException) {
                    throw CleanException(Code.OUT_ERR_CLOSE_FAILURE, e)
                }
                // Close the redirected stdout/stderr.
                java.lang.System.out.close()
                java.lang.System.err.close()
                // Delete the big subdirectories with the important content first--this
                // will take the most time. Then quickly delete the little locks, logs
                // and links right before we exit. Once the lock file is gone there will
                // be a small possibility of a server race if a client is waiting, but
                // all significant files will be gone by then.
                try {
                    outputBase.deleteTreesBelow()
                    outputBase.deleteTree()
                } catch (e: IOException) {
                    throw CleanException(Code.OUTPUT_BASE_DELETE_FAILURE, e)
                }
            } else if (expunge) {
                logger.atInfo().log("Expunging asynchronously...")
                runtime.prepareForAbruptShutdown()
                try {
                    asyncClean(env, outputBase, "Output base")
                } catch (e: IOException) {
                    throw CleanException(Code.OUTPUT_BASE_TEMP_MOVE_FAILURE, e)
                } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                    throw CleanException(Code.ASYNC_OUTPUT_BASE_DELETE_FAILURE, e)
                }
            } else {
                logger.atInfo().log("Output cleaning...")
                env.getBlazeWorkspace().resetEvaluator()
                val execroot: com.google.devtools.build.lib.vfs.Path = outputBase.getRelative("execroot")
                if (execroot.exists()) {
                    logger.atFinest().log("Cleaning %s%s", execroot, if (async) " asynchronously..." else "")
                    if (async) {
                        try {
                            asyncClean(env, execroot, "Output tree")
                        } catch (e: IOException) {
                            throw CleanException(Code.EXECROOT_TEMP_MOVE_FAILURE, e)
                        } catch (e: com.google.devtools.build.lib.shell.CommandException) {
                            throw CleanException(Code.ASYNC_EXECROOT_DELETE_FAILURE, e)
                        }
                    } else {
                        try {
                            execroot.deleteTreesBelow()
                        } catch (e: IOException) {
                            throw CleanException(Code.EXECROOT_DELETE_FAILURE, e)
                        }
                    }
                }
            }
            // remove convenience links
            OutputDirectoryLinksUtils.removeOutputDirectoryLinks(
                runtime.getRuleClassProvider().getSymlinkDefinitions(),
                env.getWorkspace(),
                env.getReporter(),
                symlinkPrefix
            )

            // shutdown on expunge cleans
            if (expunge) {
                return BlazeCommandResult.shutdownOnSuccess()
            }
            java.lang.System.gc()
            return BlazeCommandResult.success()
        }

        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setCleanCommand(FailureDetails.CleanCommand.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
