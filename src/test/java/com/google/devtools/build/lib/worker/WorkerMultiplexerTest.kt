// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for WorkerMultiplexer  */
@RunWith(JUnit4::class)
class WorkerMultiplexerTest {
    private var fileSystem: FileSystem? = null
    private var logPath: Path? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        fileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        logPath = fileSystem.getPath("/tmp/logs4")
        logPath.createDirectoryAndParents()
    }

    @org.junit.After
    fun tearDown() {
        WorkerMultiplexerManager.resetForTesting()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testGetResponse_noOutstandingRequests() {
        val workerKey: WorkerKey = WorkerTestUtils.createWorkerKey(fileSystem, "test1", true, "fakeBinary")
        val multiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(workerKey, logPath)

        val serverInputStream: PipedInputStream = PipedInputStream()
        val workerOutputStream: java.io.OutputStream = PipedOutputStream(serverInputStream)
        multiplexer.setProcessFactory({ params -> FakeSubprocess(serverInputStream) })

        val request1: WorkRequest? = WorkRequest.newBuilder().setRequestId(1).build()
        val worker: WorkerProxy =
            WorkerProxy(workerKey, 2, logPath, multiplexer, workerKey.getExecRoot())
        worker.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        worker.putRequest(request1)
        val response1: WorkResponse = WorkResponse.newBuilder().setRequestId(1).build()
        response1.writeDelimitedTo(workerOutputStream)
        workerOutputStream.flush()
        val response: WorkResponse = worker.getResponse(1)
        assertThat(response.getRequestId()).isEqualTo(1)
        // Can't get the same response twice - but the responseChecker is gone, so it just returns null
        assertThat(multiplexer.getResponse(1)).isNull()
        assertThat(multiplexer.noOutstandingRequests()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecutionException::class)
    fun testGetResponse_basicConcurrency() {
        val workerKey: WorkerKey = WorkerTestUtils.createWorkerKey(fileSystem, "test2", true, "fakeBinary")
        val multiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(workerKey, logPath)

        val serverInputStream: PipedInputStream = PipedInputStream()
        val workerOutputStream: java.io.OutputStream = PipedOutputStream(serverInputStream)
        multiplexer.setProcessFactory({ params -> FakeSubprocess(serverInputStream) })

        val worker1: WorkerProxy =
            WorkerProxy(workerKey, 1, logPath, multiplexer, workerKey.getExecRoot())
        worker1.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request1: WorkRequest? = WorkRequest.newBuilder().setRequestId(3).build()
        worker1.putRequest(request1)

        val worker2: WorkerProxy =
            WorkerProxy(workerKey, 2, logPath, multiplexer, workerKey.getExecRoot())
        worker2.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request2: WorkRequest? = WorkRequest.newBuilder().setRequestId(42).build()
        worker2.putRequest(request2)

        val executor: java.util.concurrent.Executor = Executors.newFixedThreadPool(2)
        val response1: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit(java.lang.Runnable { worker1.getResponse(3) }, executor)
        val response2: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit(java.lang.Runnable { worker2.getResponse(42) }, executor)

        val fakedResponse1: WorkResponse = WorkResponse.newBuilder().setRequestId(3).build()
        val fakedResponse2: WorkResponse = WorkResponse.newBuilder().setRequestId(42).build()
        // Responses can arrive out of order
        fakedResponse2.writeDelimitedTo(workerOutputStream)
        fakedResponse1.writeDelimitedTo(workerOutputStream)
        workerOutputStream.flush()

        assertThat(response1.get().getRequestId()).isEqualTo(3)
        assertThat(response2.get().getRequestId()).isEqualTo(42)
        assertThat(multiplexer.noOutstandingRequests()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecutionException::class)
    fun testGetResponse_slowMultiplexer() {
        val workerKey: WorkerKey = WorkerTestUtils.createWorkerKey(fileSystem, "test3", true, "fakeBinary")
        val multiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(workerKey, logPath)

        val serverInputStream: PipedInputStream = PipedInputStream()
        val workerOutputStream: java.io.OutputStream = PipedOutputStream(serverInputStream)
        multiplexer.setProcessFactory({ params -> FakeSubprocess(serverInputStream) })

        val worker1: WorkerProxy =
            WorkerProxy(workerKey, 1, logPath, multiplexer, workerKey.getExecRoot())
        worker1.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request1: WorkRequest? = WorkRequest.newBuilder().setRequestId(3).build()
        worker1.putRequest(request1)

        val worker2: WorkerProxy =
            WorkerProxy(workerKey, 2, logPath, multiplexer, workerKey.getExecRoot())
        worker2.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request2: WorkRequest? = WorkRequest.newBuilder().setRequestId(42).build()
        worker2.putRequest(request2)

        val proxyThreads: Array<java.lang.Thread?> = arrayOfNulls<java.lang.Thread>(2)
        val executor: java.util.concurrent.Executor = Executors.newFixedThreadPool(2)
        val response1: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit<O?>(
                java.util.concurrent.Callable {
                    synchronized(this) {
                        proxyThreads[0] = java.lang.Thread.currentThread()
                    }
                    worker1.getResponse(3)
                },
                executor
            )
        val response2: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit<O?>(
                java.util.concurrent.Callable {
                    synchronized(this) {
                        proxyThreads[1] = java.lang.Thread.currentThread()
                    }
                    worker2.getResponse(42)
                },
                executor
            )

        // Makes sure both workers are waiting for responses before the multiplexer processes anything.
        while (threadsAreNotWaiting(proxyThreads)) {
            java.lang.Thread.sleep(1)
        }

        val fakedResponse1: WorkResponse = WorkResponse.newBuilder().setRequestId(3).build()
        val fakedResponse2: WorkResponse = WorkResponse.newBuilder().setRequestId(42).build()
        // Responses can arrive out of order
        fakedResponse2.writeDelimitedTo(workerOutputStream)
        fakedResponse1.writeDelimitedTo(workerOutputStream)
        workerOutputStream.flush()

        assertThat(response1.get().getRequestId()).isEqualTo(3)
        assertThat(response2.get().getRequestId()).isEqualTo(42)
        assertThat(multiplexer.noOutstandingRequests()).isTrue()
    }

    @kotlin.jvm.Synchronized
    fun threadsAreNotWaiting(threads: Array<java.lang.Thread?>): Boolean {
        for (thread in threads) {
            if (thread == null || thread.getState() != java.lang.Thread.State.WAITING) {
                return true
            }
        }
        return false
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class, ExecutionException::class)
    fun testGetResponse_slowProxy() {
        val workerKey: WorkerKey = WorkerTestUtils.createWorkerKey(fileSystem, "test4", true, "fakeBinary")
        val multiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(workerKey, logPath)

        val serverInputStream: PipedInputStream = PipedInputStream()
        val workerOutputStream: java.io.OutputStream = PipedOutputStream(serverInputStream)
        multiplexer.setProcessFactory({ params -> FakeSubprocess(serverInputStream) })

        val worker1: WorkerProxy =
            WorkerProxy(workerKey, 1, logPath, multiplexer, workerKey.getExecRoot())
        worker1.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request1: WorkRequest? = WorkRequest.newBuilder().setRequestId(3).build()
        worker1.putRequest(request1)

        val worker2: WorkerProxy =
            WorkerProxy(workerKey, 2, logPath, multiplexer, workerKey.getExecRoot())
        worker2.prepareExecution(null, null, null, com.google.common.collect.ImmutableMap.of<K?, V?>())
        val request2: WorkRequest? = WorkRequest.newBuilder().setRequestId(42).build()
        worker2.putRequest(request2)

        val fakedResponse1: WorkResponse = WorkResponse.newBuilder().setRequestId(3).build()
        val fakedResponse2: WorkResponse = WorkResponse.newBuilder().setRequestId(42).build()
        // Responses can arrive out of order, and before the workerproxies are ready to get them.
        fakedResponse2.writeDelimitedTo(workerOutputStream)
        fakedResponse1.writeDelimitedTo(workerOutputStream)
        workerOutputStream.flush()

        val executor: java.util.concurrent.Executor = Executors.newFixedThreadPool(2)
        val response1: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit(java.lang.Runnable { worker1.getResponse(3) }, executor)
        val response2: java.util.concurrent.Future<WorkResponse?> =
            com.google.common.util.concurrent.Futures.submit(java.lang.Runnable { worker2.getResponse(42) }, executor)

        assertThat(response1.get().getRequestId()).isEqualTo(3)
        assertThat(response2.get().getRequestId()).isEqualTo(42)
        assertThat(multiplexer.noOutstandingRequests()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun workDir_destroyMultiplexer_successfullyDestroysWorkDir() {
        val testRoot: Path = fileSystem.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())

        val workerKey: WorkerKey =
            WorkerTestUtils.createWorkerKey(fileSystem, "TestMnemonic", true, "fakeBinary")
        val multiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(workerKey, logPath)

        val workDir: Path = testRoot.getRelative("/tmp/workdir")
        workDir.createDirectoryAndParents()
        assertThat(workDir.exists()).isTrue()

        multiplexer.setWorkDir(workDir)
        multiplexer.destroyMultiplexer()
        assertThat(workDir.exists()).isFalse()
    }
}
