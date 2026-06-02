// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** This module logs complete stdout / stderr output of Bazel to a local file.  */
class CommandLogModule : BlazeModule() {
    private var env: CommandEnvironment? = null
    private var logOutputStream: java.io.OutputStream? = null

    public override fun serverInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        builder: ServerBuilder
    ) {
        builder.addInfoItems(CommandLogInfoItem())
    }

    public override fun beforeCommand(env: CommandEnvironment?) {
        this.env = env
    }

    val outputListener: OutErr?
        get() {
            if (!env.getOptions()
                    .getOptions(CommonCommandOptions::class.java)
                    .getRedirectLocalInstrumentationOutputWrites()
            ) {
                // When instrumentation output are locally written, we need to unlink old local command log
                // from previous build, if present.
                val commandLog: com.google.devtools.build.lib.vfs.Path =
                    getCommandLogPath(env.getOutputBase())
                try {
                    commandLog.delete()
                } catch (ioException: IOException) {
                    env.getReporter()
                        .handle(com.google.devtools.build.lib.events.Event.warn("Unable to delete command log: " + ioException.message))
                }
            }

            try {
                val commandOptions: CommonCommandOptions =
                    env.getOptions().getOptions(CommonCommandOptions::class.java)
                if (commandOptions.getWriteCommandLog() && env.getCommandName() != "clean") {
                    val commandLogOutput: InstrumentationOutput =
                        env.getRuntime()
                            .getInstrumentationOutputFactory()
                            .createInstrumentationOutput(
                                "command_log",
                                PathFragment.create("command.log"),
                                DestinationRelativeTo.OUTPUT_BASE,
                                env,
                                env.getReporter(),  /* append= */
                                false,  /* internal= */
                                true
                            )
                    logOutputStream = commandLogOutput.createOutputStream()
                    return OutErr.create(logOutputStream, logOutputStream)
                }
            } catch (ioException: IOException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.warn("Unable to open command log: " + ioException.message))
            }
            return null
        }

    public override fun commandComplete() {
        val localEnv: CommandEnvironment? = this.env
        this.env = null
        if (logOutputStream != null) {
            try {
                logOutputStream.flush()
                logOutputStream.close()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("I/O exception closing log")
                val msg = "I/O exception closing log: " + e.message
                if (localEnv != null) {
                    localEnv.getReporter().handle(com.google.devtools.build.lib.events.Event.error(msg))
                } else {
                    java.lang.System.err.println(msg)
                }
            } finally {
                logOutputStream = null
            }
        }
    }

    /** Info item for the command log  */
    class CommandLogInfoItem : InfoItem(
        "command_log",
        "Location of the log containing the output from the build commands.",
        false
    ) {
        @Throws(AbruptExitException::class)
        public override fun get(
            configurationSupplier: com.google.common.base.Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
        ): ByteArray {
            com.google.common.base.Preconditions.checkNotNull<Any?>(env)
            return print(getCommandLogPath(env.getRuntime().getWorkspace().getOutputBase()))
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** For a given output_base directory, returns the command log file path.  */
        fun getCommandLogPath(outputBase: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            return outputBase.getRelative("command.log")
        }
    }
}
