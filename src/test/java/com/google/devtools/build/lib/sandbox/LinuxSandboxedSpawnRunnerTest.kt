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

import com.google.devtools.build.lib.actions.Artifact

/** Tests for [LinuxSandboxedSpawnRunner].  */
@RunWith(TestParameterInjector::class)
class LinuxSandboxedSpawnRunnerTest : SandboxedSpawnRunnerTestCase() {
    @Before
    fun assumeRunningOnLinux() {
        TruthJUnit.assume()
            .that<com.google.devtools.build.lib.util.OS?>(com.google.devtools.build.lib.util.OS.getCurrent())
            .isEqualTo(com.google.devtools.build.lib.util.OS.LINUX)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_echoCommand_executesSuccessfully() {
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(createCommandEnvironment())
        val spawn: Spawn = SpawnBuilder("echo", "echolalia").build()
        val stdout: Path? = testRoot.getChild("stdout")
        val policy: SpawnExecutionContext = createSpawnExecutionContext(spawn, stdout)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(spawnResult.exitCode()).isEqualTo(0)
        assertThat(spawnResult.setupSuccess()).isTrue()
        assertThat(spawnResult.getWallTimeInMs()).isGreaterThan(0)
        assertThat(
            FileSystemUtils.readLines(
                stdout,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("echolalia")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_commandWithParamFiles_executesSuccessfully() {
        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)
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
        val policy: SpawnExecutionContext = createSpawnExecutionContext(spawn)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        val paramFile: Path = commandEnvironment.getExecRoot().getRelative("out")
        assertThat(paramFile.exists()).isTrue()
        assertThat(FileSystemUtils.readLines(paramFile, java.nio.charset.StandardCharsets.UTF_8))
            .containsExactly("--foo", "--bar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_spawnRunningBinTool_executesSuccessfully() {
        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)
        val pathActionInput: PathActionInput =
            PathActionInput(
                Scratch().file("/execRoot/tool", "#!/bin/bash", "echo hello > $1"),
                PathFragment.create("_bin/tool")
            )
        val output: Artifact =
            ActionsTestUtil.createArtifact(
                ArtifactRoot.asDerivedRoot(
                    commandEnvironment.getExecRoot(), RootType.OUTPUT, "blaze-out"
                ),
                commandEnvironment.getExecRoot().getRelative("blaze-out/output")
            )
        val spawn: Spawn =
            SpawnBuilder("_bin/tool", output.getExecPathString())
                .withInput(pathActionInput)
                .withOutput(output)
                .build()
        val policy: SpawnExecutionContext = createSpawnExecutionContext(spawn)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(
            FileSystemUtils.readLines(
                output.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).containsExactly("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exec_collectsExecutionStatistics() {
        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)
        val cpuTimeSpenderPath: Path =
            SpawnRunnerTestUtil.copyCpuTimeSpenderIntoPath(commandEnvironment.getExecRoot())
        val minimumWallTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(10)
        // Because of e.g. interference, wall time taken may be much larger than CPU time used.
        val maximumWallTimeToSpend: java.time.Duration = java.time.Duration.ofSeconds(40)
        val minimumUserTimeToSpend: java.time.Duration = minimumWallTimeToSpend
        val maximumUserTimeToSpend: java.time.Duration = minimumUserTimeToSpend.plusSeconds(2)
        val minimumSystemTimeToSpend: java.time.Duration = java.time.Duration.ZERO
        val maximumSystemTimeToSpend: java.time.Duration = minimumSystemTimeToSpend.plusSeconds(2)
        val spawn: Spawn =
            SpawnBuilder(
                cpuTimeSpenderPath.getPathString(),
                minimumUserTimeToSpend.toSeconds().toString(),
                minimumSystemTimeToSpend.toSeconds().toString()
            )
                .build()
        val policy: SpawnExecutionContextForTesting = createSpawnExecutionContext(spawn)

        val spawnResult: SpawnResult = runner.exec(spawn, policy)

        assertThat(spawnResult.status()).isEqualTo(SpawnResult.Status.SUCCESS)
        assertThat(spawnResult.exitCode()).isEqualTo(0)
        assertThat(spawnResult.setupSuccess()).isTrue()
        assertThat(spawnResult.getWallTimeInMs()).isAtLeast(minimumWallTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getWallTimeInMs()).isAtMost(maximumWallTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getUserTimeInMs()).isAtLeast(minimumUserTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getUserTimeInMs()).isAtMost(maximumUserTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getSystemTimeInMs())
            .isAtLeast(minimumSystemTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getSystemTimeInMs()).isAtMost(maximumSystemTimeToSpend.toMillis().toInt())
        assertThat(spawnResult.getNumBlockOutputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumBlockInputOperations()).isAtLeast(0L)
        assertThat(spawnResult.getNumInvoluntaryContextSwitches()).isAtLeast(0L)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hermeticTmp_tmpCreatedAndMounted() {
        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)
        val spawn: Spawn = SpawnBuilder().build()
        val sandboxedSpawn: SandboxedSpawn = runner.prepareSpawn(spawn, createSpawnExecutionContext(spawn))

        val sandboxPath: Path =
            sandboxedSpawn.sandboxExecRoot.getParentDirectory().getParentDirectory()
        val hermeticTmpPath: Path = sandboxPath.getRelative("_hermetic_tmp")
        assertThat(hermeticTmpPath.isDirectory()).isTrue()

        assertThat(sandboxedSpawn).isInstanceOf(SymlinkedSandboxedSpawn::class.java)
        val args: String? = java.lang.String.join(" ", sandboxedSpawn.getArguments())
        Truth.assertThat(args).contains("-w /tmp")
        Truth.assertThat(args).contains("-M " + hermeticTmpPath + " -m /tmp")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hermeticTmp_sandboxTmpfsOnTmp_tmpNotCreatedOrMounted() {
        runtimeWrapper.addOptions("--sandbox_tmpfs_path=/tmp")
        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)
        val spawn: Spawn = SpawnBuilder().build()
        val sandboxedSpawn: SandboxedSpawn = runner.prepareSpawn(spawn, createSpawnExecutionContext(spawn))

        val sandboxPath: Path =
            sandboxedSpawn.sandboxExecRoot.getParentDirectory().getParentDirectory()
        val hermeticTmpPath: Path = sandboxPath.getRelative("_hermetic_tmp")
        assertThat(hermeticTmpPath.isDirectory()).isFalse()

        assertThat(sandboxedSpawn).isInstanceOf(SymlinkedSandboxedSpawn::class.java)
        val args: String? = java.lang.String.join(" ", sandboxedSpawn.getArguments())
        Truth.assertThat(args).contains("-w /tmp")
        Truth.assertThat(args).contains("-e /tmp")
        Truth.assertThat(args).doesNotContain("-m /tmp")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWritableDirs_withoutDevShm() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workDir: Path = fs.getPath("/base/workDir")
        workDir.createDirectoryAndParents()
        fs.getPath("/tmp").createDirectoryAndParents()

        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)

        val writableDirs: com.google.common.collect.ImmutableSet<Path?>? =
            runner.getWritableDirs(workDir, com.google.common.collect.ImmutableMap.of<K?, V?>("TMPDIR", "/tmp"))

        Truth.assertThat(writableDirs).contains(fs.getPath("/tmp"))
        Truth.assertThat(writableDirs).doesNotContain(fs.getPath("/dev/shm"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWritableDirs_withDevShm() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workDir: Path = fs.getPath("/base/workDir")
        workDir.createDirectoryAndParents()
        fs.getPath("/tmp").createDirectoryAndParents()
        fs.getPath("/dev/shm").createDirectoryAndParents()

        val commandEnvironment: CommandEnvironment = createCommandEnvironment()
        val runner: LinuxSandboxedSpawnRunner = setupSandboxAndCreateRunner(commandEnvironment)

        val writableDirs: com.google.common.collect.ImmutableSet<Path?>? =
            runner.getWritableDirs(workDir, com.google.common.collect.ImmutableMap.of<K?, V?>("TMPDIR", "/tmp"))

        Truth.assertThat(writableDirs).contains(fs.getPath("/tmp"))
        Truth.assertThat(writableDirs).contains(fs.getPath("/dev/shm"))
    }

    private fun createSpawnExecutionContext(spawn: Spawn?): SpawnExecutionContextForTesting {
        return createSpawnExecutionContext(spawn, testRoot.getChild("stdout"))
    }

    private fun createSpawnExecutionContext(spawn: Spawn?, stdout: Path?): SpawnExecutionContextForTesting {
        val fileOutErr: FileOutErr = FileOutErr(stdout, testRoot.getChild("stderr"))
        return SpawnExecutionContextForTesting(spawn, fileOutErr, java.time.Duration.ofMinutes(1))
    }

    @Throws(java.lang.Exception::class)
    private fun createCommandEnvironment(): CommandEnvironment {
        val commandEnvironment: CommandEnvironment = runtimeWrapper.newCommand()
        commandEnvironment
            .getLocalResourceManager()
            .setAvailableResources(LocalHostCapacity.getLocalHostCapacity())
        return commandEnvironment
    }

    companion object {
        /** Tree deleter to use by default for all tests.  */
        private val treeDeleter: TreeDeleter = SynchronousTreeDeleter()

        @Throws(IOException::class)
        private fun setupSandboxAndCreateRunner(
            commandEnvironment: CommandEnvironment
        ): LinuxSandboxedSpawnRunner {
            val execRoot: Path = commandEnvironment.getExecRoot()
            execRoot.createDirectory()

            SpawnRunnerTestUtil.copyLinuxSandboxIntoPath(execRoot)

            val sandboxBase: Path = execRoot.getRelative("sandbox")
            sandboxBase.createDirectory()

            val sandboxOptions: SandboxOptions =
                com.google.devtools.common.options.Options.getDefaults<O>(SandboxOptions::class.java)
            sandboxOptions.setSandboxBlockPath(com.google.common.collect.ImmutableList.of<E?>())
            return LinuxSandboxedStrategy.create(
                commandEnvironment,
                sandboxBase,  /* timeoutKillDelay= */
                java.time.Duration.ofSeconds(2),
                treeDeleter,
                sandboxOptions
            )
        }
    }
}
