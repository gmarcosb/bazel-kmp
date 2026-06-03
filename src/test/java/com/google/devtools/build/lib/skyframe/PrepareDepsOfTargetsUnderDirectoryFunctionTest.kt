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
 * Tests for [PrepareDepsOfTargetsUnderDirectoryFunction]. Insert excuses here.
 */
@RunWith(JUnit4::class)
class PrepareDepsOfTargetsUnderDirectoryFunctionTest : BuildViewTestCase() {
    private val injectedStats: ConcurrentHashMap<PathFragment?, FileStatus?> =
        ConcurrentHashMap<PathFragment?, FileStatus?>()

    protected override fun createFileSystem(): FileSystem? {
        return object : DelegateFileSystem(super.createFileSystem()) {
            @Throws(IOException::class)
            public override fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
                val injectedStat: FileStatus? = injectedStats.remove(path)
                if (injectedStat != null) {
                    return injectedStat
                }
                return super.statIfFound(path, followSymlinks)
            }
        }
    }

    @org.junit.After
    fun checkNoInjectedStatsLeft() {
        Truth.assertThat(injectedStats).isEmpty()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getAndCheckEvaluationResult(
        vararg keys: SkyKey?
    ): EvaluationResult<PrepareDepsOfTargetsUnderDirectoryValue?> {
        val evaluationResult: EvaluationResult<PrepareDepsOfTargetsUnderDirectoryValue?> =
            getEvaluationResult(*keys)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(evaluationResult).hasNoError()
        return evaluationResult
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getEvaluationResult(
        vararg keys: SkyKey?
    ): EvaluationResult<PrepareDepsOfTargetsUnderDirectoryValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        return skyframeExecutor.getEvaluator()
            .evaluate(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (keys), evaluationContext)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLoading() {
        // Given a package "a" with a genrule "a" that depends on a target in package "b",
        createPackages()

        // When package "a" is evaluated,
        val key: SkyKey = createPrepDepsKey(rootDirectory, PathFragment.create("a"))
        val evaluationResult: EvaluationResult<*> = getAndCheckEvaluationResult(key)
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())

        // Then the TransitiveTraversalValue for "@//a:a" is evaluated,
        val aaKey: SkyKey = TransitiveTraversalValue.key(Label.create("@//a", "a"))
        Truth.assertThat(WalkableGraphUtils.exists(aaKey, graph)).isTrue()

        // And that TransitiveTraversalValue depends on "@//b:b.txt".
        val depsOfAa: Iterable<SkyKey?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                graph.getDirectDeps(
                    com.google.common.collect.ImmutableList.of<E?>(
                        aaKey
                    )
                ).values()
            )
        val bTxtKey: SkyKey? = TransitiveTraversalValue.key(Label.create("@//b", "b.txt"))
        Truth.assertThat(depsOfAa).contains(bTxtKey)

        // And the TransitiveTraversalValue for "b:b.txt" is evaluated.
        Truth.assertThat(WalkableGraphUtils.exists(bTxtKey, graph)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetFilterSensitivity() {
        // Given a package "a" with a genrule "a" that depends on a target in package "b", and a test
        // rule "aTest",
        createPackages()

        // When package "a" is evaluated under a test-only filtering policy,
        val key: SkyKey =
            createPrepDepsKey(
                rootDirectory,
                PathFragment.create("a"),
                IgnoredSubdirectories.EMPTY,
                FilteringPolicies.FILTER_TESTS
            )
        val evaluationResult: EvaluationResult<*> = getAndCheckEvaluationResult(key)
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())

        // Then the TransitiveTraversalValue for "@//a:a" is not evaluated,
        val aaKey: SkyKey? = TransitiveTraversalValue.key(Label.create("@//a", "a"))
        Truth.assertThat(WalkableGraphUtils.exists(aaKey, graph)).isFalse()

        // But the TransitiveTraversalValue for "@//a:aTest" is.
        val aaTestKey: SkyKey? = TransitiveTraversalValue.key(Label.create("@//a", "aTest"))
        Truth.assertThat(WalkableGraphUtils.exists(aaTestKey, graph)).isTrue()
    }

    /**
     * Creates a package "a" with a genrule "a" that depends on a target in a created package "b",
     * and a test rule "aTest".
     */
    @Throws(IOException::class)
    private fun createPackages() {
        scratch.file(
            "a/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        genrule(
            name = "a",
            srcs = ["//b:b.txt"],
            outs = ["a.out"],
            cmd = "",
        )

        foo_test(
            name = "aTest",
            size = "small",
            srcs = ["aTest.sh"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "b/BUILD",
            "exports_files(['b.txt'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubdirectoryExclusion() {
        // Given a package "a" with two packages below it, "a/b" and "a/c",
        scratch.file("a/BUILD")
        scratch.file("a/b/BUILD")
        scratch.file("a/c/BUILD")

        // When the top package is evaluated via PrepareDepsOfTargetsUnderDirectoryValue with "a/b"
        // excluded,
        val excludedPathFragment: PathFragment = PathFragment.create("a/b")
        val key: SkyKey = createPrepDepsKey(
            rootDirectory, PathFragment.create("a"),
            com.google.common.collect.ImmutableSet.of<PathFragment?>(excludedPathFragment)
        )
        val collectkey: SkyKey =
            createCollectPackagesKey(
                rootDirectory,
                PathFragment.create("a"),
                com.google.common.collect.ImmutableSet.of<PathFragment?>(excludedPathFragment)
            )
        val evaluationResult: EvaluationResult<*> = getAndCheckEvaluationResult(key, collectkey)
        val value: CollectPackagesUnderDirectoryValue =
            evaluationResult
                .getWalkableGraph()
                .getValue(
                    createCollectPackagesKey(
                        rootDirectory,
                        PathFragment.create("a"),
                        com.google.common.collect.ImmutableSet.of<PathFragment?>(excludedPathFragment)
                    )
                ) as CollectPackagesUnderDirectoryValue

        // Then the value reports that "a" is a package,
        assertThat(value.isDirectoryPackage).isTrue()

        // And only the subdirectory corresponding to "a/c" is present in the result,
        val onlySubdir: RootedPath? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(value.getSubdirectoryTransitivelyContainsPackagesOrErrors())
        assertThat(onlySubdir.getRootRelativePath().getBaseName()).isEqualTo("c")

        // Also, the computation graph does not contain a cached value for "a/b".
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
        Truth.assertThat(
            WalkableGraphUtils.exists(
                createPrepDepsKey(
                    rootDirectory,
                    excludedPathFragment,
                    com.google.common.collect.ImmutableSet.of<PathFragment?>()
                ), graph
            )
        )
            .isFalse()

        // And the computation graph does contain a cached value for "a/c" with the empty set excluded,
        // because that key was evaluated.
        Truth.assertThat(
            WalkableGraphUtils.exists(
                createPrepDepsKey(
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
        // Given a package "a", and a package below it in "a/b/c", and a non-BUILD file below it in
        // "a/b/d",
        scratch.file("a/BUILD")
        scratch.file("a/b/c/BUILD")
        scratch.file("a/b/d/helloworld")

        // When the top package is evaluated for recursive package values, and "a/b/c" is excluded,
        val excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a/b/c"))
        val key: SkyKey = createPrepDepsKey(rootDirectory, PathFragment.create("a"), excludedPaths)
        val collectKey: SkyKey =
            createCollectPackagesKey(rootDirectory, PathFragment.create("a"), excludedPaths)
        val evaluationResult: EvaluationResult<*> = getAndCheckEvaluationResult(key, collectKey)
        val value: CollectPackagesUnderDirectoryValue =
            evaluationResult
                .getWalkableGraph()
                .getValue(
                    createCollectPackagesKey(
                        rootDirectory, PathFragment.create("a"), excludedPaths
                    )
                ) as CollectPackagesUnderDirectoryValue

        // Then the value reports that "a" is a package,
        assertThat(value.isDirectoryPackage).isTrue()

        // And the subdirectory corresponding to "a/b" is NOT present in the result (it is empty and
        // false).
        assertThat(value.getSubdirectoryTransitivelyContainsPackagesOrErrors()).isEmpty()

        // Also, the computation graph contains a cached value for "a/b" with "a/b/c" excluded, because
        // "a/b/c" does live underneath "a/b".
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
        val abKey: SkyKey = createCollectPackagesKey(
            rootDirectory, PathFragment.create("a/b"), excludedPaths
        )
        Truth.assertThat(WalkableGraphUtils.exists(abKey, graph)).isTrue()
        val abValue: CollectPackagesUnderDirectoryValue =
            com.google.common.base.Preconditions.checkNotNull<T?>(graph.getValue(abKey)) as CollectPackagesUnderDirectoryValue

        // And that value says that "a/b" is not a package,
        assertThat(abValue.isDirectoryPackage).isFalse()

        // And no subdirectories are present in that value (since they are all empty and false).
        assertThat(abValue.getSubdirectoryTransitivelyContainsPackagesOrErrors()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInconsistentFileSystemExceptionFailsWithProperError() {
        val buildFile: Path = scratch.file("a/b/BUILD", "sh_library(name='b')")
        val key: SkyKey = createPrepDepsKey(rootDirectory, PathFragment.create("a"))
        // Inject a "file" stat for "a/b" directory to trigger a InconsistentFilesystemException.
        injectedStats.put(buildFile.getParentDirectory().asFragment(), buildFile.stat())

        val evaluationResult: EvaluationResult<*> = getEvaluationResult(key)

        val e: java.lang.Exception? = evaluationResult.getError(key).getException()
        Truth.assertThat(e).isInstanceOf(ProcessPackageDirectoryException::class.java)
        Truth.assertThat(e).hasCauseThat().isInstanceOf(InconsistentFilesystemException::class.java)
    }

    companion object {
        private fun createCollectPackagesKey(
            root: Path?,
            rootRelativePath: PathFragment?,
            excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
        ): SkyKey {
            val rootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(root), rootRelativePath)
            return CollectPackagesUnderDirectoryValue.key(
                RepositoryName.MAIN, rootedPath, IgnoredSubdirectories.of(excludedPaths)
            )
        }

        private fun createPrepDepsKey(root: Path?, rootRelativePath: PathFragment?): SkyKey {
            return createPrepDepsKey(root, rootRelativePath, com.google.common.collect.ImmutableSet.of<PathFragment?>())
        }

        private fun createPrepDepsKey(
            root: Path?,
            rootRelativePath: PathFragment?,
            excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
        ): SkyKey {
            val rootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(root), rootRelativePath)
            return PrepareDepsOfTargetsUnderDirectoryValue.key(
                RepositoryName.MAIN, rootedPath, IgnoredSubdirectories.of(excludedPaths)
            )
        }

        private fun createPrepDepsKey(
            root: Path?,
            rootRelativePath: PathFragment?,
            excludedPaths: IgnoredSubdirectories?,
            filteringPolicy: FilteringPolicy?
        ): SkyKey {
            val rootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(root), rootRelativePath)
            return PrepareDepsOfTargetsUnderDirectoryValue.key(
                RepositoryName.MAIN, rootedPath, excludedPaths, filteringPolicy
            )
        }
    }
}
