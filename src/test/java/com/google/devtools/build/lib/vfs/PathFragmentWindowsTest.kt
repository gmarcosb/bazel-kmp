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

import com.google.devtools.build.lib.vfs.PathFragment.create

/**
 * This class tests the functionality of the PathFragment.
 */
@RunWith(JUnit4::class)
class PathFragmentWindowsTest {
    @org.junit.Test
    fun testWindowsSeparator() {
        assertThat(create("bar\\baz").toString()).isEqualTo("bar/baz")
        assertThat(create("c:\\bar\\baz").toString()).isEqualTo("C:/bar/baz")
    }

    @org.junit.Test
    fun testIsAbsoluteWindows() {
        assertThat(create("C:/").isAbsolute()).isTrue()
        assertThat(create("C:/").isAbsolute()).isTrue()
        assertThat(create("C:/foo").isAbsolute()).isTrue()
        assertThat(create("d:/foo/bar").isAbsolute()).isTrue()

        assertThat(create("*:/").isAbsolute()).isFalse()
    }

    @org.junit.Test
    fun testAbsoluteAndAbsoluteLookingPaths() {
        assertThat(create("/c").isAbsolute()).isTrue()
        assertThat(create("/c").segments()).containsExactly("c")

        assertThat(create("/c/").isAbsolute()).isTrue()
        assertThat(create("/c/").segments()).containsExactly("c")

        assertThat(create("C:/").isAbsolute()).isTrue()
        assertThat(create("C:/").segments()).isEmpty()

        val p5: PathFragment = create("/c:")
        assertThat(p5.isAbsolute()).isTrue()
        assertThat(p5.segments()).containsExactly("c:")
        assertThat(create("C:").isAbsolute()).isFalse()

        assertThat(create("/c:").isAbsolute()).isTrue()
        assertThat(create("/c:").segments()).containsExactly("c:")

        assertThat(create("/c")).isEqualTo(create("/c/"))
        assertThat(create("/c")).isNotEqualTo(create("C:/"))
        assertThat(create("/c")).isNotEqualTo(create("C:"))
        assertThat(create("/c")).isNotEqualTo(create("/c:"))
        assertThat(create("C:/")).isNotEqualTo(create("C:"))
        assertThat(create("C:/")).isNotEqualTo(create("/c:"))
    }

    @org.junit.Test
    fun testIsAbsoluteWindowsBackslash() {
        assertThat(create(java.io.File("C:\\blah").getPath()).isAbsolute()).isTrue()
        assertThat(create(java.io.File("C:\\").getPath()).isAbsolute()).isTrue()
        assertThat(create(java.io.File("\\blah").getPath()).isAbsolute()).isTrue()
        assertThat(create(java.io.File("\\").getPath()).isAbsolute()).isTrue()
    }

    @org.junit.Test
    fun testRootNodeReturnsRootStringWindows() {
        assertThat(create("C:/").getPathString()).isEqualTo("C:/")
    }

    @org.junit.Test
    fun testGetRelativeWindows() {
        assertThat(create("C:/a").getRelative("b").getPathString()).isEqualTo("C:/a/b")
        assertThat(create("C:/a/b").getRelative("c/d").getPathString()).isEqualTo("C:/a/b/c/d")
        assertThat(create("C:/a").getRelative("C:/b").getPathString()).isEqualTo("C:/b")
        assertThat(create("C:/a/b").getRelative("C:/c/d").getPathString()).isEqualTo("C:/c/d")
        assertThat(create("a").getRelative("C:/b").getPathString()).isEqualTo("C:/b")
        assertThat(create("a/b").getRelative("C:/c/d").getPathString()).isEqualTo("C:/c/d")
    }

    @org.junit.Test
    fun testGetRelativeMixed() {
        assertThat(create("a").getRelative("b")).isEqualTo(create("a/b"))
        assertThat(create("a").getRelative("/b")).isEqualTo(create("/b"))
        assertThat(create("a").getRelative("E:/b")).isEqualTo(create("E:/b"))

        assertThat(create("/a").getRelative("b")).isEqualTo(create("/a/b"))
        assertThat(create("/a").getRelative("/b")).isEqualTo(create("/b"))
        assertThat(create("/a").getRelative("E:/b")).isEqualTo(create("E:/b"))

        assertThat(create("D:/a").getRelative("b")).isEqualTo(create("D:/a/b"))
        assertThat(create("D:/a").getRelative("/b")).isEqualTo(create("/b"))
        assertThat(create("D:/a").getRelative("E:/b")).isEqualTo(create("E:/b"))
    }

    @org.junit.Test
    fun testRelativeTo() {
        assertThat(create("").relativeTo("").getPathString()).isEmpty()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").relativeTo("a") })

        assertThat(create("a").relativeTo("")).isEqualTo(create("a"))
        assertThat(create("a").relativeTo("a").getPathString()).isEmpty()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("a").relativeTo("b") })
        assertThat(create("a/b").relativeTo("a")).isEqualTo(create("b"))

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("C:/").relativeTo("") })
        assertThat(create("C:/").relativeTo("C:/").getPathString()).isEmpty()
    }

    @org.junit.Test
    fun testGetChildWorks() {
        assertThat(create("../some/path").getChild("hi")).isEqualTo(create("../some/path/hi"))
        assertThat(create("../some/path").getChild(".hi")).isEqualTo(create("../some/path/.hi"))
        assertThat(create("../some/path").getChild("..hi")).isEqualTo(create("../some/path/..hi"))
    }

    @org.junit.Test
    fun testGetChildRejectsInvalidBaseNames() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").getChild(".") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").getChild("..") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").getChild("multi/segment") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("").getChild("multi\\segment") })
    }

    @org.junit.Test
    fun testEmptyPathToEmptyPathWindows() {
        assertThat(create("C:/")).isEqualTo(create("C:/"))
    }

    @org.junit.Test
    fun testWindowsVolumeUppercase() {
        assertThat(create("C:/")).isEqualTo(create("c:/"))
    }

    @org.junit.Test
    fun testRedundantSlashesWindows() {
        assertThat(create("C:/")).isEqualTo(create("C:///"))
        assertThat(create("C:/foo/bar")).isEqualTo(create("C:/foo///bar"))
        assertThat(create("C:/foo/bar")).isEqualTo(create("C:////foo//bar"))
    }

    @org.junit.Test
    fun testSimpleNameToSimpleNameWindows() {
        assertThat(create("C:/foo")).isEqualTo(create("C:/foo"))
    }

    @org.junit.Test
    fun testStripsTrailingSlashWindows() {
        assertThat(create("C:/foo/bar")).isEqualTo(create("C:/foo/bar/"))
    }

    @org.junit.Test
    fun testGetParentDirectoryWindows() {
        assertThat(create("C:/foo/bar/wiz").getParentDirectory()).isEqualTo(create("C:/foo/bar"))
        assertThat(create("C:/foo/bar").getParentDirectory()).isEqualTo(create("C:/foo"))
        assertThat(create("C:/foo").getParentDirectory()).isEqualTo(create("C:/"))
        assertThat(create("C:/").getParentDirectory()).isNull()
    }

    @org.junit.Test
    fun testSegmentsCountWindows() {
        assertThat(create("C:/foo").segmentCount()).isEqualTo(1)
        assertThat(create("C:/").segmentCount()).isEqualTo(0)
        // Mix usage of Windows and Unix separator is valid
        assertThat(create("C:/foo\\bar").segmentCount()).isEqualTo(2)
        assertThat(create("C:\\foo\\bar/baz").segmentCount()).isEqualTo(3)
    }

    @org.junit.Test
    fun testGetSegmentWindows() {
        assertThat(create("C:/foo/bar").getSegment(0)).isEqualTo("foo")
        assertThat(create("C:/foo/bar").getSegment(1)).isEqualTo("bar")
        assertThat(create("C:/foo/").getSegment(0)).isEqualTo("foo")
        assertThat(create("C:/foo").getSegment(0)).isEqualTo("foo")
        // Mix usage of Windows and Unix separator is valid
        assertThat(create("C:/foo\\bar").getSegment(0)).isEqualTo("foo")
        assertThat(create("C:/foo\\bar").getSegment(1)).isEqualTo("bar")
        assertThat(create("C:\\foo\\bar/baz").getSegment(0)).isEqualTo("foo")
        assertThat(create("C:\\foo\\bar/baz").getSegment(1)).isEqualTo("bar")
        assertThat(create("C:\\foo\\bar/baz").getSegment(2)).isEqualTo("baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasenameWindows() {
        assertThat(create("C:/foo/bar").getBaseName()).isEqualTo("bar")
        assertThat(create("C:/foo").getBaseName()).isEqualTo("foo")
        // Never return the drive name as a basename.
        assertThat(create("C:/").getBaseName()).isEmpty()
        // Mix usage of Windows and Unix separator is valid
        assertThat(create("C:/foo\\bar").getBaseName()).isEqualTo("bar")
        assertThat(create("C:\\foo\\bar/baz").getBaseName()).isEqualTo("baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReplaceNameWindows() {
        assertThat(create("C:/foo/bar").replaceName("baz").getPathString()).isEqualTo("C:/foo/baz")
        assertThat(create("C:/").replaceName("baz")).isNull()
    }

    @org.junit.Test
    fun testStartsWithWindows() {
        assertThat(create("C:/foo/bar").startsWith(create("C:/foo"))).isTrue()
        assertThat(create("C:/foo/bar").startsWith(create("C:/"))).isTrue()
        assertThat(create("C:/").startsWith(create("C:/"))).isTrue()

        // The first path is absolute, the second is not.
        assertThat(create("C:/foo/bar").startsWith(create("C:"))).isFalse()
        assertThat(create("C:/").startsWith(create("C:"))).isFalse()
    }

    @org.junit.Test
    fun testEndsWithWindows() {
        assertThat(create("C:/foo/bar").endsWith(create("bar"))).isTrue()
        assertThat(create("C:/foo/bar").endsWith(create("foo/bar"))).isTrue()
        assertThat(create("C:/foo/bar").endsWith(create("C:/foo/bar"))).isTrue()
        assertThat(create("C:/").endsWith(create("C:/"))).isTrue()
    }

    @org.junit.Test
    fun testGetSafePathStringWindows() {
        assertThat(create("C:/").getSafePathString()).isEqualTo("C:/")
        assertThat(create("C:/abc").getSafePathString()).isEqualTo("C:/abc")
        assertThat(create("C:/abc/def").getSafePathString()).isEqualTo("C:/abc/def")
    }

    @org.junit.Test
    fun testNormalizeWindows() {
        assertThat(create("C:/a/b")).isEqualTo(create("C:/a/b"))
        assertThat(create("C:/a/./b")).isEqualTo(create("C:/a/b"))
        assertThat(create("C:/a/../b")).isEqualTo(create("C:/b"))
        assertThat(create("C:/../b")).isEqualTo(create("C:/../b"))
    }

    @org.junit.Test
    fun testWindowsDriveRelativePaths() {
        // On Windows, paths that look like "C:foo" mean "foo relative to the current directory
        // of drive C:\".
        // Bazel doesn't resolve such paths, and just takes them literally like normal path segments.
        // If the user attempts to open files under such paths, the file system API will give an error.
        assertThat(create("C:").isAbsolute()).isFalse()
        assertThat(create("C:").segments()).containsExactly("C:")
    }

    @org.junit.Test
    fun testToRelative() {
        assertThat(create("C:/foo/bar").toRelative()).isEqualTo(create("foo/bar"))
        assertThat(create("C:/").toRelative()).isEqualTo(create(""))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("foo").toRelative() })
    }

    @org.junit.Test
    fun testGetDriveStr() {
        assertThat(create("C:/foo/bar").getDriveStr()).isEqualTo("C:/")
        assertThat(create("C:/").getDriveStr()).isEqualTo("C:/")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("foo").getDriveStr() })
    }
}
