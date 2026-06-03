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

/** Tests for [SystemSuspensionEvent].  */
@RunWith(JUnit4::class)
class SystemSuspensionEventTest : BuildIntegrationTestCase() {
    internal class SystemSuspensionEventListener : BlazeModule() {
        var suspensionEventCount: Int = 0

        public override fun beforeCommand(env: CommandEnvironment) {
            env.getEventBus().register(this)
        }

        @com.google.common.eventbus.Subscribe
        fun suspensionEvent(event: SystemSuspensionEvent) {
            assertThat(event.reason()).isEqualTo(SystemSuspensionEvent.Reason.SIGCONT)
            assertThat(event.logString()).isEqualTo("SystemSuspensionEvent: Signal SIGCONT")
            ++suspensionEventCount
        }
    }

    private val eventListener = SystemSuspensionEventListener()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(eventListener)
            .addBlazeModule(SystemSuspensionModule())
            .addBlazeService(PlatformNativeDepsServiceImpl())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuspendCounter() {
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN)
        // Send a SIGCONT to ourselves which should cause our signal handler to fire.
        write(
            "system_suspension_event/BUILD",
            "genrule(",
            "  name = 'signal',",
            "  outs = ['signal.out'],",
            "  cmd = '/bin/kill -s CONT " + java.lang.ProcessHandle.current().pid() + " > $@',",
            ")"
        )
        buildTarget("//system_suspension_event:signal")
        Truth.assertThat(eventListener.suspensionEventCount).isEqualTo(1)
    }
}
