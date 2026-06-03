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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.exec.TreeDeleter

/** Tests for [SandboxedSpawn].  */
@RunWith(JUnit4::class)
class AbstractContainerizingSandboxedSpawnTest {
    private var sandboxPath: Path? = null
    private var sandboxExecRoot: Path? = null

    @Before
    @Throws(IOException::class)
    fun createSandboxExecRoot() {
        val scratch: Scratch = Scratch(InMemoryFileSystem(DigestHashFunction.SHA256))
        sandboxPath = scratch.dir("/sandbox")
        sandboxExecRoot = scratch.dir("/sandbox/execroot")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveOutputs() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testRoot: Path = fileSystem.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
        testRoot.createDirectoryAndParents()

        val execRoot: Path = testRoot.getRelative("execroot")
        execRoot.createDirectory()

        val outputFile: Path = execRoot.getRelative("very/output.txt")
        val outputLink: Path = execRoot.getRelative("very/output.link")
        val outputDangling: Path = execRoot.getRelative("very/output.dangling")
        val outputDir: Path = execRoot.getRelative("very/output.dir")
        val outputInUncreatedTargetDir: Path = execRoot.getRelative("uncreated/output.txt")

        val outputs: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<E?>(
                outputFile.relativeTo(execRoot),
                outputLink.relativeTo(execRoot),
                outputDangling.relativeTo(execRoot),
                outputInUncreatedTargetDir.relativeTo(execRoot)
            )
        val outputDirs: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<E?>(outputDir.relativeTo(execRoot))
        for (path in outputs) {
            execRoot.getRelative(path).getParentDirectory().createDirectoryAndParents()
        }
        for (path in outputDirs) {
            execRoot.getRelative(path).createDirectoryAndParents()
        }

        FileSystemUtils.createEmptyFile(outputFile)
        outputLink.createSymbolicLink(PathFragment.create("output.txt"))
        outputDangling.createSymbolicLink(PathFragment.create("doesnotexist"))
        outputDir.createDirectory()
        FileSystemUtils.createEmptyFile(outputDir.getRelative("test.txt"))
        FileSystemUtils.createEmptyFile(outputInUncreatedTargetDir)

        val outputsDir: Path = testRoot.getRelative("outputs")
        outputsDir.createDirectory()
        outputsDir.getRelative("very").createDirectory()
        SandboxHelpers.moveOutputs(SandboxOutputs.create(outputs, outputDirs), execRoot, outputsDir)

        assertThat(outputsDir.getRelative("very/output.txt").isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outputsDir.getRelative("very/output.link").isSymbolicLink()).isTrue()
        assertThat(outputsDir.getRelative("very/output.link").resolveSymbolicLinks())
            .isEqualTo(outputsDir.getRelative("very/output.txt"))
        assertThat(outputsDir.getRelative("very/output.dangling").isSymbolicLink()).isTrue()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                outputsDir.getRelative("very/output.dangling").resolveSymbolicLinks()
            })
        assertThat(outputsDir.getRelative("very/output.dir").isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outputsDir.getRelative("very/output.dir/test.txt").isFile(Symlinks.NOFOLLOW))
            .isTrue()
        assertThat(outputsDir.getRelative("uncreated/output.txt").isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    /** Watches a logger for file copy warnings (instead of moves) and counts them.  */
    private class FileCopyWarningTracker : java.util.logging.Handler() {
        var warningsCounter: Int = 0

        override fun publish(record: LogRecord) {
            if (record.getMessage().contains("different file systems")) {
                warningsCounter++
            }
        }

        override fun flush() {}

        override fun close() {}
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMoveOutputs_warnOnceIfCopyHappened() {
        class MultipleDeviceFS internal constructor() : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun renameTo(source: PathFragment?, target: PathFragment?) {
                throw IOException("EXDEV")
            }
        }
        SandboxHelpers.resetWarnedAboutMovesBeingCopiesForTesting()
        val fileSystem: FileSystem = MultipleDeviceFS()
        val testRoot: Path = fileSystem.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
        testRoot.createDirectoryAndParents()

        val tracker = FileCopyWarningTracker()
        val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger(SandboxHelpers::class.java.getName())
        logger.setUseParentHandlers(false)
        logger.addHandler(tracker)

        val execRoot: Path = testRoot.getRelative("execroot")
        execRoot.createDirectory()

        val outputFile1: Path = execRoot.getRelative("very/output1.txt")
        val outputFile2: Path = execRoot.getRelative("much/output2.txt")

        val outputs: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<E?>(
                outputFile1.relativeTo(execRoot),
                outputFile2.relativeTo(execRoot)
            )
        for (path in outputs) {
            execRoot.getRelative(path).getParentDirectory().createDirectoryAndParents()
        }

        FileSystemUtils.createEmptyFile(outputFile1)
        FileSystemUtils.createEmptyFile(outputFile2)

        val outputsDir: Path = testRoot.getRelative("outputs")
        outputsDir.createDirectory()
        outputsDir.getRelative("very").createDirectory()
        outputsDir.getRelative("much").createDirectory()
        SandboxHelpers.moveOutputs(
            SandboxOutputs.create(outputs, com.google.common.collect.ImmutableSet.of<E?>()), execRoot, outputsDir
        )

        assertThat(outputsDir.getRelative("very/output1.txt").isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outputsDir.getRelative("much/output2.txt").isFile(Symlinks.NOFOLLOW)).isTrue()

        Truth.assertThat(tracker.warningsCounter).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_createsDirectoriesForAndInputFiles() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/com.google.common.collect.ImmutableList.of<String?>("a/b"),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot)).containsExactly(directory("a"), file("a/b"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_createsDirectoriesForAndInputSymlinks() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/com.google.common.collect.ImmutableList.of<String?>(),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>("a/b/c")
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot))
            .containsExactly(directory("a"), directory("a/b"), symlink("a/b/c"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_uplevelReference_createsSiblingDirectory() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/
                com.google.common.collect.ImmutableList.of<String?>("../a/b"),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot.getParentDirectory()))
            .containsExactly(directory("a"), file("a/b"), directory("execroot"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_createsDirectoriesForOutputFiles() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/com.google.common.collect.ImmutableList.of<String?>(),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/
                com.google.common.collect.ImmutableSet.of<E?>(
                    PathFragment.create("a/b"),
                    PathFragment.create("c/d/e")
                ),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot))
            .containsExactly(directory("a"), directory("c"), directory("c/d"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_createsOutputDirectories() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/com.google.common.collect.ImmutableList.of<String?>(),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/
                com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a/b"), PathFragment.create("c/d/e"))
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot))
            .containsExactly(
                directory("a"), directory("a/b"), directory("c"), directory("c/d"), directory("c/d/e")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_nestedFileAndDirectory_createsDirectoriesAndFile() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/
                com.google.common.collect.ImmutableList.of<String?>("a/b/file"),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/
                com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a"))
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot))
            .containsExactly(directory("a"), directory("a/b"), file("a/b/file"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_overlappingPaths_createsAllDirectories() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/
                com.google.common.collect.ImmutableList.of<String?>("1/2/file1"),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>("1/2/3/symlink")
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/
                com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("1/2/file2")),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("1"), PathFragment.create("2/3/4"))
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        sandboxedSpawn.createFileSystem()

        Truth.assertThat(listDirectory(sandboxExecRoot))
            .containsExactly(
                directory("1"),
                directory("1/2"),
                file("1/2/file1"),
                directory("1/2/3"),
                symlink("1/2/3/symlink"),
                directory("2"),
                directory("2/3"),
                directory("2/3/4")
            )
    }

    @org.junit.Test
    fun createFileSystem_fileInUpUpLevelReference_fails() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/
                com.google.common.collect.ImmutableList.of<String?>("../../file"),  /*symlinks=*/
                com.google.common.collect.ImmutableList.of<String?>()
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            sandboxedSpawn::createFileSystem
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem_overlappingSymlinkAndParent_createsCorrectParentsAndFails() {
        val sandboxInputs: SandboxInputs =
            Companion.createSandboxInputs( /*files=*/
                com.google.common.collect.ImmutableList.of<String?>("1/2/3/file", "1/4/file"),  /*symlinks=*/
                com.google.common.collect.ImmutableMap.of<String?, String?>("1/2", "4")
            )
        val sandboxOutputs: SandboxOutputs? =
            SandboxOutputs.create( /*files=*/com.google.common.collect.ImmutableSet.of<E?>(),  /*dirs=*/
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val sandboxedSpawn: AbstractContainerizingSandboxedSpawn =
            createContainerizingSandboxedSpawn(sandboxInputs, sandboxOutputs)

        org.junit.Assert.assertThrows<IOException?>(IOException::class.java, sandboxedSpawn::createFileSystem)

        val entries: com.google.common.collect.ImmutableList<PathEntry?> = listDirectory(sandboxExecRoot)
        Truth.assertThat(entries)
            .containsAtLeast(directory("1"), directory("1/2"), directory("1/2/3"), directory("1/4"))
        Truth.assertThat(entries).doesNotContain(directory("1/4/3"))
    }

    private fun createContainerizingSandboxedSpawn(
        sandboxInputs: SandboxInputs?, sandboxOutputs: SandboxOutputs?
    ): AbstractContainerizingSandboxedSpawn {
        return object : AbstractContainerizingSandboxedSpawn(
            sandboxPath,
            sandboxExecRoot,  /* arguments= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* environment= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            sandboxInputs,
            sandboxOutputs,  /* writableDirs= */
            com.google.common.collect.ImmutableSet.of<E?>(),
            < T > mock < T ? > (TreeDeleter::class.java),  /* sandboxDebugPath= */
        null,  /* statisticsPath= */
        null,
        "Mnemonic") {
            protected override fun copyFile(source: Path?, target: Path?) {
                throw java.lang.UnsupportedOperationException()
            }
        }
    }

    internal class PathEntry(relativePath: PathFragment?, val type: Type?) {
        internal enum class Type {
            FILE,
            DIRECTORY,
            SYMLINK
        }

        val relativePath: PathFragment?

        init {
            this.relativePath = relativePath
            java.util.Objects.requireNonNull<Any?>(relativePath, "relativePath")
            java.util.Objects.requireNonNull<Type?>(
                type, "type"
            )
        }

        companion object {
            fun create(path: PathFragment?, type: Type?): PathEntry {
                return com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry(
                    path,
                    type
                )
            }
        }
    }

    fun file(path: String?): PathEntry {
        return com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
            PathFragment.create(path),
            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.FILE
        )
    }

    fun directory(path: String?): PathEntry {
        return com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
            PathFragment.create(path),
            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.DIRECTORY
        )
    }

    fun symlink(path: String?): PathEntry {
        return com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
            PathFragment.create(path),
            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.SYMLINK
        )
    }

    companion object {
        private fun createSandboxInputs(
            files: com.google.common.collect.ImmutableList<String?>,
            symlinks: com.google.common.collect.ImmutableList<String?>
        ): SandboxInputs {
            return Companion.createSandboxInputs(
                files,
                symlinks.stream().collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<String?, String?, String?>(
                        java.util.function.Function.identity<String?>(),
                        java.util.function.Function { ignored: String? -> "anywhere" })
                )
            )
        }

        private fun createSandboxInputs(
            files: com.google.common.collect.ImmutableList<String?>,
            symlinks: com.google.common.collect.ImmutableMap<String?, String?>
        ): SandboxInputs {
            val filesMap: MutableMap<PathFragment?, Path?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<PathFragment?, Path?>(files.size)
            for (file in files) {
                filesMap.put(PathFragment.create(file), null)
            }
            return SandboxInputs(
                filesMap,  /* virtualInputs= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                symlinks.entries.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                            java.util.function.Function { e: Any? -> PathFragment.create(e.getKey()) },
                            java.util.function.Function { e: Any? -> PathFragment.create(e.getValue()) })
                    )
            )
        }

        /** Return a list of all entries under the provided directory recursively.  */
        @Throws(IOException::class)
        private fun listDirectory(directory: Path): com.google.common.collect.ImmutableList<PathEntry?> {
            val entries: MutableCollection<Path> = FileSystemUtils.traverseTree(directory, { ignored -> true })
            val result: com.google.common.collect.ImmutableList.Builder<PathEntry?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<PathEntry?>(entries.size)
            for (path in entries) {
                val relativePath: PathFragment? = path.asFragment().relativeTo(directory.asFragment())
                val stat: FileStatus = path.stat(Symlinks.NOFOLLOW)
                if (stat.isFile) {
                    result.add(
                        com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
                            relativePath,
                            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.FILE
                        )
                    )
                } else if (stat.isDirectory) {
                    result.add(
                        com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
                            relativePath,
                            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.DIRECTORY
                        )
                    )
                } else if (stat.isSymbolicLink) {
                    result.add(
                        com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Companion.create(
                            relativePath,
                            com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawnTest.PathEntry.Type.SYMLINK
                        )
                    )
                } else {
                    throw java.lang.AssertionError("Unexpected file type for " + path)
                }
            }
            return result.build()
        }
    }
}
