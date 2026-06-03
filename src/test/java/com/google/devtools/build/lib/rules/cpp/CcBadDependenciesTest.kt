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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** A test for dependencies between C++ libraries.  */
@RunWith(JUnit4::class)
class CcBadDependenciesTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun configure(targetLabel: String?): ConfiguredTarget {
        return getConfiguredTarget(targetLabel)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRejectsSingleUnknownSourceFile() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['unknown.oops'])"
        )
        scratch.file("foo/unknown.oops", "foo")
        configure("//foo:foo")
        assertContainsEvent(
            getErrorMsgMisplacedFiles("srcs", "cc_library", "@@//foo:foo", "@@//foo:unknown.oops")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAcceptsDependencyWithAtLeastOneGoodSource() {
        scratch.file(
            "dependency/BUILD",
            """
        genrule(
            name = "goodandbad_gen",
            outs = [
                "good.cc",
                "bad.oops",
            ],
            cmd = "/bin/true",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["//dependency:goodandbad_gen"],
        )
        
        """.trimIndent()
        )
        configure("//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRejectsBadGeneratedFile() {
        setBuildLanguageOptions("--experimental_builtins_injection_override=+cc_library")
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "dependency/BUILD",
            """
        genrule(
            name = "generated",
            outs = ["bad.oops"],
            cmd = "/bin/true",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            srcs = ["//dependency:generated"],
        )
        
        """.trimIndent()
        )
        configure("//foo:foo")
        assertContainsEvent(
            "attribute srcs: '@@//dependency:generated' does not produce any cc_library srcs files"
        )
    }
}
