// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.events.Event

/**
 * An intermediate worker that sends requests and receives responses from the worker processes.
 * There is at most one of these per `WorkerKey`, corresponding to one worker process. `WorkerMultiplexer` objects run in separate long-lived threads. `WorkerProxy` objects call
 * into them to send requests. When a worker process returns a `WorkResponse`, `WorkerMultiplexer` wakes up the relevant `WorkerProxy` to retrieve the response.
 */
open class WorkerMultiplexer internal constructor(
    logFile: com.google.devtools.build.lib.vfs.Path,
    workerKey: WorkerKey,
    multiplexerId: Int
) {
    /**
     * An ID for this multiplexer that can be used by sandboxed multiplex workers to generate their
     * workdir. The workdir needs to be the same for all `SandboxedWorkerProxy` instances
     * associated with a `WorkerMultiplexer`, but needs to be unique across multiplexers for the
     * same mnemonic. This is analogous to the `workerId` created in `WorkerFactory`.
     */
    @kotlin.jvm.JvmField
    val multiplexerId: Int

    /**
     * A queue of [WorkRequest] instances that need to be sent to the worker. [ ] instances add to this queue, while the requestSender subthread remove requests and
     * send them to the worker. This prevents dynamic execution interrupts from corrupting the `stdin` of the worker process.
     */
    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val pendingRequests: BlockingQueue<WorkRequest> = LinkedBlockingQueue<WorkRequest>()

    /**
     * A map of `WorkResponse`s received from the worker process. They are stored in this map
     * keyed by the request id until the corresponding `WorkerProxy` picks them up.
     */
    private val workerProcessResponse: ConcurrentMap<Int?, WorkResponse?> = ConcurrentHashMap<Int?, WorkResponse?>()

    /**
     * A map of semaphores corresponding to `WorkRequest`s. After sending the `WorkRequest`, `WorkerProxy` will wait on a semaphore to be released. `WorkerMultiplexer` is responsible for releasing the corresponding semaphore in order to signal
     * `WorkerProxy` that the `WorkerResponse` has been received.
     */
    private val responseChecker: ConcurrentMap<Int?, Semaphore> = ConcurrentHashMap<Int?, Semaphore>()

    /**
     * The worker process that this WorkerMultiplexer should be talking to. This should only be set
     * once, when creating a new process. If the process dies or its stdio streams get corrupted, the
     * `WorkerMultiplexer` gets discarded as well and a new one gets created as needed.
     */
    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    var process: Subprocess? = null

    /** The implementation of the worker protocol (JSON or Proto).  */
    private var workerProtocol: WorkerProtocolImpl? = null

    /** InputStream from the worker process.  */
    @com.google.errorprone.annotations.concurrent.LazyInit
    private var recordingStream: RecordingInputStream? = null

    /** Status of the worker process.  */
    private val status: WorkerProcessStatus

    /**
     * The log file of the actual running worker process. It is shared between all WorkerProxy
     * instances for this multiplexer.
     */
    private val logFile: com.google.devtools.build.lib.vfs.Path

    /** The worker key that this multiplexer is for.  */
    private val workerKey: WorkerKey

    /** For testing only, allow a way to fake subprocesses.  */
    private var subprocessFactory: SubprocessFactory? = null

    /** A separate thread that sends requests.  */
    private var requestSender: java.lang.Thread? = null

    /** A separate thread that receives responses.  */
    private var responseReceiver: java.lang.Thread? = null

    /**
     * The active Reporter object, non-null if `--worker_verbose` is set. This must be cleared
     * at the end of a command execution.
     */
    private var reporter: EventHandler? = null

    /**
     * Shutdown hook to make sure we wait for the process to finish on JVM shutdown, to avoid creating
     * zombie processes. Unfortunately, shutdown hooks are not guaranteed to be called, but this is
     * the best we can do. This must be set when a process is created.
     */
    private var shutdownHook: java.lang.Thread? = null

    /**
     * The workDir of the multiplexer. We should clean this up on destroy if it's a sandboxed
     * multiplex worker.
     */
    private var workDir: com.google.devtools.build.lib.vfs.Path? = null

    init {
        this.status = WorkerProcessStatus()
        this.logFile = logFile
        this.workerKey = workerKey
        this.multiplexerId = multiplexerId
    }

    /** Sets or clears the reporter for outputting verbose info.  */
    @kotlin.jvm.Synchronized
    fun setReporter(reporter: EventHandler?) {
        this.reporter = reporter
    }

    /** Reports a string to the user if reporting is enabled.  */
    @kotlin.jvm.Synchronized
    private fun report(s: String?) {
        if (this.reporter != null && s != null) {
            this.reporter.handle(Event.info(s))
        }
    }

    fun getStatus(): WorkerProcessStatus {
        return status
    }

    /**
     * Creates a worker process corresponding to this `WorkerMultiplexer`, if it doesn't already
     * exist. Also starts up the subthreads handling reading and writing requests and responses, and
     * sets up the sandbox root dir with the required worker files.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createSandboxedProcess(
        workDir: com.google.devtools.build.lib.vfs.Path,
        workerFiles: MutableSet<PathFragment?>?,
        inputFiles: SandboxInputs,
        treeDeleter: TreeDeleter?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        // TODO: Make blaze clean remove the workdir.
        if (this.process == null) {
            // This should be a once-only operation.
            workDir.createDirectoryAndParents()
            workDir.deleteTreesBelow()
            val dirsToCreate: LinkedHashSet<PathFragment?> = LinkedHashSet<PathFragment?>()
            val inputsToCreate: MutableSet<PathFragment?> = HashSet<PathFragment?>()
            SandboxHelpers.populateInputsAndDirsToCreate(
                com.google.common.collect.ImmutableSet.of<E?>(),
                inputsToCreate,
                dirsToCreate,
                workerFiles,
                SandboxOutputs.getEmptyInstance()
            )
            SandboxHelpers.cleanExisting(
                workDir.getParentDirectory(),
                inputFiles,
                inputsToCreate,
                dirsToCreate,
                workDir,
                treeDeleter
            )
            SandboxHelpers.createDirectories(dirsToCreate, workDir,  /* strict= */false)
            WorkerExecRoot.Companion.createInputs(inputsToCreate, inputFiles.limitedCopy(workerFiles), workDir)
            createProcess(workDir, clientEnv)
        }
    }

    /**
     * Creates a worker process corresponding to this `WorkerMultiplexer`, if it doesn't already
     * exist. Also starts up the subthreads handling reading and writing requests and responses.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    open fun createProcess(
        workDir: com.google.devtools.build.lib.vfs.Path,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        if (this.process == null) {
            if (this.status.isKilled()) {
                throw IOException("Multiplexer destroyed before created process")
            }
            this.shutdownHook =
                java.lang.Thread(
                    java.lang.Runnable {
                        this.shutdownHook = null
                        this.destroyMultiplexer()
                    })
            java.lang.Runtime.getRuntime().addShutdownHook(shutdownHook)
            var args: com.google.common.collect.ImmutableList<String?> = workerKey.getArgs()
            val executable: java.io.File = java.io.File(StringEncoding.internalToPlatform(args.get(0)))
            if (!executable.isAbsolute() && executable.getParent() != null) {
                val newArgs: MutableList<String?> = java.util.ArrayList<String?>(args)
                newArgs.set(
                    0,
                    StringEncoding.platformToInternal(
                        java.io.File(workDir.getPathFile(), StringEncoding.internalToPlatform(newArgs.get(0)))
                            .getAbsolutePath()
                    )
                )
                args = com.google.common.collect.ImmutableList.copyOf<String?>(newArgs)
            }
            val processBuilder: SubprocessBuilder =
                if (subprocessFactory != null)
                    SubprocessBuilder(clientEnv, subprocessFactory)
                else
                    SubprocessBuilder(clientEnv)
            processBuilder.setArgv(args)
            processBuilder.setWorkingDirectory(workDir.getPathFile())
            processBuilder.setStderr(logFile.getPathFile())
            processBuilder.setEnv(workerKey.getEnv())
            this.process = processBuilder.start()
            status.maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.ALIVE)

            recordingStream = RecordingInputStream(process.inputStream)
            recordingStream.startRecording(4096)
            if (workerProtocol == null) {
                when (workerKey.getProtocolFormat()) {
                    JSON -> workerProtocol = JsonWorkerProtocol(process.outputStream, recordingStream)
                    PROTO -> workerProtocol = ProtoWorkerProtocol(process.outputStream, recordingStream)
                }
            }
            val id = workerKey.getMnemonic() + "-" + workerKey.hashCode()
            logger.atInfo().log(
                "Created multiplexer process %s for worker %s", process.processId, id
            )
            // TODO(larsrc): Consider moving sender/receiver threads into separate classes.
            this.requestSender =
                java.lang.Thread(
                    java.lang.Runnable {
                        while (process.isAlive && sendRequest()) {
                        }
                    })
            this.requestSender.setName("multiplexer-request-sender-" + id)
            this.requestSender.start()
            this.responseReceiver =
                java.lang.Thread(
                    java.lang.Runnable {
                        while (process.isAlive && readResponse()) {
                        }
                    })
            this.responseReceiver.setName("multiplexer-response-receiver-" + id)
            this.responseReceiver.start()
        } else if (!this.process.isAlive) {
            throw IOException("Process is dead")
        }
    }

    /**
     * Returns the path of the log file shared by all multiplex workers using this process. May be
     * null if the process has not started yet.
     */
    fun getLogFile(): com.google.devtools.build.lib.vfs.Path {
        return logFile
    }

    /**
     * Signals this object to destroy itself, including the worker process. The object might not be
     * fully destroyed at the end of this call, but will terminate soon. This is considered a
     * deliberate destruction.
     */
    @kotlin.jvm.Synchronized
    fun destroyMultiplexer() {
        if (this.process != null) {
            destroyProcess()
        }
        if (workDir != null) {
            try {
                workDir.deleteTree()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to delete workDir.")
            }
        } else if (workerKey.isSandboxed()) {
            logger.atWarning().log(
                "No workDir was deleted for this sandboxed multiplex worker because the workDir was never"
                        + " set or set to null."
            )
        }
        // The WorkerProcessStatus is only set as killed once all WorkerProxy instances are destroyed.
        status.setKilled()
    }

    /**
     * Destroys the worker subprocess. This might block forever if the subprocess refuses to die. It
     * is safe to call this multiple times.
     */
    @kotlin.jvm.Synchronized
    private fun destroyProcess() {
        var wasInterrupted = false
        try {
            this.process.destroy()
            while (true) {
                try {
                    this.process.waitFor()
                    return
                } catch (ie: java.lang.InterruptedException) {
                    wasInterrupted = true
                }
            }
        } finally {
            if (shutdownHook != null) {
                java.lang.Runtime.getRuntime().removeShutdownHook(shutdownHook)
                shutdownHook = null
            }
            // Stop the subthreads only when the process is dead, or their loops will go on.
            if (this.requestSender != null) {
                this.requestSender.interrupt()
            }
            if (this.responseReceiver != null) {
                this.responseReceiver.interrupt()
            }
            // Might as well release any waiting workers
            for (semaphore in responseChecker.values()) {
                semaphore.release()
            }
            // Read this for detailed explanation: http://www.ibm.com/developerworks/library/j-jtp05236/
            if (wasInterrupted) {
                java.lang.Thread.currentThread().interrupt() // preserve interrupted status
            }
        }
    }

    /**
     * Sends the WorkRequest to worker process. This method is called on the thread of a `WorkerProxy`, and so is subject to interrupts by dynamic execution.
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    fun putRequest(request: WorkRequest) {
        if (!process.isAlive) {
            throw IOException(
                "Attempting to send request " + request.getRequestId() + " to dead process"
            )
        }
        if (!request.getCancel()) {
            responseChecker.put(request.getRequestId(), Semaphore(0))
        }
        pendingRequests.add(request)
    }

    /**
     * Waits on a semaphore for the `WorkResponse` returned from worker process. This method is
     * called on the thread of a `WorkerProxy`, and so is subject to interrupts by dynamic
     * execution.
     */
    @Throws(java.lang.InterruptedException::class, IOException::class)
    fun getResponse(requestId: Int?): WorkResponse? {
        if (!process.isAlive) {
            // If the process has died, all we can do is return what may already have been returned.
            return workerProcessResponse.get(requestId)
        }

        val waitForResponse: Semaphore? = responseChecker.get(requestId)

        if (waitForResponse == null) {
            report("Null response semaphore for " + requestId)
            // If there is no semaphore for this request, it probably failed to send, so we just return
            // what we got, probably nothing.
            return workerProcessResponse.get(requestId)
        }

        waitForResponse.acquire()

        responseChecker.remove(requestId)
        val response: WorkResponse? = workerProcessResponse.remove(requestId)

        if (response == null && !process.isAlive) {
            throw IOException("Worker process for " + workerKey.getMnemonic() + " has died")
        }
        return response
    }

    /**
     * Sends a single pending request, if there are any. Blocks until a request is available.
     * 
     * 
     * This is only called by the `requestSender` thread and so cannot be interrupted by
     * dynamic execution cancellation, but only by a call to [.destroyProcess].
     */
    private fun sendRequest(): Boolean {
        val request: WorkRequest
        try {
            request = pendingRequests.take()
        } catch (e: java.lang.InterruptedException) {
            return false
        }
        try {
            workerProtocol.putRequest(request)
        } catch (e: IOException) {
            // We can't know how much of the request was sent, so we have to assume the worker's input
            // now contains garbage, and this request is lost.
            // TODO(b/177637516): Signal that this action failed for presumably transient reasons.
            report("Failed to send request " + request.getRequestId())
            val s: Semaphore? = responseChecker.remove(request.getRequestId())
            if (s != null) {
                s.release()
            }
            // TODO(b/177637516): Leave process in a moribound state so pending responses can be returned.
            destroyProcess()
            return false
        }
        return true
    }

    /**
     * Reads a `WorkResponse` from worker process, puts that `WorkResponse` in `workerProcessResponse`, and releases the semaphore for the `WorkerProxy`.
     * 
     * 
     * This is only called on the readResponses subthread and so cannot be interrupted by dynamic
     * execution cancellation, but only by a call to [.destroyProcess].
     * 
     * @return True if the worker is still in a consistent state.
     */
    private fun readResponse(): Boolean {
        val parsedResponse: WorkResponse?
        try {
            parsedResponse = workerProtocol.getResponse()
        } catch (e: IOException) {
            if (e !is InterruptedIOException) {
                report(
                    java.lang.String.format(
                        "Error while reading response from multiplexer process for %s: %s",
                        workerKey.getMnemonic(), e
                    )
                )
            }
            // We can't know how much of the response was read, so we have to assume the worker's output
            // now contains garbage, and we can't reliably read any further responses.
            destroyProcess()
            return false
        }

        // A null parsedResponse can only happen if the input stream is closed, in which case we
        // drop everything.
        if (parsedResponse == null) {
            report(
                java.lang.String.format(
                    "Multiplexer process for %s has closed its output stream", workerKey.getMnemonic()
                )
            )
            destroyProcess()
            return false
        }

        val requestId: Int = parsedResponse.getRequestId()
        workerProcessResponse.put(requestId, parsedResponse)

        // TODO(b/151767359): When allowing cancellation, just remove responses that have no matching
        // entry in responseChecker.
        val semaphore: Semaphore? = responseChecker.get(requestId)
        if (semaphore != null) {
            // This wakes up the WorkerProxy that should receive this response.
            semaphore.release()
        } else {
            report(java.lang.String.format("Multiplexer for %s found no semaphore", workerKey.getMnemonic()))
            workerProcessResponse.remove(requestId)
        }
        return true
    }

    val recordingStreamMessage: String?
        get() {
            // Once we read junk, we can't trust the rest of the stream
            synchronized(recordingStream) {
                recordingStream.readRemaining()
                return recordingStream.getRecordedDataAsString()
            }
        }

    /** Returns true if this process has died for other reasons than a call to `#destroy()`.  */
    fun diedUnexpectedly(): Boolean {
        return this.process != null && !this.process.isAlive && !status.isKilled()
    }

    val exitValue: java.util.Optional<Int?>
        /** Returns the exit value of multiplexer's process, if it has exited.  */
        get() = if (this.process != null && !this.process.isAlive)
            java.util.Optional.of<T?>(this.process.exitValue())
        else
            java.util.Optional.empty<Int?>()

    /** For testing only, to verify that maps are cleared after responses are reaped.  */
    @com.google.common.annotations.VisibleForTesting
    fun noOutstandingRequests(): Boolean {
        return responseChecker.isEmpty() && workerProcessResponse.isEmpty()
    }

    @com.google.common.annotations.VisibleForTesting
    fun setProcessFactory(factory: SubprocessFactory?) {
        subprocessFactory = factory
    }

    val processId: Long
        get() {
            if (process == null) {
                return -1
            }
            return process.processId
        }

    fun setWorkDir(workDir: com.google.devtools.build.lib.vfs.Path?) {
        this.workDir = workDir
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
