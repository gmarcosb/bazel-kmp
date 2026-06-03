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

/** Tests [SystemDiskSpaceEvent] by sending fake notifications.  */
@RunWith(JUnit4::class)
class SystemDiskSpaceEventTest : BuildIntegrationTestCase() {
    internal class SystemDiskSpaceEventListener : BlazeModule() {
        var lowDiskSpaceEventCount: Int = 0
        var veryLowDiskSpaceEventCount: Int = 0

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun diskSpaceEvent(event: SystemDiskSpaceEvent) {
            when (event.level()) {
                LOW -> {
                    ++lowDiskSpaceEventCount
                    assertThat(event.logString()).isEqualTo("SystemDiskSpaceEvent: Low")
                }

                VERY_LOW -> {
                    ++veryLowDiskSpaceEventCount
                    assertThat(event.logString()).isEqualTo("SystemDiskSpaceEvent: Very Low")
                }
            }
        }
    }

    private val eventListener = SystemDiskSpaceEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(eventListener)
            .addBlazeModule(SystemDiskSpaceModule())
            .addBlazeService(PlatformNativeDepsServiceImpl())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiskSpace() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        val runfiles: Runfiles = Runfiles.create()
        val notifierFilePath: String? =
            runfiles.rlocation(
                "io_bazel/src/test/java/com/google/devtools/build/lib/platform/darwin/notifier"
            )
        write(
            "system_diskSpace_event/BUILD",
            "genrule(",
            "  name = 'fire_diskSpace_notifications',",
            "  outs = ['fire_diskSpace_notifications.out'],",
            "  cmd = '" + notifierFilePath + " com.google.bazel.test.diskspace.low 0 > $@ && ' + ",
            "        '" + notifierFilePath + " com.google.bazel.test.diskspace.verylow 0 >> $@',",
            ")"
        )
        buildTarget("//system_diskSpace_event:fire_diskSpace_notifications")
        Truth.assertThat(eventListener.lowDiskSpaceEventCount).isGreaterThan(0)
        Truth.assertThat(eventListener.veryLowDiskSpaceEventCount).isGreaterThan(0)
    }
}
