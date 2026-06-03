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

import com.google.devtools.build.lib.util.OS

/** Tests for [SleepPreventionModule].  */
@RunWith(JUnit4::class)
class PlatformNativeDepsServiceImplTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSleepPrevention() {
        val service: PlatformNativeDepsServiceImpl = PlatformNativeDepsServiceImpl()
        if (haveSleepPreventionSupport()) {
            // Assert standard push pop works.
            assertThat(service.pushDisableSleep()).isEqualTo(0)
            assertThat(service.popDisableSleep()).isEqualTo(0)

            // Assert that nested push pop works, and that re-enabling after disabling (above)
            // works.
            assertThat(service.pushDisableSleep()).isEqualTo(0)
            assertThat(service.pushDisableSleep()).isEqualTo(0)
            assertThat(service.popDisableSleep()).isEqualTo(0)
            assertThat(service.popDisableSleep()).isEqualTo(0)
        } else {
            assertThat(service.pushDisableSleep()).isEqualTo(-1)
            assertThat(service.popDisableSleep()).isEqualTo(-1)
        }
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun haveSleepPreventionSupport(): Boolean {
            when (OS.getCurrent()) {
                DARWIN, WINDOWS -> return true
                LINUX, FREEBSD, OPENBSD, UNKNOWN -> return false
            }
            throw java.lang.AssertionError("switch statement out of sync with OS values")
        }
    }
}
