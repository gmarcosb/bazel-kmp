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

/** Tests [SystemThermalEvent] by sending fake notifications.  */
@RunWith(JUnit4::class)
class SystemThermalEventTest : BuildIntegrationTestCase() {
    internal class SystemThermalEventListener : BlazeModule() {
        var nominalThermalEventCount: Int = 0
        var moderateThermalEventCount: Int = 0
        var heavyThermalEventCount: Int = 0
        var trappingThermalEventCount: Int = 0
        var sleepingThermalEventCount: Int = 0

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun thermalEvent(event: SystemThermalEvent) {
            when (event.value()) {
                0 -> {
                    ++nominalThermalEventCount
                    assertThat(event.logString()).isEqualTo("SystemThermalEvent: 0 (Nominal)")
                }

                33 -> {
                    ++moderateThermalEventCount
                    assertThat(event.logString()).isEqualTo("SystemThermalEvent: 33 (Moderate)")
                }

                50 -> {
                    ++heavyThermalEventCount
                    assertThat(event.logString()).isEqualTo("SystemThermalEvent: 50 (Heavy)")
                }

                90 -> {
                    ++trappingThermalEventCount
                    assertThat(event.logString()).isEqualTo("SystemThermalEvent: 90 (Trapping)")
                }

                100 -> {
                    ++sleepingThermalEventCount
                    assertThat(event.logString()).isEqualTo("SystemThermalEvent: 100 (Sleeping)")
                }

                else -> org.junit.Assert.fail(
                    java.lang.String.format(
                        "Unknown SystemThermalEvent value: %d (%s)", event.value(), event.logString()
                    )
                )
            }
        }
    }

    private val eventListener = SystemThermalEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(eventListener)
            .addBlazeModule(SystemThermalModule())
            .addBlazeService(PlatformNativeDepsServiceImpl())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThermal() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        val runfiles: Runfiles = Runfiles.create()
        val notifierFilePath: String? =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/platform/darwin/notifier"
            )
        write(
            "system_thermal_event/BUILD",
            "genrule(",
            "  name = 'fire_thermal_notifications',",
            "  outs = ['fire_thermal_notifications.out'],",
            ("  cmd = '"
                    + notifierFilePath
                    + " com.google.bazel.test.thermalpressurelevel 0 > $@ && ' + "),
            "'" + notifierFilePath + " com.google.bazel.test.thermalpressurelevel 1 >> $@ && ' + ",
            "'" + notifierFilePath + " com.google.bazel.test.thermalpressurelevel 2 >> $@ && ' + ",
            "'" + notifierFilePath + " com.google.bazel.test.thermalpressurelevel 3 >> $@ && ' + ",
            "'" + notifierFilePath + " com.google.bazel.test.thermalpressurelevel 4 >> $@',",
            ")"
        )
        buildTarget("//system_thermal_event:fire_thermal_notifications")
        Truth.assertThat(eventListener.nominalThermalEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.moderateThermalEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.heavyThermalEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.trappingThermalEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.sleepingThermalEventCount).isGreaterThan(0)
    }
}
