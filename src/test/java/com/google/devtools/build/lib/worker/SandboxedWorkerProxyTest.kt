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

import com.google.devtools.build.lib.actions.ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING

/** Tests for WorkerProxy  */
@RunWith(JUnit4::class)
class SandboxedWorkerProxyTest {
    val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private var globalExecRoot: Path? = null
    private var workerBaseDir: Path? = null
    private var globalOutputBase: Path? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        val testRoot: Path = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())

        globalOutputBase = testRoot.getChild("outputbase")
        globalExecRoot = globalOutputBase.getChild("execroot")
        globalExecRoot.createDirectoryAndParents()

        workerBaseDir = testRoot.getRelative("bazel-workers")
        workerBaseDir.createDirectoryAndParents()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun prepareExecution_createsFilesInSandbox() {
        val proxy: SandboxedWorkerProxy = createSandboxedWorkerProxies("Mnem", 1).get(0)
        val multiplexerId: Int = proxy.workerMultiplexer.multiplexerId
        val workDir: Path =
            workerBaseDir
                .getChild("Mnem-multiplex-worker-" + multiplexerId + "-workdir")
                .getChild("execroot")
        val sandboxDir: Path =
            workDir
                .getChild("__sandbox")
                .getChild(proxy.workerId.toString())
                .getChild("execroot")
        val sandboxHelper: SandboxHelper =
            SandboxHelper(globalExecRoot, workDir)
                .addAndCreateInputFile(
                    "anInputFile",
                    "anInputFile",
                    "Just stuff"
                ) // Worker files are expected to also be inputs.
                .addInputFile("worker.sh", "worker.sh")
                .addOutput("very/output.txt")
                .addAndCreateWorkerFile("worker.sh", "#!/bin/bash")

        val serverInputStream: PipedInputStream = PipedInputStream()
        proxy.workerMultiplexer.setProcessFactory({ params -> FakeSubprocess(serverInputStream) })

        proxy.prepareExecution(
            sandboxHelper.getSandboxInputs(),
            sandboxHelper.getSandboxOutputs(),
            sandboxHelper.getWorkerFiles(),
            com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(java.lang.System.getenv())
        )

        assertThat(workDir.isDirectory()).isTrue()
        assertThat(workDir.getChild("worker.sh").exists()).isTrue()
        assertThat(workDir.getChild("worker.sh").isSymbolicLink()).isTrue()
        assertThat(sandboxDir.isDirectory()).isTrue()
        assertThat(sandboxDir.getChild("anInputFile").exists()).isTrue()
        assertThat(sandboxDir.getChild("anInputFile").isSymbolicLink()).isTrue()
        assertThat(sandboxDir.getChild("very").exists()).isTrue()
        assertThat(sandboxDir.getChild("very").isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun putRequest_setsSandboxDir() {
        val worker: SandboxedWorkerProxy = createFakedSandboxedWorkerProxy()
        val multiplexerId: Int = worker.workerMultiplexer.multiplexerId
        val workDir: Path? =
            workerBaseDir
                .getChild("Mnem-multiplex-worker-" + multiplexerId + "-workdir")
                .getChild("execroot")
        val sandboxHelper: SandboxHelper =
            SandboxHelper(globalExecRoot, workDir)
                .addAndCreateInputFile("anInputFile", "anInputFile", "Just stuff")
                .addOutput("very/output.txt")
                .addAndCreateWorkerFile("worker.sh", "#!/bin/bash")
        worker.prepareExecution(
            sandboxHelper.getSandboxInputs(),
            sandboxHelper.getSandboxOutputs(),
            sandboxHelper.getWorkerFiles(),
            com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(java.lang.System.getenv())
        )
        worker.putRequest(WorkRequest.newBuilder().setRequestId(2).build())
        assertThat(worker.workerMultiplexer.pendingRequests).isNotEmpty()
        val actualRequest: WorkRequest = worker.workerMultiplexer.pendingRequests.take()
        assertThat(actualRequest.getRequestId()).isEqualTo(2)
        assertThat(actualRequest.getSandboxDir())
            .isEqualTo("__sandbox/" + worker.workerId + "/execroot")
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun finishExecution_copiesOutputs() {
        val worker: SandboxedWorkerProxy = createFakedSandboxedWorkerProxy()
        val multiplexerId: Int = worker.workerMultiplexer.multiplexerId
        val workDir: Path? =
            workerBaseDir
                .getChild("Mnem-multiplex-worker-" + multiplexerId + "-workdir")
                .getChild("execroot")
        val sandboxHelper: SandboxHelper =
            SandboxHelper(globalExecRoot, workDir)
                .addAndCreateInputFile("anInputFile", "anInputFile", "Just stuff")
                .addOutput("very/output.txt")
                .addOutput("rootFile")
                .addAndCreateWorkerFile("worker.sh", "#!/bin/bash")
        worker.prepareExecution(
            sandboxHelper.getSandboxInputs(),
            sandboxHelper.getSandboxOutputs(),
            sandboxHelper.getWorkerFiles(),
            com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(java.lang.System.getenv())
        )
        worker.putRequest(WorkRequest.newBuilder().setRequestId(2).build())
        val actualRequest: WorkRequest = worker.workerMultiplexer.pendingRequests.take()
        val requestSandboxSubdir: String? = actualRequest.getSandboxDir()

        // Pretend to do work.
        sandboxHelper.createExecRootFile(
            com.google.common.base.Joiner.on("/").join(requestSandboxSubdir, "very/output.txt"), "some output"
        )
        sandboxHelper.createExecRootFile("very/output.txt", "some wrongly placed output")
        sandboxHelper.createExecRootFile(
            com.google.common.base.Joiner.on("/").join(requestSandboxSubdir, "rootFile"), "some output in root"
        )
        sandboxHelper.createExecRootFile(
            com.google.common.base.Joiner.on("/").join(requestSandboxSubdir, "randomFile"), "some randomOutput"
        )

        worker.finishExecution(globalExecRoot, sandboxHelper.getSandboxOutputs())

        assertThat(globalExecRoot.getChild("randomFile").exists()).isFalse()
        assertThat(
            FileSystemUtils.readContent(
                globalExecRoot.getChild("rootFile"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("some output in root")
        assertThat(
            FileSystemUtils.readContent(
                globalExecRoot.getChild("very").getChild("output.txt"), java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo("some output")
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun differentProxiesSameMultiplexerHaveSameWorkDir() {
        val proxies: java.util.ArrayList<SandboxedWorkerProxy> = createSandboxedWorkerProxies("Mnem", 2)
        val proxyOne: SandboxedWorkerProxy = proxies.get(0)
        val proxyTwo: SandboxedWorkerProxy = proxies.get(1)

        val multiplexerIdProxyOne: Int = proxyOne.workerMultiplexer.multiplexerId
        val expectedWorkDirProxyOne: Path? =
            workerBaseDir
                .getChild("Mnem-multiplex-worker-" + multiplexerIdProxyOne + "-workdir")
                .getChild("execroot")

        val multiplexerIdProxyTwo: Int = proxyTwo.workerMultiplexer.multiplexerId
        val expectedWorkDirProxyTwo: Path? =
            workerBaseDir
                .getChild("Mnem-multiplex-worker-" + multiplexerIdProxyTwo + "-workdir")
                .getChild("execroot")

        assertThat(proxyOne.workDir).isEqualTo(proxyTwo.workDir)
        assertThat(proxyOne.workDir).isEqualTo(expectedWorkDirProxyOne)
        assertThat(proxyTwo.workDir).isEqualTo(expectedWorkDirProxyTwo)
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    fun differentProxiesDifferentMultiplexerSameMnemHaveDifferentWorkDirs() {
        val sharedMnemonic = "Mnem"

        // Create a proxy on the first multiplexer
        val req: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            WorkerTestUtils.execRequirementsBuilder(sharedMnemonic)
        req.put(SUPPORTS_MULTIPLEX_SANDBOXING, "1")
        val spawn: Spawn = WorkerTestUtils.createSpawn(req.buildOrThrow())

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.workerMultiplex = true
        options.multiplexSandboxing = true

        val key: WorkerKey? =
            createWorkerKeyFromOptions(
                PROTO, globalOutputBase, options, true, spawn, "worker.sh"
            )
        val factory: WorkerFactory = WorkerFactory(workerBaseDir, options)

        val proxyOneMultiplexerOne: SandboxedWorkerProxy = factory.create(key) as SandboxedWorkerProxy
        val multiplexerIdOne: Int = proxyOneMultiplexerOne.workerMultiplexer.multiplexerId
        val expectedWorkDirOne: Path? =
            workerBaseDir
                .getChild(sharedMnemonic + "-multiplex-worker-" + multiplexerIdOne + "-workdir")
                .getChild("execroot")

        // Shut down the first multiplexer, so we get a different multiplexer for the next proxy
        WorkerMultiplexerManager.removeInstance(key)

        // Create a proxy on the second multiplexer
        val proxyOneMultiplexerTwo: SandboxedWorkerProxy = factory.create(key) as SandboxedWorkerProxy
        val multiplexerIdTwo: Int = proxyOneMultiplexerTwo.workerMultiplexer.multiplexerId
        val expectedWorkDirTwo: Path? =
            workerBaseDir
                .getChild(sharedMnemonic + "-multiplex-worker-" + multiplexerIdTwo + "-workdir")
                .getChild("execroot")

        assertThat(proxyOneMultiplexerOne.workDir).isNotEqualTo(proxyOneMultiplexerTwo.workDir)
        assertThat(proxyOneMultiplexerOne.workDir).isEqualTo(expectedWorkDirOne)
        assertThat(proxyOneMultiplexerTwo.workDir).isEqualTo(expectedWorkDirTwo)
    }

    @Throws(IOException::class)
    private fun createSandboxedWorkerProxies(
        mnemonic: String?, numProxiesToCreate: Int
    ): java.util.ArrayList<SandboxedWorkerProxy> {
        val req: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            WorkerTestUtils.execRequirementsBuilder(mnemonic)
        req.put(SUPPORTS_MULTIPLEX_SANDBOXING, "1")
        val spawn: Spawn = WorkerTestUtils.createSpawn(req.buildOrThrow())

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.workerMultiplex = true
        options.multiplexSandboxing = true

        val key: WorkerKey? =
            createWorkerKeyFromOptions(
                PROTO, globalOutputBase, options, true, spawn, "worker.sh"
            )
        val factory: WorkerFactory = WorkerFactory(workerBaseDir, options)

        Truth.assertThat(numProxiesToCreate).isGreaterThan(0)
        val proxies: java.util.ArrayList<SandboxedWorkerProxy> =
            com.google.common.collect.Lists.newArrayListWithCapacity<SandboxedWorkerProxy>(numProxiesToCreate)
        for (i in 0..<numProxiesToCreate) {
            proxies.add(factory.create(key) as SandboxedWorkerProxy?)
        }
        return proxies
    }

    @Throws(IOException::class)
    private fun createFakedSandboxedWorkerProxy(): SandboxedWorkerProxy {
        val req: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            WorkerTestUtils.execRequirementsBuilder("Mnem")
        req.put(SUPPORTS_MULTIPLEX_SANDBOXING, "1")
        val spawn: Spawn = WorkerTestUtils.createSpawn(req.buildOrThrow())

        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.workerMultiplex = true
        options.multiplexSandboxing = true

        val key: WorkerKey? =
            createWorkerKeyFromOptions(
                PROTO, globalOutputBase, options, true, spawn, "worker.sh"
            )
        WorkerMultiplexerManager.injectForTesting(
            key,
            object : WorkerMultiplexer(globalExecRoot.getChild("testWorker.log"), key, 0) {
                @kotlin.jvm.Synchronized
                @Throws(IOException::class)
                public override fun createProcess(
                    workDir: Path?, clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
                ) {
                    val serverInputStream: PipedInputStream = PipedInputStream()
                    super.process = FakeSubprocess(serverInputStream)
                }
            })
        val factory: WorkerFactory = WorkerFactory(workerBaseDir, options)
        return factory.create(key) as SandboxedWorkerProxy
    }
}
