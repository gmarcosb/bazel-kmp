// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.platform

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Detects cpu speed events.  */
class SystemCPUSpeedModule : BlazeModule() {
    @javax.annotation.concurrent.GuardedBy("this")
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    private var service: PlatformNativeDepsService? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        service = com.google.common.base.Preconditions.checkNotNull<PlatformNativeDepsService>(
            runtime.getBlazeService<PlatformNativeDepsService?>(PlatformNativeDepsService::class.java)
        )
        service.registerCPUSpeedJni(IntConsumer { speed: Int -> this.cpuSpeedCallback(speed) })
    }

    @kotlin.jvm.Synchronized
    override fun beforeCommand(env: CommandEnvironment) {
        this.reporter = env.getReporter()
        val startingSpeed: Int = service.cpuSpeed()
        if (startingSpeed < 100) {
            cpuSpeedCallback(startingSpeed)
        }
    }

    @kotlin.jvm.Synchronized
    override fun afterCommand() {
        this.reporter = null
    }

    @kotlin.jvm.Synchronized
    private fun cpuSpeedCallback(speed: Int) {
        if (speed == -1) {
            // Speeds of -1 imply an error occurred in our speed gathering code.
            // It is expected that lower level code has logged the error, so we are just going to ignore.
            return
        }
        val event: SystemCPUSpeedEvent = SystemCPUSpeedEvent(speed)
        if (reporter != null) {
            reporter.post(event)
        }
        logger.atInfo().log("%s", event.logString())
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
