// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal

/** Tests for [PathFragment].  */
@RunWith(TestParameterInjector::class)
class PathFragmentTest {
    @org.junit.Test
    fun testEqualsAndHashCode() {
        val filesystem: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        EqualsTester()
            .addEqualityGroup(
                create("../relative/path"),
                create("..").getRelative("relative").getRelative("path"),
                create(java.io.File("../relative/path").getPath())
            )
            .addEqualityGroup(create("something/else"))
            .addEqualityGroup(create("/something/else"))
            .addEqualityGroup(create("/"), create("//////"))
            .addEqualityGroup(create(""), PathFragment.EMPTY_FRAGMENT)
            .addEqualityGroup(filesystem.getPath("/")) // A Path object.
            .testEquals()
    }

    @org.junit.Test
    fun testHashCodeCache() {
        val relativePath: PathFragment = create("../relative/path")
        val rootPath: PathFragment = create("/")

        val oldResult: Int = relativePath.hashCode()
        val rootResult: Int = rootPath.hashCode()
        assertThat(relativePath.hashCode()).isEqualTo(oldResult)
        assertThat(rootPath.hashCode()).isEqualTo(rootResult)
    }

    @org.junit.Test
    fun testRelativeTo() {
        assertThat(create("foo/bar/baz").relativeTo("foo").getPathString()).isEqualTo("bar/baz")
        assertThat(create("/foo/bar/baz").relativeTo("/foo").getPathString()).isEqualTo("bar/baz")
        assertThat(create("foo/bar/baz").relativeTo("foo/bar").getPathString()).isEqualTo("baz")
        assertThat(create("/foo/bar/baz").relativeTo("/foo/bar").getPathString()).isEqualTo("baz")
        assertThat(create("/foo").relativeTo("/").getPathString()).isEqualTo("foo")
        assertThat(create("foo").relativeTo("").getPathString()).isEqualTo("foo")
        assertThat(create("foo/bar").relativeTo("").getPathString()).isEqualTo("foo/bar")
    }

    @org.junit.Test
    fun testIsAbsolute() {
        assertThat(create("/absolute/test").isAbsolute()).isTrue()
        assertThat(create("relative/test").isAbsolute()).isFalse()
        assertThat(create(java.io.File("/absolute/test").getPath()).isAbsolute()).isTrue()
        assertThat(create(java.io.File("relative/test").getPath()).isAbsolute()).isFalse()
    }

    @org.junit.Test
    fun testIsNormalized() {
        assertThat(PathFragment.isNormalized("/absolute/path")).isTrue()
        assertThat(PathFragment.isNormalized("some//path")).isTrue()
        assertThat(PathFragment.isNormalized("some/./path")).isFalse()
        assertThat(PathFragment.isNormalized("../some/path")).isFalse()
        assertThat(PathFragment.isNormalized("./some/path")).isFalse()
        assertThat(PathFragment.isNormalized("some/path/..")).isFalse()
        assertThat(PathFragment.isNormalized("some/path/.")).isFalse()
        assertThat(PathFragment.isNormalized("some/other/../path")).isFalse()
        assertThat(PathFragment.isNormalized("some/other//tricky..path..")).isTrue()
        assertThat(PathFragment.isNormalized("/some/other//tricky..path..")).isTrue()
    }

    @org.junit.Test
    fun testContainsUpLevelReferences() {
        assertThat(PathFragment.containsUplevelReferences("/absolute/path")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("some//path")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("some/./path")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("../some/path")).isTrue()
        assertThat(PathFragment.containsUplevelReferences("./some/path")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("some/path/..")).isTrue()
        assertThat(PathFragment.containsUplevelReferences("some/path/.")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("some/other/../path")).isTrue()
        assertThat(PathFragment.containsUplevelReferences("some/other//tricky..path..")).isFalse()
        assertThat(PathFragment.containsUplevelReferences("/some/other//tricky..path..")).isFalse()

        // Normalization cannot remove leading uplevel references, so this will be true
        assertThat(create("../some/path").containsUplevelReferences()).isTrue()
        // Normalization will remove these, so no uplevel references left
        assertThat(create("some/path/..").containsUplevelReferences()).isFalse()
    }

    @org.junit.Test
    fun testRootNodeReturnsRootString() {
        val rootFragment: PathFragment = create("/")
        assertThat(rootFragment.getPathString()).isEqualTo("/")
    }

    @org.junit.Test
    fun testGetRelative() {
        assertThat(create("a").getRelative("b").getPathString()).isEqualTo("a/b")
        assertThat(create("a/b").getRelative("c/d").getPathString()).isEqualTo("a/b/c/d")
        assertThat(create("c/d").getRelative("/a/b").getPathString()).isEqualTo("/a/b")
        assertThat(create("a").getRelative("").getPathString()).isEqualTo("a")
        assertThat(create("/").getRelative("").getPathString()).isEqualTo("/")
        assertThat(create("a/b").getRelative("../foo").getPathString()).isEqualTo("a/foo")
        assertThat(create("/a/b").getRelative("../foo").getPathString()).isEqualTo("/a/foo")

        // Make sure any fast path of PathFragment#getRelative(PathFragment) works
        assertThat(create("a/b").getRelative(create("../foo")).getPathString()).isEqualTo("a/foo")
        assertThat(create("/a/b").getRelative(create("../foo")).getPathString()).isEqualTo("/a/foo")

        // Make sure any fast path of PathFragment#getRelative(PathFragment) works
        assertThat(create("c/d").getRelative(create("/a/b")).getPathString()).isEqualTo("/a/b")

        // Test normalization
        assertThat(create("a").getRelative(".").getPathString()).isEqualTo("a")
    }

    @org.junit.Test
    fun getRelative_absolutePathArgument_returnsSameInstance(
        @TestParameter("/c/d", "c/d") basePath: String?
    ) {
        val absolute: PathFragment? = PathFragment.create("/a/b")
        assertThat(PathFragment.create(basePath).getRelative(absolute)).isSameInstanceAs(absolute)
    }

    @org.junit.Test
    fun getRelative_emptyBasePath_returnsSameInstance(
        @TestParameter("/a/b", "a/b") argument: String?
    ) {
        val instance: PathFragment? = PathFragment.create(argument)
        assertThat(EMPTY_FRAGMENT.getRelative(instance)).isSameInstanceAs(instance)
    }

    @org.junit.Test
    fun testIsNormalizedRelativePath() {
        assertThat(PathFragment.isNormalizedRelativePath("/a")).isFalse()
        assertThat(PathFragment.isNormalizedRelativePath("a///b")).isFalse()
        assertThat(PathFragment.isNormalizedRelativePath("../a")).isFalse()
        assertThat(PathFragment.isNormalizedRelativePath("a/../b")).isFalse()
        assertThat(PathFragment.isNormalizedRelativePath("a/b")).isTrue()
        assertThat(PathFragment.isNormalizedRelativePath("ab")).isTrue()
    }

    @org.junit.Test
    fun testContainsSeparator() {
        assertThat(PathFragment.containsSeparator("/a")).isTrue()
        assertThat(PathFragment.containsSeparator("a///b")).isTrue()
        assertThat(PathFragment.containsSeparator("../a")).isTrue()
        assertThat(PathFragment.containsSeparator("a/../b")).isTrue()
        assertThat(PathFragment.containsSeparator("a/b")).isTrue()
        assertThat(PathFragment.containsSeparator("ab")).isFalse()
    }

    @org.junit.Test
    fun testGetChildWorks() {
        val pf: PathFragment = create("../some/path")
        assertThat(pf.getChild("hi")).isEqualTo(create("../some/path/hi"))
        assertThat(pf.getChild("h\\i")).isEqualTo(create("../some/path/h\\i"))
        assertThat(create("../some/path").getChild(".hi")).isEqualTo(create("../some/path/.hi"))
        assertThat(create("../some/path").getChild("..hi")).isEqualTo(create("../some/path/..hi"))
    }

    @org.junit.Test
    fun testGetChildRejectsInvalidBaseNames() {
        val pf: PathFragment = create("../some/path")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild(".") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild("..") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild("x/y") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild("/y") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild("y/") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pf.getChild("") })
    }

    @org.junit.Test
    fun testEmptyPathToEmptyPath() {
        assertThat(create("/").getPathString()).isEqualTo("/")
        assertThat(create("").getPathString()).isEqualTo("")
    }

    @org.junit.Test
    fun testRedundantSlashes() {
        assertThat(create("///").getPathString()).isEqualTo("/")
        assertThat(create("/foo///bar").getPathString()).isEqualTo("/foo/bar")
        assertThat(create("////foo//bar").getPathString()).isEqualTo("/foo/bar")
    }

    @org.junit.Test
    fun testSimpleNameToSimpleName() {
        assertThat(create("/foo").getPathString()).isEqualTo("/foo")
        assertThat(create("foo").getPathString()).isEqualTo("foo")
    }

    @org.junit.Test
    fun testSimplePathToSimplePath() {
        assertThat(create("/foo/bar").getPathString()).isEqualTo("/foo/bar")
        assertThat(create("foo/bar").getPathString()).isEqualTo("foo/bar")
    }

    @org.junit.Test
    fun testStripsTrailingSlash() {
        assertThat(create("/foo/bar/").getPathString()).isEqualTo("/foo/bar")
    }

    @org.junit.Test
    fun testGetParentDirectory() {
        val fooBarWiz: PathFragment = create("foo/bar/wiz")
        val fooBar: PathFragment = create("foo/bar")
        val foo: PathFragment = create("foo")
        val empty: PathFragment = create("")
        assertThat(fooBarWiz.getParentDirectory()).isEqualTo(fooBar)
        assertThat(fooBar.getParentDirectory()).isEqualTo(foo)
        assertThat(foo.getParentDirectory()).isEqualTo(empty)
        assertThat(empty.getParentDirectory()).isNull()

        val fooBarWizAbs: PathFragment = create("/foo/bar/wiz")
        val fooBarAbs: PathFragment = create("/foo/bar")
        val fooAbs: PathFragment = create("/foo")
        val rootAbs: PathFragment = create("/")
        assertThat(fooBarWizAbs.getParentDirectory()).isEqualTo(fooBarAbs)
        assertThat(fooBarAbs.getParentDirectory()).isEqualTo(fooAbs)
        assertThat(fooAbs.getParentDirectory()).isEqualTo(rootAbs)
        assertThat(rootAbs.getParentDirectory()).isNull()
    }

    @org.junit.Test
    fun testSegmentsCount() {
        assertThat(create("foo/bar").segmentCount()).isEqualTo(2)
        assertThat(create("/foo/bar").segmentCount()).isEqualTo(2)
        assertThat(create("foo//bar").segmentCount()).isEqualTo(2)
        assertThat(create("/foo//bar").segmentCount()).isEqualTo(2)
        assertThat(create("foo/").segmentCount()).isEqualTo(1)
        assertThat(create("/foo/").segmentCount()).isEqualTo(1)
        assertThat(create("foo").segmentCount()).isEqualTo(1)
        assertThat(create("/foo").segmentCount()).isEqualTo(1)
        assertThat(create("/").segmentCount()).isEqualTo(0)
        assertThat(create("").segmentCount()).isEqualTo(0)
    }

    @org.junit.Test
    fun isSingleSegment_true(@TestParameter("/foo", "foo") path: String?) {
        assertThat(create(path).isSingleSegment()).isTrue()
    }

    @org.junit.Test
    fun isSingleSegment_false(
        @TestParameter("/", "", "/foo/bar", "foo/bar", "/foo/bar/baz", "foo/bar/baz") path: String?
    ) {
        assertThat(create(path).isSingleSegment()).isFalse()
    }

    @org.junit.Test
    fun isMultiSegment_true(
        @TestParameter("/foo/bar", "foo/bar", "/foo/bar/baz", "foo/bar/baz") path: String?
    ) {
        assertThat(create(path).isMultiSegment()).isTrue()
    }

    @org.junit.Test
    fun isMultiSegment_false(@TestParameter("/", "", "/foo", "foo") path: String?) {
        assertThat(create(path).isMultiSegment()).isFalse()
    }

    @org.junit.Test
    fun testGetSegment() {
        assertThat(create("foo/bar").getSegment(0)).isEqualTo("foo")
        assertThat(create("foo/bar").getSegment(1)).isEqualTo("bar")
        assertThat(create("/foo/bar").getSegment(0)).isEqualTo("foo")
        assertThat(create("/foo/bar").getSegment(1)).isEqualTo("bar")
        assertThat(create("foo/").getSegment(0)).isEqualTo("foo")
        assertThat(create("/foo/").getSegment(0)).isEqualTo("foo")
        assertThat(create("foo").getSegment(0)).isEqualTo("foo")
        assertThat(create("/foo").getSegment(0)).isEqualTo("foo")
    }

    @org.junit.Test
    fun segments() {
        assertThat(create("/this/is/a/path").segments())
            .containsExactly("this", "is", "a", "path")
            .inOrder()
    }

    @org.junit.Test
    fun testBasename() {
        assertThat(create("foo/bar").getBaseName()).isEqualTo("bar")
        assertThat(create("/foo/bar").getBaseName()).isEqualTo("bar")
        assertThat(create("foo/").getBaseName()).isEqualTo("foo")
        assertThat(create("/foo/").getBaseName()).isEqualTo("foo")
        assertThat(create("foo").getBaseName()).isEqualTo("foo")
        assertThat(create("/foo").getBaseName()).isEqualTo("foo")
        assertThat(create("/").getBaseName()).isEmpty()
        assertThat(create("").getBaseName()).isEmpty()
    }

    @org.junit.Test
    fun testFileExtension() {
        assertThat(create("foo.bar").getFileExtension()).isEqualTo("bar")
        assertThat(create("foo.barr").getFileExtension()).isEqualTo("barr")
        assertThat(create("foo.b").getFileExtension()).isEqualTo("b")
        assertThat(create("foo.").getFileExtension()).isEmpty()
        assertThat(create("foo").getFileExtension()).isEmpty()
        assertThat(create(".").getFileExtension()).isEmpty()
        assertThat(create("").getFileExtension()).isEmpty()
        assertThat(create("foo/bar.baz").getFileExtension()).isEqualTo("baz")
        assertThat(create("foo.bar.baz").getFileExtension()).isEqualTo("baz")
        assertThat(create("foo.bar/baz").getFileExtension()).isEmpty()
    }

    @org.junit.Test
    fun testReplaceName() {
        assertThat(create("foo/bar").replaceName("baz").getPathString()).isEqualTo("foo/baz")
        assertThat(create("/foo/bar").replaceName("baz").getPathString()).isEqualTo("/foo/baz")
        assertThat(create("foo/bar").replaceName("").getPathString()).isEqualTo("foo")
        assertThat(create("foo/").replaceName("baz").getPathString()).isEqualTo("baz")
        assertThat(create("/foo/").replaceName("baz").getPathString()).isEqualTo("/baz")
        assertThat(create("foo").replaceName("baz").getPathString()).isEqualTo("baz")
        assertThat(create("/foo").replaceName("baz").getPathString()).isEqualTo("/baz")
        assertThat(create("/").replaceName("baz")).isNull()
        assertThat(create("/").replaceName("")).isNull()
        assertThat(create("").replaceName("baz")).isNull()
        assertThat(create("").replaceName("")).isNull()

        assertThat(create("foo/bar").replaceName("bar/baz").getPathString()).isEqualTo("foo/bar/baz")
        assertThat(create("foo/bar").replaceName("bar/baz/").getPathString()).isEqualTo("foo/bar/baz")

        // Absolute path arguments will clobber the original path.
        assertThat(create("foo/bar").replaceName("/absolute").getPathString()).isEqualTo("/absolute")
        assertThat(create("foo/bar").replaceName("/").getPathString()).isEqualTo("/")
    }

    @org.junit.Test
    fun testSubFragment() {
        assertThat(create("/foo/bar/baz").subFragment(0, 3).getPathString()).isEqualTo("/foo/bar/baz")
        assertThat(create("foo/bar/baz").subFragment(0, 3).getPathString()).isEqualTo("foo/bar/baz")
        assertThat(create("/foo/bar/baz").subFragment(0, 2).getPathString()).isEqualTo("/foo/bar")
        assertThat(create("/foo/bar/baz").subFragment(1, 3).getPathString()).isEqualTo("bar/baz")
        assertThat(create("/foo/bar/baz").subFragment(0, 1).getPathString()).isEqualTo("/foo")
        assertThat(create("/foo/bar/baz").subFragment(1, 2).getPathString()).isEqualTo("bar")
        assertThat(create("/foo/bar/baz").subFragment(2, 3).getPathString()).isEqualTo("baz")
        assertThat(create("/foo/bar/baz").subFragment(0, 0).getPathString()).isEqualTo("/")
        assertThat(create("foo/bar/baz").subFragment(0, 0).getPathString()).isEqualTo("")
        assertThat(create("foo/bar/baz").subFragment(1, 1).getPathString()).isEqualTo("")

        assertThat(create("/foo/bar/baz").subFragment(0).getPathString()).isEqualTo("/foo/bar/baz")
        assertThat(create("foo/bar/baz").subFragment(0).getPathString()).isEqualTo("foo/bar/baz")
        assertThat(create("/foo/bar/baz").subFragment(1).getPathString()).isEqualTo("bar/baz")
        assertThat(create("foo/bar/baz").subFragment(1).getPathString()).isEqualTo("bar/baz")
        assertThat(create("foo/bar/baz").subFragment(2).getPathString()).isEqualTo("baz")
        assertThat(create("foo/bar/baz").subFragment(3).getPathString()).isEqualTo("")

        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { create("foo/bar/baz").subFragment(3, 2) })
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { create("foo/bar/baz").subFragment(4, 4) })
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { create("foo/bar/baz").subFragment(3, 2) })
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { create("foo/bar/baz").subFragment(4) })
    }

    @org.junit.Test
    fun testStripComponents() {
        val pathFragmentStripZero: PathFragment = create("/foo/bar/baz")
        assertThat(pathFragmentStripZero.stripComponents(0)).isSameInstanceAs(pathFragmentStripZero)

        assertThat(create("/foo/bar/baz").stripComponents(0).getPathString()).isEqualTo("/foo/bar/baz")
        assertThat(create("/foo/bar/baz").stripComponents(1).getPathString()).isEqualTo("bar/baz")
        assertThat(create("/foo/bar/baz").stripComponents(2).getPathString()).isEqualTo("baz")
        assertThat(create("/foo/bar/baz").stripComponents(3).getPathString()).isEqualTo("")
        assertThat(create("/foo/bar/baz").stripComponents(4).getPathString()).isEqualTo("")

        val pathFragmentStripAll: PathFragment = create("/foo/bar/baz")
        assertThat(pathFragmentStripAll.stripComponents(3)).isSameInstanceAs(EMPTY_FRAGMENT)
        assertThat(pathFragmentStripAll.stripComponents(4)).isSameInstanceAs(EMPTY_FRAGMENT)

        val pathFragment: PathFragment = create("/foo/bar/baz")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { pathFragment.stripComponents(-2) })
    }

    @org.junit.Test
    fun testStartsWith() {
        val foobar: PathFragment = create("/foo/bar")
        val foobarRelative: PathFragment = create("foo/bar")

        // (path, prefix) => true
        assertThat(foobar.startsWith(foobar)).isTrue()
        assertThat(foobar.startsWith(create("/"))).isTrue()
        assertThat(foobar.startsWith(create("/foo"))).isTrue()
        assertThat(foobar.startsWith(create("/foo/"))).isTrue()
        assertThat(foobar.startsWith(create("/foo/bar/"))).isTrue() // Includes trailing slash.

        // (prefix, path) => false
        assertThat(create("/foo").startsWith(foobar)).isFalse()
        assertThat(create("/").startsWith(foobar)).isFalse()

        // (absolute, relative) => false
        assertThat(foobar.startsWith(foobarRelative)).isFalse()
        assertThat(foobarRelative.startsWith(foobar)).isFalse()

        // (relative path, relative prefix) => true
        assertThat(foobarRelative.startsWith(foobarRelative)).isTrue()
        assertThat(foobarRelative.startsWith(create("foo"))).isTrue()
        assertThat(foobarRelative.startsWith(create(""))).isTrue()

        // (path, sibling) => false
        assertThat(create("/foo/wiz").startsWith(foobar)).isFalse()
        assertThat(foobar.startsWith(create("/foo/wiz"))).isFalse()

        // (path, different case) => false
        assertThat(foobar.startsWith(create("/Foo/bar"))).isFalse()
        assertThat(foobar.startsWith(create("/Foo"))).isFalse()
        assertThat(create(unicodeToInternal("/ÄÖÜ/bar")).startsWith(create(unicodeToInternal("/äöü"))))
            .isFalse()
        assertThat(create(unicodeToInternal("ÄÖÜ/bar")).startsWith(create(unicodeToInternal("äöü"))))
            .isFalse()
    }

    @org.junit.Test
    fun testStartsWithIgnoringCase() {
        val foobar: PathFragment = create("/foo/bar")
        val foobarRelative: PathFragment = create("foo/bar")

        // (path, prefix) => true
        assertThat(foobar.startsWithIgnoringCase(foobar)).isTrue()
        assertThat(foobar.startsWithIgnoringCase(create("/"))).isTrue()
        assertThat(foobar.startsWithIgnoringCase(create("/foo"))).isTrue()
        assertThat(foobar.startsWithIgnoringCase(create("/foo/"))).isTrue()
        assertThat(foobar.startsWithIgnoringCase(create("/foo/bar/")))
            .isTrue() // Includes trailing slash.

        // (prefix, path) => false
        assertThat(create("/foo").startsWithIgnoringCase(foobar)).isFalse()
        assertThat(create("/").startsWithIgnoringCase(foobar)).isFalse()

        // (absolute, relative) => false
        assertThat(foobar.startsWithIgnoringCase(foobarRelative)).isFalse()
        assertThat(foobarRelative.startsWithIgnoringCase(foobar)).isFalse()

        // (relative path, relative prefix) => true
        assertThat(foobarRelative.startsWithIgnoringCase(foobarRelative)).isTrue()
        assertThat(foobarRelative.startsWithIgnoringCase(create("foo"))).isTrue()
        assertThat(foobarRelative.startsWithIgnoringCase(create(""))).isTrue()

        // (path, sibling) => false
        assertThat(create("/foo/wiz").startsWithIgnoringCase(foobar)).isFalse()
        assertThat(foobar.startsWithIgnoringCase(create("/foo/wiz"))).isFalse()

        // (path, different case) => false
        assertThat(foobar.startsWithIgnoringCase(create("/Foo/bar"))).isTrue()
        assertThat(foobar.startsWithIgnoringCase(create("/Foo"))).isTrue()
        assertThat(
            create(unicodeToInternal("/ÄÖÜ/bar"))
                .startsWithIgnoringCase(create(unicodeToInternal("/äöü")))
        )
            .isTrue()
        assertThat(
            create(unicodeToInternal("ÄÖÜ/bar"))
                .startsWithIgnoringCase(create(unicodeToInternal("äöü")))
        )
            .isTrue()
    }

    @org.junit.Test
    fun testCheckAllPathsStartWithButAreNotEqualTo() {
        // Check passes:
        PathFragment.checkAllPathsAreUnder(toPathsSet("a/b", "a/c"), create("a"))

        // Check trivially passes:
        PathFragment.checkAllPathsAreUnder(com.google.common.collect.ImmutableList.of<E?>(), create("a"))

        // Check fails when some path does not start with startingWithPath:
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                PathFragment.checkAllPathsAreUnder(
                    toPathsSet("a/b", "b/c"),
                    create("a")
                )
            })

        // Check fails when some path is equal to startingWithPath:
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                PathFragment.checkAllPathsAreUnder(
                    toPathsSet("a/b", "a"),
                    create("a")
                )
            })
    }

    @org.junit.Test
    fun testEndsWith() {
        val foobar: PathFragment = create("/foo/bar")
        val foobarRelative: PathFragment = create("foo/bar")

        // (path, suffix) => true
        assertThat(foobar.endsWith(foobar)).isTrue()
        assertThat(foobar.endsWith(create("bar"))).isTrue()
        assertThat(foobar.endsWith(create("foo/bar"))).isTrue()
        assertThat(foobar.endsWith(create("/foo/bar"))).isTrue()
        assertThat(foobar.endsWith(create("/bar"))).isFalse()

        // (prefix, path) => false
        assertThat(create("/foo").endsWith(foobar)).isFalse()
        assertThat(create("/").endsWith(foobar)).isFalse()

        // (suffix, path) => false
        assertThat(create("/bar").endsWith(foobar)).isFalse()
        assertThat(create("bar").endsWith(foobar)).isFalse()
        assertThat(create("").endsWith(foobar)).isFalse()

        // (absolute, relative) => true
        assertThat(foobar.endsWith(foobarRelative)).isTrue()

        // (relative, absolute) => false
        assertThat(foobarRelative.endsWith(foobar)).isFalse()

        // (relative path, relative prefix) => true
        assertThat(foobarRelative.endsWith(foobarRelative)).isTrue()
        assertThat(foobarRelative.endsWith(create("bar"))).isTrue()
        assertThat(foobarRelative.endsWith(create(""))).isTrue()

        // (path, sibling) => false
        assertThat(create("/foo/wiz").endsWith(foobar)).isFalse()
        assertThat(foobar.endsWith(create("/foo/wiz"))).isFalse()
    }

    @org.junit.Test
    fun testToRelative() {
        assertThat(create("/foo/bar").toRelative()).isEqualTo(create("foo/bar"))
        assertThat(create("/").toRelative()).isEqualTo(create(""))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("foo").toRelative() })
    }

    @org.junit.Test
    fun testGetDriveStr() {
        assertThat(create("/foo/bar").getDriveStr()).isEqualTo("/")
        assertThat(create("/").getDriveStr()).isEqualTo("/")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("foo").getDriveStr() })
    }

    @org.junit.Test
    fun testCompareTo() {
        val pathStrs: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "",
                "/",
                "foo",
                "/foo",
                "foo/bar",
                "foo.bar",
                "foo/bar.baz",
                "foo/bar/baz",
                "foo/barfile",
                "foo/Bar",
                "Foo/bar"
            )
        val paths: MutableList<PathFragment> = toPaths(pathStrs)
        // First test that compareTo is self-consistent.
        for (x in paths) {
            for (y in paths) {
                for (z in paths) {
                    // Anti-symmetry
                    Truth.assertThat(-1 * java.lang.Integer.signum(y.compareTo(x)))
                        .isEqualTo(java.lang.Integer.signum(x.compareTo(y)))
                    // Transitivity
                    if (x.compareTo(y) > 0 && y.compareTo(z) > 0) {
                        assertThat(x.compareTo(z)).isGreaterThan(0)
                    }
                    // "Substitutability"
                    if (x.compareTo(y) === 0) {
                        Truth.assertThat(java.lang.Integer.signum(y.compareTo(z)))
                            .isEqualTo(java.lang.Integer.signum(x.compareTo(z)))
                    }
                    // Consistency with equals
                    assertThat(x.equals(y)).isEqualTo((x.compareTo(y) === 0))
                }
            }
        }
        // Now test that compareTo does what we expect.  The exact ordering here doesn't matter much.
        Collections.shuffle(paths)
        Collections.sort<T?>(paths)
        val expectedOrder: MutableList<PathFragment> =
            toPaths(
                com.google.common.collect.ImmutableList.of<String?>(
                    "",
                    "/",
                    "/foo",
                    "Foo/bar",
                    "foo",
                    "foo.bar",
                    "foo/Bar",
                    "foo/bar",
                    "foo/bar.baz",
                    "foo/bar/baz",
                    "foo/barfile"
                )
            )
        Truth.assertThat(paths).isEqualTo(expectedOrder)
    }

    @org.junit.Test
    fun testHierarchicalComparator() {
        val pathStrs: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "",
                "/",
                "foo",
                "/foo",
                "foo/bar",
                "foo.bar",
                "foo/bar.baz",
                "foo/bar/baz",
                "foo/barfile",
                "foo/Bar",
                "Foo/bar"
            )
        val paths: MutableList<PathFragment> = toPaths(pathStrs)
        // First test that compareTo is self-consistent.
        for (x in paths) {
            for (y in paths) {
                for (z in paths) {
                    // Anti-symmetry
                    Truth.assertThat(-1 * java.lang.Integer.signum(HIERARCHICAL_COMPARATOR.compare(y, x)))
                        .isEqualTo(java.lang.Integer.signum(HIERARCHICAL_COMPARATOR.compare(x, y)))
                    // Transitivity
                    if (HIERARCHICAL_COMPARATOR.compare(x, y) > 0
                        && HIERARCHICAL_COMPARATOR.compare(y, z) > 0
                    ) {
                        assertThat(HIERARCHICAL_COMPARATOR.compare(x, z)).isGreaterThan(0)
                    }
                    // "Substitutability"
                    if (HIERARCHICAL_COMPARATOR.compare(x, y) === 0) {
                        Truth.assertThat(java.lang.Integer.signum(HIERARCHICAL_COMPARATOR.compare(y, z)))
                            .isEqualTo(java.lang.Integer.signum(HIERARCHICAL_COMPARATOR.compare(x, z)))
                    }
                    // Consistency with equals
                    assertThat(x.equals(y)).isEqualTo(HIERARCHICAL_COMPARATOR.compare(x, y) === 0)
                }
            }
        }
        // Now test that compareTo does what we expect.  The exact ordering here doesn't matter much.
        Collections.shuffle(paths)
        paths.sort(HIERARCHICAL_COMPARATOR)
        val expectedOrder: MutableList<PathFragment> =
            toPaths(
                com.google.common.collect.ImmutableList.of<String?>(
                    "",
                    "/",
                    "/foo",
                    "Foo/bar",
                    "foo",
                    "foo/Bar",
                    "foo/bar",
                    "foo/bar/baz",
                    "foo/bar.baz",
                    "foo/barfile",
                    "foo.bar"
                )
            )
        Truth.assertThat(paths).isEqualTo(expectedOrder)
    }

    @org.junit.Test
    fun testGetSafePathString() {
        assertThat(create("/").getSafePathString()).isEqualTo("/")
        assertThat(create("/abc").getSafePathString()).isEqualTo("/abc")
        assertThat(create("").getSafePathString()).isEqualTo(".")
        assertThat(PathFragment.EMPTY_FRAGMENT.getSafePathString()).isEqualTo(".")
        assertThat(create("abc/def").getSafePathString()).isEqualTo("abc/def")
    }

    @org.junit.Test
    fun testNormalize() {
        assertThat(create("/a/b")).isEqualTo(create("/a/b"))
        assertThat(create("/a/./b")).isEqualTo(create("/a/b"))
        assertThat(create("/a/../b")).isEqualTo(create("/b"))
        assertThat(create("a/b")).isEqualTo(create("a/b"))
        assertThat(create("a/../../b")).isEqualTo(create("../b"))
        assertThat(create("a/../..")).isEqualTo(create(".."))
        assertThat(create("a/../b")).isEqualTo(create("b"))
        assertThat(create("a/b/../b")).isEqualTo(create("a/b"))
        assertThat(create("/..")).isEqualTo(create("/.."))
        assertThat(create("..")).isEqualTo(create(".."))
    }

    @org.junit.Test
    fun testSegments() {
        assertThat(create("").segmentCount()).isEqualTo(0)
        assertThat(create("a").segmentCount()).isEqualTo(1)
        assertThat(create("a/b").segmentCount()).isEqualTo(2)
        assertThat(create("a/b/c").segmentCount()).isEqualTo(3)
        assertThat(create("/").segmentCount()).isEqualTo(0)
        assertThat(create("/a").segmentCount()).isEqualTo(1)
        assertThat(create("/a/b").segmentCount()).isEqualTo(2)
        assertThat(create("/a/b/c").segmentCount()).isEqualTo(3)

        assertThat(create("").splitToListOfSegments()).isEmpty()
        assertThat(create("a").splitToListOfSegments()).containsExactly("a").inOrder()
        assertThat(create("a/b").splitToListOfSegments()).containsExactly("a", "b").inOrder()
        assertThat(create("a/b/c").splitToListOfSegments()).containsExactly("a", "b", "c").inOrder()
        assertThat(create("/").splitToListOfSegments()).isEmpty()
        assertThat(create("/a").splitToListOfSegments()).containsExactly("a").inOrder()
        assertThat(create("/a/b").splitToListOfSegments()).containsExactly("a", "b").inOrder()
        assertThat(create("/a/b/c").splitToListOfSegments()).containsExactly("a", "b", "c").inOrder()

        assertThat(create("a").getSegment(0)).isEqualTo("a")
        assertThat(create("a/b").getSegment(0)).isEqualTo("a")
        assertThat(create("a/b").getSegment(1)).isEqualTo("b")
        assertThat(create("a/b/c").getSegment(2)).isEqualTo("c")
        assertThat(create("/a").getSegment(0)).isEqualTo("a")
        assertThat(create("/a/b").getSegment(0)).isEqualTo("a")
        assertThat(create("/a/b").getSegment(1)).isEqualTo("b")
        assertThat(create("/a/b/c").getSegment(2)).isEqualTo("c")

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").getSegment(0) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("a/b").getSegment(2) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            com.google.common.collect.ImmutableList.of<String?>(
                "",
                "a",
                "/foo",
                "foo/bar/baz",
                "/a/path/fragment/with/lots/of/parts"
            )
                .stream()
                .map<Any?>(PathFragment::create)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializationSimple() {
        checkSerialization("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializationAbsolute() {
        checkSerialization("/foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializationNested() {
        checkSerialization("foo/bar/baz")
    }

    @org.junit.Test
    fun containsUplevelReference_emptyPath_returnsFalse() {
        assertThat(EMPTY_FRAGMENT.containsUplevelReferences()).isFalse()
    }

    @org.junit.Test
    fun containsUplevelReference_uplevelOnlyPath_returnsTrue() {
        val pathFragment: PathFragment = create("..")
        assertThat(pathFragment.containsUplevelReferences()).isTrue()
    }

    @org.junit.Test
    fun containsUplevelReferences_firstSegmentStartingWithDotDot_returnsFalse() {
        val pathFragment: PathFragment = create("..file")
        assertThat(pathFragment.containsUplevelReferences()).isFalse()
    }

    @org.junit.Test
    fun containsUplevelReferences_startsWithUplevelReference_returnsTrue() {
        val pathFragment: PathFragment = create("../file")
        assertThat(pathFragment.containsUplevelReferences()).isTrue()
    }

    @org.junit.Test
    fun containsUplevelReferences_uplevelReferenceMidPath_normalizesAndReturnsFalse() {
        val pathFragment: PathFragment = create("a/../b")

        assertThat(pathFragment.containsUplevelReferences()).isFalse()
        assertThat(pathFragment.getPathString()).isEqualTo("b")
    }

    @org.junit.Test
    fun containsUplevelReferenes_uplevelReferenceMidGlobalPath_normalizesAndReturnsFalse() {
        val pathFragment: PathFragment = create("/dir1/dir2/../file")

        assertThat(pathFragment.containsUplevelReferences()).isFalse()
        assertThat(pathFragment.getPathString()).isEqualTo("/dir1/file")
    }

    companion object {
        private fun toPaths(strs: MutableList<String?>): MutableList<PathFragment> {
            val paths: MutableList<PathFragment> = java.util.ArrayList<PathFragment>()
            for (s in strs) {
                paths.add(create(s))
            }
            return paths
        }

        private fun toPathsSet(vararg strs: String?): com.google.common.collect.ImmutableSet<PathFragment?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
                com.google.common.collect.ImmutableSet.builder<PathFragment?>()
            for (str in strs) {
                builder.add(create(str))
            }
            return builder.build()
        }

        @Throws(java.lang.Exception::class)
        private fun checkSerialization(pathFragmentString: String?) {
            val a: PathFragment? = create(pathFragmentString)
            val a2: PathFragment? = RoundTripping.roundTrip(a)
            assertThat(a2).isEqualTo(a)
        }
    }
}
