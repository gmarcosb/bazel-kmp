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

import com.google.devtools.build.lib.cmdline.TargetParsingException

/**
 * Integration test for labels that cross package boundaries.
 */
@RunWith(JUnit4::class)
class LabelCrossesPackageBoundaryTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelCrossesPackageBoundary_target() {
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            srcs = ["//x:y/z"],
            outs = ["out"],
            cmd = "true",
        )
        
        """.trimIndent()
        )
        write(
            "x/y/BUILD",
            "exports_files(['z'])"
        )

        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x") })

        events.assertContainsError("Label '//x:y/z' is invalid because 'x/y' is a subpackage")
    }
}
