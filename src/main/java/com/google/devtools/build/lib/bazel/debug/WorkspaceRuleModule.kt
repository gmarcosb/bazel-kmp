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
package com.google.devtools.build.lib.bazel.debug

import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos

/** A module for logging workspace rule events  */
class WorkspaceRuleModule : BlazeModule() {
    private var outFileStream: AsynchronousMessageOutputStream<WorkspaceLogProtos.WorkspaceEvent?>? = null

    override fun beforeCommand(env: CommandEnvironment) {
        val reporter: com.google.devtools.build.lib.events.Reporter = env.getReporter()
        val eventBus: com.google.common.eventbus.EventBus = env.getEventBus()

        if (env.getOptions() == null || env.getOptions()
                .getOptions<DebuggingOptions?>(DebuggingOptions::class.java) == null
        ) {
            reporter.handle(com.google.devtools.build.lib.events.Event.error("Installation is corrupt: could not retrieve debugging options"))
            return
        }

        val logFile: PathFragment? =
            env.getOptions().getOptions<DebuggingOptions?>(DebuggingOptions::class.java).getWorkspaceRulesLogFile()
        if (logFile != null) {
            try {
                outFileStream =
                    AsynchronousMessageOutputStream<WorkspaceLogProtos.WorkspaceEvent?>(
                        env.getWorkingDirectory().getRelative(logFile)
                    )
            } catch (e: IOException) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                env.getBlazeModuleEnvironment()
                    .exit(
                        AbruptExitException(
                            createDetailedExitCode(
                                "Error initializing workspace rule log file.",
                                Code.WORKSPACES_LOG_INITIALIZATION_FAILURE
                            )
                        )
                    )
            }
            eventBus.register(this)
        }
    }

    @Throws(AbruptExitException::class)
    override fun afterCommand() {
        if (outFileStream != null) {
            try {
                // Any AsynchronousMessageOutputStream write failures get rethrown here.
                outFileStream.close()
            } catch (e: IOException) {
                val message: String? =
                    if (e.getMessage() == null) "Error writing workspace rule log file." else e.getMessage()
                throw AbruptExitException(
                    createDetailedExitCode(message, Code.WORKSPACES_LOG_WRITE_FAILURE), e
                )
            } finally {
                outFileStream = null
            }
        }
    }

    override fun getCommonCommandOptions(): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            DebuggingOptions::class.java
        )
    }

    @com.google.common.eventbus.Subscribe
    fun workspaceRuleEventReceived(event: WorkspaceRuleEvent) {
        if (outFileStream != null) {
            outFileStream.write(event.getLogEvent())
        }
    }

    companion object {
        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setWorkspaces(Workspaces.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
