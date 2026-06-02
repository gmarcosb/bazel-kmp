// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.junit

import com.google.common.truth.Truth
import com.google.devtools.build.lib.blackbox.junit.TimeoutTestWatcherBaseTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Test for [TimeoutTestWatcher]. Tests both that the timeout was intercepted, and the
 * assertion from the test was not blocked.
 */
@RunWith(JUnit4::class)
class TimeoutTestWatcherTest : TimeoutTestWatcherBaseTest() {
    @org.junit.After
    fun tearDown() {
        if ("testTimeoutCaught" == testWatcher.getName()) {
            Truth.assertThat(timeoutCaught).isTrue()
        }
    }

    /** Test that timeout handler is called  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimeoutCaught() {
        for (i in 0..9) {
            java.lang.Thread.sleep(500)
        }
    }

    /** Test that normal test failures are not blocked  */
    @org.junit.Test(expected = java.lang.AssertionError::class)
    fun testFailure() {
        org.junit.Assert.fail()
    }
}
