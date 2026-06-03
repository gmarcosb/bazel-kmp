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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/**
 * This tests how canonical paths and non-canonical paths are equal with each
 * other, and also how paths from different filesystems behave with each other.
 */
@RunWith(JUnit4::class)
class UnixPathEqualityTest {
    private var otherUnixFs: FileSystem? = null
    private var unixFs: FileSystem? = null

    @Before
    fun initializeFileSystem() {
        unixFs =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )
        otherUnixFs =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )
        Truth.assertThat(unixFs !== otherUnixFs).isTrue()
    }

    private fun assertTwoWayEquals(obj1: Any?, obj2: Any?) {
        Truth.assertThat(obj1).isEqualTo(obj2)
        EqualsTester().addEqualityGroup(obj1, obj2).testEquals()
    }

    private fun assertTwoWayNotEquals(obj1: Any, obj2: Any) {
        Truth.assertThat(obj1 == obj2).isFalse()
        Truth.assertThat(obj2 == obj1).isFalse()
    }

    @Test
    fun testPathsAreEqualEvenIfNotCanonical() {
        // This path is already canonical, so there's no difference between
        // the canonical / nonCanonical path, as far as equals is concerned
        val nonCanonical: Path? = unixFs.getPath("/a/canonical/unix/path")
        val canonical: Path? = unixFs.getPath("/a/canonical/unix/path")
        assertTwoWayEquals(nonCanonical, canonical)
    }

    @Test
    fun testPathsAreNeverEqualWithStrings() {
        // Make sure that paths aren't equal to plain old strings
        val nonCanonical: Path = unixFs.getPath("/a/non/../canonical/unix/path")
        val canonical: Path = unixFs.getPath("/a/non/../canonical/unix/path")
        assertTwoWayNotEquals(nonCanonical, "/a/non/../canonical/unix/path")
        assertTwoWayNotEquals(canonical, "/a/non/../canonical/unix/path")
    }

    @Test
    fun testCanonicalPathsFromDifferentFileSystemsAreNeverEqual() {
        val canonical: Path = unixFs.getPath("/canonical/path")
        val otherCanonical: Path = otherUnixFs.getPath("/canonical/path")
        assertTwoWayNotEquals(canonical, otherCanonical)
    }

    @Test
    fun testNonCanonicalPathsFromDifferentFileSystemsAreNeverEqual() {
        val nonCanonical: Path = unixFs.getPath("/non/canonical/path")
        val otherNonCanonical: Path = otherUnixFs.getPath("/non/canonical/path")
        assertTwoWayNotEquals(nonCanonical, otherNonCanonical)
    }

    @Test
    fun testCrossFilesystemStartsWithReturnsFalse() {
        assertThat(unixFs.getPath("/a").startsWith(otherUnixFs.getPath("/b"))).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testCrossFilesystemOperationsForbidden() {
        val a: Path = unixFs.getPath("/a")
        val b: Path? = otherUnixFs.getPath("/b")

        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { a.renameTo(b) })
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { a.relativeTo(b) })
        Assert.assertThrows<IllegalArgumentException?>(
            IllegalArgumentException::class.java,
            ThrowingRunnable { a.createSymbolicLink(b) })
    }
}
