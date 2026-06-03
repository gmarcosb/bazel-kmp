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

import com.google.devtools.build.lib.actions.FileArtifactValue.createForTesting

/** Tests for [FileArtifactValue].  */
@RunWith(JUnit4::class)
class FileArtifactValueTest {
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()
    private val fs: FileSystem = InMemoryFileSystem(clock, DigestHashFunction.SHA256)

    @Throws(IOException::class)
    private fun scratchFile(name: String?, mtime: Long, content: String?): Path {
        return scratchFile(name, mtime, content, fs)
    }

    @Throws(IOException::class)
    private fun scratchFile(name: String?, mtime: Long, content: String?, fileSystem: FileSystem): Path {
        val path: Path = fileSystem.getPath(name)
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(path, content)
        path.setLastModifiedTime(mtime)
        return path
    }

    @Throws(IOException::class)
    private fun scratchDir(name: String?, mtime: Long): Path {
        val path: Path = fs.getPath(name)
        path.createDirectoryAndParents()
        path.setLastModifiedTime(mtime)
        return path
    }

    @Throws(IOException::class)
    private fun scratchSymlink(name: String?, targetPath: String?): Path {
        val path: Path = fs.getPath(name)
        path.getParentDirectory().createDirectoryAndParents()
        path.createSymbolicLink(PathFragment.create(targetPath))
        return path
    }

    @org.junit.Test
    fun testEqualsAndHashCode() {
        // Each "equality group" is checked for equality within itself (including hashCode equality)
        // and inequality with members of other equality groups.
        EqualsTester()
            .addEqualityGroup(
                FileArtifactValue.createForNormalFile(
                    toBytes("00112233445566778899AABBCCDDEEFF"),  /* proxy= */null, 1L
                ),
                FileArtifactValue.createForNormalFile(
                    toBytes("00112233445566778899AABBCCDDEEFF"),  /* proxy= */null, 1L
                )
            )
            .addEqualityGroup(
                FileArtifactValue.createForNormalFile(
                    toBytes("00112233445566778899AABBCCDDEEFF"),  /* proxy= */null, 2L
                )
            )
            .addEqualityGroup(FileArtifactValue.createForDirectoryWithMtime(1))
            .addEqualityGroup(
                FileArtifactValue.createForNormalFile(
                    toBytes("FFFFFF00000000000000000000000000"),  /* proxy= */null, 1L
                )
            )
            .addEqualityGroup(
                FileArtifactValue.createForDirectoryWithMtime(2),
                FileArtifactValue.createForDirectoryWithMtime(2)
            )
            .addEqualityGroup( // expireAtEpochMilli doesn't contribute to the equality
                FileArtifactValue.createForRemoteFileWithMaterializationData(
                    toBytes("00112233445566778899AABBCCDDEEFF"),  /* size= */
                    1,  /* locationIndex= */
                    1,  /* expirationTime= */
                    Instant.ofEpochMilli(1)
                ),
                FileArtifactValue.createForRemoteFileWithMaterializationData(
                    toBytes("00112233445566778899AABBCCDDEEFF"),  /* size= */
                    1,  /* locationIndex= */
                    1,  /* expirationTime= */
                    Instant.ofEpochMilli(2)
                )
            )
            .addEqualityGroup( // A ResolvedSymlinkArtifactValue is not equal to the FileArtifactValue it wraps.
                FileArtifactValue.createFromExistingWithResolvedPath(
                    FileArtifactValue.createForNormalFile(
                        toBytes("00112233445566778899AABBCCDDEEFF"),  /* proxy= */null, 1L
                    ),
                    PathFragment.create("/some/path")
                )
            )
            .addEqualityGroup(FileArtifactValue.MISSING_FILE_MARKER)
            .addEqualityGroup(FileArtifactValue.RUNFILES_TREE_MARKER)
            .addEqualityGroup("a string")
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEquality() {
        val path1: Path = scratchFile("/dir/artifact1", 0L, "content")
        val path2: Path = scratchFile("/dir/artifact2", 0L, "content")
        val digestPath: Path = scratchFile("/dir/diffDigest", 0L, "1234567")
        val mtimePath: Path = scratchFile("/dir/diffMtime", 1L, "content")

        val empty1: Path = scratchFile("/dir/empty1", 0L, "")
        val empty2: Path = scratchFile("/dir/empty2", 1L, "")
        val empty3: Path = scratchFile("/dir/empty3", 1L, "")

        val dir1: Path = scratchDir("/dir1", 0L)
        val dir2: Path = scratchDir("/dir2", 1L)
        val dir3: Path = scratchDir("/dir3", 1L)

        EqualsTester() // We check for ctime and inode equality for paths.
            .addEqualityGroup(createForTesting(path1))
            .addEqualityGroup(createForTesting(path2))
            .addEqualityGroup(createForTesting(mtimePath))
            .addEqualityGroup(createForTesting(digestPath))
            .addEqualityGroup(createForTesting(empty1))
            .addEqualityGroup(createForTesting(empty2))
            .addEqualityGroup(createForTesting(empty3)) // We check for mtime equality for directories.
            .addEqualityGroup(createForTesting(dir1))
            .addEqualityGroup(
                createForTesting(dir2),
                createForTesting(dir3)
            ) // A ResolvedSymlinkArtifactValue is not equal to the FileArtifactValue it wraps.
            .addEqualityGroup(
                FileArtifactValue.createFromExistingWithResolvedPath(
                    createForTesting(path1), PathFragment.create("/some/path")
                )
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCtimeInEquality() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val before: FileArtifactValue? = createForTesting(path)
        clock.advanceMillis(1)
        path.chmod(511)
        val after: FileArtifactValue? = createForTesting(path)
        assertThat(before).isNotEqualTo(after)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoMtimeIfNonemptyFile() {
        val path: Path = scratchFile("/root/non-empty", 1L, "abc")
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.getDigest()).isEqualTo(path.getDigest())
        assertThat(value.getSize()).isEqualTo(3L)
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            "mtime for non-empty file should not be stored.",
            java.lang.UnsupportedOperationException::class.java,
            value::getModifiedTime
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectory() {
        val path: Path = scratchDir("/dir",  /* mtime= */1L)
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.getDigest()).isNull()
        assertThat(value.getModifiedTime()).isEqualTo(1L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvedSymlink() {
        val path: Path = scratchSymlink("/sym", "/some/path")
        val value: FileArtifactValue = FileArtifactValue.createForUnresolvedSymlink(path)
        val value2: FileArtifactValue? = FileArtifactValue.createForUnresolvedSymlink(path)
        assertThat(value.getType()).isEqualTo(FileStateType.SYMLINK)
        assertThat(value.getUnresolvedSymlinkTarget()).isEqualTo("/some/path")
        EqualsTester().addEqualityGroup(value, value2).testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolvedSymlinkToFile() {
        val path: Path = scratchFile("/file",  /* mtime= */1L, "content")
        val delegate: FileArtifactValue = FileArtifactValue.createForTesting(path)
        val value: FileArtifactValue =
            FileArtifactValue.createFromExistingWithResolvedPath(
                delegate, PathFragment.create("/file")
            )
        assertThat(value.getType()).isEqualTo(FileStateType.REGULAR_FILE)
        assertThat(value.getResolvedPath()).isEqualTo(PathFragment.create("/file"))
        assertThat(value.getDigest()).isEqualTo(delegate.getDigest())
        assertThat(value.getSize()).isEqualTo(delegate.getSize())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolvedSymlinkToDirectory() {
        val path: Path = scratchDir("/dir",  /* mtime= */1L)
        val delegate: FileArtifactValue = FileArtifactValue.createForTesting(path)
        val value: FileArtifactValue =
            FileArtifactValue.createFromExistingWithResolvedPath(
                delegate, PathFragment.create("/file")
            )
        assertThat(value.getType()).isEqualTo(FileStateType.DIRECTORY)
        assertThat(value.getResolvedPath()).isEqualTo(PathFragment.create("/file"))
        assertThat(value.getModifiedTime()).isEqualTo(delegate.getModifiedTime())
    }

    // Empty files are the same as normal files -- mtime is not stored.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyFile() {
        val path: Path = scratchFile("/root/empty", 1L, "")
        path.setLastModifiedTime(1L)
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.getDigest()).isEqualTo(path.getDigest())
        assertThat(value.getSize()).isEqualTo(0L)
        org.junit.Assert.assertThrows<java.lang.UnsupportedOperationException?>(
            "mtime for non-empty file should not be stored.",
            java.lang.UnsupportedOperationException::class.java,
            value::getModifiedTime
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIOException() {
        val exception: IOException = IOException("beep")
        val fs: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                @Throws(IOException::class)
                public override fun getDigest(path: PathFragment?): ByteArray? {
                    throw exception
                }

                @Throws(IOException::class)
                public override fun getFastDigest(path: PathFragment?): ByteArray? {
                    throw exception
                }
            }
        val path: Path = fs.getPath("/some/path")
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(path, "content")
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { createForTesting(path) })
        Truth.assertThat(e).isSameInstanceAs(exception)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeMetadata() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        path.setLastModifiedTime(123)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeContent() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        FileSystemUtils.writeContentAsLatin1(path, "new content")
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeContentAndResetMetadata() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        FileSystemUtils.writeContentAsLatin1(path, "new content")
        path.setLastModifiedTime(0)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeContentAndResetMetadataWithMatchingSize() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        FileSystemUtils.writeContentAsLatin1(path, "cOntent")
        path.setLastModifiedTime(0)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeContentAndReset() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        FileSystemUtils.writeContentAsLatin1(path, "new content")
        FileSystemUtils.writeContentAsLatin1(path, "content")
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckChangeContentAndResetIncludingMetadata() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        FileSystemUtils.writeContentAsLatin1(path, "new content")
        FileSystemUtils.writeContentAsLatin1(path, "content")
        path.setLastModifiedTime(0)
        // This is not necessarily the intended behavior, but a consequence of the need to avoid
        // false positives due to hard link creation/deletion. "Breaking" this test in the future while
        // preserving the behavior on other test cases would thus be a welcome change.
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckReplaceWithMove() {
        TruthJUnit.assume()
            .that<com.google.devtools.build.lib.util.OS?>(com.google.devtools.build.lib.util.OS.getCurrent())
            .isNotEqualTo(com.google.devtools.build.lib.util.OS.WINDOWS)

        // Use the real file system as the semantics of moves are subtle and not necessarily fully
        // captured by the in-memory file system.
        val realFs: UnixFileSystem =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )
        val tempDirJvm: Path = java.nio.file.Files.createTempDirectory(null)
        tempDirJvm.toFile().deleteOnExit()

        val path: Path = scratchFile(tempDirJvm.toString() + "/dir/artifact1", 0L, "content", realFs)
        val newPath: Path = scratchFile(tempDirJvm.toString() + "/dir/artifact2", 0L, "new content", realFs)
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        newPath.renameTo(path)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckCreateHardlink() {
        TruthJUnit.assume()
            .that<com.google.devtools.build.lib.util.OS?>(com.google.devtools.build.lib.util.OS.getCurrent())
            .isNotEqualTo(com.google.devtools.build.lib.util.OS.WINDOWS)

        // Use the real file system as the semantics of hard links are subtle and not necessarily
        // fully captured by the in-memory file system.
        val realFs: UnixFileSystem =
            UnixFileSystem(
                DigestHashFunction.SHA256,  /* hashAttributeName= */
                "",
                NativePosixFilesServiceImpl()
            )
        val tempDirJvm: Path = java.nio.file.Files.createTempDirectory(null)
        tempDirJvm.toFile().deleteOnExit()

        val path: Path = scratchFile(tempDirJvm.toString() + "/dir/artifact1", 0L, "content", realFs)
        val value: FileArtifactValue = createForTesting(path)
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        path.createHardLink(realFs.getPath(tempDirJvm.toString() + "/dir/artifact1_link"))
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckDeleteFile() {
        val path: Path = scratchFile("/dir/artifact1", 0L, "content")
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        path.delete()
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateCheckDirectory() {
        // For now, we don't attempt to detect changes to directories.
        val path: Path = scratchDir("/dir", 0L)
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        path.delete()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateChangeFileToDirectory() {
        // For now, we don't attempt to detect changes to directories.
        val path: Path = scratchFile("/dir/file", 0L, "")
        val value: FileArtifactValue = createForTesting(path)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()
        // If we only check ctime, then we need to change the clock here, or we get a ctime match on the
        // stat.
        path.delete()
        path.createDirectoryAndParents()
        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUptodateUnresolvedSymlink() {
        val path: Path = fs.getPath("/dir/symlink")
        path.getParentDirectory().createDirectoryAndParents()
        path.createSymbolicLink(PathFragment.create("target_path"))
        val value: FileArtifactValue = FileArtifactValue.createForUnresolvedSymlink(path)

        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isFalse()

        path.delete()
        path.createSymbolicLink(PathFragment.create("modified_target_path"))

        clock.advanceMillis(1)
        assertThat(value.wasModifiedSinceDigest(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerprint_equalByDigest() {
        val value1: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchFile("/dir/file1",  /* mtime= */1, "content"))
        val value2: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchFile("/dir/file2",  /* mtime= */2, "content"))
        val fingerprint1: Fingerprint = Fingerprint()
        val fingerprint2: Fingerprint = Fingerprint()

        value1.addTo(fingerprint1)
        value2.addTo(fingerprint2)

        assertThat(value1.getDigest()).isNotNull()
        assertThat(value2.getDigest()).isNotNull()
        assertThat(fingerprint1.digestAndReset()).isEqualTo(fingerprint2.digestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerprint_notEqualByDigest() {
        val value1: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchFile("/dir/file1",  /* mtime= */1, "content1"))
        val value2: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchFile("/dir/file2",  /* mtime= */1, "content2"))
        val fingerprint1: Fingerprint = Fingerprint()
        val fingerprint2: Fingerprint = Fingerprint()

        value1.addTo(fingerprint1)
        value2.addTo(fingerprint2)

        assertThat(value1.getDigest()).isNotNull()
        assertThat(value2.getDigest()).isNotNull()
        assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerprint_equalByMtime() {
        val value1: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchDir("/dir1",  /* mtime= */1))
        val value2: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchDir("/dir2",  /* mtime= */1))
        val fingerprint1: Fingerprint = Fingerprint()
        val fingerprint2: Fingerprint = Fingerprint()

        value1.addTo(fingerprint1)
        value2.addTo(fingerprint2)

        assertThat(value1.getDigest()).isNull()
        assertThat(value2.getDigest()).isNull()
        assertThat(fingerprint1.digestAndReset()).isEqualTo(fingerprint2.digestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerprint_notEqualByMtime() {
        val value1: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchDir("/dir1",  /* mtime= */1))
        val value2: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchDir("/dir2",  /* mtime= */2))
        val fingerprint1: Fingerprint = Fingerprint()
        val fingerprint2: Fingerprint = Fingerprint()

        value1.addTo(fingerprint1)
        value2.addTo(fingerprint2)

        assertThat(value1.getDigest()).isNull()
        assertThat(value2.getDigest()).isNull()
        assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerprint_fileWithDigestNotEqualToFileWithOnlyMtime() {
        val value1: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchDir("/dir",  /* mtime= */1))
        val value2: FileArtifactValue =
            FileArtifactValue.createForTesting(scratchFile("/dir/file",  /* mtime= */1, "contents"))
        val fingerprint1: Fingerprint = Fingerprint()
        val fingerprint2: Fingerprint = Fingerprint()

        value1.addTo(fingerprint1)
        value2.addTo(fingerprint2)

        assertThat(value1.getDigest()).isNull()
        assertThat(value2.getDigest()).isNotNull()
        assertThat(fingerprint1.digestAndReset()).isNotEqualTo(fingerprint2.digestAndReset())
    }

    companion object {
        private fun toBytes(hex: String): ByteArray {
            return com.google.common.io.BaseEncoding.base16().upperCase().decode(hex)
        }
    }
}
