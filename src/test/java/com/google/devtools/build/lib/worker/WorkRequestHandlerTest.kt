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

import com.google.devtools.build.lib.worker.WorkRequestHandler.RequestInfo

/** Tests for the WorkRequestHandler  */
@RunWith(JUnit4::class)
class WorkRequestHandlerTest {
    private val testWorkerIO: WorkRequestHandler.WorkerIO = createTestWorkerIO()

    @Before
    fun init() {
        MockitoAnnotations.initMocks(this)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun after() {
        testWorkerIO.close()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testNormalWorkRequest() {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err -> 1 },
                PrintStream(java.io.ByteArrayOutputStream()),
                ProtoWorkerMessageProcessor(ByteArrayInputStream(ByteArray(0)), out)
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val request: WorkRequest? = WorkRequest.newBuilder().addAllArguments(args).build()
        handler.respondToRequest(testWorkerIO, request, RequestInfo(null))

        val response: WorkResponse =
            WorkResponse.parseDelimitedFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getExitCode()).isEqualTo(1)
        assertThat(response.getOutput()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMultiplexWorkRequest() {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err -> 0 },
                PrintStream(java.io.ByteArrayOutputStream()),
                ProtoWorkerMessageProcessor(ByteArrayInputStream(ByteArray(0)), out)
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val request: WorkRequest? = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build()
        handler.respondToRequest(testWorkerIO, request, RequestInfo(null))

        val response: WorkResponse =
            WorkResponse.parseDelimitedFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(response.getRequestId()).isEqualTo(42)
        assertThat(response.getExitCode()).isEqualTo(0)
        assertThat(response.getOutput()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testMultiplexWorkRequest_stopsThreadsOnShutdown() {
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()

        // Work request threads release this when they have started.
        val started: Semaphore = Semaphore(0)
        // Work request threads wait forever on this, so we can see how they react to closed stdin.
        val eternity: Semaphore = Semaphore(0)
        // Released when the work request handler thread has noticed the closed stdin and interrupted
        // the work request threads.
        val stopped: Semaphore = Semaphore(0)
        val workerThreads: MutableList<java.lang.Thread?> = java.util.ArrayList<java.lang.Thread?>()
        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err ->
                    // Each call to this runs in its own thread.
                    synchronized(workerThreads) {
                        workerThreads.add(java.lang.Thread.currentThread())
                    }
                    started.release()
                    try {
                        eternity.acquire() // This blocks until the thread is interrupted at shutdown.
                    } catch (e: java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                    0
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        handler.processRequests()
                        stopped.release()
                    } catch (e: IOException) {
                        throw java.lang.AssertionError("Unhandled exception", e)
                    }
                })
        t.start()
        val request1: WorkRequest = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build()
        request1.writeDelimitedTo(src)
        val request2: WorkRequest = WorkRequest.newBuilder().addAllArguments(args).setRequestId(43).build()
        request2.writeDelimitedTo(src)
        src.flush()

        started.acquire(2)
        Truth.assertThat(workerThreads).hasSize(2)
        // Now both request threads are started, closing the input to the "worker" should shut it down.
        src.close()
        stopped.acquire()
        while (workerThreads.get(0).isAlive() || workerThreads.get(1).isAlive()) {
            java.lang.Thread.sleep(1)
        }
        Truth.assertThat(workerThreads.get(0).isAlive()).isFalse()
        Truth.assertThat(workerThreads.get(1).isAlive()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testMultiplexWorkRequest_stopsWorkerOnException() {
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()

        // Work request threads release this when they have started.
        val started: Semaphore = Semaphore(0)
        // One work request threads waits forever on this, so the second one can throw an exception
        val eternity: Semaphore = Semaphore(0)
        // Released when the work request handler thread has been stopped after a worker thread died.
        val stopped: Semaphore = Semaphore(0)
        val workerThreads: MutableList<java.lang.Thread?> = java.util.ArrayList<java.lang.Thread?>()
        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err ->
                    // Each call to this runs in its own thread.
                    try {
                        synchronized(workerThreads) {
                            workerThreads.add(java.lang.Thread.currentThread())
                        }
                        started.release()
                        if (workerThreads.size < 2) {
                            eternity.acquire() // This blocks forever.
                        } else {
                            // This is triggered by the second WorkRequest. This causes the PipedInputStream
                            // under the hood to throw an InterruptedIOException. This process helps us
                            // simulate the situation when the infinite loop in the WorkRequestHandler catches
                            // an IOException while calling messageProcess.readWorkRequest(). This exception
                            // will then trigger the path we're testing to stop the worker.
                            messageProcessor.interruptReader()
                        }
                    } catch (e: java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                    }
                    0
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        handler.processRequests()
                        stopped.release()
                    } catch (e: IOException) {
                        throw java.lang.AssertionError("Unhandled exception", e)
                    }
                })
        t.start()
        val request1: WorkRequest = WorkRequest.newBuilder().addAllArguments(args).setRequestId(42).build()
        request1.writeDelimitedTo(src)
        val request2: WorkRequest = WorkRequest.newBuilder().addAllArguments(args).setRequestId(43).build()
        request2.writeDelimitedTo(src)
        src.flush()

        started.acquire(2)
        Truth.assertThat(workerThreads).hasSize(2)
        stopped.acquire()
        while (workerThreads.get(0).isAlive() || workerThreads.get(1).isAlive()) {
            java.lang.Thread.sleep(1)
        }
        Truth.assertThat(workerThreads.get(0).isAlive()).isFalse()
        Truth.assertThat(workerThreads.get(1).isAlive()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testOutput() {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err ->
                    err.println("Failed!")
                    1
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                ProtoWorkerMessageProcessor(ByteArrayInputStream(ByteArray(0)), out)
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val request: WorkRequest? = WorkRequest.newBuilder().addAllArguments(args).build()
        handler.respondToRequest(testWorkerIO, request, RequestInfo(null))

        val response: WorkResponse =
            WorkResponse.parseDelimitedFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getExitCode()).isEqualTo(1)
        com.google.common.truth.Subject.contains("Failed!")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testException() {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val handler: WorkRequestHandler =
            WorkRequestHandler(
                { args, err ->
                    throw java.lang.RuntimeException("Exploded!")
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                ProtoWorkerMessageProcessor(ByteArrayInputStream(ByteArray(0)), out)
            )

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "A.java")
        val request: WorkRequest? = WorkRequest.newBuilder().addAllArguments(args).build()
        handler.respondToRequest(testWorkerIO, request, RequestInfo(null))

        val response: WorkResponse =
            WorkResponse.parseDelimitedFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getExitCode()).isEqualTo(1)
        assertThat(response.getOutput()).startsWith("java.lang.RuntimeException: Exploded!")
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testCancelRequest_exactlyOneResponseSent() {
        val handlerCalled = booleanArrayOf(false)
        val cancelCalled = booleanArrayOf(false)
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()
        val done: Semaphore = Semaphore(0)
        val finish: Semaphore = Semaphore(0)
        val failures: MutableList<String?> = java.util.ArrayList<String?>()

        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        val handler: WorkRequestHandler =
            WorkRequestHandlerBuilder(
                { args, err ->
                    handlerCalled[0] = true
                    err.println("Such work! Much progress! Wow!")
                    1
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )
                .setCancelCallback(
                    { i, t ->
                        cancelCalled[0] = true
                    })
                .build()

        runRequestHandlerThread(done, handler, finish, failures)
        WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src)
        WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src)
        val response: WorkResponse = WorkResponse.parseDelimitedFrom(dest)
        messageProcessor.stop()
        done.acquire()

        Truth.assertThat(handlerCalled[0] || cancelCalled[0]).isTrue()
        assertThat(response.getRequestId()).isEqualTo(42)
        if (response.getWasCancelled()) {
            assertThat(response.getOutput()).isEmpty()
            assertThat(response.getExitCode()).isEqualTo(0)
        } else {
            assertThat(response.getOutput()).startsWith("Such work! Much progress! Wow!")
            assertThat(response.getExitCode()).isEqualTo(1)
        }

        // Checks that nothing more was sent.
        Truth.assertThat(dest.available()).isEqualTo(0)
        finish.release()

        // Checks that there weren't other unexpected failures.
        Truth.assertThat(failures).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testCancelRequest_sendsResponseWhenDone() {
        val waitForCancel: Semaphore = Semaphore(0)
        val handlerCalled: Semaphore = Semaphore(0)
        val cancelCalled: Semaphore = Semaphore(0)
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()
        val done: Semaphore = Semaphore(0)
        val requestDone: Semaphore = Semaphore(0)
        val finish: Semaphore = Semaphore(0)
        val failures: MutableList<String?> = java.util.ArrayList<String?>()

        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        // We force the regular handling to not finish until after we have read the cancel response,
        // to avoid flakiness.
        val handler: WorkRequestHandler =
            WorkRequestHandlerBuilder(
                { args, err ->
                    // This handler waits until the main thread has sent a cancel request.
                    handlerCalled.release(2)
                    try {
                        waitForCancel.acquire()
                    } catch (e: java.lang.InterruptedException) {
                        failures.add("Unexpected interrupt waiting for cancel request")
                        e.printStackTrace()
                    }
                    requestDone.release()
                    0
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )
                .setCancelCallback(
                    { i, t ->
                        cancelCalled.release()
                    })
                .build()

        runRequestHandlerThread(done, handler, finish, failures)
        WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src)
        // Make sure the handler is called before sending the cancel request, or we might process
        // the cancellation entirely before that.
        handlerCalled.acquire()
        WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src)
        cancelCalled.acquire()
        waitForCancel.release()
        // Give the other request a chance to process, so we can check that no other response is sent
        requestDone.acquire()
        messageProcessor.stop()
        done.acquire()

        val response: WorkResponse = WorkResponse.parseDelimitedFrom(dest)
        Truth.assertThat(handlerCalled.availablePermits()).isEqualTo(1) // Released 2, one was acquired
        Truth.assertThat(cancelCalled.availablePermits()).isEqualTo(0)
        assertThat(response.getRequestId()).isEqualTo(42)
        assertThat(response.getOutput()).isEmpty()
        assertThat(response.getWasCancelled()).isTrue()

        // Checks that nothing more was sent.
        Truth.assertThat(dest.available()).isEqualTo(0)
        src.close()
        finish.release()

        // Checks that there weren't other unexpected failures.
        Truth.assertThat(failures).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testCancelRequest_noDoubleCancelResponse() {
        val waitForCancel: Semaphore = Semaphore(0)
        val cancelCalled: Semaphore = Semaphore(0)
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()
        val done: Semaphore = Semaphore(0)
        val requestsDone: Semaphore = Semaphore(0)
        val finish: Semaphore = Semaphore(0)
        val failures: MutableList<String?> = java.util.ArrayList<String?>()

        // We force the regular handling to not finish until after we have read the cancel response,
        // to avoid flakiness.
        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        val handler: WorkRequestHandler =
            WorkRequestHandlerBuilder(
                { args, err ->
                    try {
                        waitForCancel.acquire()
                    } catch (e: java.lang.InterruptedException) {
                        failures.add("Unexpected interrupt waiting for cancel request")
                        e.printStackTrace()
                    }
                    requestsDone.release()
                    0
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )
                .setCancelCallback(
                    { i, t ->
                        cancelCalled.release()
                    })
                .build()

        runRequestHandlerThread(done, handler, finish, failures)
        WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src)
        WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src)
        WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src)
        cancelCalled.acquire()
        waitForCancel.release()
        requestsDone.acquire()
        messageProcessor.stop()
        done.acquire()

        val response: WorkResponse = WorkResponse.parseDelimitedFrom(dest)
        Truth.assertThat(cancelCalled.availablePermits()).isLessThan(2)
        assertThat(response.getRequestId()).isEqualTo(42)
        assertThat(response.getOutput()).isEmpty()
        assertThat(response.getWasCancelled()).isTrue()

        // Checks that nothing more was sent.
        Truth.assertThat(dest.available()).isEqualTo(0)
        src.close()
        finish.release()

        // Checks that there weren't other unexpected failures.
        Truth.assertThat(failures).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testCancelRequest_sendsNoResponseWhenAlreadySent() {
        val handlerCalled: Semaphore = Semaphore(0)
        val src: PipedOutputStream = PipedOutputStream()
        val dest: PipedInputStream = PipedInputStream()
        val done: Semaphore = Semaphore(0)
        val finish: Semaphore = Semaphore(0)
        val failures: MutableList<String?> = java.util.ArrayList<String?>()

        // We force the cancel request to not happen until after we have read the normal response,
        // to avoid flakiness.
        val messageProcessor =
            StoppableWorkerMessageProcessor(
                ProtoWorkerMessageProcessor(
                    PipedInputStream(src), PipedOutputStream(dest)
                )
            )
        val handler: WorkRequestHandler =
            WorkRequestHandlerBuilder(
                { args, err ->
                    handlerCalled.release()
                    err.println("Such work! Much progress! Wow!")
                    2
                },
                PrintStream(java.io.ByteArrayOutputStream()),
                messageProcessor
            )
                .setCancelCallback({ i, t -> })
                .build()

        runRequestHandlerThread(done, handler, finish, failures)
        WorkRequest.newBuilder().setRequestId(42).build().writeDelimitedTo(src)
        val response: WorkResponse = WorkResponse.parseDelimitedFrom(dest)
        WorkRequest.newBuilder().setRequestId(42).setCancel(true).build().writeDelimitedTo(src)
        messageProcessor.stop()
        done.acquire()

        assertThat(response).isNotNull()

        Truth.assertThat(handlerCalled.availablePermits()).isEqualTo(1)
        assertThat(response.getRequestId()).isEqualTo(42)
        assertThat(response.getWasCancelled()).isFalse()
        assertThat(response.getExitCode()).isEqualTo(2)
        assertThat(response.getOutput()).startsWith("Such work! Much progress! Wow!")

        // Checks that nothing more was sent.
        Truth.assertThat(dest.available()).isEqualTo(0)
        src.close()
        finish.release()

        // Checks that there weren't other unexpected failures.
        Truth.assertThat(failures).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testWorkRequestHandler_withWorkRequestCallback() {
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val callback: WorkRequestCallback =
            WorkRequestCallback({ request, err -> request.getArgumentsCount() })
        val handler: WorkRequestHandler =
            WorkRequestHandlerBuilder(
                callback,
                PrintStream(java.io.ByteArrayOutputStream()),
                ProtoWorkerMessageProcessor(ByteArrayInputStream(ByteArray(0)), out)
            )
                .build()

        val args: MutableList<String?> = mutableListOf<String?>("--sources", "B.java")
        val request: WorkRequest? = WorkRequest.newBuilder().addAllArguments(args).build()
        handler.respondToRequest(testWorkerIO, request, RequestInfo(null))

        val response: WorkResponse =
            WorkResponse.parseDelimitedFrom(ByteArrayInputStream(out.toByteArray()))
        assertThat(response.getRequestId()).isEqualTo(0)
        assertThat(response.getExitCode()).isEqualTo(2)
        assertThat(response.getOutput()).isEmpty()
    }

    private fun runRequestHandlerThread(
        done: Semaphore, handler: WorkRequestHandler, finish: Semaphore, failures: MutableList<String?>
    ) {
        // This thread just makes sure the WorkRequestHandler does work asynchronously.
        java.lang.Thread(
            java.lang.Runnable {
                try {
                    handler.processRequests()
                    while (!handler.activeRequests.isEmpty()) {
                        java.lang.Thread.sleep(1)
                    }
                } catch (e: IOException) {
                    failures.add("Unexpected I/O error talking to worker thread")
                    e.printStackTrace()
                } catch (e: java.lang.InterruptedException) {
                    // Getting interrupted while waiting for requests to finish is OK.
                }
                try {
                    done.release()
                    finish.acquire()
                } catch (e: java.lang.InterruptedException) {
                    // Getting interrupted at the end is OK.
                }
            })
            .start()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerIO_doesWrapSystemStreams() {
        // Save the original streams
        val originalInputStream: java.io.InputStream? = java.lang.System.`in`
        val originalOutputStream: PrintStream? = java.lang.System.out
        val originalErrorStream: PrintStream? = java.lang.System.err

        // Swap in the test streams to assert against
        val byteArrayInputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        java.lang.System.setIn(byteArrayInputStream)
        val outputBuffer: PrintStream = PrintStream(java.io.ByteArrayOutputStream(), true)
        java.lang.System.setOut(outputBuffer)
        java.lang.System.setErr(outputBuffer)

        try {
            outputBuffer.use {
                byteArrayInputStream.use {
                    WorkRequestHandler.WorkerIO.capture().use { io ->
                        // Assert that the WorkerIO returns the correct wrapped streams and the new System instance
                        // has been swapped out with the wrapped one
                        assertThat(io.getOriginalInputStream()).isSameInstanceAs(byteArrayInputStream)
                        Truth.assertThat(java.lang.System.`in`).isNotSameInstanceAs(byteArrayInputStream)

                        assertThat(io.getOriginalOutputStream()).isSameInstanceAs(outputBuffer)
                        Truth.assertThat(java.lang.System.out).isNotSameInstanceAs(outputBuffer)

                        assertThat(io.getOriginalErrorStream()).isSameInstanceAs(outputBuffer)
                        Truth.assertThat(java.lang.System.err).isNotSameInstanceAs(outputBuffer)
                    }
                }
            }
        } finally {
            // Swap back in the original streams
            java.lang.System.setIn(originalInputStream)
            java.lang.System.setOut(originalOutputStream)
            java.lang.System.setErr(originalErrorStream)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerIO_doesCaptureStandardOutAndErrorStreams() {
        WorkRequestHandler.WorkerIO.capture().use { io ->
            // Assert that nothing has been captured in the new instance
            assertThat(io.readCapturedAsUtf8String()).isEmpty()

            // Assert that the standard out/error stream redirect to our own streams
            print("This is a standard out message!")
            java.lang.System.err.print("This is a standard error message!")
            assertThat(io.readCapturedAsUtf8String())
                .isEqualTo("This is a standard out message!This is a standard error message!")

            // Assert that readCapturedAsUtf8String calls reset on the captured stream after a read
            assertThat(io.readCapturedAsUtf8String()).isEmpty()

            print("out 1")
            java.lang.System.err.print("err 1")
            print("out 2")
            java.lang.System.err.print("err 2")
            assertThat(io.readCapturedAsUtf8String()).isEqualTo("out 1err 1out 2err 2")
            assertThat(io.readCapturedAsUtf8String()).isEmpty()
        }
    }

    private fun createTestWorkerIO(): WorkRequestHandler.WorkerIO {
        val captured: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        return WorkerIO(java.lang.System.`in`, java.lang.System.out, java.lang.System.err, captured, captured)
    }

    /** A wrapper around a WorkerMessageProcessor that can be stopped by calling `#stop()`.  */
    private class StoppableWorkerMessageProcessor(delegate: WorkerMessageProcessor) : WorkerMessageProcessor {
        private val delegate: WorkerMessageProcessor
        private val stop: AtomicBoolean = AtomicBoolean(false)
        private var readerThread: java.lang.Thread? = null

        init {
            this.delegate = delegate
        }

        @Throws(IOException::class)
        public override fun readWorkRequest(): WorkRequest? {
            readerThread = java.lang.Thread.currentThread()
            if (stop.get()) {
                return null
            } else {
                try {
                    return delegate.readWorkRequest()
                } catch (e: InterruptedIOException) {
                    // Being interrupted is only an error if we didn't ask for it.
                    if (!stop.get()) {
                        throw e
                    } else {
                        return null
                    }
                }
            }
        }

        @Throws(IOException::class)
        public override fun writeWorkResponse(workResponse: WorkResponse?) {
            delegate.writeWorkResponse(workResponse)
        }

        @Throws(IOException::class)
        public override fun close() {
            delegate.close()
        }

        fun stop() {
            stop.set(true)
            if (readerThread != null) {
                readerThread.interrupt()
            }
        }

        fun interruptReader() {
            readerThread.interrupt()
        }
    }
}
