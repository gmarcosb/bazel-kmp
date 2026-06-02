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

import com.google.devtools.build.lib.actions.UserExecException

/**
 * An abstract superclass for persistent workers. Workers execute actions in long-running processes
 * that can handle multiple actions.
 */
abstract class Worker(
    workerKey: WorkerKey,
    workerId: Int,
    logFile: com.google.devtools.build.lib.vfs.Path?,
    status: WorkerProcessStatus
) {
    /** An unique identifier of the work process.  */
    @kotlin.jvm.JvmField
    val workerKey: WorkerKey

    /**
     * Returns a unique id for this worker. This is used to distinguish different worker processes in
     * logs and messages.
     */
    /** An unique ID of the worker. It will be used in WorkRequest and WorkResponse as well.  */
    @kotlin.jvm.JvmField
    val workerId: Int

    /** The path of the log file for this worker.  */
    protected val logFile: com.google.devtools.build.lib.vfs.Path?

    protected val status: WorkerProcessStatus

    protected var cgroup: Cgroup? = null

    init {
        this.workerKey = workerKey
        this.workerId = workerId
        this.logFile = logFile
        this.status = status
    }

    /** Returns the path of the log file for this worker.  */
    fun getLogFile(): com.google.devtools.build.lib.vfs.Path? {
        return logFile
    }

    /** Returns the worker key of this worker  */
    fun getWorkerKey(): WorkerKey {
        return workerKey
    }

    fun getStatus(): WorkerProcessStatus {
        return status
    }

    open fun getCgroup(): Cgroup? {
        return cgroup
    }

    val workerFilesCombinedHash: com.google.common.hash.HashCode
        get() = workerKey.getWorkerFilesCombinedHash()

    val workerFilesWithDigests: SortedMap<PathFragment?, ByteArray?>
        get() = workerKey.getWorkerFilesWithDigests()

    /** Returns true if this worker is sandboxed.  */
    @kotlin.jvm.JvmField
    abstract val isSandboxed: Boolean

    /**
     * Sets the reporter this `Worker` should report anomalous events to, or clears it. We
     * expect the reporter to be cleared at end of build.
     */
    open fun setReporter(reporter: EventHandler?) {}

    /**
     * Performs the necessary steps to prepare for execution. Once this is done, the worker should be
     * able to receive a WorkRequest without further setup.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    abstract fun prepareExecution(
        inputFiles: SandboxInputs?,
        outputs: SandboxOutputs?,
        workerFiles: MutableSet<PathFragment?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    )

    /**
     * Sends a WorkRequest to the worker.
     * 
     * @param request The request to send.
     * @throws IOException If there was a problem doing I/O, or this thread was interrupted at a time
     * where some or all of the expected I/O has been done.
     */
    @Throws(IOException::class)
    abstract fun putRequest(request: WorkRequest?)

    /**
     * Waits to receive a response from the worker. This method should return as soon as a response
     * has been received, moving of files and cleanup should wait until finishExecution().
     * 
     * @param requestId ID of the request to retrieve a response for.
     * @return The WorkResponse received.
     * @throws IOException If there was a problem doing I/O.
     * @throws InterruptedException If this thread was interrupted, which can also happen during IO.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    abstract fun getResponse(requestId: Int): WorkResponse?

    /**
     * Does whatever cleanup may be required after execution is done.
     * 
     * @param execRoot The global execRoot, where outputs must go.
     * @param outputs The expected outputs.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    open fun finishExecution(execRoot: com.google.devtools.build.lib.vfs.Path?, outputs: SandboxOutputs?) {
        status.maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.ALIVE)
    }

    /**
     * Destroys this worker. Once this has been called, we assume it's safe to clean up related
     * directories.
     */
    abstract fun destroy()

    /** Returns true if this worker is dead but we didn't deliberately kill it.  */
    abstract fun diedUnexpectedly(): Boolean

    /** Returns the exit value of this worker's process, if it has exited.  */
    @kotlin.jvm.JvmField
    abstract val exitValue: java.util.Optional<Int?>?

    /**
     * Returns the last message received on the InputStream, if an unparseable message has been
     * received.
     */
    @kotlin.jvm.JvmField
    abstract val recordingStreamMessage: String?

    /** Returns process id of the worker, if the process already started. Otherwise returns -1.  */
    abstract val processId: Long
}
