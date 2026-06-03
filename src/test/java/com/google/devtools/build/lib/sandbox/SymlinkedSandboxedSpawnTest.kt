// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs

/** Tests for [SymlinkedSandboxedSpawn].  */
@RunWith(JUnit4::class)
class SymlinkedSandboxedSpawnTest {
    private var workspaceDir: Path? = null
    private var sandboxDir: Path? = null
    private var execRoot: Path? = null
    private var outputsDir: Path? = null

    @Before
    @Throws(IOException::class)
    fun setupTestDirs() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testRoot: Path = fileSystem.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
        testRoot.createDirectoryAndParents()

        workspaceDir = testRoot.getRelative("workspace")
        workspaceDir.createDirectory()
        sandboxDir = testRoot.getRelative("sandbox")
        sandboxDir.createDirectory()
        execRoot = sandboxDir.getRelative("execroot")
        execRoot.createDirectory()
        outputsDir = testRoot.getRelative("outputs")
        outputsDir.createDirectory()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFileSystem() {
        val helloTxt: Path = workspaceDir.getRelative("hello.txt")
        FileSystemUtils.createEmptyFile(helloTxt)

        val symlinkedExecRoot: SymlinkedSandboxedSpawn =
            SymlinkedSandboxedSpawn(
                sandboxDir,
                execRoot,
                com.google.common.collect.ImmutableList.of<E?>("/bin/true"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                SandboxInputs(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(PathFragment.create("such/input.txt"), helloTxt),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                ),
                SandboxOutputs.create(
                    com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("very/output.txt")),
                    com.google.common.collect.ImmutableSet.of<E?>()
                ),
                com.google.common.collect.ImmutableSet.of<E?>(execRoot.getRelative("wow/writable")),
                SynchronousTreeDeleter(),  /* sandboxDebugPath= */
                null,  /* statisticsPath= */
                null,  /* interactiveDebugArguments= */
                null,
                "SomeMnemonic",  /* targetLabel= */
                null
            )

        symlinkedExecRoot.createFileSystem()

        assertThat(execRoot.getRelative("such/input.txt").isSymbolicLink()).isTrue()
        assertThat(execRoot.getRelative("such/input.txt").resolveSymbolicLinks()).isEqualTo(helloTxt)
        assertThat(execRoot.getRelative("very").isDirectory()).isTrue()
        assertThat(execRoot.getRelative("wow/writable").isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun copyOutputs() {
        // These tests are very simple because we just rely on
        // AbstractContainerizingSandboxedSpawnTest.testMoveOutputs to properly verify all corner cases.
        val outputFile: Path = execRoot.getRelative("very/output.txt")

        val symlinkedExecRoot: SymlinkedSandboxedSpawn =
            SymlinkedSandboxedSpawn(
                sandboxDir,
                execRoot,
                com.google.common.collect.ImmutableList.of<E?>("/bin/true"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                SandboxInputs(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                ),
                SandboxOutputs.create(
                    com.google.common.collect.ImmutableSet.of<E?>(outputFile.relativeTo(execRoot)),
                    com.google.common.collect.ImmutableSet.of<E?>()
                ),
                com.google.common.collect.ImmutableSet.of<E?>(),
                SynchronousTreeDeleter(),  /* sandboxDebugPath= */
                null,  /* statisticsPath= */
                null,  /* interactiveDebugArguments= */
                null,
                "SomeMnemonic",  /* targetLabel= */
                null
            )
        symlinkedExecRoot.createFileSystem()

        FileSystemUtils.createEmptyFile(outputFile)

        outputsDir.getRelative("very").createDirectory()
        symlinkedExecRoot.copyOutputs(outputsDir)

        assertThat(outputsDir.getRelative("very/output.txt").isFile(Symlinks.NOFOLLOW)).isTrue()
    }
}
