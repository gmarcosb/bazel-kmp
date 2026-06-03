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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Tests for [RecursivePkgFunction]. Unfortunately, we can't directly test
 * RecursivePkgFunction as it uses PackageValues, and PackageFunction uses legacy stuff that
 * isn't easily mockable. So our testing strategy is to make hacky calls to
 * SequencedSkyframeExecutor.
 * 
 * 
 * Target parsing tests already cover most of the behavior of RecursivePkgFunction, but there
 * are a couple of corner cases we need to test directly.
 */
@RunWith(JUnit4::class)
class RecursivePkgFunctionTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun buildRecursivePkgValue(root: Path?, rootRelativePath: PathFragment?): RecursivePkgValue {
        return buildRecursivePkgValue(
            root,
            rootRelativePath,
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun buildRecursivePkgValue(
        root: Path?,
        rootRelativePath: PathFragment?,
        excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    ): RecursivePkgValue {
        val key: SkyKey = buildRecursivePkgKey(root, rootRelativePath, excludedPaths)
        return getEvaluationResult(key).get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getEvaluationResult(key: SkyKey): EvaluationResult<RecursivePkgValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        val evaluationResult: EvaluationResult<RecursivePkgValue?> =
            skyframeExecutor.getEvaluator()
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        com.google.common.base.Preconditions.checkState(!evaluationResult.hasError())
        return evaluationResult
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartingAtBuildFile() {
        scratch.file("a/b/c/BUILD")
        val value: RecursivePkgValue =
            buildRecursivePkgValue(rootDirectory, PathFragment.create("a/b/c/BUILD"))
        assertThat(value.getPackages().isEmpty()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesUnderMultipleRoots() {
        // PackageLoader doesn't support --package_path.
        initializeSkyframeExecutor( /*doPackageLoadingChecks=*/false)
        skyframeExecutor = getSkyframeExecutor()

        val root1: Path? = rootDirectory.getRelative("root1")
        val root2: Path? = rootDirectory.getRelative("root2")
        scratch.file(root1.toString() + "/WORKSPACE")
        scratch.file(root2.toString() + "/WORKSPACE")
        scratch.file(root1.toString() + "/a/BUILD")
        scratch.file(root2.toString() + "/a/b/BUILD")
        setPackageOptions("--package_path=" + "root1" + ":" + "root2")

        val valueForRoot1: RecursivePkgValue = buildRecursivePkgValue(root1, PathFragment.create("a"))
        val root1Pkg: String? = valueForRoot1.getPackages().getSingleton()
        Truth.assertThat(root1Pkg).isEqualTo("a")

        val valueForRoot2: RecursivePkgValue = buildRecursivePkgValue(root2, PathFragment.create("a"))
        val root2Pkg: String? = valueForRoot2.getPackages().getSingleton()
        Truth.assertThat(root2Pkg).isEqualTo("a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubdirectoryExclusion() {
        // Given a package "a" with two packages below it, "a/b" and "a/c",
        scratch.file("a/BUILD")
        scratch.file("a/b/BUILD")
        scratch.file("a/c/BUILD")

        // When the top package is evaluated for recursive package values, and "a/b" is excluded,
        val excludedPathFragment: PathFragment = PathFragment.create("a/b")
        val key: SkyKey =
            buildRecursivePkgKey(
                rootDirectory,
                PathFragment.create("a"),
                com.google.common.collect.ImmutableSet.of<PathFragment?>(excludedPathFragment)
            )
        val evaluationResult: EvaluationResult<RecursivePkgValue?> = getEvaluationResult(key)
        val value: RecursivePkgValue = evaluationResult.get(key)

        // Then the package corresponding to "a/b" is not present in the result,
        assertThat(value.getPackages().toList()).doesNotContain("a/b")

        // And the "a" package and "a/c" package are.
        com.google.common.truth.Subject.contains("a")
        com.google.common.truth.Subject.contains("a/c")

        // Also, the computation graph does not contain a cached value for "a/b".
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
        Truth.assertThat(
            WalkableGraphUtils.exists(
                buildRecursivePkgKey(
                    rootDirectory,
                    excludedPathFragment,
                    com.google.common.collect.ImmutableSet.of<PathFragment?>()
                ),
                graph
            )
        )
            .isFalse()

        // And the computation graph does contain a cached value for "a/c" with the empty set excluded,
        // because that key was evaluated.
        Truth.assertThat(
            WalkableGraphUtils.exists(
                buildRecursivePkgKey(
                    rootDirectory,
                    PathFragment.create("a/c"),
                    com.google.common.collect.ImmutableSet.of<PathFragment?>()
                ),
                graph
            )
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcludedSubdirectoryGettingPassedDown() {
        // Given a package "a" with two packages below a directory below it, "a/b/c" and "a/b/d",
        scratch.file("a/BUILD")
        scratch.file("a/b/c/BUILD")
        scratch.file("a/b/d/BUILD")

        // When the top package is evaluated for recursive package values, and "a/b/c" is excluded,
        val excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a/b/c"))
        val key: SkyKey = buildRecursivePkgKey(rootDirectory, PathFragment.create("a"), excludedPaths)
        val evaluationResult: EvaluationResult<RecursivePkgValue?> = getEvaluationResult(key)
        val value: RecursivePkgValue = evaluationResult.get(key)

        // Then the package corresponding to the excluded subdirectory is not present in the result,
        assertThat(value.getPackages().toList()).doesNotContain("a/b/c")

        // And the top package and other subsubdirectory package are.
        com.google.common.truth.Subject.contains("a")
        com.google.common.truth.Subject.contains("a/b/d")

        // Also, the computation graph contains a cached value for "a/b" with "a/b/c" excluded, because
        // "a/b/c" does live underneath "a/b".
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
        Truth.assertThat(
            WalkableGraphUtils.exists(
                buildRecursivePkgKey(rootDirectory, PathFragment.create("a/b"), excludedPaths),
                graph
            )
        )
            .isTrue()
    }

    companion object {
        private fun buildRecursivePkgKey(
            root: Path?,
            rootRelativePath: PathFragment?,
            excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
        ): SkyKey {
            val rootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(root), rootRelativePath)
            return RecursivePkgValue.key(
                RepositoryName.MAIN, rootedPath, IgnoredSubdirectories.of(excludedPaths)
            )
        }
    }
}
