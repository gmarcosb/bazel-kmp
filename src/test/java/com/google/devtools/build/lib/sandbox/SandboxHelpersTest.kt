// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.ActionInput

/** Tests for [SandboxHelpers].  */
@RunWith(TestParameterInjector::class)
class SandboxHelpersTest {
    private class CustomInMemoryFileSystem : InMemoryFileSystem(DigestHashFunction.SHA256) {
        private var forbidRenameTo = false

        @Throws(IOException::class)
        public override fun renameTo(source: PathFragment, target: PathFragment) {
            if (forbidRenameTo) {
                throw IOException("error injected by test")
            }
            super.renameTo(source, target)
        }

        fun forbidRenameTo() {
            forbidRenameTo = true
        }
    }

    private val treeDeleter: TreeDeleter = SynchronousTreeDeleter()

    private val fs = CustomInMemoryFileSystem()
    private val scratch: Scratch = Scratch(fs)
    private var execRoot: Path? = null
    private var sandboxRoot: Path? = null
    private var executorToCleanup: ExecutorService? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        execRoot = scratch.dir("/execroot")
        sandboxRoot = scratch.dir("/sandbox")
    }

    @org.junit.After
    @Throws(java.lang.InterruptedException::class)
    fun tearDown() {
        if (executorToCleanup == null) {
            return
        }

        executorToCleanup.shutdown()
        executorToCleanup.awaitTermination(
            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun processInputFiles_materializesParamFile() {
        val paramFile: ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("paramFile"),
                com.google.common.collect.ImmutableList.of<E?>("-a", "-b"),
                ParameterFileType.UNQUOTED
            )

        val inputs: SandboxInputs = SandboxHelpers.processInputFiles(inputMap(paramFile), execRoot)

        assertThat(inputs.getFiles())
            .containsExactly(PathFragment.create("paramFile"), execRoot.getChild("paramFile"))
        assertThat(inputs.getSymlinks()).isEmpty()
        assertThat(FileSystemUtils.readLines(execRoot.getChild("paramFile"), java.nio.charset.StandardCharsets.UTF_8))
            .containsExactly("-a", "-b")
            .inOrder()
        assertThat(execRoot.getChild("paramFile").isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun processInputFiles_materializesBinToolsFile() {
        val tool: PathActionInput =
            PathActionInput(
                scratch.file("tool", "#!/bin/bash", "echo hello"),
                PathFragment.create("_bin/say_hello")
            )

        val inputs: SandboxInputs = SandboxHelpers.processInputFiles(inputMap(tool), execRoot)

        assertThat(inputs.getFiles())
            .containsExactly(
                PathFragment.create("_bin/say_hello"), execRoot.getRelative("_bin/say_hello")
            )
        assertThat(inputs.getSymlinks()).isEmpty()
        assertThat(
            FileSystemUtils.readLines(
                execRoot.getRelative("_bin/say_hello"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("#!/bin/bash", "echo hello")
            .inOrder()
        assertThat(execRoot.getRelative("_bin/say_hello").isExecutable()).isTrue()
    }

    /**
     * Test simulating a scenario when 2 parallel writes of the same virtual input both complete write
     * of the temp file and then proceed with post-processing steps one-by-one.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sandboxInputMaterializeVirtualInput_parallelWritesForSameInput_writesCorrectFile() {
        val input: VirtualActionInput? = ActionsTestUtil.createVirtualActionInput("file", "hello")
        executorToCleanup = Executors.newSingleThreadExecutor()
        val bothWroteTempFile: CyclicBarrier = CyclicBarrier(2)
        val finishProcessingSemaphore: Semaphore = Semaphore(1)
        val customFs: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA1) {
                @Throws(IOException::class)  // .await() inside
                public override fun setExecutable(path: PathFragment, executable: Boolean) {
                    try {
                        bothWroteTempFile.await()
                        finishProcessingSemaphore.acquire()
                    } catch (e: BrokenBarrierException) {
                        throw java.lang.IllegalArgumentException(e)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalArgumentException(e)
                    }
                    super.setExecutable(path, executable)
                }
            }
        val customScratch: Scratch = Scratch(customFs)
        val customExecRoot: Path = customScratch.dir("/execroot")

        val future: java.util.concurrent.Future<*> =
            executorToCleanup.submit(
                java.lang.Runnable {
                    try {
                        SandboxHelpers.processInputFiles(inputMap(input), customExecRoot)
                        finishProcessingSemaphore.release()
                    } catch (e: IOException) {
                        throw java.lang.IllegalArgumentException(e)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalArgumentException(e)
                    }
                })
        SandboxHelpers.processInputFiles(inputMap(input), customExecRoot)
        finishProcessingSemaphore.release()
        future.get()

        assertThat(customExecRoot.readdir(Symlinks.NOFOLLOW))
            .containsExactly(Dirent("file", Dirent.Type.FILE))
        val outputFile: Path = customExecRoot.getChild("file")
        assertThat(
            FileSystemUtils.readLines(
                outputFile,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("hello")
        assertThat(outputFile.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun atomicallyWriteVirtualInput_writesParamFile() {
        val paramFile: ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("paramFile"),
                com.google.common.collect.ImmutableList.of<E?>("-a", "-b"),
                ParameterFileType.UNQUOTED
            )

        paramFile.atomicallyWriteRelativeTo(scratch.resolve("/outputs"))

        assertThat(scratch.resolve("/outputs").readdir(Symlinks.NOFOLLOW))
            .containsExactly(Dirent("paramFile", Dirent.Type.FILE))
        val outputFile: Path = scratch.resolve("/outputs/paramFile")
        assertThat(FileSystemUtils.readLines(outputFile, java.nio.charset.StandardCharsets.UTF_8)).containsExactly(
            "-a",
            "-b"
        ).inOrder()
        assertThat(outputFile.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun atomicallyWriteVirtualInput_writesBinToolsFile() {
        val tool: PathActionInput =
            PathActionInput(
                scratch.file("tool", "tool_code"), PathFragment.create("tools/tool")
            )

        tool.atomicallyWriteRelativeTo(scratch.resolve("/outputs"))

        assertThat(scratch.resolve("/outputs").readdir(Symlinks.NOFOLLOW))
            .containsExactly(Dirent("tools", Dirent.Type.DIRECTORY))
        val outputFile: Path = scratch.resolve("/outputs/tools/tool")
        assertThat(
            FileSystemUtils.readLines(
                outputFile,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("tool_code")
        assertThat(outputFile.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun cleanExisting_updatesDirs() {
        val inputTxt: Path = scratch.getFileSystem().getPath(PathFragment.create("/hello.txt"))
        val rootDir: Path? = execRoot.getParentDirectory()
        val input1: PathFragment = PathFragment.create("existing/directory/with/input1.txt")
        val input2: PathFragment = PathFragment.create("partial/directory/input2.txt")
        val input3: PathFragment = PathFragment.create("new/directory/input3.txt")
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(input1, inputTxt, input2, inputTxt, input3, inputTxt),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val inputsToCreate: MutableSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val dirsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        SandboxHelpers.populateInputsAndDirsToCreate(
            com.google.common.collect.ImmutableSet.of<E?>(),
            inputsToCreate,
            dirsToCreate,
            com.google.common.collect.Iterables.concat(
                com.google.common.collect.ImmutableSet.of<E?>(),
                inputs.getFiles().keySet(),
                inputs.getSymlinks().keySet()
            ),
            SandboxOutputs.create(
                com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("out/dir/output.txt")),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        )

        val inputDir1: PathFragment? = input1.getParentDirectory()
        val inputDir2: PathFragment? = input2.getParentDirectory()
        val inputDir3: PathFragment? = input3.getParentDirectory()
        val outputDir: PathFragment? = PathFragment.create("out/dir")
        Truth.assertThat(dirsToCreate).containsExactly(inputDir1, inputDir2, inputDir3, outputDir)
        Truth.assertThat(inputsToCreate).containsExactly(input1, input2, input3)

        // inputdir1 exists fully
        execRoot.getRelative(inputDir1).createDirectoryAndParents()
        // inputdir2 exists partially, should be kept nonetheless.
        execRoot
            .getRelative(inputDir2)
            .getParentDirectory()
            .getRelative("doomedSubdir")
            .createDirectoryAndParents()
        // inputDir3 just doesn't exist
        // outputDir only exists partially
        execRoot.getRelative(outputDir).getParentDirectory().createDirectoryAndParents()
        execRoot.getRelative("justSomeDir/thatIsDoomed").createDirectoryAndParents()
        // `thiswillbeafile/output` simulates a directory that was in the stashed dir but whose same
        // path is used later for a regular file.
        scratch.dir("/execroot/thiswillbeafile/output")
        scratch.file("/execroot/thiswillbeafile/output/file1")
        dirsToCreate.add(PathFragment.create("thiswillbeafile"))
        val input4: PathFragment = PathFragment.create("thiswillbeafile/output")
        val inputs2: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    input1,
                    inputTxt,
                    input2,
                    inputTxt,
                    input3,
                    inputTxt,
                    input4,
                    inputTxt
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        SandboxHelpers.cleanExisting(
            rootDir, inputs2, inputsToCreate, dirsToCreate, execRoot, treeDeleter
        )
        Truth.assertThat(dirsToCreate).containsExactly(inputDir2, inputDir3, outputDir)
        assertThat(execRoot.getRelative("existing/directory/with").exists()).isTrue()
        assertThat(execRoot.getRelative("partial").exists()).isTrue()
        assertThat(execRoot.getRelative("partial/doomedSubdir").exists()).isFalse()
        assertThat(execRoot.getRelative("partial/directory").exists()).isFalse()
        assertThat(execRoot.getRelative("justSomeDir/thatIsDoomed").exists()).isFalse()
        assertThat(execRoot.getRelative("out").exists()).isTrue()
        assertThat(execRoot.getRelative("out/dir").exists()).isFalse()
    }

    @org.junit.Test
    fun populateInputsAndDirsToCreate_createsMappedDirectories() {
        val outputRoot: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(execRoot, ArtifactRoot.RootType.OUTPUT, "outputs")
        val outputFile: ActionInput = ActionsTestUtil.createArtifact(outputRoot, "bin/config/dir/file")
        val outputDir: ActionInput? =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(
                outputRoot, "bin/config/other_dir/subdir"
            )
        val pathMapper: PathMapper =
            PathMapper { execPath -> PathFragment.create(execPath.getPathString().replace("config/", "")) }
        val spawn: Spawn =
            SpawnBuilder().withOutputs(outputFile, outputDir).setPathMapper(pathMapper).build()
        val writableDirs: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val inputsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()
        val dirsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()

        SandboxHelpers.populateInputsAndDirsToCreate(
            writableDirs,
            inputsToCreate,
            dirsToCreate,
            com.google.common.collect.ImmutableList.of<E?>(),
            SandboxHelpers.getOutputs(spawn)
        )

        Truth.assertThat(writableDirs).isEmpty()
        Truth.assertThat(inputsToCreate).isEmpty()
        Truth.assertThat(dirsToCreate)
            .containsExactly(
                PathFragment.create("outputs/bin/dir"),
                PathFragment.create("outputs/bin/other_dir/subdir")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_movesFile(@TestParameter forceCopy: Boolean) {
        if (forceCopy) {
            fs.forbidRenameTo()
        }

        val sandboxFile: Path? = sandboxRoot.getRelative("output")
        FileSystemUtils.writeContent(sandboxFile, java.nio.charset.StandardCharsets.UTF_8, "hello")

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        val realFile: Path = execRoot.getRelative("output")
        assertThat(realFile.isFile()).isTrue()
        assertThat(FileSystemUtils.readContent(realFile, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_movesSymlink(@TestParameter forceCopy: Boolean) {
        if (forceCopy) {
            fs.forbidRenameTo()
        }

        val sandboxSymlink: Path = sandboxRoot.getRelative("output")
        sandboxSymlink.createSymbolicLink(PathFragment.create("target"))

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        val realSymlink: Path = execRoot.getRelative("output")
        assertThat(realSymlink.isSymbolicLink()).isTrue()
        assertThat(realSymlink.readSymbolicLink()).isEqualTo(PathFragment.create("target"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_movesDirectory(@TestParameter forceCopy: Boolean) {
        if (forceCopy) {
            fs.forbidRenameTo()
        }

        val sandboxDir: Path = sandboxRoot.getRelative("output")
        sandboxDir.createDirectoryAndParents()
        FileSystemUtils.writeContent(sandboxDir.getRelative("file"), java.nio.charset.StandardCharsets.UTF_8, "hello")
        sandboxDir.getRelative("symlink").createSymbolicLink(PathFragment.create("target"))
        sandboxDir.getRelative("subdir").createDirectoryAndParents()

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        val realDir: Path = execRoot.getRelative("output")
        assertThat(realDir.isDirectory()).isTrue()
        assertThat(realDir.getRelative("file").isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                realDir.getRelative("file"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello")
        assertThat(realDir.getRelative("symlink").isSymbolicLink()).isTrue()
        assertThat(realDir.getRelative("symlink").readSymbolicLink())
            .isEqualTo(PathFragment.create("target"))
        assertThat(realDir.getRelative("subdir").isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_ignoresMissing(@TestParameter forceCopy: Boolean) {
        if (forceCopy) {
            fs.forbidRenameTo()
        }

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        assertThat(execRoot.getRelative("output").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_fixesPermissionsOnFileWhenCopying() {
        fs.forbidRenameTo()

        val sandboxFile: Path = sandboxRoot.getRelative("output")
        FileSystemUtils.writeContent(sandboxFile, java.nio.charset.StandardCharsets.UTF_8, "hello")
        sandboxFile.chmod(0)

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        val realFile: Path = execRoot.getRelative("output")
        assertThat(realFile.isFile()).isTrue()
        assertThat(FileSystemUtils.readContent(realFile, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_fixesPermissionsOnDirectoryWhenCopying() {
        fs.forbidRenameTo()

        val sandboxDir: Path = sandboxRoot.getRelative("output")
        sandboxDir.createDirectoryAndParents()
        FileSystemUtils.writeContent(sandboxDir.getRelative("file"), java.nio.charset.StandardCharsets.UTF_8, "hello")
        sandboxDir.chmod(0)

        val spawn: Spawn = SpawnBuilder().withOutputs("output").build()
        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        val realDir: Path = execRoot.getRelative("output")
        assertThat(realDir.isDirectory()).isTrue()
        assertThat(realDir.getRelative("file").isFile()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                realDir.getRelative("file"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun moveOutputs_mappedPathMovedToUnmappedPath(@TestParameter forceCopy: Boolean) {
        if (forceCopy) {
            fs.forbidRenameTo()
        }

        val unmappedOutputPath: PathFragment = PathFragment.create("bin/config/output")
        val pathMapper: PathMapper =
            PathMapper { execPath -> PathFragment.create(execPath.getPathString().replace("config/", "")) }
        val spawn: Spawn =
            SpawnBuilder()
                .withOutputs(unmappedOutputPath.getPathString())
                .setPathMapper(pathMapper)
                .build()
        val mappedOutputPath: PathFragment? = PathFragment.create("bin/output")
        sandboxRoot.getRelative(mappedOutputPath).getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeLinesAs(
            sandboxRoot.getRelative(mappedOutputPath), java.nio.charset.StandardCharsets.UTF_8, "hello", "pathmapper"
        )

        SandboxHelpers.moveOutputs(SandboxHelpers.getOutputs(spawn), sandboxRoot, execRoot)

        assertThat(
            FileSystemUtils.readLines(
                execRoot.getRelative(unmappedOutputPath.getPathString()), java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("hello", "pathmapper")
            .inOrder()
    }

    companion object {
        private fun inputMap(vararg inputs: ActionInput?): com.google.common.collect.ImmutableMap<PathFragment?, ActionInput?> {
            return java.util.Arrays.stream<ActionInput?>(inputs)
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        ActionInput::getExecPath,
                        java.util.function.Function.identity<Any?>()
                    )
                )
        }
    }
}
