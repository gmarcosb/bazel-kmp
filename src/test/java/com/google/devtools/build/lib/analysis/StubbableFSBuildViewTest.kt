// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** BuildViewTest where it's possible to stub the FileSystem operations.  */
@RunWith(JUnit4::class)
class StubbableFSBuildViewTest : BuildViewTestBase() {
    override fun createFileSystem(): FileSystem? {
        return StubbableFs(com.google.devtools.build.lib.testutil.ManualClock())
    }

    private val stubbableFS: StubbableFs?
        get() = fileSystem as StubbableFs?

    // Regression test for b/227641207.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastrophicAnalysisErrorAspect_keepGoing_noCrashCatastrophicErrorReported() {
        // We're expecting failures.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pathToBuildB: Path = scratch.file("b/BUILD", "cc_library(name='b')")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs = ['a.cc'], deps = ['//b:b'])"
        )
        scratch.file("a/a.cc", "")
        scratch.file(
            "a/aspect.bzl",
            """
        def _impl(target, ctx):
            print("This aspect does nothing")
            return []

        MyAspect = aspect(implementation = _impl)
        
        """.trimIndent()
        )
        this.stubbableFS!!.stubFastDigestError(pathToBuildB, IOException("testException"))
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)

        val result: AnalysisResult =
            update(
                eventBus,
                defaultFlags().with(com.google.devtools.build.lib.analysis.util.AnalysisTestCase.Flag.KEEP_GOING),
                com.google.common.collect.ImmutableList.of<String?>("a/aspect.bzl%MyAspect"),
                "//a"
            )

        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains("command succeeded, but not all targets were analyzed")
        Truth.assertThat(recorder.events).hasSize(1)
        com.google.common.truth.Subject.contains(
            ("Inconsistent filesystem operations. 'stat' said /workspace/b/BUILD is a file but then"
                    + " we later encountered error 'testException' which indicates that"
                    + " /workspace/b/BUILD is no longer a file.")
        )
    }

    // Regression test for b/227641207.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCatastrophicAnalysisError_keepGoing_noCrashCatastrophicErrorReported() {
        // We're expecting failures.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pathToBuildB: Path = scratch.file("b/BUILD", "cc_library(name='b')")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs = ['a.cc'], deps = ['//b:b'])"
        )
        scratch.file("a/a.cc", "")
        this.stubbableFS!!.stubFastDigestError(pathToBuildB, IOException("testExeception"))
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)

        val result: AnalysisResult = update(
            eventBus,
            defaultFlags().with(com.google.devtools.build.lib.analysis.util.AnalysisTestCase.Flag.KEEP_GOING),
            "//a"
        )

        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains("command succeeded, but not all targets were analyzed")
        Truth.assertThat(recorder.events).hasSize(1)
        com.google.common.truth.Subject.contains(
            ("Inconsistent filesystem operations. 'stat' said /workspace/b/BUILD is a file but then"
                    + " we later encountered error 'testExeception' which indicates that"
                    + " /workspace/b/BUILD is no longer a file.")
        )
    }

    private class StubbableFs(manualClock: com.google.devtools.build.lib.testutil.ManualClock) :
        InMemoryFileSystem(manualClock, DigestHashFunction.SHA256) {
        private val stubbedFastDigestErrors: MutableMap<PathFragment?, IOException?> =
            HashMap<PathFragment?, IOException?>()

        fun stubFastDigestError(path: Path, error: IOException?) {
            stubbedFastDigestErrors.put(path.asFragment(), error)
        }

        @Throws(IOException::class)
        public override fun getFastDigest(path: PathFragment?): ByteArray {
            if (stubbedFastDigestErrors.containsKey(path)) {
                throw stubbedFastDigestErrors.get(path)
            }
            return getDigest(path)
        }
    }
}
