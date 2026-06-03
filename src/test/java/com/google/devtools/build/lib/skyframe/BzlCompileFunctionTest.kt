// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Unit tests of specific functionality of BzlCompileFunction.  */
@RunWith(JUnit4::class)
class BzlCompileFunctionTest : BuildViewTestCase() {
    private class MockFileSystem : InMemoryFileSystem(DigestHashFunction.SHA256) {
        var throwIOExceptionFor: PathFragment? = null

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
            if (path.equals(throwIOExceptionFor)) {
                throw IOException("bork")
            }
            return super.statIfFound(path, followSymlinks)
        }
    }

    private var mockFS: MockFileSystem? = null

    protected override fun createFileSystem(): FileSystem {
        mockFS = com.google.devtools.build.lib.skyframe.BzlCompileFunctionTest.MockFileSystem()
        return mockFS
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOExceptionOccursDuringReading() {
        reporter.removeHandler(failFastHandler)
        scratch.file("/workspace/tools/test_build_rules/BUILD")
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out.txt"],
            cmd = "echo hello >@",
        )
        
        """.trimIndent()
        )
        mockFS!!.throwIOExceptionFor = PathFragment.create("/workspace/foo/BUILD")
        invalidatePackages( /*alsoConfigs=*/false) // We don't want to fail early on config creation.

        val skyKey: SkyKey? = PackageIdentifier.createInMainRepo("foo")
        val result: EvaluationResult<PackageValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(skyKey)
        val e: Throwable? = errorInfo.getException()
        Truth.assertThat(e).isInstanceOf(NoSuchPackageException::class.java)
        Truth.assertThat(e).hasMessageThat().contains("bork")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadFromFileInRemoteRepo() {
        val repoPath: Path? = scratch.dir("/a_remote_repo")
        scratch.file("/a_remote_repo/REPO.bazel")
        scratch.file("/a_remote_repo/remote_pkg/BUILD")
        scratch.file("/a_remote_repo/remote_pkg/foo.bzl", "load(':bar.bzl', 'CONST')")
        scratch.file("/a_remote_repo/remote_pkg/bar.bzl", "CONST = 17")

        invalidatePackages( /*alsoConfigs=*/false) // Repository shuffling messes with toolchains.
        val skyKey: SkyKey? =
            BzlCompileValue.key(
                Root.fromPath(repoPath),
                Label.parseCanonicalUnchecked("@a_remote_repo//remote_pkg:foo.bzl")
            )
        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        val loads: MutableList<String?>? =
            BzlLoadFunction.getLoadsFromProgram(result.get(skyKey).program).stream()
                .map(Pair::getFirst)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(loads).containsExactly(":bar.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadOfNonexistentFile() {
        val skyKey: SkyKey? = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"))
        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        assertThat(result.get(skyKey).lookupSuccessful()).isFalse()
        com.google.common.truth.Subject.contains("cannot load '//pkg:foo.bzl': no such file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBigIntegerLiterals() {
        // This test ensures that numerical literals with values that can't be expressed as Java longs
        // can be compiled. Regression test for b/217548647.
        val skyKey: SkyKey? = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:bigint.bzl"))
        scratch.file("pkg/BUILD")
        scratch.file(
            "pkg/bigint.bzl",
            String.format(
                "[%s, %s]",
                BigInteger.valueOf(Long.Companion.MIN_VALUE).subtract(BigInteger.ONE),
                BigInteger.valueOf(Long.Companion.MAX_VALUE).add(BigInteger.ONE)
            )
        )

        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /*keepGoing=*/false, reporter
            )
        val bzlCompileValue: BzlCompileValue = result.get(skyKey)
        assertThat(bzlCompileValue.lookupSuccessful()).isTrue()

        Mutability.create().use { mu ->
            val `val`: Any =
                Starlark.execFileProgram(
                    bzlCompileValue.program,
                    net.starlark.java.eval.Module.withPredeclared(
                        StarlarkSemantics.DEFAULT,
                        com.google.common.collect.ImmutableMap.of<String?, Any?>()
                    ),
                    StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                )
            Truth.assertThat(`val`.toString()).isEqualTo("[-9223372036854775809, 9223372036854775808]")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidUtf8_enforcementOff() {
        setBuildLanguageOptions("--noincompatible_enforce_starlark_utf8")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/foo.bzl", byteArrayOf('#'.code.toByte(), ' '.code.toByte(), 0x80.toByte()))

        val skyKey: SkyKey? = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"))
        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        assertThat(result.get(skyKey).lookupSuccessful()).isTrue()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidUtf8_enforcementWarning() {
        setBuildLanguageOptions("--incompatible_enforce_starlark_utf8=warning")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/foo.bzl", byteArrayOf('#'.code.toByte(), ' '.code.toByte(), 0x80.toByte()))

        val skyKey: SkyKey? = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"))
        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        assertThat(result.get(skyKey).lookupSuccessful()).isTrue()
        assertContainsEvent(
            "WARNING /workspace/pkg/foo.bzl: not a valid UTF-8 encoded file; this can lead to"
                    + " inconsistent behavior and will be disallowed in a future version of Bazel"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidUtf8_enforcementError() {
        reporter.removeHandler(failFastHandler)
        setBuildLanguageOptions("--incompatible_enforce_starlark_utf8")

        scratch.file("pkg/BUILD")
        scratch.file("pkg/foo.bzl", byteArrayOf('#'.code.toByte(), ' '.code.toByte(), 0x80.toByte()))

        val skyKey: SkyKey? = BzlCompileValue.key(root, Label.parseCanonicalUnchecked("//pkg:foo.bzl"))
        val result: EvaluationResult<BzlCompileValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), skyKey,  /* keepGoing= */false, reporter
            )
        assertThat(result.get(skyKey).lookupSuccessful()).isFalse()
        assertThat(result.get(skyKey).error)
            .isEqualTo("compilation of '/workspace/pkg/foo.bzl' failed")
        assertContainsEvent(
            "ERROR /workspace/pkg/foo.bzl: not a valid UTF-8 encoded file; this can lead to"
                    + " inconsistent behavior and will be disallowed in a future version of Bazel"
        )
    }
}
