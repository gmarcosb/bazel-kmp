// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ActionInput

/** Unit tests for the WorkerSpawnRunner.  */
@RunWith(JUnit4::class)
class WorkerSpawnRunnerTest {
    val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    var reporter: ExtendedEventHandler? = null

    @org.mockito.Mock
    var localEnvProvider: LocalEnvProvider? = null

    @org.mockito.Mock
    var resourceManager: ResourceManager? = null

    @org.mockito.Mock
    var spawnMetrics: SpawnMetrics.Builder? = null

    @org.mockito.Mock
    var spawn: Spawn? = null

    @org.mockito.Mock
    var context: SpawnExecutionContext? = null

    @org.mockito.Mock
    var inputFileCache: InputMetadataProvider? = null

    @org.mockito.Mock
    var worker: Worker? = null

    @org.mockito.Mock
    var options: WorkerOptions? = null

    @org.mockito.Mock
    var metricsCollector: WorkerProcessMetricsCollector? = null

    @org.mockito.Mock
    var resourceHandle: ResourceManager.ResourceHandle? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        Mockito.`when`<T?>(spawn.getInputFiles()).thenReturn(NestedSetBuilder.emptySet(Order.COMPILE_ORDER))
        Mockito.doNothing()
            .`when`<Any?>(metricsCollector)
            .registerWorker(
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.any<T?>()
            )
        Mockito.`when`<T?>(spawn.getLocalResources()).thenReturn(ResourceSet.createWithRamCpu(100, 1))
        Mockito.`when`<T?>(spawn.getPathMapper()).thenReturn(PathMapper.NOOP)
        Mockito.`when`<T?>(
            resourceManager.acquireResources(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(resourceHandle)
        Mockito.`when`<T?>(resourceHandle.getWorker()).thenReturn(worker)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_happyPath() {
        val runner: WorkerSpawnRunner =
            createWorkerSpawnRunner(com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java))
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")
        Mockito.`when`<T?>(worker.getResponse(0))
            .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build())
        val response: WorkResponse =
            runner.execInWorker(
                spawn,
                key,
                context,
                SandboxInputs(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                ),
                SandboxOutputs.create(
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.common.collect.ImmutableSet.of<E?>()
                ),
                com.google.common.collect.ImmutableList.of<E?>(),
                inputFileCache,
                spawnMetrics
            )

        assertThat(response).isNotNull()
        assertThat(response.getExitCode()).isEqualTo(0)
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getOutput()).isEqualTo("out")
        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(context).report(SpawnExecutingEvent.create("worker"))
        Mockito.verify<Any?>(resourceHandle).close()
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).invalidateAndClose(ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(context)
            .lockOutputFiles(ArgumentMatchers.eq(0), < T > eq < T ? > ("out"), ArgumentMatchers.isNull<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_virtualInputs_doesntQueryInputFileCache() {
        val execRoot: Path = fs.getPath("/execRoot")
        val workDir: Path? = execRoot.getRelative("workdir")

        val runner: WorkerSpawnRunner =
            WorkerSpawnRunner(
                execRoot,
                WorkerTestUtils.createTestWorkerPool(worker),
                reporter,
                localEnvProvider,  /* binTools= */
                null,
                resourceManager,  /* runfilesTreeUpdater= */
                null,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java),
                metricsCollector,
                com.google.devtools.build.lib.clock.JavaClock()
            )
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")

        val sandboxHelper: SandboxHelper = SandboxHelper(execRoot, workDir)
        sandboxHelper.addAndCreateVirtualInput("input", "content")

        val virtualActionInput: VirtualActionInput? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                sandboxHelper.getSandboxInputs().getVirtualInputDigests().keySet()
            )

        Mockito.`when`<T?>(worker.getResponse(0))
            .thenReturn(WorkResponse.newBuilder().setExitCode(0).setOutput("out").build())
        Mockito.`when`<T?>(spawn.getInputFiles())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    NestedSetBuilder.create(
                        Order.COMPILE_ORDER,
                        virtualActionInput as ActionInput?
                    )
                })

        val response: WorkResponse =
            runner.execInWorker(
                spawn,
                key,
                context,
                sandboxHelper.getSandboxInputs(),
                sandboxHelper.getSandboxOutputs(),
                com.google.common.collect.ImmutableList.of<E?>(),
                inputFileCache,
                spawnMetrics
            )

        assertThat(response).isNotNull()
        assertThat(response.getExitCode()).isEqualTo(0)
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getOutput()).isEqualTo("out")
        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(inputFileCache, Mockito.never()).getInputMetadata(virtualActionInput)
        Mockito.verify<Any?>(resourceHandle).close()
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).invalidateAndClose(ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(context)
            .lockOutputFiles(ArgumentMatchers.eq(0), ArgumentMatchers.startsWith("out"), ArgumentMatchers.isNull<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_finishesAsyncOnInterrupt() {
        val runner: WorkerSpawnRunner =
            createWorkerSpawnRunner(com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java))
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")
        val interruptedException: java.lang.InterruptedException = java.lang.InterruptedException()
        Mockito.`when`<T?>(worker.getResponse(ArgumentMatchers.anyInt()))
            .thenThrow(interruptedException)
            .thenReturn(WorkResponse.newBuilder().setRequestId(2).build())
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                runner.execInWorker(
                    spawn,
                    key,
                    context,
                    SandboxInputs(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    ),
                    SandboxOutputs.create(
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    inputFileCache,
                    spawnMetrics
                )
            })
        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(context).report(SpawnExecutingEvent.create("worker"))
        Mockito.verify<Any?>(worker).putRequest(WorkRequest.newBuilder().setRequestId(0).build())
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).close()
        Mockito.verify<Any?>(resourceHandle).invalidateAndClose(interruptedException)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_sendsCancelMessageOnInterrupt() {
        val workerOptions: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        workerOptions.workerCancellation = true
        workerOptions.workerSandboxing = true
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION,
                    "1"
                )
            )
        Mockito.`when`<Any?>(worker.isSandboxed).thenReturn(true)
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")
        val secondResponseRequested: Semaphore = Semaphore(0)
        // Fake that the getting the regular response gets interrupted and we then answer the cancel.
        Mockito.`when`<T?>(worker.getResponse(ArgumentMatchers.anyInt()))
            .thenThrow(java.lang.InterruptedException())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? ->
                    secondResponseRequested.release()
                    WorkResponse.newBuilder()
                        .setRequestId(invocation.getArgument<T?>(0))
                        .setWasCancelled(true)
                        .build()
                })
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                runner.execInWorker(
                    spawn,
                    key,
                    context,
                    SandboxInputs(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    ),
                    SandboxOutputs.create(
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    inputFileCache,
                    spawnMetrics
                )
            })
        secondResponseRequested.acquire()
        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(context).report(SpawnExecutingEvent.create("worker"))
        val argumentCaptor: ArgumentCaptor<WorkRequest?> =
            ArgumentCaptor.forClass<WorkRequest?, WorkRequest?>(WorkRequest::class.java)
        Mockito.verify<Any?>(worker, Mockito.times(2)).putRequest(argumentCaptor.capture())
        assertThat(argumentCaptor.getAllValues().get(0))
            .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build())
        assertThat(argumentCaptor.getAllValues().get(1))
            .isEqualTo(WorkRequest.newBuilder().setRequestId(0).setCancel(true).build())
        // Wait until thread produced by WorkerSpawnRunner.finishWorkAsync is finshed and returned
        // resources via resourceHandle.
        java.lang.Thread.sleep(50)
        Mockito.verify<Any?>(resourceHandle).close()
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).invalidateAndClose(ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_unsandboxedDiesOnInterrupt() {
        val workerOptions: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        workerOptions.workerCancellation = true
        workerOptions.workerSandboxing = false
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION,
                    "1"
                )
            )
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")
        val interruptedException: java.lang.InterruptedException = java.lang.InterruptedException()
        Mockito.`when`<T?>(worker.getResponse(ArgumentMatchers.anyInt())).thenThrow(interruptedException)
        // Since this worker is not sandboxed, it will just get killed on interrupt.
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                runner.execInWorker(
                    spawn,
                    key,
                    context,
                    SandboxInputs(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>()
                    ),
                    SandboxOutputs.create(
                        com.google.common.collect.ImmutableSet.of<E?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    inputFileCache,
                    spawnMetrics
                )
            })

        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(context).report(SpawnExecutingEvent.create("worker"))
        val argumentCaptor: ArgumentCaptor<WorkRequest?> =
            ArgumentCaptor.forClass<WorkRequest?, WorkRequest?>(WorkRequest::class.java)
        Mockito.verify<Any?>(worker).putRequest(argumentCaptor.capture())
        assertThat(argumentCaptor.getAllValues().get(0))
            .isEqualTo(WorkRequest.newBuilder().setRequestId(0).build())
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).close()
        Mockito.verify<Any?>(resourceHandle).invalidateAndClose(interruptedException)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_noMultiplexWithDynamic() {
        val workerOptions: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        workerOptions.workerMultiplex = true
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        // This worker key just so happens to be multiplex and require sandboxing.
        val key: WorkerKey? = createWorkerKey(WorkerProtocolFormat.JSON, fs, true)
        val logFile: Path = fs.getPath("/worker.log")
        Mockito.`when`<T?>(worker.getResponse(0))
            .thenReturn(
                WorkResponse.newBuilder().setExitCode(0).setRequestId(0).setOutput("out").build()
            )
        val response: WorkResponse =
            runner.execInWorker(
                spawn,
                key,
                context,
                SandboxInputs(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    com.google.common.collect.ImmutableMap.of<K?, V?>()
                ),
                SandboxOutputs.create(
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.common.collect.ImmutableSet.of<E?>()
                ),
                com.google.common.collect.ImmutableList.of<E?>(),
                inputFileCache,
                spawnMetrics
            )

        assertThat(response).isNotNull()
        assertThat(response.getExitCode()).isEqualTo(0)
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getOutput()).isEqualTo("out")
        assertThat(logFile.exists()).isFalse()
        Mockito.verify<Any?>(context).report(SpawnExecutingEvent.create("worker"))
        Mockito.verify<Any?>(resourceHandle).close()
        Mockito.verify<Any?>(resourceHandle, Mockito.times(0)).invalidateAndClose(ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(context)
            .lockOutputFiles(ArgumentMatchers.eq(0), ArgumentMatchers.startsWith("out"), ArgumentMatchers.isNull<T?>())
    }

    @Throws(java.lang.Exception::class)
    private fun assertRecordedResponsethrowsException(recordedResponse: String, exceptionText: String?) {
        val workerOptions: WorkerOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        val key: WorkerKey? = WorkerTestUtils.createWorkerKey(fs, "mnem", false)
        val logFile: Path = fs.getPath("/worker.log")
        Mockito.`when`<T?>(worker.getLogFile()).thenReturn(logFile)
        Mockito.`when`<T?>(worker.getResponse(0)).thenThrow(IOException("Bad protobuf"))
        Mockito.`when`<Any?>(worker.recordingStreamMessage).thenReturn(recordedResponse)
        Mockito.`when`<Any?>(worker.exitValue).thenReturn(java.util.Optional.of<Int?>(2))
        val workerLog = "Log from worker\n"
        FileSystemUtils.writeIsoLatin1(logFile, workerLog)
        val execException: UserExecException? =
            org.junit.Assert.assertThrows<T?>(
                UserExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    runner.execInWorker(
                        spawn,
                        key,
                        context,
                        SandboxInputs(
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),
                            com.google.common.collect.ImmutableMap.of<K?, V?>()
                        ),
                        SandboxOutputs.create(
                            com.google.common.collect.ImmutableSet.of<E?>(),
                            com.google.common.collect.ImmutableSet.of<E?>()
                        ),
                        com.google.common.collect.ImmutableList.of<E?>(),
                        inputFileCache,
                        spawnMetrics
                    )
                })

        assertThat(execException).hasMessageThat().contains(exceptionText)
        if (!recordedResponse.isEmpty()) {
            assertThat(execException)
                .hasMessageThat()
                .contains(logMarker("Exception details") + "java.io.IOException: Bad protobuf")

            assertThat(execException)
                .hasMessageThat()
                .contains(
                    logMarker("Start of response") + recordedResponse + logMarker("End of response")
                )
        }
        assertThat(execException)
            .hasMessageThat()
            .contains(logMarker("Start of log, file at " + logFile.getPathString()) + workerLog)
        Mockito.verify<Any?>(context)
            .lockOutputFiles(
                ArgumentMatchers.eq(2), ArgumentMatchers.contains(exceptionText), ArgumentMatchers.isNull<T?>()
            )
        Mockito.verify<Any?>(resourceHandle).invalidateAndClose(execException)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_showsLogFileInException() {
        assertRecordedResponsethrowsException("Some text", "unparseable WorkResponse!\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecInWorker_throwsWithEmptyResponse() {
        assertRecordedResponsethrowsException("", "did not return a WorkResponse")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandArgument_expandsArgumentsRecursively() {
        val requestBuilder: WorkRequest.Builder = WorkRequest.newBuilder()
        FileSystemUtils.writeIsoLatin1(fs.getPath("/file"), "arg1\n@file2\nmulti arg\n")
        FileSystemUtils.writeIsoLatin1(fs.getPath("/file2"), "arg2\narg3")
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PathFragment.create("file"),
                    fs.getPath("/file"),
                    PathFragment.create("file2"),
                    fs.getPath("/file2")
                ),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder)
        assertThat(requestBuilder.getArgumentsList())
            .containsExactly("arg1", "arg2", "arg3", "multi arg", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandArgument_expandsOnlyProperArguments() {
        val requestBuilder: WorkRequest.Builder = WorkRequest.newBuilder()
        FileSystemUtils.writeIsoLatin1(fs.getPath("/file"), "arg1\n@@nonfile\n@foo//bar\narg2")
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(PathFragment.create("file"), fs.getPath("/file")),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        WorkerSpawnRunner.expandArgument(inputs, "@file", requestBuilder)
        assertThat(requestBuilder.getArgumentsList())
            .containsExactly("arg1", "@@nonfile", "@foo//bar", "arg2")
    }

    @org.junit.Test
    fun testExpandArgument_failsOnMissingFile() {
        val requestBuilder: WorkRequest.Builder? = WorkRequest.newBuilder()
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(PathFragment.create("file"), fs.getPath("/dir/file")),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    WorkerSpawnRunner.expandArgument(
                        inputs,
                        "@file",
                        requestBuilder
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("file")
        Truth.assertThat(e).hasMessageThat().contains("/dir/file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanExec_checksRequirements() {
        val workerOptions: WorkerOptions? =
            com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        Mockito.`when`<T?>(spawn.getMnemonic()).thenReturn("Mnemonic")

        // Missing "supports-workers"
        Mockito.`when`<T?>(spawn.getExecutionInfo()).thenReturn(com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(runner.canExec(spawn)).isFalse()

        // Missing toolFiles
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(com.google.common.collect.ImmutableMap.of<K?, V?>(ExecutionRequirements.SUPPORTS_WORKERS, "1"))
        Mockito.`when`<T?>(spawn.getToolFiles())
            .thenAnswer(
                Answer { invocation: InvocationOnMock? -> NestedSetBuilder.emptySet(Order.STABLE_ORDER) } as Answer<NestedSet<ActionInput?>?>)
        assertThat(runner.canExec(spawn)).isFalse()

        // Minimum requirements met
        val toolFiles: NestedSet<ActionInput?>? =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                ActionInputHelper.fromPath("myTools/tool1"),
                ActionInputHelper.fromPath("myTools/tool2")
            )
        // Using `thenAnswer` to work around Mockito type capture issues.
        Mockito.`when`<T?>(spawn.getToolFiles())
            .thenAnswer(Answer { invocation: InvocationOnMock? -> toolFiles } as Answer<NestedSet<ActionInput?>?>)
        assertThat(runner.canExec(spawn)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCanExec_obeysAllowlist() {
        val workerOptions: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        val runner: WorkerSpawnRunner = createWorkerSpawnRunner(workerOptions)
        Mockito.`when`<T?>(spawn.getMnemonic()).thenReturn("Mnemonic")
        val toolFiles: NestedSet<ActionInput?>? =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                ActionInputHelper.fromPath("myTools/tool1"),
                ActionInputHelper.fromPath("myTools/tool2")
            )
        // Using `thenAnswer` to work around Mockito type capture issues.
        Mockito.`when`<T?>(spawn.getToolFiles())
            .thenAnswer(Answer { invocation: InvocationOnMock? -> toolFiles } as Answer<NestedSet<ActionInput?>?>)

        // Allowed due to no allowlist
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKERS,
                    "1",
                    ExecutionRequirements.WORKER_KEY_MNEMONIC,
                    "WKM2"
                )
            )
        assertThat(runner.canExec(spawn)).isTrue()

        workerOptions.setAllowlist(com.google.common.collect.ImmutableList.of<E?>("WKM1", "Mnemonic"))

        // Blocked by allowlist
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKERS,
                    "1",
                    ExecutionRequirements.WORKER_KEY_MNEMONIC,
                    "WKM2"
                )
            )
        assertThat(runner.canExec(spawn)).isFalse()

        // On allowlist
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKERS,
                    "1",
                    ExecutionRequirements.WORKER_KEY_MNEMONIC,
                    "WKM1"
                )
            )
        assertThat(runner.canExec(spawn)).isTrue()

        // On allowlist
        Mockito.`when`<T?>(spawn.getExecutionInfo())
            .thenReturn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.SUPPORTS_WORKERS,
                    "1",
                    ExecutionRequirements.WORKER_KEY_MNEMONIC,
                    "WKM1"
                )
            )
        assertThat(runner.canExec(spawn)).isTrue()
    }

    private fun createWorkerSpawnRunner(workerOptions: WorkerOptions?): WorkerSpawnRunner {
        return WorkerSpawnRunner(
            fs.getPath("/execRoot"),
            WorkerTestUtils.createTestWorkerPool(worker),
            reporter,
            localEnvProvider,  /* binTools= */
            null,
            resourceManager,  /* runfilesTreeUpdater= */
            null,
            workerOptions,
            metricsCollector,
            com.google.devtools.build.lib.clock.JavaClock()
        )
    }

    @org.junit.Test
    fun testExpandArgument_failsOnUndeclaredInput() {
        val requestBuilder: WorkRequest.Builder? = WorkRequest.newBuilder()
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    WorkerSpawnRunner.expandArgument(
                        inputs,
                        "@file",
                        requestBuilder
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("file")
        Truth.assertThat(e).hasMessageThat().contains("declared input")
    }

    companion object {
        private fun logMarker(text: String): String {
            return "---8<---8<--- " + text + " ---8<---8<---\n"
        }
    }
}
