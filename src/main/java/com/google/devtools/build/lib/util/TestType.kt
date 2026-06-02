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
package com.google.devtools.build.lib.util

/**
 * Utility to detect if in a test. Typically just [.isInTest] can be called to branch on
 * unavoidable test-only behavior (avoiding filesystem access, crashing on errors, etc.).
 * 
 * 
 * Some integration tests may need to distinguish more fully between shell and Java integration
 * tests, and can thread a `TestType` object to the necessary libraries to indicate that.
 */
enum class TestType(private val inTest: Boolean) {
    PRODUCTION(false),
    UNKNOWN_TEST(true),
    JAVA_INTEGRATION(true),
    SHELL_INTEGRATION(true);

    fun inTest(): Boolean {
        return inTest
    }

    companion object {
        val testType: TestType = testTypeFromEnvVars

        private val testTypeFromEnvVars: TestType
            get() {
                val inTest = java.lang.System.getenv("TEST_TMPDIR") != null
                val inShellIntegrationTest = java.lang.System.getenv("BAZEL_SHELL_TEST") != null
                return if (inShellIntegrationTest) TestType.SHELL_INTEGRATION else if (inTest) TestType.UNKNOWN_TEST else TestType.PRODUCTION
            }

        val isInTest: Boolean
            get() = testType.inTest()
    }
}
