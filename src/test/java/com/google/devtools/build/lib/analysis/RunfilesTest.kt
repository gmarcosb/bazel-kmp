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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** Test for [Runfiles].  */
@RunWith(JUnit4::class)
class RunfilesTest : FoundationTestCase() {
    private fun checkWarning() {
        assertContainsEvent("obscured by a -> x")
        Truth.assertWithMessage("Runfiles.filterListForObscuringSymlinks should have warned once")
            .that(eventCollector.count())
            .isEqualTo(1)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector
            ).getKind()
        ).isEqualTo(com.google.devtools.build.lib.events.EventKind.WARNING)
    }

    @org.junit.Test
    fun testFilterListForObscuringSymlinksCatchesBadObscurer() {
        val obscuringMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        val pathA: PathFragment? = PathFragment.create("a")
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "x")
        obscuringMap.put(pathA, artifactA)
        obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "c/b"))
        assertThat(
            Runfiles.filterListForObscuringSymlinks(warningPrefixConflictReceiver(), obscuringMap)
        )
            .containsExactly(pathA, artifactA)
        checkWarning()
    }

    @org.junit.Test
    fun testFilterListForObscuringSymlinksCatchesBadGrandParentObscurer() {
        val obscuringMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        val pathA: PathFragment? = PathFragment.create("a")
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "x")
        obscuringMap.put(pathA, artifactA)
        obscuringMap.put(PathFragment.create("a/b/c"), ActionsTestUtil.createArtifact(root, "b/c"))
        assertThat(
            Runfiles.filterListForObscuringSymlinks(warningPrefixConflictReceiver(), obscuringMap)
        )
            .containsExactly(pathA, artifactA)
        checkWarning()
    }

    @org.junit.Test
    fun testFilterListForObscuringSymlinksCatchesBadObscurerNoListener() {
        val obscuringMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        val pathA: PathFragment? = PathFragment.create("a")
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a")
        obscuringMap.put(pathA, artifactA)
        obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "c/b"))
        assertThat(
            Runfiles.filterListForObscuringSymlinks(warningPrefixConflictReceiver(), obscuringMap)
        )
            .containsExactly(pathA, artifactA)
    }

    @org.junit.Test
    fun testFilterListForObscuringSymlinksIgnoresOkObscurer() {
        val obscuringMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        val pathA: PathFragment? = PathFragment.create("a")
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a")
        obscuringMap.put(pathA, artifactA)
        obscuringMap.put(PathFragment.create("a/b"), ActionsTestUtil.createArtifact(root, "a/b"))

        assertThat(
            Runfiles.filterListForObscuringSymlinks(warningPrefixConflictReceiver(), obscuringMap)
        )
            .containsExactly(pathA, artifactA)
        assertNoEvents()
    }

    @org.junit.Test
    fun testFilterListForObscuringSymlinksNoObscurers() {
        val obscuringMap: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        val pathA: PathFragment? = PathFragment.create("a")
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a")
        obscuringMap.put(pathA, artifactA)
        val pathBC: PathFragment? = PathFragment.create("b/c")
        val artifactBC: Artifact? = ActionsTestUtil.createArtifact(root, "a/b")
        obscuringMap.put(pathBC, artifactBC)
        assertThat(
            Runfiles.filterListForObscuringSymlinks(warningPrefixConflictReceiver(), obscuringMap)
        )
            .containsExactly(pathA, artifactA, pathBC, artifactBC)
        assertNoEvents()
    }

    private fun warningPrefixConflictReceiver(): RunfilesConflictReceiver {
        return object : RunfilesConflictReceiver() {
            public override fun nestedRunfilesTree(runfilesTree: Artifact?) {
                throw java.lang.AssertionError(runfilesTree)
            }

            public override fun prefixConflict(message: String?) {
                reporter.handle(com.google.devtools.build.lib.events.Event.warn(message))
            }
        }
    }

    @org.junit.Test
    fun testBuilderMergeConflictPolicyDefault() {
        val r1: Runfiles? = Builder("TESTING").build()
        val r2: Runfiles = Builder("TESTING").merge(r1).build()
        assertThat(r2.getConflictPolicy()).isEqualTo(ConflictPolicy.WARN)
    }

    @org.junit.Test
    fun testBuilderMergeConflictPolicyInherit() {
        val r1: Runfiles? = Builder("TESTING").build().setConflictPolicy(ConflictPolicy.WARN)
        val r2: Runfiles = Builder("TESTING").merge(r1).build()
        assertThat(r2.getConflictPolicy()).isEqualTo(ConflictPolicy.WARN)
    }

    @org.junit.Test
    fun testBuilderMergeConflictPolicyInheritStrictest() {
        val r1: Runfiles? = Builder("TESTING").build().setConflictPolicy(ConflictPolicy.WARN)
        val r2: Runfiles? = Builder("TESTING").build().setConflictPolicy(ConflictPolicy.ERROR)
        val r3: Runfiles = Builder("TESTING").merge(r1).merge(r2).build()
        assertThat(r3.getConflictPolicy()).isEqualTo(ConflictPolicy.ERROR)
        // Swap ordering
        val r4: Runfiles = Builder("TESTING").merge(r2).merge(r1).build()
        assertThat(r4.getConflictPolicy()).isEqualTo(ConflictPolicy.ERROR)
    }

    @org.junit.Test
    fun testRunfileAdded() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val pathB: PathFragment? = PathFragment.create("repo/b")
        val legacyPathB: PathFragment? = LabelConstants.EXTERNAL_PATH_PREFIX.getRelative(pathB)
        val runfilesPathB: PathFragment? = LabelConstants.EXTERNAL_RUNFILES_PATH_PREFIX.getRelative(pathB)
        val artifactB: Artifact? = ActionsTestUtil.createArtifactWithRootRelativePath(root, legacyPathB)

        val runfiles: Runfiles = Builder("wsname").addSymlink(runfilesPathB, artifactB).build()

        assertThat(
            runfiles.getRunfilesInputs(
                warningPrefixConflictReceiver(),  /* repoMappingManifest= */null
            )
        )
            .containsExactly(
                PathFragment.create("wsname/.runfile"), null, PathFragment.create("repo/b"), artifactB
            )
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMergeWithSymlinks() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a/target")
        val artifactB: Artifact? = ActionsTestUtil.createArtifact(root, "b/target")
        val runfilesA: Runfiles =
            Builder("TESTING")
                .addSymlink(PathFragment.create("a/symlink"), artifactA)
                .build()
        val runfilesB: Runfiles? =
            Builder("TESTING")
                .addSymlink(PathFragment.create("b/symlink"), artifactB)
                .build()
        val thread: StarlarkThread? = newStarlarkThread()

        val runfilesC: Runfiles = runfilesA.merge(runfilesB, thread)
        assertThat(runfilesC.getRunfilesInputs( /* repoMappingManifest= */null))
            .containsExactly(
                PathFragment.create("TESTING/a/symlink"),
                artifactA,
                PathFragment.create("TESTING/b/symlink"),
                artifactB
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeAll_symlinks() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a/target")
        val artifactB: Artifact? = ActionsTestUtil.createArtifact(root, "b/target")
        val artifactC: Artifact? = ActionsTestUtil.createArtifact(root, "c/target")
        val runfilesA: Runfiles =
            Builder("TESTING")
                .addSymlink(PathFragment.create("a/symlink"), artifactA)
                .build()
        val runfilesB: Runfiles? =
            Builder("TESTING")
                .addSymlink(PathFragment.create("b/symlink"), artifactB)
                .build()
        val runfilesC: Runfiles? =
            Builder("TESTING")
                .addSymlink(PathFragment.create("c/symlink"), artifactC)
                .build()
        val thread: StarlarkThread? = newStarlarkThread()

        val runfilesMerged: Runfiles =
            runfilesA.mergeAll(StarlarkList.immutableOf<T?>(runfilesB, runfilesC), thread)
        assertThat(runfilesMerged.getRunfilesInputs( /* repoMappingManifest= */null))
            .containsExactly(
                PathFragment.create("TESTING/a/symlink"),
                artifactA,
                PathFragment.create("TESTING/b/symlink"),
                artifactB,
                PathFragment.create("TESTING/c/symlink"),
                artifactC
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMergeEmptyWithNonEmpty() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a/target")
        val runfilesB: Runfiles = Builder("TESTING").addArtifact(artifactA).build()
        val thread: StarlarkThread? = newStarlarkThread()

        assertThat(Runfiles.EMPTY.merge(runfilesB, thread)).isSameInstanceAs(runfilesB)
        assertThat(runfilesB.merge(Runfiles.EMPTY, thread)).isSameInstanceAs(runfilesB)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeAll_emptyWithNonEmpty() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifact: Artifact? = ActionsTestUtil.createArtifact(root, "target")
        val nonEmpty: Runfiles = Builder("TESTING").addArtifact(artifact).build()
        val thread: StarlarkThread? = newStarlarkThread()

        assertThat(Runfiles.EMPTY.mergeAll(StarlarkList.< T > immutableOf < T ? > (nonEmpty), thread))
            .isSameInstanceAs(nonEmpty)
        assertThat(
            Runfiles.EMPTY.mergeAll(
                StarlarkList.immutableOf<T?>(Runfiles.EMPTY, nonEmpty, Runfiles.EMPTY), thread
            )
        )
            .isSameInstanceAs(nonEmpty)
        assertThat(nonEmpty.mergeAll(StarlarkList.immutableOf<T?>(Runfiles.EMPTY, Runfiles.EMPTY), thread))
            .isSameInstanceAs(nonEmpty)
        assertThat(nonEmpty.mergeAll(StarlarkList.immutableOf<T?>(), thread)).isSameInstanceAs(nonEmpty)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeAll_emptyWithEmpty() {
        val thread: StarlarkThread? = newStarlarkThread()
        assertThat(Runfiles.EMPTY.mergeAll(StarlarkList.immutableOf<T?>(), thread))
            .isSameInstanceAs(Runfiles.EMPTY)
        assertThat(
            Runfiles.EMPTY.mergeAll(
                StarlarkList.immutableOf<T?>(Runfiles.EMPTY, Runfiles.EMPTY), thread
            )
        )
            .isSameInstanceAs(Runfiles.EMPTY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun merge_exceedsDepthLimit_throwsException() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a/target")
        val artifactB: Artifact? = ActionsTestUtil.createArtifact(root, "b/target")
        val artifactC: Artifact? = ActionsTestUtil.createArtifact(root, "c/target")
        val runfilesA: Runfiles = Builder("TESTING").addArtifact(artifactA).build()
        val runfilesB: Runfiles? = Builder("TESTING").addArtifact(artifactB).build()
        val runfilesC: Runfiles? = Builder("TESTING").addArtifact(artifactC).build()
        val thread: StarlarkThread? = newStarlarkThread("--nested_set_depth_limit=2")

        val mergeAB: Runfiles = runfilesA.merge(runfilesB, thread)
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { mergeAB.merge(runfilesC, thread) })
        Truth.assertThat(expected).hasMessageThat().contains("artifacts depset depth 3 exceeds limit (2)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergeAll_exceedsDepthLimit_throwsException() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifactA: Artifact? = ActionsTestUtil.createArtifact(root, "a/target")
        val artifactB: Artifact? = ActionsTestUtil.createArtifact(root, "b/target")
        val artifactC: Artifact? = ActionsTestUtil.createArtifact(root, "c/target")
        val sympathA: PathFragment? = PathFragment.create("a/symlink")
        val sympathB: PathFragment? = PathFragment.create("b/symlink")
        val sympathC: PathFragment? = PathFragment.create("c/symlink")
        val runfilesA: Runfiles = Builder("TESTING").addSymlink(sympathA, artifactA).build()
        val runfilesB: Runfiles? = Builder("TESTING").addSymlink(sympathB, artifactB).build()
        val runfilesC: Runfiles? = Builder("TESTING").addSymlink(sympathC, artifactC).build()
        val thread: StarlarkThread? = newStarlarkThread("--nested_set_depth_limit=2")

        val mergeAllAB: Runfiles = runfilesA.mergeAll(StarlarkList.< T > immutableOf < T ? > (runfilesB), thread)
        val expected: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    mergeAllAB.mergeAll(
                        StarlarkList.< T > immutableOf < T ? > (runfilesC),
                        thread
                    )
                })
        Truth.assertThat(expected).hasMessageThat().contains("symlinks depset depth 3 exceeds limit (2)")
    }

    @org.junit.Test
    fun testGetEmptyFilenames() {
        val root: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(scratch.resolve("/workspace")))
        val artifact: Artifact? = ActionsTestUtil.createArtifact(root, "my-artifact")
        val runfiles: Runfiles =
            Builder("TESTING")
                .addArtifact(artifact)
                .addSymlink(PathFragment.create("my-symlink"), artifact)
                .addRootSymlink(PathFragment.create("my-root-symlink"), artifact)
                .setEmptyFilesSupplier(
                    object : EmptyFilesSupplier() {
                        public override fun getExtraPaths(
                            manifestPaths: MutableSet<PathFragment?>
                        ): com.google.common.collect.ImmutableList<PathFragment?> {
                            return manifestPaths.stream()
                                .map<Any?> { f: PathFragment? -> f.replaceName(f.getBaseName() + "-empty") }
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                        }

                        public override fun fingerprint(fingerprint: Fingerprint?) {}
                    })
                .build()
        assertThat(runfiles.getEmptyFilenames())
            .containsExactly(
                PathFragment.create("my-artifact-empty"), PathFragment.create("my-symlink-empty")
            )
    }

    companion object {
        @Throws(OptionsParsingException::class)
        private fun newStarlarkThread(vararg options: String?): StarlarkThread? {
            return StarlarkThread.createTransient(
                Mutability.create("test"),
                com.google.devtools.common.options.Options.parse(
                    BuildLanguageOptions::class.java,
                    options
                ).options.toStarlarkSemantics()
            )
        }
    }
}
