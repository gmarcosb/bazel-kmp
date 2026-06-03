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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests that are common to `py_binary`, `py_test`, and `py_library`.  */
abstract class PyBaseConfiguredTargetTestBase protected constructor(private val ruleName: String?) :
    BuildViewTestCase() {
    protected val bzlLoad: String?

    init {
        bzlLoad = PythonTestUtils.getPyLoad(ruleName)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUpPython() {
        analysisMock.pySupport().setup(mockToolsConfig)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun goodSrcsVersionValue() {
        scratch.file(
            "pkg/BUILD",
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    srcs_version = 'PY3',",
            "    srcs = ['foo.py'])"
        )
        getConfiguredTarget("//pkg:foo")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun producesProvider() {
        scratch.file(
            "pkg/BUILD",  //
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    srcs = ['foo.py'])"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//pkg:foo")
        Truth.assertThat(PyInfo.Companion.fromTarget(target)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dataSetsUsesSharedLibrary() {
        scratch.file(
            "pkg/BUILD",
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    srcs = ['foo.py'],",
            "    data = ['lib.so']",
            ")"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//pkg:foo")
        Truth.assertThat(PyInfo.Companion.fromTarget(target).getUsesSharedLibraries()).isTrue()
    }
}
