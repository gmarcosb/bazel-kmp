// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.UserExecException

/** A proxy that talks to the multiplexer  */
internal open class WorkerProxy(
    workerKey: WorkerKey?,
    workerId: Int,
    logFile: com.google.devtools.build.lib.vfs.Path?,
    workerMultiplexer: WorkerMultiplexer,
    workDir: com.google.devtools.build.lib.vfs.Path?
) : com.google.devtools.build.lib.worker.Worker(workerKey, workerId, logFile, workerMultiplexer.getStatus()) {
    @kotlin.jvm.JvmField
    val workerMultiplexer: WorkerMultiplexer

    /** The execution root of the worker. This is the CWD of the worker process.  */
    @kotlin.jvm.JvmField
    val workDir: com.google.devtools.build.lib.vfs.Path?

    init {
        // Worker proxies of the same multiplexer share a WorkerProcessStatus.
        this.workDir = workDir
        this.workerMultiplexer = workerMultiplexer
    }

    val cgroup: Cgroup?
        get() =// WorkerProxy does not have a cgroup at the momemnt. Consider adding it to the
            // multiplexer and returning it here?
            null

    val isSandboxed: Boolean
        get() = false

    override fun setReporter(reporter: EventHandler?) {
        // We might have created this multiplexer after setting the reporter for existing multiplexers
        workerMultiplexer.setReporter(reporter)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun prepareExecution(
        inputFiles: SandboxInputs?,
        outputs: SandboxOutputs?,
        workerFiles: MutableSet<PathFragment?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        workerMultiplexer.createProcess(workDir, clientEnv)
    }

    @kotlin.jvm.Synchronized
    override fun destroy() {
        try {
            WorkerMultiplexerManager.removeInstance(workerKey)
        } catch (e: UserExecException) {
            logger.atWarning().withCause(e).log("Exception")
        }
    }

    /** Send the WorkRequest to multiplexer.  */
    @Throws(IOException::class)
    public override fun putRequest(request: WorkRequest?) {
        workerMultiplexer.putRequest(request)
    }

    /** Wait for WorkResponse from multiplexer.  */
    @Throws(java.lang.InterruptedException::class, IOException::class)
    override fun getResponse(requestId: Int): WorkResponse? {
        return workerMultiplexer.getResponse(requestId)
    }

    override fun diedUnexpectedly(): Boolean {
        return workerMultiplexer.diedUnexpectedly()
    }

    val exitValue: java.util.Optional<Int?>?
        get() = workerMultiplexer.getExitValue()

    val recordingStreamMessage: String?
        get() = workerMultiplexer.getRecordingStreamMessage()

    override fun toString(): String {
        return workerKey.getMnemonic() + " proxy worker #" + workerId
    }

    val processId: Long
        get() = workerMultiplexer.getProcessId()

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
