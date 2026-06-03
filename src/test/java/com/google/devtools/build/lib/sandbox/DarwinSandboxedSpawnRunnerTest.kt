// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput

/**
 * Tests for [DarwinSandboxedSpawnRunner].
 * 
 * 
 * These tests do not require macOS to run because we have no easy means of expressing that
 * requirement. Instead, we just implement "unit-like" tests by mocking out the tools that this
 * spawn runner requires and rely on our shell-level integration tests to validate this properly.
 */
@RunWith(JUnit4::class)
class DarwinSandboxedSpawnRunnerTest : SandboxedSpawnRunnerTestCase() {
    /** Environment for the running test.  */
    private var commandEnvironment: CommandEnvironment? = null

    /** Path to the base of the sandbox to pass to the spawn runner.  */
    private var sandboxBase: Path? = null

    /** Location of the real `sandbox-exec` binary; saved while the test is running.  */
    private var oldSandboxExec: String? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        commandEnvironment = runtimeWrapper.newCommand()
        commandEnvironment
            .getLocalResourceManager()
            .setAvailableResources(LocalHostCapacity.getLocalHostCapacity())

        val execRoot: Path = commandEnvironment.getExecRoot()
        execRoot.createDirectory()
        SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot)

        sandboxBase = execRoot.getRelative("sandbox")
        sandboxBase.createDirectory()

        // The mock sandbox-exec just executes the given command and returns its output.
        val sandboxExec: Path = execRoot.getRelative("sandbox-exec")
        FileSystemUtils.writeContentAsLatin1(
            sandboxExec,
            ("#!/bin/sh\n"
                    + "shift\n" // Skip -f flag.
                    + "shift\n" // Skip target of -f flag.
                    + "exec \"$@\"\n")
        ) // Remaining arguments are the process-wrapper's ones.
        sandboxExec.setExecutable(true)
        oldSandboxExec = DarwinSandboxedSpawnRunner.sandboxExecBinary
        DarwinSandboxedSpawnRunner.sandboxExecBinary = sandboxExec.toString()
    }

    @org.junit.After
    fun tearDown() {
        DarwinSandboxedSpawnRunner.sandboxExecBinary = oldSandboxExec
    }

    @Throws(java.lang.Exception::class)
    private fun doSimpleExecutionTest(runner: DarwinSandboxedSpawnRunner) {
        val spawn: Spawn = SpawnBuilder("/bin/sh", "-c", "exit 42").build()

        val fileOutErr: FileOutErr =
            FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"))
        val policy: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofMinutes(1))

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.NON_ZERO_EXIT)
        assertThat(spawnResult.exitCode()).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleExecution() {
        val runner: DarwinSandboxedSpawnRunner =
            DarwinSandboxedSpawnRunner(commandEnvironment, sandboxBase, treeDeleter)
        doSimpleExecutionTest(runner)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSupportsParamFiles() {
        val runner: DarwinSandboxedSpawnRunner =
            DarwinSandboxedSpawnRunner(commandEnvironment, sandboxBase, treeDeleter)
        val spawn: Spawn =
            SpawnBuilder("cp", "params/param-file", "out")
                .withInput(
                    ParamFileActionInput(
                        PathFragment.create("params/param-file"),
                        com.google.common.collect.ImmutableList.of<E?>("--foo", "--bar"),
                        ParameterFileType.UNQUOTED
                    )
                )
                .withOutput("out")
                .build()
        val fileOutErr: FileOutErr =
            FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"))
        val policy: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofMinutes(1))
        val spawnResult: SpawnResult = runner.exec(spawn, policy)
        assertThat(spawnResult.status()).isEqualTo(Status.SUCCESS)
        val paramFile: Path = commandEnvironment.getExecRoot().getRelative("out")
        assertThat(paramFile.exists()).isTrue()
        paramFile.getInputStream().use { inputStream ->
            Truth.assertThat<String?>(
                String(
                    com.google.common.io.ByteStreams.toByteArray(inputStream),
                    java.nio.charset.StandardCharsets.UTF_8
                ).split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
                .asList()
                .containsExactly("--foo", "--bar")
        }
    }

    companion object {
        /** Tree deleter to use by default for all tests.  */
        private val treeDeleter: TreeDeleter = SynchronousTreeDeleter()
    }
}
