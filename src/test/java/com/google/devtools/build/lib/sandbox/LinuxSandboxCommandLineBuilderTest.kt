// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS_WITH_LOOPBACK

/** Unit tests for [LinuxSandboxCommandLineBuilderTest].  */
@RunWith(JUnit4::class)
class LinuxSandboxCommandLineBuilderTest {
    private var testFS: FileSystem? = null

    @Before
    fun createFileSystem() {
        testFS = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    @org.junit.Test
    fun testLinuxSandboxCommandLineBuilder_fakeRootAndFakeUsernameAreExclusive() {
        val linuxSandboxPath: Path? = testFS.getPath("/linux-sandbox")
        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, flo")

        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
                        .setUseFakeRoot(true)
                        .setUseFakeUsername(true)
                        .buildForCommand(commandArguments)
                })
        Truth.assertThat(e).hasMessageThat().contains("exclusive")
    }

    @org.junit.Test
    fun testLinuxSandboxCommandLineBuilder_buildsWithoutOptionalArguments() {
        val linuxSandboxPath: Path = testFS.getPath("/linux-sandbox")

        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, max")

        val expectedCommandLine: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add(linuxSandboxPath.getPathString())
                .add("--")
                .addAll(commandArguments)
                .build()

        val commandLine: MutableList<String?>? =
            LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
                .buildForCommand(commandArguments)

        Truth.assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder()
    }

    @org.junit.Test
    fun testLinuxSandboxCommandLineBuilder_buildsWithOptionalArguments() {
        val linuxSandboxPath: Path = testFS.getPath("/linux-sandbox")

        val commandArguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("echo", "hello, tom")

        val timeout: java.time.Duration = java.time.Duration.ofSeconds(10)
        val killDelay: java.time.Duration = java.time.Duration.ofSeconds(2)

        val sandboxDebugPath: Path = testFS.getPath("/debug.out")
        val statisticsPath: Path = testFS.getPath("/stats.out")

        val workingDirectory: Path = testFS.getPath("/all-work-and-no-play")
        val stdoutPath: Path = testFS.getPath("/stdout.txt")
        val stderrPath: Path = testFS.getPath("/stderr.txt")

        // These two flags are exclusive.
        val useFakeUsername = true
        val useFakeRoot = false

        val createNetworkNamespace = true
        val useFakeHostname = true

        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workDir: Path = fileSystem.getPath("/work")
        val concreteDir: Path = workDir.getRelative("concrete")
        val sandboxDir: Path = workDir.getRelative("sandbox")

        val bindMountSource1: Path? = concreteDir.getRelative("bindMountSource1")
        val bindMountSource2: Path? = concreteDir.getRelative("bindMountSource2")
        val mountDir: Path = sandboxDir.getRelative("mount")
        val bindMountTarget1: Path? = mountDir.getRelative("bindMountTarget1")
        val bindMountTarget2: Path? = mountDir.getRelative("bindMountTarget2")
        val bindMountSameSourceAndTarget: Path? = mountDir.getRelative("bindMountSourceAndTarget")

        val writableDir1: Path = sandboxDir.getRelative("writable1")
        val writableDir2: Path? = sandboxDir.getRelative("writable2")

        val tmpfsDir1: PathFragment = sandboxDir.asFragment().getRelative("tmpfs1")
        val tmpfsDir2: PathFragment? = sandboxDir.asFragment().getRelative("tmpfs2")

        val writableFilesAndDirectories: com.google.common.collect.ImmutableSet<Path?> =
            com.google.common.collect.ImmutableSet.of<Path?>(writableDir1, writableDir2)

        val tmpfsDirectories: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.of<PathFragment?>(tmpfsDir1, tmpfsDir2)

        val bindMounts: com.google.common.collect.ImmutableMap<Path?, Path?> =
            com.google.common.collect.ImmutableSortedMap.naturalOrder<Path?, Path?>()
                .put(bindMountSameSourceAndTarget, bindMountSameSourceAndTarget)
                .put(bindMountTarget1, bindMountSource1)
                .put(bindMountTarget2, bindMountSource2)
                .buildOrThrow()

        val cgroupsDir: Path = fileSystem.getPath("/sys/fs/cgroups/something")

        val expectedCommandLine: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add(linuxSandboxPath.getPathString())
                .add("-W", workingDirectory.getPathString())
                .add("-T", timeout.toSeconds().toString())
                .add("-t", killDelay.toSeconds().toString())
                .add("-l", stdoutPath.getPathString())
                .add("-L", stderrPath.getPathString())
                .add("-w", writableDir1.getPathString())
                .add("-w", writableDir2.getPathString())
                .add("-e", tmpfsDir1.getPathString())
                .add("-e", tmpfsDir2.getPathString())
                .add("-M", bindMountSameSourceAndTarget.getPathString())
                .add("-M", bindMountSource1.getPathString())
                .add("-m", bindMountTarget1.getPathString())
                .add("-M", bindMountSource2.getPathString())
                .add("-m", bindMountTarget2.getPathString())
                .add("-S", statisticsPath.getPathString())
                .add("-H")
                .add("-N")
                .add("-U")
                .add("-D", sandboxDebugPath.getPathString())
                .add("-p")
                .add("-C", cgroupsDir.toString())
                .add("--")
                .addAll(commandArguments)
                .build()

        val commandLine: MutableList<String?>? =
            LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandboxPath)
                .setWorkingDirectory(workingDirectory)
                .setStdoutPath(stdoutPath)
                .setStderrPath(stderrPath)
                .setTimeout(timeout)
                .setKillDelay(killDelay)
                .setWritableFilesAndDirectories(writableFilesAndDirectories)
                .setTmpfsDirectories(tmpfsDirectories)
                .setBindMounts(bindMounts)
                .setUseFakeHostname(useFakeHostname)
                .setCreateNetworkNamespace(if (createNetworkNamespace) NETNS_WITH_LOOPBACK else NO_NETNS)
                .setUseFakeRoot(useFakeRoot)
                .setStatisticsPath(statisticsPath)
                .setUseFakeUsername(useFakeUsername)
                .setSandboxDebugPath(sandboxDebugPath.getPathString())
                .setPersistentProcess(true)
                .setCgroupsDirs(com.google.common.collect.ImmutableSet.of<E?>(cgroupsDir.getPathFile().toPath()))
                .buildForCommand(commandArguments)

        Truth.assertThat(commandLine).containsExactlyElementsIn(expectedCommandLine).inOrder()
    }
}
