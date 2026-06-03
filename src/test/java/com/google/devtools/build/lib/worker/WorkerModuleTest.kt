// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat.JSON

/** Tests for WorkerModule. I bet you didn't see that coming, eh?  */
@RunWith(JUnit4::class)
class WorkerModuleTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    var env: CommandEnvironment? = null

    @org.mockito.Mock
    var request: BuildRequest? = null

    @org.mockito.Mock
    var startupOptionsProvider: OptionsParsingResult? = null

    private val fs: FileSystem =
        InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
    private var storedEventHandler: StoredEventHandler? = null

    @org.junit.Test
    @Throws(AbruptExitException::class, IOException::class, java.lang.InterruptedException::class)
    fun buildStarting_createsPools() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? = WorkerOptions.DEFAULTS
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))

        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()
        assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isFalse()
        assertThat(module.workerPool).isNotNull()

        val workerKey: WorkerKey? = createWorkerKey(JSON, fs)
        val worker: Worker = module.workerPool.borrowWorker(workerKey)

        assertThat(worker.workerKey).isEqualTo(workerKey)
        assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun buildStarting_noRestartOnSandboxChange() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions = WorkerOptions.DEFAULTS
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()

        val workerDir: Path = fs.getPath("/outputRoot/outputBase/bazel-workers")
        val aLog: Path = workerDir.getRelative("f.log")
        workerDir.createDirectoryAndParents()
        aLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT)
        val oldPool: WorkerPool? = module.workerPool
        options.workerSandboxing = !options.workerSandboxing
        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()
        assertThat(module.workerPool).isSameInstanceAs(oldPool)
        assertThat(aLog.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun buildStarting_restartsOnOutputbaseChanges() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? = WorkerOptions.DEFAULTS
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()

        // Log file from old root, doesn't get cleaned
        val workerDir: Path = fs.getPath("/outputRoot/outputBase/bazel-workers")
        val oldLog: Path = workerDir.getRelative("f.log")
        workerDir.createDirectoryAndParents()
        oldLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT)

        val oldPool: WorkerPool? = module.workerPool
        setupEnvironment("/otherRootDir")
        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Worker factory configuration has changed")
        assertThat(module.workerPool).isNotSameInstanceAs(oldPool)
        val workerKey: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnemonic", false)
        module.workerFactory.create(workerKey)
        assertThat(fs.getPath("/otherRootDir/outputBase/bazel-workers").exists()).isTrue()
        assertThat(oldLog.exists()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class, OptionsParsingException::class)
    fun buildStarting_restartsOnUseCgroupsOnLinuxChanges() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? =
            com.google.devtools.common.options.Options.parse(
                WorkerOptions::class.java,
                "--noexperimental_worker_use_cgroups_on_linux"
            ).options
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        // Check that new pools/factories are made with default options
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()

        val oldPool: WorkerPool? = module.workerPool
        val newOptions: WorkerOptions? =
            com.google.devtools.common.options.Options.parse(
                WorkerOptions::class.java,
                "--experimental_worker_use_cgroups_on_linux"
            ).options
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(newOptions)

        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Worker factory configuration has changed")
        assertThat(module.workerPool).isNotSameInstanceAs(oldPool)
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun buildStarting_clearsLogsOnFactoryCreation() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? = WorkerOptions.DEFAULTS
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        val workerDir: Path = fs.getPath("/outputRoot/outputBase/bazel-workers")
        workerDir.createDirectoryAndParents()
        val oldLog: Path = workerDir.getRelative("f.log")
        oldLog.createSymbolicLink(PathFragment.EMPTY_FRAGMENT)

        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))

        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()
        assertThat(fs.getPath("/outputRoot/outputBase/bazel-workers").exists()).isTrue()
        assertThat(oldLog.exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun buildStarting_restartsOnNumMultiplexWorkersChanges() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions = WorkerOptions.DEFAULTS
        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        // Check that new pools/factories are made with default options
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()

        val oldPool: WorkerPool? = module.workerPool
        options.setWorkerMaxMultiplexInstances(
            com.google.common.collect.Lists.< E > newArrayList < E ? > (com.google.common.collect.Maps.immutableEntry<K?, V?>(
                "foo",
                42
            ))
        )
        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Worker pool configuration has changed")
        assertThat(module.workerPool).isNotSameInstanceAs(oldPool)
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun buildStarting_restartsOnNumWorkersChanges() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions = WorkerOptions.DEFAULTS

        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        // Check that new pools/factories are made with default options
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()

        val oldPool: WorkerPool? = module.workerPool
        options.setWorkerMaxInstances(
            com.google.common.collect.Lists.< E > newArrayList < E ? > (com.google.common.collect.Maps.immutableEntry<K?, V?>(
                "bar",
                3
            ))
        )
        module.beforeCommand(env)
        module.buildStarting(buildStartingEvent(request))
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Worker pool configuration has changed")
        assertThat(module.workerPool).isNotSameInstanceAs(oldPool)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildStarting_survivesNoWorkerDir() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? = WorkerOptions.DEFAULTS

        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        val workerDir: Path = fs.getPath("/outputRoot/outputBase/bazel-workers")

        // Check that new pools/factories can be created without a worker dir.
        module.buildStarting(buildStartingEvent(request))

        // But once we try to get a worker, it should fail. This forces a situation where we can't
        // have a workerDir.
        assertThat(workerDir.exists()).isFalse()
        workerDir.getParentDirectory().createDirectoryAndParents()
        workerDir.getParentDirectory().setWritable(false)

        // But an actual worker cannot be created.
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "Work",  /* proxied= */false)
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { module.workerPool.borrowWorker(key) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildStarting_cleansStaleTrashDirCleanedOnFirstBuild() {
        val module: WorkerModule = WorkerModule()
        val options: WorkerOptions? = WorkerOptions.DEFAULTS

        Mockito.`when`<T?>(request.getOptions(WorkerOptions::class.java)).thenReturn(options)
        setupEnvironment("/outputRoot")

        module.beforeCommand(env)
        val workerDir: Path = fs.getPath("/outputRoot/outputBase/bazel-workers")
        val trashBase: Path = workerDir.getChild(AsynchronousTreeDeleter.MOVED_TRASH_DIR)
        trashBase.createDirectoryAndParents()

        val staleTrash: Path = trashBase.getChild("random-trash")

        staleTrash.createDirectoryAndParents()
        module.buildStarting(buildStartingEvent(request))
        // Trash is cleaned upon first build.
        assertThat(staleTrash.exists()).isFalse()

        staleTrash.createDirectoryAndParents()
        module.buildStarting(buildStartingEvent(request))
        // Trash is not cleaned upon subsequent builds.
        assertThat(staleTrash.exists()).isTrue()
    }

    @Throws(IOException::class, AbruptExitException::class)
    private fun setupEnvironment(rootDir: String?) {
        storedEventHandler = StoredEventHandler()
        val root: Path = fs.getPath(rootDir)
        val outputBase: Path = root.getRelative("outputBase")
        outputBase.createDirectoryAndParents()
        Mockito.`when`<T?>(env.getOutputBase()).thenReturn(outputBase)
        val workspace: Path? = fs.getPath("/workspace")
        Mockito.`when`<T?>(env.getWorkingDirectory()).thenReturn(workspace)
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                root.getRelative("userroot/install"), outputBase, root.getRelative("userroot")
            )
        val blazeRuntime: BlazeRuntime? =
            Builder()
                .setProductName("bazel")
                .setServerDirectories(serverDirectories)
                .setStartupOptionsProvider(startupOptionsProvider)
                .build()
        Mockito.`when`<T?>(env.getRuntime()).thenReturn(blazeRuntime)
        val blazeDirectories: BlazeDirectories = BlazeDirectories(serverDirectories, null, "blaze")
        val blazeWorkspace: BlazeWorkspace =
            BlazeWorkspace(
                blazeRuntime,
                blazeDirectories,  /* skyframeExecutor= */
                null,
                RecordingExceptionHandler(),  /* workspaceStatusActionFactory= */
                null,
                BinTools.forUnitTesting(
                    fs.getPath("/execroot"),
                    com.google.common.collect.ImmutableList.of<E?>()
                ),  /* allocationTracker= */
                null,  /* syscallCache= */
                null,  /* analysisCodecRegistrySupplier= */
                null,  /* fingerprintValueServiceFactory= */
                null,  /* allowExternalRepositories= */
                true
            )
        Mockito.`when`<T?>(env.getBlazeWorkspace()).thenReturn(blazeWorkspace)
        Mockito.`when`<T?>(env.getDirectories()).thenReturn(blazeDirectories)
        val eventBus: com.google.common.eventbus.EventBus = com.google.common.eventbus.EventBus()
        Mockito.`when`<T?>(env.getEventBus()).thenReturn(eventBus)
        Mockito.`when`<T?>(env.getReporter())
            .thenReturn(
                com.google.devtools.build.lib.events.Reporter(
                    EventBusEventHandler(eventBus),
                    storedEventHandler
                )
            )
    }

    companion object {
        private fun buildStartingEvent(request: BuildRequest?): BuildStartingEvent {
            return BuildStartingEvent.create("", false, request, "/workspace", "/workspace")
        }
    }
}
