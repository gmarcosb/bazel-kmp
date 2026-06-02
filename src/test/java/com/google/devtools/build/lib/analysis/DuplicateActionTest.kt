import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**/ Copyright 2015 The Bazel Authors. All rights reserved. */ //
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
package com.google.devtools.build.lib.analysis

/**
 * Tests for duplicate action detection and handling when incremental analysis is enabled.
 */
@RunWith(JUnit4::class)
class DuplicateActionTest : AnalysisTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateBuildInfoHeaderAction() {
        scratch.file(
            "a/stamp.cc",
            "// Empty."
        )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_binary(
            name = "a",
            srcs = ["a.cc"],
            stamp = 1,
            deps = [":c"],
        )

        cc_binary(
            name = "b",
            srcs = ["b.cc"],
            stamp = 1,
            deps = [":c"],
        )

        cc_library(
            name = "c",
            linkstamp = "stamp.cc",
        )
        
        """.trimIndent()
        )
        update("//a:a", "//a:b")
        Truth.assertThat(hasErrors(getConfiguredTarget("//a:a"))).isFalse()
        Truth.assertThat(hasErrors(getConfiguredTarget("//a:b"))).isFalse()
    }
}
