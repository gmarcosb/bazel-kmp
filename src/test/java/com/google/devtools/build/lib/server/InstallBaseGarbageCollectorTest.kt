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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.server.InstallBaseGarbageCollector.DELETED_SUFFIX
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.Test
import java.time.Duration

/** Tests for [InstallBaseGarbageCollector].  */
@RunWith(JUnit4::class)
class InstallBaseGarbageCollectorTest {
    private var rootDir: Path? = null
    private var ownInstallBase: Path? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        rootDir = TestUtils.createUniqueTmpDir(null)
        ownInstallBase = createSubdirectory(OWN_MD5)
    }

    @Test
    @Throws(Exception::class)
    fun onlyOwnInstallBase_notCollected() {
        run(Duration.ZERO)

        assertDirectoryContents(OWN_MD5)
    }

    @Test
    @Throws(Exception::class)
    fun otherInstallBase_notStaleAndUnlocked_notCollected() {
        val otherInstallBase: Path = createSubdirectory(OTHER_MD5)
        setAge(otherInstallBase, Duration.ofDays(1))

        run(Duration.ofDays(2))

        assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX)
    }

    @Test
    @Throws(Exception::class)
    fun otherInstallBase_notStaleAndLocked_notCollected() {
        val otherInstallBase: Path = createSubdirectory(OTHER_MD5)
        setAge(otherInstallBase, Duration.ofDays(1))

        ExternalFileSystemLock.getShared(rootDir.getChild(OTHER_MD5 + LOCK_SUFFIX)).use { lock ->
            run(Duration.ofDays(2))
        }
        assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX)
    }

    @Test
    @Throws(Exception::class)
    fun otherInstallBase_staleAndUnlocked_collected() {
        val otherInstallBase: Path = createSubdirectory(OTHER_MD5)
        setAge(otherInstallBase, Duration.ofDays(3))

        run(Duration.ofDays(2))

        assertDirectoryContents(OWN_MD5)
    }

    @Test
    @Throws(Exception::class)
    fun otherInstallBase_staleAndLocked_notCollected() {
        val otherInstallBase: Path = createSubdirectory(OTHER_MD5)
        setAge(otherInstallBase, Duration.ofDays(3))

        ExternalFileSystemLock.getShared(rootDir.getChild(OTHER_MD5 + LOCK_SUFFIX)).use { lock ->
            run(Duration.ofDays(2))
        }
        assertDirectoryContents(OWN_MD5, OTHER_MD5, OTHER_MD5 + LOCK_SUFFIX)
    }

    @Test
    @Throws(Exception::class)
    fun incompleteDeletion_collected() {
        val incompleteDeletion: Path = createSubdirectory(OTHER_MD5 + DELETED_SUFFIX)
        setAge(incompleteDeletion, Duration.ofDays(2))

        run(Duration.ofDays(1))

        assertDirectoryContents(OWN_MD5)
    }

    @Test
    @Throws(Exception::class)
    fun otherFilesAndDirectories_notCollected() {
        val otherFile: Path = rootDir.getChild("file")
        FileSystemUtils.writeContentAsLatin1(otherFile, "content")
        setAge(otherFile, Duration.ofDays(2))
        val otherDir: Path = rootDir.getChild("dir")
        otherDir.createDirectoryAndParents()
        setAge(otherDir, Duration.ofDays(2))
        val otherSymlink: Path = rootDir.getChild("symlink")
        otherSymlink.createSymbolicLink(PathFragment.create(OWN_MD5))

        run(Duration.ofDays(1))

        assertDirectoryContents(OWN_MD5, "file", "dir", "symlink")
    }

    @Throws(IOException::class)
    private fun createSubdirectory(name: String?): Path {
        val dir: Path = rootDir.getChild(name)
        val file: Path? = dir.getChild("file")
        val subdir: Path = dir.getChild("subdir")
        val subfile: Path? = subdir.getChild("file")
        dir.createDirectoryAndParents()
        subdir.createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(file, "content")
        FileSystemUtils.writeContentAsLatin1(subfile, "content")

        return dir
    }

    @Throws(IOException::class)
    private fun setAge(path: Path, age: Duration) {
        path.setLastModifiedTime(Instant.now().minus(age).toEpochMilli())
    }

    @Throws(Exception::class)
    private fun run(maxAge: Duration?) {
        InstallBaseGarbageCollector(rootDir, ownInstallBase, maxAge).run()
    }

    @Throws(Exception::class)
    private fun assertDirectoryContents(vararg expected: Any?) {
        assertThat(rootDir.getDirectoryEntries().stream().map(Path::getBaseName))
            .containsExactly(expected)
    }

    companion object {
        private const val OWN_MD5 = "012345678901234567890123456789012"
        private const val OTHER_MD5 = "abcdefabcdefabcdefabcdefabcdefab"
    }
}
