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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.bazel.Bazel

/** Test for Bazel documentation.  */
@RunWith(JUnit4::class)
class BazelDocumentationTest {
    /**
     * Checks that the user-manual is in sync with the [ ].
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelUserManual() {
        val runfiles: Runfiles = Runfiles.create()
        val documentationFilePath: String =
            runfiles.rlocation("io_bazel/site/en/docs/user-manual.md")
        val documentationFile: java.io.File = java.io.File(documentationFilePath)
        DocumentationTestUtil.validateUserManual(
            Bazel.BAZEL_MODULES,
            BazelServices.BAZEL_SERVICES,
            BazelRuleClassProvider.create(),
            com.google.common.io.Files.asCharSource(documentationFile, java.nio.charset.StandardCharsets.UTF_8).read(),
            com.google.common.collect.ImmutableSet.of<String?>()
        )
    }
}
