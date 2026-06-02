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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.testing.common.DirectoryListingHelper.leafDirectoryEntries

/** Tests for [ActionOutputDirectoryHelper].  */
@RunWith(TestParameterInjector::class)
class ActionOutputDirectoryHelperTest {
    private var execRoot: Path? = null
    private var outputRoot: ArtifactRoot? = null

    @Before
    @Throws(IOException::class)
    fun createArtifactRootAndOutputDirectoryHelper() {
        val scratch: Scratch = Scratch()
        execRoot = scratch.dir("/execroot")
        outputRoot = createOutputRoot(execRoot)
    }

    internal enum class DirectoryCache(spec: CaffeineSpec) {
        CACHE_ENABLED(CaffeineSpec.parse("maximumSize=100000")),
        CACHE_DISABLED(CaffeineSpec.parse("maximumSize=0"));

        val spec: CaffeineSpec?

        init {
            this.spec = spec
        }
    }

    private enum class OutputSet(
        fileOutputs: com.google.common.collect.ImmutableSet<String?>,
        treeOutputs: com.google.common.collect.ImmutableSet<String?>,
        expectedDirectories: com.google.common.collect.ImmutableList<String?>
    ) {
        SINGLE_FILE( /* fileOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>("a/b"),  /* treeOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* expectedDirectories= */
            com.google.common.collect.ImmutableList.of<String?>("a")
        ),
        DEEP_DIRECTORY_STRUCTURE( /* fileOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>("a/b/c/d/e/f/g/h/i/j/k/l/m"),  /* treeOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* expectedDirectories= */
            com.google.common.collect.ImmutableList.of<String?>("a/b/c/d/e/f/g/h/i/j/k/l")
        ),
        MULTIPLE_FILES( /* fileOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>("a/b/c", "a/c", "a/d/1", "a/d/2"),  /* treeOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* expectedDirectories= */
            com.google.common.collect.ImmutableList.of<String?>("a/b", "a/d")
        ),
        TREE_OUTPUT( /* fileOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* treeOutputs= */
            com.google.common.collect.ImmutableSet.of<String?>("a/b"),  /* expectedDirectories= */
            com.google.common.collect.ImmutableList.of<String?>("a/b")
        );

        fun actionOutputs(test: ActionOutputDirectoryHelperTest): com.google.common.collect.ImmutableSet<Artifact?> {
            val outs: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builderWithExpectedSize<Artifact?>(fileOutputs.size() + treeOutputs.size())
            fileOutputs.stream().map<Artifact?>(java.util.function.Function { relativeExecPath: String? ->
                test.createOutput(relativeExecPath)
            }).forEach(java.util.function.Consumer { element: Artifact? -> outs.add(element) })
            treeOutputs.stream().map<SpecialArtifact?>(java.util.function.Function { relativeExecPath: String? ->
                test.createTreeOutput(relativeExecPath)
            }).forEach(outs::add)
            return outs.build()
        }

        fun expectedDirectoryEntries(): com.google.common.collect.ImmutableList<Dirent?> {
            return expectedDirectories.stream()
                .map<Any?>(DirectoryListingHelper::directory)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        private val fileOutputs: com.google.common.collect.ImmutableSet<String?>
        private val treeOutputs: com.google.common.collect.ImmutableSet<String?>
        private val expectedDirectories: com.google.common.collect.ImmutableList<String?>

        init {
            this.fileOutputs = fileOutputs
            this.treeOutputs = treeOutputs
            this.expectedDirectories = expectedDirectories
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createOutputDirectories_createsExpectedDirectories(
        @TestParameter cache: DirectoryCache, @TestParameter outputSet: OutputSet
    ) {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = ActionOutputDirectoryHelper(cache.spec)

        outputDirectoryHelper.createOutputDirectories(outputSet.actionOutputs(this))

        assertThat(leafDirectoryEntries(outputRoot.getRoot().asPath()))
            .containsExactlyElementsIn(outputSet.expectedDirectoryEntries())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createOutputDirectories_makesOutputDirectoryWritable() {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val fileOutput: Artifact = createOutput("dir/file")
        val parentDir: Path = fileOutput.getPath().getParentDirectory()
        parentDir.createDirectoryAndParents()
        parentDir.setWritable(false)
        parentDir.setExecutable(false)

        outputDirectoryHelper.createOutputDirectories(com.google.common.collect.ImmutableSet.of<E?>(fileOutput))

        assertThat(parentDir.isDirectory()).isTrue()
        assertThat(parentDir.isReadable()).isTrue()
        assertThat(parentDir.isWritable()).isTrue()
        assertThat(parentDir.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createOutputDirectories_overwritesExistingFileAtParentPath() {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val fileOutput: Artifact = createOutput("dir/file")
        val parentPath: Path = fileOutput.getPath().getParentDirectory()
        parentPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(parentPath, java.nio.charset.StandardCharsets.UTF_8, "garbage")
        parentPath.setWritable(false)

        outputDirectoryHelper.createOutputDirectories(com.google.common.collect.ImmutableSet.of<E?>(fileOutput))

        assertThat(parentPath.isDirectory()).isTrue()
        assertThat(parentPath.isReadable()).isTrue()
        assertThat(parentPath.isWritable()).isTrue()
        assertThat(parentPath.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createOutputDirectories_overwritesExistingFileAtGrandparentPath() {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val fileOutput: Artifact = createOutput("dir/subdir/file")
        val parentPath: Path = fileOutput.getPath().getParentDirectory()
        val grandparentPath: Path = parentPath.getParentDirectory()
        grandparentPath.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(grandparentPath, java.nio.charset.StandardCharsets.UTF_8, "garbage")
        grandparentPath.setWritable(false)

        outputDirectoryHelper.createOutputDirectories(com.google.common.collect.ImmutableSet.of<E?>(fileOutput))

        assertThat(parentPath.isDirectory()).isTrue()
        assertThat(parentPath.isReadable()).isTrue()
        assertThat(parentPath.isWritable()).isTrue()
        assertThat(parentPath.isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createActionFsOutputDirectories_createsExpectedDirectoriesInActionFs(
        @TestParameter outputSet: OutputSet
    ) {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val actionFileSystem: FileSystem = Scratch().getFileSystem()
        val resolver: ArtifactPathResolver? =
            ArtifactPathResolver.createPathResolver(actionFileSystem, execRoot)

        outputDirectoryHelper.createActionFsOutputDirectories(outputSet.actionOutputs(this), resolver)

        val outputRootPath: Path = outputRoot.getRoot().asPath()
        assertThat(outputRootPath.exists()).isFalse()
        assertThat(leafDirectoryEntries(actionFileSystem.getPath(outputRootPath.asFragment())))
            .containsExactlyElementsIn(outputSet.expectedDirectoryEntries())
    }

    @org.junit.Test
    fun createOutputDirectories_ioExceptionWhenCreatingDirectory_fails(
        @TestParameter cache: DirectoryCache
    ) {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = ActionOutputDirectoryHelper(cache.spec)
        val injectedException: IOException = IOException("oh no!")
        val outputRootPath: PathFragment = outputRoot.getRoot().asPath().asFragment()
        val fsWithFailures: FileSystem =
            createFileSystemInjectingException(outputRootPath.getRelative("dir"), injectedException)
        val rootWithFailure: ArtifactRoot = createOutputRoot(fsWithFailures.getPath(execRoot.asFragment()))
        val outputs: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(
                createOutput(rootWithFailure, "dir/file")
            )

        val e: CreateOutputDirectoryException =
            org.junit.Assert.assertThrows<T>(
                CreateOutputDirectoryException::class.java,
                org.junit.function.ThrowingRunnable { outputDirectoryHelper.createOutputDirectories(outputs) })

        assertThat(e.getDirectoryPath()).isEqualTo(outputRootPath.getRelative("dir"))
        assertThat(e).hasCauseThat().isSameInstanceAs(injectedException)
    }

    @org.junit.Test
    fun createActionFsOutputDirectories_ioExceptionWhenCreatingDirectory_fails() {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val injectedException: IOException = IOException("oh no!")
        val outputRootPath: PathFragment = outputRoot.getRoot().asPath().asFragment()
        val fsWithFailures: FileSystem =
            createFileSystemInjectingException(outputRootPath.getRelative("dir"), injectedException)
        val rootWithFailure: ArtifactRoot = createOutputRoot(fsWithFailures.getPath(execRoot.asFragment()))
        val outputs: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(
                createOutput(rootWithFailure, "dir/file")
            )
        val actionFileSystem: FileSystem = object : DelegateFileSystem(fsWithFailures) {}
        val pathResolver: ArtifactPathResolver? =
            ArtifactPathResolver.createPathResolver(actionFileSystem, execRoot)

        val e: CreateOutputDirectoryException =
            org.junit.Assert.assertThrows<T>(
                CreateOutputDirectoryException::class.java,
                org.junit.function.ThrowingRunnable {
                    outputDirectoryHelper.createActionFsOutputDirectories(
                        outputs,
                        pathResolver
                    )
                })

        assertThat(e.getDirectoryPath()).isEqualTo(outputRootPath.getRelative("dir"))
        assertThat(e).hasCauseThat().isSameInstanceAs(injectedException)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidateTreeArtifactDirectoryCreation_onlyInvalidatesTreeArtifactDirs() {
        val outputDirectoryHelper: ActionOutputDirectoryHelper = createActionOutputDirectoryHelper()
        val regularOutput: Artifact = createOutput("example/regular/file.txt")
        val treeOutput: SpecialArtifact? = createTreeOutput("example/tree/tree_dir")
        val outputs: com.google.common.collect.ImmutableSet<Artifact?> =
            com.google.common.collect.ImmutableSet.of<Artifact?>(regularOutput, treeOutput)

        outputDirectoryHelper.createOutputDirectories(outputs)
        regularOutput.getPath().getParentDirectory().deleteTree()
        treeOutput.getPath().deleteTree()
        outputDirectoryHelper.invalidateTreeArtifactDirectoryCreation(outputs)
        outputDirectoryHelper.createOutputDirectories(outputs)

        // Only tree artifact directories are recreated.
        assertThat(regularOutput.getPath().getParentDirectory().exists()).isFalse()
        assertThat(treeOutput.getPath().exists()).isTrue()
    }

    private fun createFileSystemInjectingException(
        failingPath: PathFragment?, injectedException: IOException
    ): FileSystem {
        return object : DelegateFileSystem(execRoot.getFileSystem()) {
            @Throws(IOException::class)
            public override fun createDirectory(path: PathFragment): Boolean {
                if (path.equals(failingPath)) {
                    throw injectedException
                }
                return super.createDirectory(path)
            }

            @Throws(IOException::class)
            public override fun createDirectoryAndParents(path: PathFragment) {
                if (path.equals(failingPath)) {
                    throw injectedException
                }
                super.createDirectoryAndParents(path)
            }
        }
    }

    private fun createTreeOutput(relativeExecPath: String?): SpecialArtifact? {
        return createTreeArtifactWithGeneratingAction(
            outputRoot, outputRoot.getExecPath().getRelative(relativeExecPath)
        )
    }

    private fun createOutput(relativeExecPath: String?): Artifact {
        return createOutput(outputRoot, relativeExecPath)
    }

    companion object {
        private fun createOutput(outputRoot: ArtifactRoot, relativeExecPath: String?): Artifact {
            return ActionsTestUtil.Companion.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create(relativeExecPath)
            )
        }

        private fun createOutputRoot(execRoot: Path?): ArtifactRoot {
            return ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out")
        }

        private fun createActionOutputDirectoryHelper(): ActionOutputDirectoryHelper {
            return ActionOutputDirectoryHelper(DirectoryCache.CACHE_ENABLED.spec)
        }
    }
}
