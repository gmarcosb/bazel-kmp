// Copyright 2021 The Bazel Authors. All rights reserved.
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

/** Detects suspension events.  */
class SystemDiskSpaceModule : BlazeModule() {
    private var service: PlatformNativeDepsService? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        service = com.google.common.base.Preconditions.checkNotNull<PlatformNativeDepsService?>(
            runtime.getBlazeService<PlatformNativeDepsService?>(PlatformNativeDepsService::class.java)
        )
        service.registerDiskSpaceJni(IntConsumer { value: Int -> this.diskSpaceCallback(value) })
    }

    @kotlin.jvm.Synchronized
    override fun beforeCommand(env: CommandEnvironment) {
        this.reporter = env.getReporter()
    }

    @kotlin.jvm.Synchronized
    override fun afterCommand() {
        this.reporter = null
    }

    @kotlin.jvm.Synchronized
    private fun diskSpaceCallback(value: Int) {
        val event: SystemDiskSpaceEvent = SystemDiskSpaceEvent(value)
        if (reporter != null) {
            reporter.post(event)
        }
        logger.atInfo().log("%s", event.logString())
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
