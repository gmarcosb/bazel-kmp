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
package com.google.devtools.build.lib.bazel.rules.java

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Integration tests for [License].  */
@RunWith(JUnit4::class)
class BazelLicensingTests : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testJavaPluginAllowsOutputLicenseDeclaration() {
        scratch.file(
            "ise/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        licenses(["restricted"])

        java_library(
            name = "dependency",
            srcs = ["dependency.java"],
        )

        java_plugin(
            name = "plugin",
            srcs = ["plugin.java"],
            output_licenses = ["unencumbered"],
            deps = [":dependency"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "gsa/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        licenses(["unencumbered"])

        java_library(
            name = "library",
            srcs = ["library.java"],
            plugins = ["//ise:plugin"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//gsa:library")).isNotNull()
        assertNoEvents()
    }
}
