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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ArtifactRoot.RootType

/**
 * Tests [ArtifactFactory]. Also see [ArtifactTest] for a test
 * of individual artifacts.
 */
@RunWith(JUnit4::class)
class ArtifactFactoryTest {
    private val scratch: Scratch = Scratch()

    private var execRoot: Path? = null
    private var clientRoot: Root? = null
    private var clientRoRoot: Root? = null
    private var alienRoot: Root? = null
    private var outRoot: ArtifactRoot? = null

    private var fooPath: PathFragment? = null
    private var fooPackage: PackageIdentifier? = null
    private var fooRelative: PathFragment? = null

    private var barPath: PathFragment? = null
    private var barPackage: PackageIdentifier? = null
    private var barRelative: PathFragment? = null

    private var alienPath: PathFragment? = null
    private var alienPackage: PackageIdentifier? = null
    private var alienRelative: PathFragment? = null

    private var artifactFactory: ArtifactFactory? = null
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        execRoot = scratch.dir("/output/workspace")
        clientRoot = Root.fromPath(scratch.dir("/client/workspace"))
        clientRoRoot = Root.fromPath(scratch.dir("/client/RO/workspace"))
        alienRoot = Root.fromPath(scratch.dir("/client/workspace"))
        outRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out-root", "x", "bin")

        fooPath = PathFragment.create("foo")
        fooPackage = PackageIdentifier.createInMainRepo(fooPath)
        fooRelative = fooPath.getRelative("foosource.txt")

        barPath = PathFragment.create("foo/bar")
        barPackage = PackageIdentifier.createInMainRepo(barPath)
        barRelative = barPath.getRelative("barsource.txt")

        alienPath = PathFragment.create("external/alien")
        alienPackage = PackageIdentifier.create("alien", alienPath)
        alienRelative = alienPath.getRelative("alien.txt")

        artifactFactory = ArtifactFactory(execRoot.getParentDirectory(), "bazel-out")
        setupRoots()
    }

    private fun setupRoots() {
        val packageRootMap: MutableMap<PackageIdentifier?, Root?> = HashMap<PackageIdentifier?, Root?>()
        packageRootMap.put(fooPackage, clientRoot)
        packageRootMap.put(barPackage, clientRoRoot)
        packageRootMap.put(alienPackage, alienRoot)
        artifactFactory.setPackageRoots({ key: Any? -> packageRootMap.get(key) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSourceArtifactYieldsSameArtifact() {
        assertThat(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
            .isSameInstanceAs(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSourceArtifactUnnormalized() {
        assertThat(
            artifactFactory.getSourceArtifact(
                PathFragment.create("foo/./foosource.txt"), clientRoot
            )
        )
            .isSameInstanceAs(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveArtifact_noDerived_simpleSource() {
        assertThat(artifactFactory.resolveSourceArtifact(fooRelative, MAIN))
            .isSameInstanceAs(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
        assertThat(artifactFactory.resolveSourceArtifact(barRelative, MAIN))
            .isSameInstanceAs(artifactFactory.getSourceArtifact(barRelative, clientRoRoot))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveArtifact_inExternalRepo() {
        val a1: Artifact? = artifactFactory.getSourceArtifact(alienRelative, alienRoot)
        val a2: Artifact? = artifactFactory.resolveSourceArtifact(alienRelative, MAIN)
        assertThat(a1).isSameInstanceAs(a2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveArtifact_noDerived_derivedRoot() {
        assertThat(
            artifactFactory.resolveSourceArtifact(
                outRoot.getRoot().getRelative(fooRelative).relativeTo(execRoot), MAIN
            )
        )
            .isNull()
        assertThat(
            artifactFactory.resolveSourceArtifact(
                outRoot.getRoot().getRelative(barRelative).relativeTo(execRoot), MAIN
            )
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveArtifact_noDerived_simpleSource_other() {
        var actual: Artifact? = artifactFactory.resolveSourceArtifact(fooRelative, MAIN)
        assertThat(actual).isSameInstanceAs(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
        actual = artifactFactory.resolveSourceArtifact(barRelative, MAIN)
        assertThat(actual)
            .isSameInstanceAs(artifactFactory.getSourceArtifact(barRelative, clientRoRoot))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveArtifactWithUpLevelFailsCleanly() {
        // We need a package in the root directory to make every exec path (even one with up-level
        // references) be in a package.
        val packageRoots: MutableMap<PackageIdentifier?, Root?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                PackageIdentifier.createInMainRepo(PathFragment.create("")),
                clientRoot
            )
        artifactFactory.setPackageRoots({ key: Any? -> packageRoots.get(key) })
        val outsideWorkspace: PathFragment? = PathFragment.create("../foo")
        val insideWorkspace: PathFragment? = PathFragment.create("../workspace/foo")
        assertThat(artifactFactory.resolveSourceArtifact(outsideWorkspace, MAIN)).isNull()
        Truth.assertWithMessage(
            "Up-level-containing paths that descend into the right workspace aren't allowed"
        )
            .that(artifactFactory.resolveSourceArtifact(insideWorkspace, MAIN))
            .isNull()
        val packageRootResolver = MockPackageRootResolver()
        packageRootResolver.setPackageRoots(packageRoots)
        val result: MutableMap<PathFragment?, Artifact?> = HashMap<PathFragment?, Artifact?>()
        result.put(insideWorkspace, null)
        result.put(outsideWorkspace, null)
        assertThat(
            artifactFactory.resolveSourceArtifacts(
                com.google.common.collect.ImmutableList.of<E?>(insideWorkspace, outsideWorkspace),
                packageRootResolver
            ).entrySet()
        ).containsExactlyElementsIn(result.entrySet())
    }

    @org.junit.Test
    fun testClearResetsFactory() {
        val fooArtifact: Artifact? = artifactFactory.getSourceArtifact(fooRelative, clientRoot)
        artifactFactory.clear()
        setupRoots()
        assertThat(artifactFactory.getSourceArtifact(fooRelative, clientRoot))
            .isNotSameInstanceAs(fooArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFindDerivedRoot() {
        assertThat(artifactFactory.isDerivedArtifact(fooRelative)).isFalse()
        assertThat(
            artifactFactory.isDerivedArtifact(
                PathFragment.create("bazel-out/local-fastbuild/bin/foo")
            )
        ).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteArtifact() {
        val absoluteRoot: Root? = Root.absoluteRoot(scratch.getFileSystem())

        assertThat(
            artifactFactory.getSourceArtifact(PathFragment.create("foo"), clientRoot).getExecPath()
        )
            .isEqualTo(PathFragment.create("foo"))
        assertThat(
            artifactFactory
                .getSourceArtifact(PathFragment.create("/foo"), absoluteRoot)
                .getExecPath()
        )
            .isEqualTo(PathFragment.create("/foo"))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                artifactFactory.getSourceArtifact(
                    PathFragment.create("/foo"),
                    clientRoot
                )
            })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                artifactFactory.getSourceArtifact(
                    PathFragment.create("foo"),
                    absoluteRoot
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetGeneratingActionIdempotenceNewActionGraph() {
        val a: Artifact.DerivedArtifact =
            artifactFactory.getDerivedArtifact(fooRelative, outRoot, ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER)
        val b: Artifact.DerivedArtifact =
            artifactFactory.getDerivedArtifact(barRelative, outRoot, ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER)
        a.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        b.setGeneratingActionKey(ActionsTestUtil.Companion.NULL_ACTION_LOOKUP_DATA)
        val actionGraph: MutableActionGraph = MapBasedActionGraph(actionKeyContext)
        val originalAction: Action = NullAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, a)
        actionGraph.registerAction(originalAction)

        // Creating a second Action referring to the Artifact should create a conflict.
        val action: Action = NullAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, a, b)
        val e: ActionConflictException =
            org.junit.Assert.assertThrows<T>(
                ActionConflictException::class.java,
                org.junit.function.ThrowingRunnable { actionGraph.registerAction(action) })
        assertThat(e.getArtifact()).isSameInstanceAs(a)
        assertThat(actionGraph.getGeneratingAction(a)).isSameInstanceAs(originalAction)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_exactMatch() {
        artifactFactory.noteAnalysisStarting()
        val original: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(fooRelative, clientRoot)

        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(fooRelative, MAIN)

        Truth.assertThat(result).containsExactly(original)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_upperCaseLookupFindsLowerCaseArtifact() {
        artifactFactory.noteAnalysisStarting()
        val lowerPath: PathFragment? = PathFragment.create("foo/foosource.txt")
        val original: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(lowerPath, clientRoot)

        val upperPath: PathFragment? = PathFragment.create("foo/FooSource.txt")
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(upperPath, MAIN)

        Truth.assertThat(result).containsExactly(original)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_lowerCaseLookupFindsUpperCaseArtifact() {
        artifactFactory.noteAnalysisStarting()
        val upperPath: PathFragment? = PathFragment.create("foo/FooSource.txt")
        val original: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(upperPath, clientRoot)

        val lowerPath: PathFragment? = PathFragment.create("foo/foosource.txt")
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(lowerPath, MAIN)

        Truth.assertThat(result).containsExactly(original)
    }

    @org.junit.Test
    fun testGetSourceArtifactDifferentCasings_returnsDifferentArtifacts() {
        artifactFactory.noteAnalysisStarting()
        val lower: PathFragment? = PathFragment.create("foo/header.h")
        val upper: PathFragment? = PathFragment.create("foo/Header.h")
        val lowerArtifact: Artifact.SourceArtifact = artifactFactory.getSourceArtifact(lower, clientRoot)
        val upperArtifact: Artifact.SourceArtifact = artifactFactory.getSourceArtifact(upper, clientRoot)

        assertThat(upperArtifact).isNotSameInstanceAs(lowerArtifact)
        assertThat(lowerArtifact.getExecPath()).isEqualTo(lower)
        assertThat(upperArtifact.getExecPath()).isEqualTo(upper)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_multipleMatches() {
        artifactFactory.noteAnalysisStarting()
        val lower: PathFragment? = PathFragment.create("foo/header.h")
        val upper: PathFragment? = PathFragment.create("foo/Header.h")
        val lowerArtifact: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(lower, clientRoot)
        val upperArtifact: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(upper, clientRoot)

        val resultFromLower: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(lower, MAIN)
        val resultFromUpper: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(upper, MAIN)

        Truth.assertThat(resultFromLower).containsExactly(lowerArtifact, upperArtifact)
        Truth.assertThat(resultFromUpper).containsExactly(lowerArtifact, upperArtifact)
    }

    @org.junit.Test
    fun testCaseInsensitiveLookupWithThreeVariants() {
        artifactFactory.noteAnalysisStarting()
        val path1: PathFragment? = PathFragment.create("foo/File.h")
        val path2: PathFragment? = PathFragment.create("foo/file.h")
        val path3: PathFragment? = PathFragment.create("foo/FILE.h")
        val a1: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path1, clientRoot)
        val a2: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path2, clientRoot)
        val a3: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path3, clientRoot)

        assertThat(artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(path1, MAIN))
            .containsExactly(a1, a2, a3)
        assertThat(artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(path2, MAIN))
            .containsExactly(a1, a2, a3)
        assertThat(
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(
                PathFragment.create("foo/fIlE.h"), MAIN
            )
        )
            .containsExactly(a1, a2, a3)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_derivedPathReturnsEmpty() {
        artifactFactory.noteAnalysisStarting()
        val derivedPath: PathFragment? = PathFragment.create("bazel-out/x/bin/foo/header.h")

        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(derivedPath, MAIN)

        Truth.assertThat(result).isEmpty()
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_staleArtifactRevalidatedViaSourceRoot() {
        // First build: create an artifact.
        artifactFactory.noteAnalysisStarting()
        val path: PathFragment? = PathFragment.create("foo/stale.h")
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            artifactFactory.getSourceArtifact(path, clientRoot)

        // Second build: the artifact from the first build is invalid in the cache, but the method
        // falls back to source root resolution which re-validates it.
        artifactFactory.noteAnalysisStarting()
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(path, MAIN)

        Truth.assertThat(result).hasSize(1)
        assertThat(result.get(0).getExecPath()).isEqualTo(path)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_uplevelReturnsEmpty() {
        artifactFactory.noteAnalysisStarting()
        val uplevelPath: PathFragment? = PathFragment.create("../outside/header.h")

        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(uplevelPath, MAIN)

        Truth.assertThat(result).isEmpty()
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_fallbackToSourceRootResolution() {
        artifactFactory.noteAnalysisStarting()
        // Path not in cache but resolvable via source roots (foo package exists).
        val path: PathFragment? = PathFragment.create("foo/brand_new.h")

        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(path, MAIN)

        Truth.assertThat(result).hasSize(1)
        assertThat(result.get(0).getExecPath()).isEqualTo(path)
    }

    @org.junit.Test
    fun testExactLookupStillWorksWithCaseInsensitiveCache() {
        artifactFactory.noteAnalysisStarting()
        val lower: PathFragment? = PathFragment.create("foo/header.h")
        val lowerArtifact: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(lower, clientRoot)

        // Exact-case resolveSourceArtifact should return the correct artifact.
        assertThat(artifactFactory.resolveSourceArtifact(lower, MAIN)).isSameInstanceAs(lowerArtifact)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_staleArtifactWithDifferentCasingRevalidated() {
        // First build: create an artifact with specific casing.
        artifactFactory.noteAnalysisStarting()
        val originalPath: PathFragment? = PathFragment.create("foo/Header.h")
        val original: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(originalPath, clientRoot)

        // Second build: the artifact from the first build is stale. Resolve with different casing.
        artifactFactory.noteAnalysisStarting()
        val wrongCasePath: PathFragment? = PathFragment.create("foo/header.h")
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(wrongCasePath, MAIN)

        // Should return the original artifact with correct casing, not a new one.
        Truth.assertThat(result).hasSize(1)
        assertThat(result.get(0).getExecPath()).isEqualTo(originalPath)
        assertThat(result.get(0)).isSameInstanceAs(original)
    }

    @org.junit.Test
    fun testResolveSourceArtifactCaseInsensitively_multipleStaleArtifactsWithDifferentCasingsRevalidated() {
        // First build: create artifacts with different casings.
        artifactFactory.noteAnalysisStarting()
        val path1: PathFragment? = PathFragment.create("foo/Header.h")
        val path2: PathFragment? = PathFragment.create("foo/HEADER.h")
        val artifact1: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path1, clientRoot)
        val artifact2: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path2, clientRoot)

        // Second build: both are stale. Resolve with yet another casing.
        artifactFactory.noteAnalysisStarting()
        val queryCasePath: PathFragment? = PathFragment.create("foo/header.h")
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(queryCasePath, MAIN)

        // Both original artifacts should be revalidated and returned.
        Truth.assertThat(result).containsExactly(artifact1, artifact2)
    }

    @org.junit.Test
    fun testClearResetsCaseInsensitiveCache() {
        artifactFactory.noteAnalysisStarting()
        val path: PathFragment? = PathFragment.create("foo/header.h")
        val oldArtifact: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path, clientRoot)

        artifactFactory.clear()
        setupRoots()
        artifactFactory.noteAnalysisStarting()

        val newArtifact: Artifact.SourceArtifact? = artifactFactory.getSourceArtifact(path, clientRoot)
        assertThat(newArtifact).isNotSameInstanceAs(oldArtifact)
        val result: com.google.common.collect.ImmutableList<Artifact.SourceArtifact?>? =
            artifactFactory.resolveSourceArtifactsAsciiCaseInsensitively(path, MAIN)
        Truth.assertThat(result).containsExactly(newArtifact)
    }

    private class MockPackageRootResolver : PackageRootResolver {
        private val packageRoots: MutableMap<PathFragment?, Root?> = HashMap<PathFragment?, Root?>()

        fun setPackageRoots(packageRoots: MutableMap<PackageIdentifier?, Root?>) {
            for (packageRoot in packageRoots.entrySet()) {
                this.packageRoots.put(packageRoot.getKey().getPackageFragment(), packageRoot.getValue())
            }
        }

        public override fun findPackageRootsForFiles(execPaths: Iterable<PathFragment>): MutableMap<PathFragment?, Root?> {
            val result: MutableMap<PathFragment?, Root?> = HashMap<PathFragment?, Root?>()
            for (execPath in execPaths) {
                var dir: PathFragment? = execPath.getParentDirectory()
                while (dir != null
                ) {
                    if (packageRoots.get(dir) != null) {
                        result.put(execPath, packageRoots.get(dir))
                    }
                    dir = dir.getParentDirectory()
                }
                if (result.get(execPath) == null) {
                    result.put(execPath, null)
                }
            }
            return result
        }
    }

    companion object {
        private val MAIN: RepositoryName? = RepositoryName.MAIN
    }
}
