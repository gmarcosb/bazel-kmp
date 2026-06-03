// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Regression test for bug #649512: an action that doesn't generate the
 * outputs it's supposed to should cause an error to be reported, but not a
 * crash.
 */
@RunWith(JUnit4::class)
class NoOutputActionTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoOutput() {
        write(
            "nooutput/BUILD",
            """
        genrule(
            name = "nooutput",
            outs = [
                "out1",
                "out2",
            ],
            cmd = "",
        )
        
        """.trimIndent()
        )

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//nooutput") })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "nooutput/BUILD:1:8 Executing genrule //nooutput:nooutput failed: not all outputs were"
                        + " created or valid"
            )
        events.assertContainsError("declared output 'nooutput/out1' was not created by genrule")
        events.assertContainsError("declared output 'nooutput/out2' was not created by genrule")
    }
}
