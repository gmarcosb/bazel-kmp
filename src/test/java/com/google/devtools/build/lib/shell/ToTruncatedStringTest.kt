// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.shell

import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests [LogUtil.toTruncatedString].  */ /*
 * Note: The toTruncatedString method uses the platform encoding intentionally,
 * so the unittest does to. Check out the comment in the implementation in
 * case you're wondering why.
 */
@RunWith(JUnit4::class)
class ToTruncatedStringTest {
    @Before
    @Throws(java.lang.Exception::class)
    fun configureLogger() {
        // enable all log statements to ensure there are no problems with
        // logging code
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)
    }

    @org.junit.Test
    fun testTruncatingNullYieldsEmptyString() {
        assertThat(LogUtil.toTruncatedString(null)).isEmpty()
    }

    @org.junit.Test
    fun testTruncatingEmptyArrayYieldsEmptyString() {
        assertThat(LogUtil.toTruncatedString(ByteArray(0))).isEmpty()
    }

    @org.junit.Test
    fun testTruncatingSampleArrayYieldsTruncatedString() {
        val sampleInput = "Well, there could be a lot of output, but we want " +
                "to produce a useful log. A log is useful if it contains the " +
                "interesting information (like what the command was), and maybe " +
                "some of the output. However, too much is too much, so we just " +
                "cut it after 150 bytes ..."
        val expectedOutput = "Well, there could be a lot of output, but we " +
                "want to produce a useful log. A log is useful if it contains " +
                "the interesting information (like what the c[... truncated. " +
                "original size was 261 bytes.]"
        assertThat(LogUtil.toTruncatedString(sampleInput.toByteArray())).isEqualTo(expectedOutput)
    }

    @org.junit.Test
    fun testTruncatingHelloWorldYieldsHelloWorld() {
        val helloWorld = "Hello, world."
        assertThat(LogUtil.toTruncatedString(helloWorld.toByteArray())).isEqualTo(helloWorld)
    }
}
