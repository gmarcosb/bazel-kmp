// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.python

import com.google.devtools.build.lib.testutil.TestConstants
import org.junit.Assume

/** Helpers for Python tests.  */
object PythonTestUtils {
    /**
     * Skips the test if the product isn't bazel. This is mostly to skip tests for py2 support that
     * the Google implementation would otherwise fail on.
     */
    fun assumeIsBazel() {
        Assume.assumeTrue(TestConstants.PRODUCT_NAME == "bazel") // Google has py2 disabled.
    }

    /**
     * Stub method that is used to annotate that the calling test case assumes the default Python
     * version is PY2.
     * 
     * 
     * Marking test cases that depend on the default Python version helps to diagnose failures. It
     * also helps guard against accidentally making the test spuriously pass, e.g. if the expected
     * value becomes the same as the default value..
     */
    fun assumesDefaultIsPY2() {
        // No-op.
    }

    /** Same as [.assumesDefaultIsPY2], but for PY3.  */
    fun assumesDefaultIsPY3() {
        // No-op.
    }

    fun getPyLoad(symbolName: String): String? {
        if (TestConstants.RULES_PYTHON_PACKAGE_ROOT.isEmpty()) {
            return ""
        }
        val bzlFilename: String?
        when (symbolName) {
            "PyInfo" -> bzlFilename = "py_info.bzl"
            "PyRuntimeInfo" -> bzlFilename = "py_runtime_info.bzl"
            else -> bzlFilename = symbolName + ".bzl"
        }
        return String.format(
            "load('%s/python:%s', '%s')", TestConstants.RULES_PYTHON_PACKAGE_ROOT, bzlFilename, symbolName
        )
    }
}
