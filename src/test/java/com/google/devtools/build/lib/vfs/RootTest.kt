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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry

/** Tests for [RootTest].  */
@RunWith(JUnit4::class)
class RootTest {
    private var fs: FileSystem? = null

    @Before
    fun initializeFileSystem() {
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
    }

    @org.junit.Test
    fun testEqualsAndHashCodeContract() {
        val otherFs: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        EqualsTester()
            .addEqualityGroup(Root.absoluteRoot(fs), Root.absoluteRoot(fs))
            .addEqualityGroup(Root.absoluteRoot(otherFs), Root.absoluteRoot(otherFs))
            .addEqualityGroup(Root.fromPath(fs.getPath("/foo")), Root.fromPath(fs.getPath("/foo")))
            .testEquals()
    }

    @org.junit.Test
    fun testPathRoot() {
        val root: Root = Root.fromPath(fs.getPath("/foo"))
        assertThat(root.asPath()).isEqualTo(fs.getPath("/foo"))
        assertThat(root.contains(fs.getPath("/foo/bar"))).isTrue()
        assertThat(root.contains(fs.getPath("/boo/bar"))).isFalse()
        assertThat(root.contains(PathFragment.create("/foo/bar"))).isTrue()
        assertThat(root.contains(PathFragment.create("foo/bar"))).isFalse()
        assertThat(root.getRelative(PathFragment.create("bar"))).isEqualTo(fs.getPath("/foo/bar"))
        assertThat(root.getRelative("bar")).isEqualTo(fs.getPath("/foo/bar"))
        assertThat(root.getRelative(PathFragment.create("/bar"))).isEqualTo(fs.getPath("/bar"))
        assertThat(root.relativize(fs.getPath("/foo/bar"))).isEqualTo(PathFragment.create("bar"))
        assertThat(root.relativize(PathFragment.create("/foo/bar")))
            .isEqualTo(PathFragment.create("bar"))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { root.relativize(PathFragment.create("foo")) })
    }

    @org.junit.Test
    fun testFilesystemTransform() {
        val fs2: FileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        val root: Root = Root.fromPath(fs.getPath("/foo"))
        val root2: Root = Root.toFileSystem(root, fs2)
        assertThat(root2.asPath().getFileSystem()).isSameInstanceAs(fs2)
        assertThat(root2.asPath().asFragment()).isEqualTo(PathFragment.create("/foo"))
        assertThat(root.isAbsolute).isFalse()
    }

    @org.junit.Test
    fun testFileSystemAbsoluteRoot() {
        val root: Root = Root.absoluteRoot(fs)
        assertThat(root.asPath()).isNull()
        assertThat(root.contains(fs.getPath("/foo"))).isTrue()
        assertThat(root.contains(PathFragment.create("/foo/bar"))).isTrue()
        assertThat(root.contains(PathFragment.create("foo/bar"))).isFalse()
        assertThat(root.getRelative("/foo")).isEqualTo(fs.getPath("/foo"))
        assertThat(root.relativize(fs.getPath("/foo"))).isEqualTo(PathFragment.create("/foo"))
        assertThat(root.relativize(PathFragment.create("/foo"))).isEqualTo(PathFragment.create("/foo"))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { root.getRelative(PathFragment.create("foo")) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { root.getRelative(PathFragment.create("foo")) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { root.relativize(PathFragment.create("foo")) })
    }

    @org.junit.Test
    fun testCompareTo() {
        val a: Root = Root.fromPath(fs.getPath("/a"))
        val b: Root? = Root.fromPath(fs.getPath("/b"))
        val root: Root? = Root.absoluteRoot(fs)
        val list: MutableList<Root?> = com.google.common.collect.Lists.newArrayList<Root?>(a, root, b)
        list.sort(java.util.Comparator.naturalOrder<Root?>())
        Truth.assertThat(list).containsExactly(root, a, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization_simple() {
        val fooPathRoot: Root? = Root.fromPath(fs.getPath("/foo"))
        val barPathRoot: Root? = Root.fromPath(fs.getPath("/bar"))
        SerializationTester(Root.absoluteRoot(fs), fooPathRoot, barPathRoot)
            .addDependency(FileSystem::class.java, fs)
            .addDependency(
                Root.RootCodecDependencies::class.java,
                RootCodecDependencies( /*likelyPopularRoot=*/fooPathRoot)
            )
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization_likelyPopularRootIsCanonicalized() {
        val fooPathRoot: Root? = Root.fromPath(fs.getPath("/foo"))
        val otherFooPathRoot: Root? = Root.fromPath(fs.getPath("/foo"))
        val barPathRoot: Root? = Root.fromPath(fs.getPath("/bar"))
        val bazPathRoot: Root? = Root.fromPath(fs.getPath("/baz"))
        val fsAabsoluteRoot: Root? = Root.absoluteRoot(fs)

        assertThat(fooPathRoot).isNotSameInstanceAs(otherFooPathRoot)
        assertThat(fooPathRoot).isEqualTo(otherFooPathRoot)

        val registry: ObjectCodecRegistry = AutoRegistry.get()
        val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .put<FileSystem?>(FileSystem::class.java, fs)
                .put<Root.RootCodecDependencies?>(
                    Root.RootCodecDependencies::class.java,
                    RootCodecDependencies( /*likelyPopularRoots=*/
                        com.google.common.collect.ImmutableList.of<E?>(fooPathRoot, bazPathRoot)
                    )
                )
                .build()
        val registryBuilder: ObjectCodecRegistry.Builder = registry.getBuilder()
        for (`val` in dependencies.values) {
            registryBuilder.addReferenceConstant(`val`)
        }
        val objectCodecs: ObjectCodecs = ObjectCodecs(registryBuilder.build(), dependencies)

        val fooPathRootDeserialized: Root? =
            objectCodecs.deserialize(objectCodecs.serialize(fooPathRoot)) as Root?
        val otherFooPathRootDeserialized: Root? =
            objectCodecs.deserialize(objectCodecs.serialize(otherFooPathRoot)) as Root?
        assertThat(fooPathRootDeserialized).isSameInstanceAs(fooPathRoot)
        assertThat(otherFooPathRootDeserialized).isSameInstanceAs(fooPathRoot)

        val barPathRootDeserialized: Root? =
            objectCodecs.deserialize(objectCodecs.serialize(barPathRoot)) as Root?
        assertThat(barPathRootDeserialized).isNotSameInstanceAs(barPathRoot)
        assertThat(barPathRootDeserialized).isEqualTo(barPathRoot)

        val bazPathRootDeserialized: Root? =
            objectCodecs.deserialize(objectCodecs.serialize(bazPathRoot)) as Root?
        assertThat(bazPathRootDeserialized).isSameInstanceAs(bazPathRoot)

        val fsAabsoluteRootDeserialized: Root? =
            objectCodecs.deserialize(objectCodecs.serialize(fsAabsoluteRoot)) as Root?
        assertThat(fsAabsoluteRootDeserialized).isNotSameInstanceAs(fsAabsoluteRoot)
        assertThat(fsAabsoluteRootDeserialized).isEqualTo(fsAabsoluteRoot)
    }
}
