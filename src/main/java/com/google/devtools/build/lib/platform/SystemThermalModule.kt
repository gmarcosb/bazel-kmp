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
class SystemThermalModule : BlazeModule() {
    private var service: PlatformNativeDepsService? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        service = com.google.common.base.Preconditions.checkNotNull<PlatformNativeDepsService>(
            runtime.getBlazeService<PlatformNativeDepsService?>(PlatformNativeDepsService::class.java)
        )
        service.registerThermalJni(IntConsumer { value: Int -> this.thermalCallback(value) })
    }

    @kotlin.jvm.Synchronized
    override fun beforeCommand(env: CommandEnvironment) {
        this.reporter = env.getReporter()
        reportThermalEvent(true, service.thermalLoad())
    }

    @kotlin.jvm.Synchronized
    override fun afterCommand() {
        this.reporter = null
    }

    /**
     * Callback that is called from the native thermal monitoring code. Made @VisibleForTesting
     * because it is expected to be called only from JNI callbacks which are difficult to mock.
     * 
     * @param value - 0-100 where 0 is no thermal issues to 100 which is worst case.
     * 
     * Intermediate values are platform dependent.
     * 
     * For macOS the thermal states map to:
     * 
     *  * 0 - kOSThermalPressureLevelNominal
     *  * 33 - kOSThermalPressureLevelModerate (Expect CPU performance > 50%)
     *  * 50 - kOSThermalPressureLevelHeavy (Expect CPU performance < 50%)
     *  * 90 - kOSThermalPressureLevelTrapping (Expect machine is about to die).
     *  * 100 - kOSThermalPressureLevelSleeping (Machine is going to sleep to lower heat).
     * 
     */
    @kotlin.jvm.Synchronized
    fun thermalCallback(value: Int) {
        reportThermalEvent(false, value)
    }

    private fun macOSThermalDescription(value: Int): String {
        return when (value) {
            0 -> "Nominal"
            33 -> "Moderate"
            50 -> "Heavy"
            90 -> "Trapping"
            100 -> "Sleeping"
            else -> "Unknown"
        }
    }

    @kotlin.jvm.Synchronized
    private fun reportThermalEvent(isInitialValue: Boolean, value: Int) {
        var osDescription = "Unknown"
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN) {
            osDescription = macOSThermalDescription(value)
        }
        val event: SystemThermalEvent = SystemThermalEvent(value, osDescription)
        val logString: String? = event.logString()

        if (value < 0 || value > 100) {
            // values outside this range are not expected.
            logger.atSevere().log("%s", logString)
        } else if (value > 50) {
            // 50 arbitrarily chosen as point where user is likely to be more concerned.
            logger.atWarning().log("%s", logString)
        } else if (!isInitialValue || value != 0) {
            // Don't spam the logs if we have a nominal value at startup.
            logger.atInfo().log("%s", logString)
        }
        if (reporter != null) {
            reporter.post(event)
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
