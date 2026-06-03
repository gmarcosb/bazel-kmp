// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.standalone

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.vfs.util.FileSystems
import com.google.devtools.common.options.Options
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Test StandaloneSpawnStrategy.  */
@RunWith(JUnit4::class)
class StandaloneSpawnStrategyTest {
    private val reporter = Reporter(
        EventBusEventHandler.createWithNewEventBus(),
        PrintingEventHandler.ERRORS_AND_WARNINGS_TO_STDERR
    )
    private var executor: BlazeExecutor? = null
    private var fileSystem: FileSystem? = null
    private var outErr: FileOutErr? = null

    @Throws(IOException::class)
    private fun createTestRoot(): Path {
        fileSystem = FileSystems.getNativeFileSystem()
        val testRoot: Path = fileSystem.getPath(TestUtils.tmpDir()).getRelative("test")
        testRoot.createDirectoryAndParents()
        try {
            testRoot.deleteTreesBelow()
        } catch (e: IOException) {
            System.err.println("Failed to remove directory " + testRoot + ": " + e.message)
            throw e
        }
        return testRoot
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        val testRoot: Path = createTestRoot()
        val workspaceDir: Path = testRoot.getRelative("workspace-name")
        workspaceDir.createDirectory()
        outErr = FileOutErr(testRoot.getRelative("stdout"), testRoot.getRelative("stderr"))

        // setup output base & directories
        val outputBase: Path = testRoot.getRelative("outputBase")
        outputBase.createDirectory()

        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(outputBase, outputBase, outputBase),
                workspaceDir,
                "mock-product-name"
            )
        // This call implicitly symlinks the integration bin tools into the exec root.
        IntegrationMock.get().getIntegrationBinTools(fileSystem, directories)
        val optionsParser: OptionsParser =
            OptionsParser.builder().optionsClasses(ExecutionOptions::class.java).build()
        optionsParser.parse("--verbose_failures")
        val localExecutionOptions: LocalExecutionOptions? = Options.getDefaults<O?>(LocalExecutionOptions::class.java)

        val resourceManager: ResourceManager = ResourceManager()
        resourceManager.setAvailableResources(
            ResourceSet.create( /* memoryMb= */1,  /* cpu= */1,  /* localTestCount= */1)
        )
        val execRoot: Path? = directories.getExecRoot(TestConstants.WORKSPACE_NAME)
        val binTools: BinTools? = BinTools.forIntegrationTesting(directories, ImmutableList.of<E?>())
        val strategy =
            StandaloneSpawnStrategy(
                LocalSpawnRunner(
                    execRoot,
                    localExecutionOptions,
                    resourceManager,
                    { env, binTools1, fallbackTmpDir -> ImmutableMap.copyOf(env) },
                    binTools,  /* processWrapper= */
                    null,
                    Mockito.< T > mock < T ? > (RunfilesTreeUpdater::class.java)
                ),
                Options.getDefaults<O?>(ExecutionOptions::class.java)
            )
        this.executor =
            TestExecutorBuilder(fileSystem, directories)
                .addStrategy(strategy, "standalone")
                .setDefaultStrategies("standalone")
                .build()

        executor.getExecRoot().createDirectoryAndParents()
    }

    private fun out(): String {
        return outErr.outAsLatin1()
    }

    private fun err(): String {
        return outErr.errAsLatin1()
    }

    @Test
    @Throws(Exception::class)
    fun testBinTrueExecutesFine() {
        val spawn: Spawn = createSpawn(trueCommand)
        executor.getContext(SpawnStrategyResolver::class.java).exec(spawn, createContext())

        if (OS.getCurrent() != OS.WINDOWS) {
            Truth.assertThat(out()).isEmpty()
        }
        Truth.assertThat(err()).isEmpty()
    }

    @Throws(Exception::class)
    private fun run(spawn: Spawn?): MutableList<SpawnResult?> {
        return executor.getContext(SpawnStrategyResolver::class.java).exec(spawn, createContext())
    }

    private fun createContext(): ActionExecutionContext {
        val execRoot: Path = executor.getExecRoot()
        return ActionExecutionContext(
            executor,
            SingleBuildFileCache(
                execRoot.getPathString(),
                PathFragment.create("dummy-output-path"),
                execRoot.getFileSystem(),
                SyscallCache.NO_CACHE
            ),
            ActionInputPrefetcher.NONE,
            ActionKeyContext(),  /* outputMetadataStore= */
            null,  /* rewindingEnabled= */
            false,
            LostInputsCheck.NONE,
            outErr,
            reporter,  /* clientEnv= */
            System.getenv(),  /* actionFileSystem= */
            null,
            DiscoveredModulesPruner.DEFAULT,
            SyscallCache.NO_CACHE,
            ThreadStateReceiver.NULL_INSTANCE
        )
    }

    @Test
    fun testBinFalseYieldsException() {
        val e: ExecException = Assert.assertThrows<T>(ExecException::class.java, ThrowingRunnable {
            run(
                createSpawn(
                    falseCommand
                )
            )
        })
        assertWithMessage("got: %s", e.getMessage())
            .that(e.getMessage().contains("failed: error executing Null command"))
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testBinEchoPrintsArguments() {
        val spawn: Spawn?
        if (OS.getCurrent() == OS.WINDOWS) {
            spawn = createSpawn(CMD_EXE, "/c", "echo", "Hello,", "world.")
        } else {
            spawn = createSpawn("/bin/echo", "Hello,", "world.")
        }
        run(spawn)
        Truth.assertThat(out()).isEqualTo("Hello, world." + System.lineSeparator())
        Truth.assertThat(err()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testCommandRunsInWorkingDir() {
        val spawn: Spawn?
        if (OS.getCurrent() == OS.WINDOWS) {
            spawn = createSpawn(CMD_EXE, "/c", "cd")
        } else {
            spawn = createSpawn("/bin/pwd")
        }
        run(spawn)
        Truth.assertThat(out().replace('\\', '/')).isEqualTo(executor.getExecRoot() + System.lineSeparator())
    }

    @Test
    @Throws(Exception::class)
    fun testCommandHonorsEnvironment() {
        val spawn: Spawn =
            SimpleSpawn(
                NullAction(),
                if (OS.getCurrent() == OS.WINDOWS)
                    ImmutableList.of<E?>(CMD_EXE, "/c", "set")
                else
                    ImmutableList.of<E?>("/usr/bin/env"),  /* environment= */
                ImmutableMap.of<K?, V?>("foo", "bar", "baz", "boo"),  /* executionInfo= */
                ImmutableMap.of<K?, V?>(),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )
        run(spawn)
        val environment: HashSet<String?> =
            Sets.newHashSet<String?>(*out().split(System.lineSeparator().toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray())
        if (OS.getCurrent() == OS.WINDOWS || OS.getCurrent() == OS.DARWIN) {
            // On Windows and macOS, we may have some other env vars
            // (eg. SystemRoot or __CF_USER_TEXT_ENCODING).
            Truth.assertThat(environment).contains("foo=bar")
            Truth.assertThat(environment).contains("baz=boo")
        } else {
            Truth.assertThat(environment).isEqualTo(Sets.newHashSet<String?>("foo=bar", "baz=boo"))
        }
    }

    @Test
    @Throws(Exception::class)
    fun testStandardError() {
        val spawn: Spawn?
        if (OS.getCurrent() == OS.WINDOWS) {
            spawn = createSpawn(CMD_EXE, "/c", "echo Oops!>&2")
        } else {
            spawn = createSpawn("/bin/sh", "-c", "echo Oops! >&2")
        }
        run(spawn)
        Truth.assertThat(err()).isEqualTo("Oops!" + System.lineSeparator())
        Truth.assertThat(out()).isEmpty()
    }

    /**
     * Regression test for https://github.com/bazelbuild/bazel/issues/10572 Make sure we do have the
     * command line executed in the error message of ActionExecutionException when --verbose_failures
     * is enabled.
     */
    @Test
    fun testVerboseFailures() {
        val e: ExecException? = Assert.assertThrows<T?>(ExecException::class.java, ThrowingRunnable {
            run(
                createSpawn(
                    falseCommand
                )
            )
        })
        val actionExecutionException: ActionExecutionException =
            ActionExecutionException.fromExecException(e, NullAction())
        assertWithMessage("got: %s", actionExecutionException.getMessage())
            .that(
                actionExecutionException.getMessage().contains("failed: error executing Null command")
            )
            .isTrue()
    }

    companion object {
        init {
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        }

        private const val WINDOWS_SYSTEM_DRIVE = "C:"
        private val CMD_EXE: String = getWinSystemBinary("cmd.exe")

        /**
         * We assume Windows is installed on C: and all system binaries exist under C:\Windows\System32\
         */
        private fun getWinSystemBinary(binary: String?): String {
            return WINDOWS_SYSTEM_DRIVE + "\\Windows\\System32\\" + binary
        }

        private fun createSpawn(vararg arguments: String?): Spawn {
            return SimpleSpawn(
                NullAction(),
                ImmutableList.< E > copyOf < E ? > (arguments),  /* environment= */
                ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                ImmutableMap.of<K?, V?>(),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )
        }

        private val falseCommand: String
            get() {
                if (OS.getCurrent() == OS.WINDOWS) {
                    // No false command on Windows, we use help.exe as an alternative,
                    // the caveat is that the command will have some output to stdout.
                    // Default exit code of help is 1
                    return getWinSystemBinary("help.exe")
                }
                return if (OS.getCurrent() == OS.DARWIN) "/usr/bin/false" else "/bin/false"
            }

        private val trueCommand: String
            get() {
                if (OS.getCurrent() == OS.WINDOWS) {
                    // No true command on Windows, we use whoami.exe as an alternative,
                    // the caveat is that the command will have some output to stdout.
                    // Default exit code of help is 0
                    return getWinSystemBinary("whoami.exe")
                }
                return if (OS.getCurrent() == OS.DARWIN) "/usr/bin/true" else "/bin/true"
            }
    }
}
