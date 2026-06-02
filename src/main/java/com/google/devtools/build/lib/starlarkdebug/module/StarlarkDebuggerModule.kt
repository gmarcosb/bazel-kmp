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
package com.google.devtools.build.lib.starlarkdebug.module

import com.google.devtools.build.lib.runtime.BlazeModule

/** Blaze module for setting up Starlark debugging.  */
class StarlarkDebuggerModule : BlazeModule() {
    public override fun beforeCommand(env: CommandEnvironment) {
        // Conditionally enable debugging
        val buildOptions: StarlarkDebuggerOptions? =
            env.getOptions().getOptions(StarlarkDebuggerOptions::class.java)
        val enabled = buildOptions != null && buildOptions.getDebugStarlark()
        if (enabled) {
            initializeDebugging(
                env,
                buildOptions.getDebugServerPort(),
                buildOptions.getVerboseLogs(),
                buildOptions.getResetAnalysis()
            )
        } else {
            disableDebugging()
        }
    }

    public override fun afterCommand() {
        disableDebugging()
    }

    public override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                StarlarkDebuggerOptions::class.java
            )
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    public override fun blazeShutdown() {
        disableDebugging()
    }

    public override fun blazeShutdownOnCrash(exitCode: DetailedExitCode?) {
        disableDebugging()
    }

    companion object {
        private fun initializeDebugging(
            env: CommandEnvironment, debugPort: Int, verboseLogs: Boolean, resetAnalysis: Boolean
        ) {
            try {
                val callback: DebugCallback =
                    if (resetAnalysis) getBreakpointInvalidatingCallback(env) else DebugCallback.Companion.noop()
                val server: StarlarkDebugServer =
                    StarlarkDebugServer.Companion.createAndWaitForConnection(
                        env.getReporter(), debugPort, verboseLogs, callback
                    )
                net.starlark.java.eval.Debug.setDebugger(server)
                // we need to block otherwise the build (i.e. analysis) may start and the request to set
                // breakpoints may lose the race to delete skyframe nodes
                callback.maybeBlockBeforeStart()
            } catch (e: IOException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Error while setting up the debug server: " + e.message))
            } catch (e: java.lang.InterruptedException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Error while setting up the debug server: " + e.message))
            }
        }

        private fun getBreakpointInvalidatingCallback(env: CommandEnvironment): DebugCallback {
            return object : DebugCallback {
                private val latch: CountDownLatch = CountDownLatch(1)

                override fun beforeDebuggingStart(breakPointPaths: com.google.common.collect.ImmutableSet<String?>) {
                    handle(com.google.devtools.build.lib.events.Event.debug("resetting analysis for: " + breakPointPaths))
                    // we delete the FILE nodes for all paths with breakpoints to force re-analysis. Ideally,
                    // we should perhaps invalidate bzl-compile (for .bzl files) and package(??) (for BUILD
                    // files) but computing the right arguments for those skykeys is a lot harder.
                    env.getSkyframeExecutor()
                        .getEvaluator()
                        .delete(
                            { skyKey ->
                                skyKey.functionName() == SkyFunctions.FILE
                                        && breakPointPaths.contains(
                                    (skyKey.argument() as RootedPath).asPath().toString()
                                )
                            })
                    handle(com.google.devtools.build.lib.events.Event.debug("analysis reset complete"))
                    // unblock the build
                    latch.countDown()
                }

                @Throws(java.lang.InterruptedException::class)
                override fun maybeBlockBeforeStart() {
                    handle(com.google.devtools.build.lib.events.Event.debug("waiting for breakpoints before executing build"))
                    latch.await()
                }

                override fun onClose() {
                    latch.countDown()
                }

                fun handle(event: com.google.devtools.build.lib.events.Event?) {
                    env.getReporter().handle(event)
                }
            }
        }

        private fun disableDebugging() {
            net.starlark.java.eval.Debug.setDebugger(null)
        }
    }
}
