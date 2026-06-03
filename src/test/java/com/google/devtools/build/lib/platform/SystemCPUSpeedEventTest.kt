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

import com.google.devtools.build.lib.runtime.BlazeModule

/** Tests [SystemCPUSpeedEvent] by sending fake notifications.  */
@RunWith(JUnit4::class)
class SystemCPUSpeedEventTest : BuildIntegrationTestCase() {
    internal class SystemCPUSpeedEventListener : BlazeModule() {
        var cpuSpeedEventCount: Int = 0
        var highestSpeed: Int = Int.Companion.MIN_VALUE
        var lowestSpeed: Int = Int.Companion.MAX_VALUE

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun cpuSpeedEvent(event: SystemCPUSpeedEvent) {
            ++cpuSpeedEventCount
            val speed: Int = event.speed()
            if (speed > highestSpeed) {
                highestSpeed = speed
            }
            if (speed < lowestSpeed) {
                lowestSpeed = speed
            }
        }
    }

    private val eventListener = SystemCPUSpeedEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(eventListener)
            .addBlazeModule(SystemCPUSpeedModule())
            .addBlazeService(PlatformNativeDepsServiceImpl())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCPUSpeed() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        val runfiles: Runfiles = Runfiles.create()
        val notifierFilePath: String? =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/platform/darwin/notifier"
            )
        write(
            "system_cpuspeed_event/BUILD",
            "genrule(",
            "  name = 'fire_cpuspeed_notifications',",
            "  outs = ['fire_cpuspeed_notifications.out'],",
            "  cmd = '" + notifierFilePath + " com.google.bazel.test.cpuspeed 80 > $@ && ' + ",
            "        '" + notifierFilePath + " com.google.bazel.test.cpuspeed 60 >> $@ && ' + ",
            "        '" + notifierFilePath + " com.google.bazel.test.cpuspeed 70 >> $@ && ' + ",
            "        '" + notifierFilePath + " com.google.bazel.test.cpuspeed 40 >> $@ && ' + ",
            "        '" + notifierFilePath + " com.google.bazel.test.cpuspeed 50 >> $@',",
            ")"
        )
        buildTarget("//system_cpuspeed_event:fire_cpuspeed_notifications")
        Truth.assertThat(eventListener.cpuSpeedEventCount).isAtLeast(5)
        Truth.assertThat(eventListener.highestSpeed).isAtLeast(80)
        Truth.assertThat(eventListener.lowestSpeed).isAtMost(40)
    }
}
