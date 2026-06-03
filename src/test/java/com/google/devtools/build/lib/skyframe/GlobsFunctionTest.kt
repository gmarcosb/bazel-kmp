// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionException

@RunWith(TestParameterInjector::class)
class GlobsFunctionTest : GlobTestBase() {
    override fun createGlobSkyFunction(skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?>) {
        skyFunctions.put(SkyFunctions.GLOBS, GlobsFunction())
    }

    @Throws(java.lang.Exception::class)
    override fun assertSingleGlobMatches(
        pattern: String?, globberOperation: Globber.Operation?, vararg expecteds: String?
    ) {
        val matchesInPathFragment: com.google.common.collect.ImmutableSet<PathFragment?> =
            runSingleGlob(pattern, globberOperation).getMatches()
        Truth.assertThat(
            matchesInPathFragment.stream()
                .map<Any?>(PathFragment::getPathString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        )
            .isEqualTo(com.google.common.collect.ImmutableSet.copyOf<String?>(expecteds))
    }

    @Throws(java.lang.Exception::class)
    override fun runSingleGlob(pattern: String?, globberOperation: Globber.Operation?): GlobsValue {
        val globRequest: GlobRequest = GlobRequest.create(pattern, globberOperation)
        return queryGlobsValue(
            GlobsValue.key(
                GlobTestBase.Companion.PKG_ID,
                Root.fromPath(root),
                com.google.common.collect.ImmutableSet.of<E?>(globRequest)
            )
        )
    }

    @Throws(java.lang.Exception::class)
    private fun queryGlobsValue(globsKey: GlobsValue.Key): GlobsValue {
        val result: EvaluationResult<SkyValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(globsKey),
                GlobTestBase.Companion.EVALUATION_OPTIONS
            )
        if (result.hasError()) {
            throw result.getError().getException()
        }

        val skyValue: SkyValue? = result.get(globsKey)
        assertThat(skyValue).isInstanceOf(GlobsValue::class.java)
        return skyValue as GlobsValue
    }

    override fun assertIllegalPattern(pattern: String?) {
        org.junit.Assert.assertThrows<T?>(
            "invalid pattern not detected: " + pattern,
            InvalidGlobPatternException::class.java,
            org.junit.function.ThrowingRunnable {
                GlobsValue.GlobRequest.create(
                    pattern,
                    Globber.Operation.FILES_AND_DIRS
                )
            })
    }

    @Throws(InvalidGlobPatternException::class)
    override fun createdGlobRelatedSkyKey(
        pattern: String?, globberOperation: Globber.Operation?
    ): GlobsValue.Key {
        return GlobsValue.key(
            GlobTestBase.Companion.PKG_ID,
            Root.fromPath(root),
            com.google.common.collect.ImmutableSet.of<E?>(GlobRequest.create(pattern, globberOperation))
        )
    }

    @Throws(java.lang.Exception::class)
    override fun getSubpackagesMatches(pattern: String?): Iterable<String?> {
        val skyValue: SkyValue = runSingleGlob(pattern, Globber.Operation.SUBPACKAGES)
        assertThat(skyValue).isInstanceOf(GlobsValue::class.java)
        return com.google.common.collect.Iterables.transform<F?, T?>(
            (skyValue as GlobsValue).getMatches(),
            com.google.common.base.Functions.toStringFunction()
        )
    }

    // The test cases below cover scenario when there are multiple GlobRequests defined.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobs_allGlobRequestsAllSucceeds() {
        val globRequest1: GlobRequest = GlobRequest.create("foo/barnacle/**", Operation.FILES_AND_DIRS)
        val globRequest2: GlobRequest? = GlobRequest.create("foo/bar/**", Operation.FILES)
        val globRequest3: GlobRequest? = GlobRequest.create("a2/**", Operation.SUBPACKAGES)

        val globsValue: GlobsValue =
            queryGlobsValue(
                GlobsValue.key(
                    GlobTestBase.Companion.PKG_ID,
                    Root.fromPath(root),
                    com.google.common.collect.ImmutableSet.of<E?>(globRequest1, globRequest2, globRequest3)
                )
            )
        assertThat(
            globsValue.getMatches().stream()
                .map(PathFragment::getPathString)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        )
            .containsExactly("a2/b2", "foo/barnacle", "foo/barnacle/wiz", "foo/bar/wiz/file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobs_oneGoodAndOneGlobUnboundedSymlink() {
        pkgPath.getRelative("parent/sub").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("parent/sub/symlink"), pkgPath.getRelative("parent")
        )

        val unboundedSymlinksGlobRequest: GlobRequest =
            GlobRequest.create("parent/sub/*", Operation.FILES_AND_DIRS)
        val goodGlobRequest: GlobRequest? = GlobRequest.create("foo/bar/**", Operation.FILES)

        val globsKey: GlobsValue.Key =
            GlobsValue.key(
                GlobTestBase.Companion.PKG_ID,
                Root.fromPath(root),
                com.google.common.collect.ImmutableSet.of<E?>(unboundedSymlinksGlobRequest, goodGlobRequest)
            )
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(globsKey),
                GlobTestBase.Companion.EVALUATION_OPTIONS
            )

        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(globsKey)
        assertThat(errorInfo.getException()).isInstanceOf(FileSymlinkInfiniteExpansionException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains("Infinite symlink expansion")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobs_bothTwoGlobBothAreSymlinkCycles() {
        pkgPath.getRelative("parent/sub1").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("parent/sub1/self1"), pkgPath.getRelative("parent")
        )
        val symlinkGlobRequest1: GlobRequest = GlobRequest.create("parent/sub1/*", Operation.FILES_AND_DIRS)

        pkgPath.getRelative("parent/sub2").createDirectoryAndParents()
        FileSystemUtils.ensureSymbolicLink(
            pkgPath.getRelative("parent/sub2/self2"), pkgPath.getRelative("parent")
        )
        val symlinkGlobRequest2: GlobRequest? = GlobRequest.create("parent/sub2/*", Operation.FILES_AND_DIRS)

        val globsKey: GlobsValue.Key =
            GlobsValue.key(
                GlobTestBase.Companion.PKG_ID,
                Root.fromPath(root),
                com.google.common.collect.ImmutableSet.of<E?>(symlinkGlobRequest1, symlinkGlobRequest2)
            )
        val result: EvaluationResult<GlobValue?> =
            evaluator.evaluate(
                com.google.common.collect.ImmutableList.of<E?>(globsKey),
                GlobTestBase.Companion.EVALUATION_OPTIONS
            )

        assertThat(result.hasError()).isTrue()
        val errorInfo: ErrorInfo = result.getError(globsKey)
        assertThat(errorInfo.getException()).isInstanceOf(FileSymlinkInfiniteExpansionException::class.java)
        assertThat(errorInfo.getException()).hasMessageThat().contains("Infinite symlink expansion")

        // The two globs are evaluated in parallel inside GlobsFunction, so it is non-deterministic
        // which SymlinkInfiniteExpansionException is thrown and caught first. So this test only needs
        // to verify the output error is from either one of the SymlinkInfiniteExpansion errors.
        assertThat((errorInfo.getException() as FileSymlinkInfiniteExpansionException).getChain())
            .containsAnyOf(
                RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("parent/sub1/self1")),
                RootedPath.toRootedPath(Root.fromPath(root), pkgPath.getRelative("parent/sub2/self2"))
            )
    }
}
