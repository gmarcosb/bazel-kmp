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

import com.google.devtools.build.lib.actions.LocalHostCapacity

/** Tests for [ProcessWrapperSandboxSpawnRunner].  */
@RunWith(JUnit4::class)
class ProcessWrapperSandboxedSpawnRunnerTest : SandboxedSpawnRunnerTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun processWrapperSandboxedSpawnRunner_canRunEcho() {
        // TODO(b/62588075) Currently no process-wrapper support in windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val commandEnvironment: CommandEnvironment = runtimeWrapper.newCommand()
        commandEnvironment
            .getLocalResourceManager()
            .setAvailableResources(LocalHostCapacity.getLocalHostCapacity())

        val execRoot: Path = commandEnvironment.getExecRoot()
        execRoot.createDirectory()

        SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot)

        val sandboxBase: Path = execRoot.getRelative("sandbox")
        sandboxBase.createDirectory()

        val policyTimeout: java.time.Duration? = java.time.Duration.ofSeconds(60)

        val runner: ProcessWrapperSandboxedSpawnRunner =
            ProcessWrapperSandboxedSpawnRunner(commandEnvironment, sandboxBase, treeDeleter)

        val spawn: Spawn = SpawnBuilder("echo", "cooee").build()

        val fileOutErr: FileOutErr =
            FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"))
        val policy: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, policyTimeout)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(spawnResult.exitCode()).isEqualTo(0)
        assertThat(spawnResult.setupSuccess()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasExecutionStatistics_whenOptionIsEnabled() {
        // TODO(b/62588075) Currently no process-wrapper or execution statistics support in Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        val minimumWallTimeToSpend = 10 * 1000
        // Because of e.g. interference, wall time taken may be much larger than CPU time used.
        val maximumWallTimeToSpend = 40 * 1000

        val minimumUserTimeToSpend = minimumWallTimeToSpend
        val maximumUserTimeToSpend = minimumUserTimeToSpend + 2 * 1000

        val minimumSystemTimeToSpend = 0
        val maximumSystemTimeToSpend = minimumSystemTimeToSpend + 2 * 1000

        val commandEnvironment: CommandEnvironment = runtimeWrapper.newCommand()
        commandEnvironment
            .getLocalResourceManager()
            .setAvailableResources(LocalHostCapacity.getLocalHostCapacity())
        val execRoot: Path = commandEnvironment.getExecRoot()
        execRoot.createDirectory()

        SpawnRunnerTestUtil.copyProcessWrapperIntoPath(execRoot)

        val sandboxBase: Path = execRoot.getRelative("sandbox")
        sandboxBase.createDirectory()

        val cpuTimeSpenderPath: Path = SpawnRunnerTestUtil.copyCpuTimeSpenderIntoPath(execRoot)

        val policyTimeout: java.time.Duration? = java.time.Duration.ofSeconds(60)

        val runner: ProcessWrapperSandboxedSpawnRunner =
            ProcessWrapperSandboxedSpawnRunner(commandEnvironment, sandboxBase, treeDeleter)

        val spawn: Spawn =
            SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                (minimumUserTimeToSpend / 1000).toString(),
                (minimumSystemTimeToSpend / 1000).toString()
            )
                .build()

        val fileOutErr: FileOutErr =
            FileOutErr(testRoot.getChild("stdout"), testRoot.getChild("stderr"))
        val policy: SpawnExecutionContextForTesting =
            SpawnExecutionContextForTesting(spawn, fileOutErr, policyTimeout)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(spawnResult.exitCode()).isEqualTo(0)
        assertThat(spawnResult.setupSuccess()).isTrue()

        assertThat(spawnResult.getWallTimeInMs()).isAtLeast(minimumWallTimeToSpend)
        assertThat(spawnResult.getWallTimeInMs()).isAtMost(maximumWallTimeToSpend)
        assertThat(spawnResult.getUserTimeInMs()).isAtLeast(minimumUserTimeToSpend)
        assertThat(spawnResult.getUserTimeInMs()).isAtMost(maximumUserTimeToSpend)
        assertThat(spawnResult.getSystemTimeInMs()).isAtLeast(minimumSystemTimeToSpend)
        assertThat(spawnResult.getSystemTimeInMs()).isAtMost(maximumSystemTimeToSpend)
        assertThat(spawnResult.getNumBlockOutputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumBlockInputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isAtLeast(0L)
    }

    companion object {
        /** Tree deleter to use by default for all tests.  */
        private val treeDeleter: TreeDeleter = SynchronousTreeDeleter()
    }
}
