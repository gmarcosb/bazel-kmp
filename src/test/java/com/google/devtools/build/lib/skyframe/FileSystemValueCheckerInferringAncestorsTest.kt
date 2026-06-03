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

import com.google.devtools.build.lib.actions.FileStateValue.DIRECTORY_FILE_STATE_NODE

/** Unit tests for [FileSystemValueCheckerInferringAncestors].  */
@RunWith(TestParameterInjector::class)
class FileSystemValueCheckerInferringAncestorsTest

    : FileSystemValueCheckerInferringAncestorsTestBase() {
    private val skyValueDirtinessChecker: SkyValueDirtinessChecker = FileDirtinessChecker()

    @TestParameter("1", "16")
    private val fsvcThreads = 0

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_unknownFileChanged_returnsFileAndDirs: Unit
        get() {
            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileStateValueKey("foo/file")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    fileStateValueKey(""),
                    fileStateValueKey("foo"),
                    fileStateValueKey("foo/file"),
                    directoryListingStateValueKey(""),
                    directoryListingStateValueKey("foo")
                )
            assertThat(diff.changedKeysWithNewValues()).isEmpty()
            Truth.assertThat(statedPaths).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_fileModified_returnsFileWithValues: Unit
        get() {
            scratch.file("file", "hello")
            val key: FileStateKey = fileStateValueKey("file")
            val value: FileStateValue = fileStateValue("file")
            scratch.overwriteFile("file", "there")
            addDoneNodesAndThenMarkChanged(com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(key, value))

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            val newValue: Delta = fileStateValueDelta("file")
            assertThat(diff.changedKeysWithNewValues()).containsExactly(key, newValue)
            assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
            Truth.assertThat(statedPaths).containsExactly("file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_fileAdded_returnsFileAndDirListing: Unit
        get() {
            scratch.file("file")
            val key: FileStateKey = fileStateValueKey("file")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key, NONEXISTENT_FILE_STATE_NODE, fileStateValueKey(""), fileStateValue("")
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            val delta: Delta = fileStateValueDelta("file")
            assertThat(diff.changedKeysWithNewValues()).containsExactly(key, delta)
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey(""))
            Truth.assertThat(statedPaths).containsExactly("file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_fileWithDirsAdded_returnsFileAndInjectsDirs: Unit
        get() {
            scratch.file("a/b/file")
            val fileKey: FileStateKey = fileStateValueKey("a/b/file")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey(""),
                    fileStateValue(""),
                    fileStateValueKey("a"),
                    NONEXISTENT_FILE_STATE_NODE,
                    fileStateValueKey("a/b"),
                    NONEXISTENT_FILE_STATE_NODE,
                    fileKey,
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileKey),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            val delta: Delta = fileStateValueDelta("a/b/file")
            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    fileKey,
                    delta,
                    fileStateValueKey("a"),
                    DIRECTORY_FILE_STATE_NODE_DELTA,
                    fileStateValueKey("a/b"),
                    DIRECTORY_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    directoryListingStateValueKey(""),
                    directoryListingStateValueKey("a"),
                    directoryListingStateValueKey("a/b")
                )
            Truth.assertThat(statedPaths).containsExactly("a/b/file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_addedFileWithReportedDirs_returnsFileAndInjectsDirs: Unit
        get() {
            scratch.file("a/b/file")
            val fileKey: FileStateKey = fileStateValueKey("a/b/file")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey(""),
                    fileStateValue(""),
                    fileStateValueKey("a"),
                    NONEXISTENT_FILE_STATE_NODE,
                    fileStateValueKey("a/b"),
                    NONEXISTENT_FILE_STATE_NODE,
                    fileKey,
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileKey, fileStateValueKey("a")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            val newState: Delta = fileStateValueDelta("a/b/file")
            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    fileKey,
                    newState,
                    fileStateValueKey("a"),
                    DIRECTORY_FILE_STATE_NODE_DELTA,
                    fileStateValueKey("a/b"),
                    DIRECTORY_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    directoryListingStateValueKey(""),
                    directoryListingStateValueKey("a"),
                    directoryListingStateValueKey("a/b")
                )
            Truth.assertThat(statedPaths).containsExactly("a/b/file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_fileWithUnknownDirsAdded_returnsFileAndDirs: Unit
        /**
         * This is a degenerate case since we normally only know about a file if we checked all parents,
         * but that is theoretically possible with this API.
         */
        get() {
            scratch.file("a/b/c/d")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey(""),
                    fileStateValue(""),
                    fileStateValueKey("a/b/c/d"),
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileStateValueKey("a/b/c/d")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    fileStateValueKey("a"),
                    fileStateValueKey("a/b"),
                    fileStateValueKey("a/b/c"),
                    directoryListingStateValueKey(""),
                    directoryListingStateValueKey("a"),
                    directoryListingStateValueKey("a/b"),
                    directoryListingStateValueKey("a/b/c")
                )
            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(fileStateValueKey("a/b/c/d"), fileStateValueDelta("a/b/c/d"))
            Truth.assertThat(statedPaths).containsExactly("a/b/c/d")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_addEmptyDir_returnsDirAndParentListing: Unit
        get() {
            scratch.dir("dir")
            val key: FileStateKey = fileStateValueKey("dir")
            val delta: Delta = fileStateValueDelta("dir")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key, NONEXISTENT_FILE_STATE_NODE, fileStateValueKey(""), fileStateValue("")
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues()).containsExactly(key, delta)
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey(""))
            Truth.assertThat(statedPaths).containsExactly("dir")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteFile_returnsFileParentListing: Unit
        get() {
            val file: Path = scratch.file("dir/file1")
            scratch.file("dir/file2")
            val key: FileStateKey = fileStateValueKey("dir/file1")
            val oldValue: FileStateValue = fileStateValue("dir/file1")
            file.delete()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key,
                    oldValue,
                    fileStateValueKey("dir"),
                    fileStateValue("dir")
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    key,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey("dir"))
            Truth.assertThat(statedPaths).containsExactly("dir/file1", "dir")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteFileFromDirWithListing_skipsDirStat: Unit
        get() {
            val file1: Path = scratch.file("dir/file1")
            val key: FileStateKey = fileStateValueKey("dir/file1")
            val oldValue: FileStateValue = fileStateValue("dir/file1")
            file1.delete()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key,
                    oldValue,
                    fileStateValueKey("dir"),
                    fileStateValue("dir")
                )
            )
            addDoneNodes(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    directoryListingStateValueKey("dir"),
                    FileSystemValueCheckerInferringAncestorsTestBase.Companion.directoryListingStateValue(
                        file("file1"),
                        file("file2")
                    )
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    key,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey("dir"))
            Truth.assertThat(statedPaths).containsExactly("dir/file1")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteLastFileFromDir_ignoresInvalidatedListing: Unit
        get() {
            val file1: Path = scratch.file("dir/file1")
            val key: FileStateKey = fileStateValueKey("dir/file1")
            val oldValue: FileStateValue = fileStateValue("dir/file1")
            file1.delete()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key,
                    oldValue,
                    fileStateValueKey("dir"),
                    fileStateValue("dir"),
                    directoryListingStateValueKey("dir"),
                    FileSystemValueCheckerInferringAncestorsTestBase.Companion.directoryListingStateValue(
                        file("file1"),
                        file("file2")
                    )
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    key,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey("dir"))
            Truth.assertThat(statedPaths).containsExactly("dir/file1", "dir")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_modifyAllUnknownEntriesInDirWithListing_skipsDir: Unit
        get() {
            val file: Path = scratch.file("dir/file")
            file.getParentDirectory()
                .getRelative("symlink")
                .createSymbolicLink(PathFragment.create("file"))
            val fileKey: FileStateKey = fileStateValueKey("dir/file")
            val symlinkKey: FileStateKey = fileStateValueKey("dir/symlink")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("dir"),
                    fileStateValue("dir")
                )
            )
            addDoneNodes(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    directoryListingStateValueKey("dir"),
                    FileSystemValueCheckerInferringAncestorsTestBase.Companion.directoryListingStateValue(
                        file("file"),
                        symlink("symlink")
                    )
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileKey, symlinkKey),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    fileKey,
                    fileStateValueDelta("dir/file"),
                    symlinkKey,
                    fileStateValueDelta("dir/symlink")
                )
            assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
            Truth.assertThat(statedPaths).containsExactly("dir/file", "dir/symlink")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_replaceUnknownEntriesInDirWithListing_skipsSiblingStat: Unit
        get() {
            scratch.dir("dir/file1")
            scratch.dir("dir/file2")
            val file1Key: FileStateKey = fileStateValueKey("dir/file1")
            val file2Key: FileStateKey = fileStateValueKey("dir/file2")
            val dirKey: DirectoryListingStateValue.Key = directoryListingStateValueKey("dir")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("dir"),
                    fileStateValue("dir")
                )
            )
            addDoneNodes(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    dirKey,
                    FileSystemValueCheckerInferringAncestorsTestBase.Companion.directoryListingStateValue(
                        file("file1"),
                        file("file2")
                    )
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(file1Key, file2Key),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            FileSystemValueCheckerInferringAncestorsTestBase.Companion.assertIsSubsetOf<T?>(
                diff.changedKeysWithNewValues().entrySet(),
                com.google.common.collect.Maps.immutableEntry<Any?, Any?>(
                    file1Key,
                    fileStateValueDelta("dir/file1")
                ),
                com.google.common.collect.Maps.immutableEntry<Any?, Any?>(
                    file2Key,
                    fileStateValueDelta("dir/file2")
                )
            )
            com.google.common.truth.Subject.contains(dirKey)
            Truth.assertThat(
                com.google.common.collect.Streams.concat(
                    diff.changedKeysWithoutNewValues().stream(),
                    diff.changedKeysWithNewValues().keySet().stream()
                )
            )
                .containsExactly(file1Key, file2Key, dirKey)
            Truth.assertThat(statedPaths).isNotEmpty()
            FileSystemValueCheckerInferringAncestorsTestBase.Companion.assertIsSubsetOf<String?>(
                statedPaths,
                "dir/file1",
                "dir/file2"
            )
            if (fsvcThreads == 1) {
                // In case of single-threaded execution, we know that once we check dir/file1 or dir/file2, we
                // will be able to skip stat on the other one.
                assertThat(diff.changedKeysWithNewValues()).hasSize(1)
                assertThat(diff.changedKeysWithoutNewValues()).hasSize(2)
                Truth.assertThat(statedPaths).hasSize(1)
            }
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteAllFilesFromDir_returnsFilesAndDirListing: Unit
        get() {
            val file1: Path = scratch.file("dir/file1")
            val file2: Path = scratch.file("dir/file2")
            val file3: Path = scratch.file("dir/file3")
            val key1: FileStateKey = fileStateValueKey("dir/file1")
            val oldValue1: FileStateValue = fileStateValue("dir/file1")
            val key2: FileStateKey = fileStateValueKey("dir/file2")
            val oldValue2: FileStateValue = fileStateValue("dir/file2")
            val key3: FileStateKey = fileStateValueKey("dir/file3")
            val oldValue3: FileStateValue = fileStateValue("dir/file3")
            file1.delete()
            file2.delete()
            file3.delete()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    key1,
                    oldValue1,
                    key2,
                    oldValue2,
                    key3,
                    oldValue3,
                    fileStateValueKey("dir"),
                    fileStateValue("dir")
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(key1, key2, key3),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    key1, NONEXISTENT_FILE_STATE_NODE_DELTA,
                    key2, NONEXISTENT_FILE_STATE_NODE_DELTA,
                    key3, NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey("dir"))
            Truth.assertThat(statedPaths).containsExactly("dir", "dir/file1", "dir/file2", "dir/file3")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteFileWithDirs_returnsFileAndDirs: Unit
        get() {
            scratch.file("a/b/c/file")
            val abKey: FileStateKey = fileStateValueKey("a/b")
            val abValue: FileStateValue = fileStateValue("a/b")
            val abcKey: FileStateKey = fileStateValueKey("a/b/c")
            val abcValue: FileStateValue = fileStateValue("a/b/c")
            val abcFileKey: FileStateKey = fileStateValueKey("a/b/c/file")
            val abcFileValue: FileStateValue = fileStateValue("a/b/c/file")
            scratch.dir("a/b").deleteTree()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("a"),
                    fileStateValue("a"),
                    abKey,
                    abValue,
                    abcKey,
                    abcValue,
                    abcFileKey,
                    abcFileValue
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(abcFileKey),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    abKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA,
                    abcKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA,
                    abcFileKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    directoryListingStateValueKey("a"),
                    directoryListingStateValueKey("a/b"),
                    directoryListingStateValueKey("a/b/c")
                )
            Truth.assertThat(statedPaths).containsExactly("a", "a/b", "a/b/c", "a/b/c/file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteFileWithReportedDirs_returnsFileAndDirListings: Unit
        get() {
            scratch.file("a/b/c/file")
            val abKey: FileStateKey = fileStateValueKey("a/b")
            val abValue: FileStateValue = fileStateValue("a/b")
            val abcKey: FileStateKey = fileStateValueKey("a/b/c")
            val abcValue: FileStateValue = fileStateValue("a/b/c")
            val abcFileKey: FileStateKey = fileStateValueKey("a/b/c/file")
            val abcFileValue: FileStateValue = fileStateValue("a/b/c/file")
            scratch.dir("a/b").deleteTree()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("a"),
                    fileStateValue("a"),
                    abKey,
                    abValue,
                    abcKey,
                    abcValue,
                    abcFileKey,
                    abcFileValue
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(abcFileKey, abKey),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    abKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA,
                    abcKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA,
                    abcFileKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    directoryListingStateValueKey("a"),
                    directoryListingStateValueKey("a/b"),
                    directoryListingStateValueKey("a/b/c")
                )
            Truth.assertThat(statedPaths).containsExactly("a", "a/b", "a/b/c", "a/b/c/file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteFile_infersDirFromModifiedSibling: Unit
        get() {
            val file1: Path = scratch.file("dir/file1")
            scratch.file("dir/file2", "1")
            val file1Key: FileStateKey = fileStateValueKey("dir/file1")
            val file1Value: FileStateValue = fileStateValue("dir/file1")
            val file2Key: FileStateKey = fileStateValueKey("dir/file2")
            val file2Value: FileStateValue = fileStateValue("dir/file2")
            file1.delete()
            scratch.overwriteFile("dir/file2", "12")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("dir"),
                    fileStateValue("dir"),
                    file1Key,
                    file1Value,
                    file2Key,
                    file2Value
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(file1Key, file2Key, fileStateValueKey("dir")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            val file2NewValue: Delta = fileStateValueDelta("dir/file2")
            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    file1Key,
                    NONEXISTENT_FILE_STATE_NODE_DELTA,
                    file2Key,
                    file2NewValue
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(directoryListingStateValueKey("dir"))
            Truth.assertThat(statedPaths).containsExactly("dir/file1", "dir/file2")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_deleteDirReportDirOnly_returnsDir: Unit
        get() {
            val file1: Path = scratch.file("dir/file1")
            scratch.file("dir/file2")
            val file1Key: FileStateKey = fileStateValueKey("dir/file1")
            val file1Value: FileStateValue = fileStateValue("dir/file1")
            val file2Key: FileStateKey = fileStateValueKey("dir/file2")
            val file2Value: FileStateValue = fileStateValue("dir/file2")
            val subdirFileKey: FileStateKey = fileStateValueKey("dir/subdir/file")
            val subdirFileValue: FileStateValue = fileStateValue("dir/subdir/file")
            val dirKey: FileStateKey = fileStateValueKey("dir")
            val dirValue: FileStateValue = fileStateValue("dir")
            val subdirKey: FileStateKey = fileStateValueKey("dir/subdir")
            val subdirValue: FileStateValue = fileStateValue("dir/subdir")
            val subdirListingKey: SkyKey = directoryListingStateValueKey("dir/subdir")
            val subdirListingValue: DirectoryListingStateValue = directoryListingStateValue(file("file"))
            file1.getParentDirectory().deleteTree()
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    file1Key,
                    file1Value,
                    file2Key,
                    file2Value,
                    subdirFileKey,
                    subdirFileValue,
                    dirKey,
                    dirValue,
                    subdirKey,
                    subdirValue,
                    subdirListingKey,
                    subdirListingValue,
                    fileStateValueKey(""),
                    fileStateValue("")
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(dirKey),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues())
                .containsExactly(
                    dirKey,
                    NONEXISTENT_FILE_STATE_NODE_DELTA
                )
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    directoryListingStateValueKey(""),
                    file1Key,
                    file2Key,
                    subdirKey,
                    subdirListingKey,
                    subdirFileKey
                )
            Truth.assertThat(statedPaths).containsExactly("dir", "")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_phantomChangeForNonexistentEntry_returnsEmptyDiff: Unit
        get() {
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("file"),
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileStateValueKey("file")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithoutNewValues()).isEmpty()
            assertThat(diff.changedKeysWithNewValues()).isEmpty()
            Truth.assertThat(statedPaths).containsExactly("file")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_statFails_invalidatesFileAndParents: Unit
        get() {
            throwOnStat = IOException("oh no")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("file"),
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            val diff: ImmutableDiff =
                FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                    null,
                    inMemoryGraph,  /* modifiedKeys= */
                    com.google.common.collect.ImmutableSet.of<E?>(fileStateValueKey("file")),
                    fsvcThreads,
                    syscallCache,
                    skyValueDirtinessChecker
                )

            assertThat(diff.changedKeysWithNewValues()).isEmpty()
            assertThat(diff.changedKeysWithoutNewValues())
                .containsExactly(
                    fileStateValueKey("file"), fileStateValueKey(""), directoryListingStateValueKey("")
                )
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val diffWithInferredAncestors_statCrashes_fails: Unit
        get() {
            throwOnStat = java.lang.RuntimeException("oh no")
            addDoneNodesAndThenMarkChanged(
                com.google.common.collect.ImmutableMap.of<SkyKey?, SkyValue?>(
                    fileStateValueKey("file"),
                    NONEXISTENT_FILE_STATE_NODE
                )
            )

            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors( /* tsgm= */
                        null,
                        inMemoryGraph,  /* modifiedKeys= */
                        com.google.common.collect.ImmutableSet.of<E?>(fileStateValueKey("file")),
                        fsvcThreads,
                        syscallCache,
                        skyValueDirtinessChecker
                    )
                })
        }

    @Throws(IOException::class)
    private fun fileStateValueDelta(relativePath: String?): Delta {
        return Delta.justNew(fileStateValue(relativePath))
    }

    @Throws(java.lang.InterruptedException::class)
    private fun addDoneNodesAndThenMarkChanged(values: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>) {
        for (entry in values.entries) {
            val node: InMemoryNodeEntry = addDoneNode(entry.key, entry.value)
            node.markDirty(DirtyType.CHANGE)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun addDoneNodes(values: com.google.common.collect.ImmutableMap<SkyKey?, SkyValue?>) {
        for (entry in values.entries) {
            addDoneNode(entry.key, entry.value)
        }
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    private fun addDoneNode(key: SkyKey, value: SkyValue?): InMemoryNodeEntry {
        val batch: NodeBatch =
            inMemoryGraph.createIfAbsentBatch(null, Reason.OTHER, com.google.common.collect.ImmutableList.of<E?>(key))
        val entry: InMemoryNodeEntry = batch.get(key) as InMemoryNodeEntry
        entry.addReverseDepAndCheckIfDone(null)
        entry.markRebuilding()
        entry.setValue(value, Version.minimal(),  /* maxTransitiveSourceVersion= */null)
        return entry
    }

    companion object {
        private val DIRECTORY_FILE_STATE_NODE_DELTA: Delta? = Delta.justNew(DIRECTORY_FILE_STATE_NODE)
        private val NONEXISTENT_FILE_STATE_NODE_DELTA: Delta? = Delta.justNew(NONEXISTENT_FILE_STATE_NODE)
    }
}
