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

/** Tests for [ArtifactRoot].  */
@RunWith(JUnit4::class)
class ArtifactRootTest {
    private val scratch: Scratch = Scratch()

    @org.junit.Test
    @Throws(IOException::class)
    fun asSourceRoot_createsValidSourceRoot() {
        val sourceDir: Path = scratch.dir("/source")
        val root: ArtifactRoot = ArtifactRoot.asSourceRoot(Root.fromPath(sourceDir))
        assertThat(root.isSourceRoot()).isTrue()
        assertThat(root.getExecPath()).isEqualTo(PathFragment.EMPTY_FRAGMENT)
        assertThat(root.getRoot()).isEqualTo(Root.fromPath(sourceDir))
        assertThat(root.toString()).isEqualTo("/source[source]")
    }

    @org.junit.Test
    fun asSourceRoot_nullRoot_fails() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ArtifactRoot.asSourceRoot(null) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun asDerivedRoot_createsValidDerivedRoot() {
        val execRoot: Path = scratch.dir("/exec")
        val rootDir: Path = scratch.dir("/exec/root")

        val root: ArtifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "root")

        assertThat(root.isSourceRoot()).isFalse()
        assertThat(root.getExecPath()).isEqualTo(PathFragment.create("root"))
        assertThat(root.getRoot()).isEqualTo(Root.fromPath(rootDir))
        assertThat(root.toString()).isEqualTo("/exec/root[derived]")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun asDerivedRoot_derivedRootIsExecRoot_failsNotOk() {
        val execRoot: Path = scratch.dir("/exec")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "") })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun asDerivedRoot_emptyPrefix_createsArtifactRoot() {
        val execRoot: Path = scratch.dir("/exec")
        assertThat(ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "", "suffix", ""))
            .isEqualTo(ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "suffix"))
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun asDerivedRoot_prefixWithSlash_fails() {
        val execRoot: Path = scratch.dir("/exec")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "suffix/") })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun asDerivedRoot_noPrefixes_fails() {
        val execRoot: Path = scratch.dir("/exec")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT) })
    }

    @org.junit.Test
    fun asDerivedRoot_nullExecPath_fails() {
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { ArtifactRoot.asDerivedRoot(null, RootType.OUTPUT, "exec") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_simpleExecPath_createsArtifactRoot() {
        val execRoot: Path = scratch.dir("/exec")
        val rootDir: Path = scratch.dir("/exec/root")

        val root: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, PathFragment.create("root"))

        assertThat(root.isSourceRoot()).isFalse()
        assertThat(root.getExecPath()).isEqualTo(PathFragment.create("root"))
        assertThat(root.getRoot()).isEqualTo(Root.fromPath(rootDir))
        assertThat(root.toString()).isEqualTo("/exec/root[derived]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_nestedExecPath_createsArtifactRoot() {
        val execRoot: Path = scratch.dir("/exec")
        val rootDir: Path = scratch.dir("/exec/dir1/dir2/dir3")

        val root: ArtifactRoot =
            ArtifactRoot.asDerivedRoot(
                execRoot, RootType.OUTPUT, PathFragment.create("dir1/dir2/dir3")
            )

        assertThat(root.isSourceRoot()).isFalse()
        assertThat(root.getExecPath()).isEqualTo(PathFragment.create("dir1/dir2/dir3"))
        assertThat(root.getRoot()).isEqualTo(Root.fromPath(rootDir))
        assertThat(root.toString()).isEqualTo("/exec/dir1/dir2/dir3[derived]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_emptyExecPath_fails() {
        val execRoot: Path = scratch.dir("/exec")

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ArtifactRoot.asDerivedRoot(
                    execRoot,
                    RootType.OUTPUT,
                    PathFragment.EMPTY_FRAGMENT
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_execPathIsCurrentDirectory_fails() {
        val execRoot: Path = scratch.dir("/exec")

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ArtifactRoot.asDerivedRoot(
                    execRoot,
                    RootType.OUTPUT,
                    PathFragment.create(".")
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_execPathIsDirectoryUp_fails() {
        val execRoot: Path = scratch.dir("/exec")

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ArtifactRoot.asDerivedRoot(
                    execRoot,
                    RootType.OUTPUT,
                    PathFragment.create("..")
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asDerivedRootPathFragment_execPathContainsDirectoryUp_fails() {
        val execRoot: Path = scratch.dir("/exec")

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ArtifactRoot.asDerivedRoot(
                    execRoot, RootType.OUTPUT, PathFragment.create("../outsideExecRoot")
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun derivedRootSerialization_rootMatchesDesignatedLikelyRoot_skipsRootInSerialization() {
        val execRoot: Path = scratch.dir("/thisisaveryverylongexecrootthatwedontwanttoserialize")
        val derivedRoot: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "first", "second", "third")
        val registry: ObjectCodecRegistry = AutoRegistry.get()
        val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .put<FileSystem?>(FileSystem::class.java, scratch.getFileSystem())
                .put<Root.RootCodecDependencies?>(
                    Root.RootCodecDependencies::class.java,
                    RootCodecDependencies( /*likelyPopularRoot=*/Root.fromPath(execRoot))
                )
                .build()
        val registryBuilder: ObjectCodecRegistry.Builder = registry.getBuilder()
        for (`val` in dependencies.values()) {
            registryBuilder.addReferenceConstant(`val`)
        }
        val objectCodecs: ObjectCodecs = ObjectCodecs(registryBuilder.build(), dependencies)
        val serialized: ByteString = objectCodecs.serialize(derivedRoot)
        // 30 bytes as of 2020/04/27.
        Truth.assertThat(serialized.size()).isLessThan(31)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun equals_returnsTrueForIdenticalRootAndDetectsDifferencesOnEachField() {
        val execRoot: Path = scratch.dir("/exec")
        val rootSegment = "root"
        val rootDir: Path = execRoot.getChild(rootSegment)
        rootDir.createDirectoryAndParents()
        val otherRootDir: Path = scratch.dir("/")
        val sourceDir: Path = scratch.dir("/source")

        EqualsTester()
            .addEqualityGroup(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, rootSegment),
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, PathFragment.create(rootSegment))
            )
            .addEqualityGroup(
                ArtifactRoot.asDerivedRoot(otherRootDir, RootType.OUTPUT, "exec", rootSegment)
            )
            .addEqualityGroup(ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "otherSegment"))
            .addEqualityGroup(ArtifactRoot.asSourceRoot(Root.fromPath(sourceDir)))
            .addEqualityGroup(ArtifactRoot.asSourceRoot(Root.fromPath(rootDir)))
            .testEquals()
    }
}
