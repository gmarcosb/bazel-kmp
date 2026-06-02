// Copyright 2019 The Bazel Authors. All rights reserved.
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
class SystemSuspensionModule : BlazeModule() {
    private var service: PlatformNativeDepsService? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        service = com.google.common.base.Preconditions.checkNotNull<PlatformNativeDepsService?>(
            runtime.getBlazeService<PlatformNativeDepsService?>(PlatformNativeDepsService::class.java)
        )
        service.registerSuspensionJni(IntConsumer { reason: Int -> this.suspendCallback(reason) })
    }

    @kotlin.jvm.Synchronized
    override fun beforeCommand(env: CommandEnvironment) {
        this.reporter = env.getReporter()
    }

    @kotlin.jvm.Synchronized
    override fun afterCommand() {
        this.reporter = null
    }

    /** Callback method called from JNI whenever a suspension event occurs.  */
    @kotlin.jvm.Synchronized
    fun suspendCallback(reason: Int) {
        val event: SystemSuspensionEvent = SystemSuspensionEvent(reason)
        logger.atInfo().log("%s", event.logString())
        if (reporter != null) {
            reporter.post(event)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
