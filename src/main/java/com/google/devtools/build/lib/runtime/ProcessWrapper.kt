// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInput

/** Tracks process-wrapper configuration and allows building command lines that rely on it.  */
class ProcessWrapper @com.google.common.annotations.VisibleForTesting constructor(
    binPath: PathFragment?,
    actionInput: ActionInput?,
    killDelay: java.time.Duration?,
    gracefulSigterm: Boolean
) {
    /** Path to the process-wrapper binary to use.  */
    private val binPath: PathFragment

    /** The process-wrapper binary from [BinTools.getActionInput].  */
    private val actionInput: ActionInput

    /** Grace delay between asking a process to stop and forcibly killing it, or null for none.  */
    private val killDelay: java.time.Duration?

    /** Whether to pass `--graceful_sigterm` or not to the process-wrapper.  */
    private val gracefulSigterm: Boolean

    /** Creates a new process-wrapper instance from explicit values.  */
    init {
        this.binPath = com.google.common.base.Preconditions.checkNotNull<PathFragment>(binPath)
        this.actionInput = com.google.common.base.Preconditions.checkNotNull<ActionInput>(actionInput)
        this.killDelay = killDelay
        this.gracefulSigterm = gracefulSigterm
    }

    /** Returns a new [CommandLineBuilder] for the process-wrapper tool.  */
    fun commandLineBuilder(commandArguments: MutableList<String?>): CommandLineBuilder {
        return com.google.devtools.build.lib.runtime.ProcessWrapper.CommandLineBuilder(
            binPath,
            commandArguments,
            killDelay,
            gracefulSigterm
        )
    }

    /** Returns an [ActionInput] representation of the process-wrapper tool.  */
    fun asActionInput(): ActionInput {
        return actionInput
    }

    /**
     * A builder class for constructing the full command line to run a command using the
     * process-wrapper tool.
     */
    class CommandLineBuilder private constructor(
        processWrapperPath: PathFragment,
        commandArguments: MutableList<String?>,
        killDelay: java.time.Duration?,
        gracefulSigterm: Boolean
    ) {
        private var processWrapperPath: PathFragment
        private val commandArguments: MutableList<String?>
        private val killDelay: java.time.Duration?
        private var gracefulSigterm: Boolean

        private var stdoutPath: PathFragment? = null
        private var stderrPath: PathFragment? = null
        private var timeout: java.time.Duration? = null
        private var statisticsPath: PathFragment? = null

        init {
            this.processWrapperPath = processWrapperPath
            this.commandArguments = commandArguments
            this.killDelay = killDelay
            this.gracefulSigterm = gracefulSigterm
        }

        /**
         * Overrides the location of the process-wrapper tool in the command line.
         * 
         * 
         * By default, the process-wrapper tool is invoked at its embedded location in the install
         * base. Setting an alternate path may be useful if the command is not being run locally.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun overrideProcessWrapperPath(processWrapperPath: PathFragment): CommandLineBuilder {
            this.processWrapperPath = processWrapperPath
            return this
        }

        /** Sets the path to use for redirecting stdout, if any.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStdoutPath(stdoutPath: PathFragment?): CommandLineBuilder {
            this.stdoutPath = stdoutPath
            return this
        }

        /** Sets the path to use for redirecting stderr, if any.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStderrPath(stderrPath: PathFragment?): CommandLineBuilder {
            this.stderrPath = stderrPath
            return this
        }

        /** Sets the timeout for the command run using the process-wrapper tool.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTimeout(timeout: java.time.Duration?): CommandLineBuilder {
            this.timeout = timeout
            return this
        }

        /** Sets the path for writing execution statistics (e.g. resource usage).  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStatisticsPath(statisticsPath: PathFragment?): CommandLineBuilder {
            this.statisticsPath = statisticsPath
            return this
        }

        /** Incorporates settings from a spawn's execution info.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecutionInfo(executionInfo: MutableMap<String?, String?>): CommandLineBuilder {
            if (executionInfo.containsKey(ExecutionRequirements.GRACEFUL_TERMINATION)) {
                gracefulSigterm = true
            }
            return this
        }

        /** Build the command line to invoke a specific command using the process wrapper tool.  */
        fun build(): com.google.common.collect.ImmutableList<String?> {
            val fullCommandLine: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            fullCommandLine.add(processWrapperPath.getPathString())

            if (timeout != null) {
                fullCommandLine.add("--timeout=" + timeout.toSeconds())
            }
            if (killDelay != null) {
                fullCommandLine.add("--kill_delay=" + killDelay.toSeconds())
            }
            if (stdoutPath != null) {
                fullCommandLine.add("--stdout=" + stdoutPath)
            }
            if (stderrPath != null) {
                fullCommandLine.add("--stderr=" + stderrPath)
            }
            if (statisticsPath != null) {
                fullCommandLine.add("--stats=" + statisticsPath)
            }
            if (gracefulSigterm) {
                fullCommandLine.add("--graceful_sigterm")
            }

            fullCommandLine.addAll(commandArguments)

            return fullCommandLine.build()
        }
    }

    companion object {
        /** Name of the process-wrapper binary, without any path components.  */
        private val BIN_BASENAME = "process-wrapper" + OsUtils.executableExtension()

        /**
         * Constructs a new process-wrapper instance based on the context of an invocation.
         * 
         * @param cmdEnv command environment for this invocation
         * @return a process-wrapper handler, or null if this is not supported in the current system
         */
        fun fromCommandEnvironment(cmdEnv: CommandEnvironment): ProcessWrapper? {
            val options: LocalExecutionOptions? = cmdEnv.getOptions().getOptions(LocalExecutionOptions::class.java)
            val killDelay: java.time.Duration? =
                if (options == null) null else options.getLocalSigkillGraceSecondsDuration()

            val gracefulSigterm = options != null && options.processWrapperGracefulSigterm

            val binTools: BinTools = cmdEnv.getBlazeWorkspace().getBinTools()
            val actionInput: ActionInput? = binTools.getActionInput(BIN_BASENAME)
            if (actionInput != null && com.google.devtools.build.lib.util.OS.isPosixCompatible()) {
                return ProcessWrapper(
                    binTools.getEmbeddedPath(BIN_BASENAME).asFragment(),
                    actionInput,
                    killDelay,
                    gracefulSigterm
                )
            } else {
                return null
            }
        }
    }
}
