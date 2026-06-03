// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.FileSystemUtils.createEmptyFile

/** Integration test for invalidation of actions that consume source directories.  */
@RunWith(JUnit4::class)
class SourceDirectoryIntegrationTest : BuildIntegrationTestCase() {
    private var sourceDir: Path? = null

    override fun additionalEventsToCollect(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?> {
        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(com.google.devtools.build.lib.events.EventKind.FINISH)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUpGenrule() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["dir"],
            outs = ["foo.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        sourceDir = getWorkspace().getRelative("foo/dir")
        sourceDir.createDirectoryAndParents()
        writeIsoLatin1(sourceDir.getRelative("file1"), "content")
        writeIsoLatin1(sourceDir.getRelative("file2"), "content")
        writeIsoLatin1(sourceDir.getRelative("file3"), "other content")
        sourceDir.getRelative("symlink").createSymbolicLink(PathFragment.create("file3"))
        sourceDir
            .getRelative("dangling_symlink")
            .createSymbolicLink(PathFragment.create("does_not_exist"))

        val subDir: Path = sourceDir.getRelative("subdir")
        subDir.createDirectory()
        writeIsoLatin1(subDir.getRelative("file1"), "content")
        writeIsoLatin1(subDir.getRelative("file2"), "content")
        writeIsoLatin1(subDir.getRelative("file3"), "other content")
        subDir.getRelative("symlink").createSymbolicLink(PathFragment.create("file3"))
        subDir
            .getRelative("dangling_symlink")
            .createSymbolicLink(PathFragment.create("does_not_exist"))

        subDir.getRelative("nested").createDirectory()
        subDir.getRelative("nested2").createDirectory()
        subDir.getRelative("nested_non_empty").createDirectory()
        writeIsoLatin1(subDir.getRelative("nested_non_empty/file1"), "content")

        buildTarget("//foo")
        assertContainsEvent(events.collector(), "Executing genrule //foo:foo")

        events.collector().clear()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nothingModified_doesNotInvalidateAction() {
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun touched_doesNotInvalidateAction() {
        touchFile(sourceDir)
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelFileTouched_doesNotInvalidateAction() {
        touchFile(sourceDir.getRelative("file1"))
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelDirTouched_doesNotInvalidateAction() {
        touchFile(sourceDir.getRelative("subdir"))
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedFileTouched_doesNotInvalidateAction() {
        touchFile(sourceDir.getRelative("subdir/file1"))
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedDirTouched_doesNotInvalidateAction() {
        sourceDir.getRelative("subdir/nested").setLastModifiedTime(Path.NOW_SENTINEL_TIME)
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelFileDeleted_invalidatesAction() {
        sourceDir.getRelative("file1").delete()
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedFileDeleted_invalidatesAction() {
        sourceDir.getRelative("subdir/file1").delete()
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelFileModified_invalidatesAction() {
        writeIsoLatin1(sourceDir.getRelative("file1"), "modified content")
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedFileModified_invalidatesAction() {
        writeIsoLatin1(sourceDir.getRelative("subdir/file1"), "modified content")
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelFileAdded_invalidatesAction() {
        writeIsoLatin1(sourceDir.getRelative("new_file"), "modified content")
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nestedFileAdded_invalidatesAction() {
        writeIsoLatin1(sourceDir.getRelative("subdir/new_file"), "modified content")
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDirAdded_invalidatesAction() {
        sourceDir.getRelative("subdir/nested3").createDirectory()
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDirDeleted_invalidatesAction() {
        sourceDir.getRelative("subdir/nested").delete()
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDirReplacedWithEmptyFile_invalidatesAction() {
        val dir: Path = sourceDir.getRelative("subdir/nested")
        dir.delete()
        createEmptyFile(dir)
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileAddedToEmptyDir_invalidatesAction() {
        createEmptyFile(sourceDir.getRelative("subdir/nested/file1"))
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileReplacedByIdenticalSymlink_doesNotInvalidateAction() {
        val file: Path = sourceDir.getRelative("file1")
        file.delete()
        file.createSymbolicLink(sourceDir.getRelative("file2"))
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileReplacedByDifferentSymlink_invalidatesAction() {
        val file: Path = sourceDir.getRelative("file1")
        file.delete()
        file.createSymbolicLink(sourceDir.getRelative("file3"))
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Ignore("TODO(#25834)")
    @Throws(java.lang.Exception::class)
    fun emptyDirReplacedWithIdenticalSymlink_doesNotInvalidateAction() {
        val dir: Path = sourceDir.getRelative("subdir/nested2")
        dir.delete()
        dir.createSymbolicLink(PathFragment.create("nested"))
        assertNotInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyDirReplacedWithDifferentSymlink_invalidatesAction() {
        val dir: Path = sourceDir.getRelative("subdir/nested2")
        dir.delete()
        dir.createSymbolicLink(PathFragment.create("nested_non_empty"))
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun danglingSymlinkModified_invalidatesAction() {
        ensureSymbolicLink(
            sourceDir.getRelative("dangling_symlink"), PathFragment.create("still_does_not_exist")
        )
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun danglingSymlinkReplacedWithFile_invalidatesAction() {
        val danglingSymlink: Path = sourceDir.getRelative("dangling_symlink")
        val target: String? = danglingSymlink.readSymbolicLink().getPathString()
        danglingSymlink.delete()
        writeContent(danglingSymlink, java.nio.charset.StandardCharsets.ISO_8859_1, target)
        assertInvalidatedByBuild()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun crossingPackageBoundary_fails() {
        createEmptyFile(sourceDir.getRelative("subdir/BUILD"))
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo") })
        assertContainsEvent(
            "Directory artifact foo/dir crosses package boundary into package rooted at"
                    + " foo/dir/subdir"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun infiniteSymlinkExpansion_fails() {
        val dir: Path = sourceDir.getRelative("subdir/nested2")
        dir.delete()
        dir.createSymbolicLink(PathFragment.create(".."))
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo") })
        assertContainsEvent("infinite symlink expansion detected")
        assertContainsEvent("foo/dir/subdir/nested2")
    }

    @Throws(java.lang.Exception::class)
    private fun assertInvalidatedByBuild() {
        buildTarget("//foo")
        assertContainsEvent(events.collector(), GENRULE_EVENT)
    }

    @Throws(java.lang.Exception::class)
    private fun assertNotInvalidatedByBuild() {
        buildTarget("//foo")
        assertDoesNotContainEvent(events.collector(), GENRULE_EVENT)
    }

    companion object {
        private const val GENRULE_EVENT = "Executing genrule //foo:foo"
    }
}
