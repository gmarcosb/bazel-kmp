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

import com.google.devtools.build.lib.runtime.BlazeModule

/** Tests [SystemMemoryPressureEvent] by sending fake notifications.  */
@RunWith(JUnit4::class)
class SystemMemoryPressureEventTest : BuildIntegrationTestCase() {
    internal class SystemMemoryPressureEventListener : BlazeModule() {
        var memoryPressureNormalEventCount: Int = 0
        var memoryPressureWarningEventCount: Int = 0
        var memoryPressureCriticalEventCount: Int = 0

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun memoryPressureEvent(event: SystemMemoryPressureEvent) {
            when (event.level()) {
                NORMAL -> {
                    ++memoryPressureNormalEventCount
                    assertThat(event.logString()).isEqualTo("SystemMemoryPressureEvent: Normal")
                }

                WARNING -> {
                    ++memoryPressureWarningEventCount
                    assertThat(event.logString()).isEqualTo("SystemMemoryPressureEvent: Warning")
                }

                CRITICAL -> {
                    ++memoryPressureCriticalEventCount
                    assertThat(event.logString()).isEqualTo("SystemMemoryPressureEvent: Critical")
                }
            }
        }
    }

    private val eventListener = SystemMemoryPressureEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(eventListener)
            .addBlazeModule(SystemMemoryPressureModule())
            .addBlazeService(PlatformNativeDepsServiceImpl())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMemoryPressure() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        val runfiles: Runfiles = Runfiles.create()
        val notifierFilePath: String? =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/platform/darwin/notifier"
            )
        write(
            "system_memory_pressure_event/BUILD",
            "genrule(",
            "  name = 'fire_memory_pressure_notifications',",
            "  outs = ['fire_memory_pressure_notifications.out'],",
            ("  cmd = '"
                    + notifierFilePath
                    + " com.google.bazel.test.memorypressurelevel 0 > $@ && ' + "),
            ("        '"
                    + notifierFilePath
                    + " com.google.bazel.test.memorypressurelevel 1 >> $@ && ' + "),
            "        '" + notifierFilePath + " com.google.bazel.test.memorypressurelevel 2 >> $@',",
            ")"
        )
        buildTarget("//system_memory_pressure_event:fire_memory_pressure_notifications")
        Truth.assertThat(eventListener.memoryPressureNormalEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.memoryPressureWarningEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.memoryPressureCriticalEventCount).isGreaterThan(0)
    }
}
