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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.UserExecException

/**
 * Interface to a worker process running as a single child process.
 * 
 * 
 * A worker process must follow this protocol to be usable via this class: The worker process is
 * spawned on demand. The worker process is free to exit whenever necessary, as new instances will
 * be relaunched automatically. Communication happens via the WorkerProtocol protobuf, sent to and
 * received from the worker process via stdin / stdout.
 * 
 * 
 * Other code in Blaze can talk to the worker process via input / output streams provided by this
 * class.
 */
internal open class SingleplexWorker(
    workerKey: WorkerKey?,
    workerId: Int,
    workDir: com.google.devtools.build.lib.vfs.Path,
    logFile: com.google.devtools.build.lib.vfs.Path?,
    options: WorkerOptions,
    cgroupFactory: VirtualCgroupFactory?
) : com.google.devtools.build.lib.worker.Worker(workerKey, workerId, logFile, WorkerProcessStatus()) {
    /** The execution root of the worker.  */
    protected val workDir: com.google.devtools.build.lib.vfs.Path

    /**
     * Stream for recording the WorkResponse as it's read, so that it can be printed in the case of
     * parsing failures.
     */
    private var recordingInputStream: RecordingInputStream? = null

    /** The implementation of the worker protocol (JSON or Proto).  */
    @javax.annotation.concurrent.GuardedBy("this")
    private var workerProtocol: WorkerProtocolImpl? = null

    private var process: Subprocess? = null

    /** True if we deliberately destroyed this process.  */
    private var wasDestroyed = false

    /**
     * Shutdown hook to make sure we wait for the process to finish on JVM shutdown, to avoid creating
     * zombie processes. Unfortunately, shutdown hooks are not guaranteed to be called, but this is
     * the best we can do. This must be set when a process is created.
     */
    protected var shutdownHook: java.lang.Thread? = null

    protected var options: WorkerOptions
    protected val cgroupFactory: VirtualCgroupFactory?

    init {
        this.workDir = workDir
        this.options = options
        this.cgroupFactory = cgroupFactory
    }

    @Throws(IOException::class, UserExecException::class)
    protected open fun createProcess(clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?): Subprocess {
        val args: com.google.common.collect.ImmutableList<String?> = makeExecPathAbsolute(workerKey.getArgs())
        val process: Subprocess = createProcessBuilder(args, clientEnv).start()
        if (cgroupFactory != null) {
            cgroup = cgroupFactory.create(workerId, com.google.common.collect.ImmutableMap.of<K?, V?>())
        } else if (options.getUseCgroupsOnLinux() && CgroupsInfo.isSupported()) {
            cgroup =
                CgroupsInfo.getBlazeSpawnsCgroup()
                    .createIndividualSpawnCgroup( /* dirName= */
                        "worker_" + workerId,  /* memoryLimitMb= */0
                    )
        }
        if (cgroup != null && cgroup.exists()) {
            cgroup.addProcess(process.processId)
        }
        return process
    }

    protected fun createProcessBuilder(
        argv: com.google.common.collect.ImmutableList<String?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ): SubprocessBuilder {
        val processBuilder: SubprocessBuilder = SubprocessBuilder(clientEnv)
        processBuilder.setArgv(argv)
        processBuilder.setWorkingDirectory(workDir.getPathFile())
        processBuilder.setStderr(logFile.getPathFile())
        processBuilder.setEnv(workerKey.getEnv())
        return processBuilder
    }

    val isSandboxed: Boolean
        get() = false

    @Throws(IOException::class, java.lang.InterruptedException::class, UserExecException::class)
    override fun prepareExecution(
        inputFiles: SandboxInputs?,
        outputs: SandboxOutputs?,
        workerFiles: MutableSet<PathFragment?>?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    ) {
        if (process == null) {
            addShutdownHook()
            process = createProcess(clientEnv)
            logger.atInfo().log(
                "Created worker process %s for worker id %d", process.processId, workerId
            )
            status.maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.ALIVE)
            recordingInputStream = RecordingInputStream(process.inputStream)
        }
        synchronized(this) {
            if (workerProtocol == null) {
                workerProtocol =
                    when (workerKey.getProtocolFormat()) {
                        JSON -> JsonWorkerProtocol(process.outputStream, recordingInputStream)
                        PROTO -> ProtoWorkerProtocol(process.outputStream, recordingInputStream)
                    }
            }
        }
    }

    fun addShutdownHook() {
        this.shutdownHook =
            java.lang.Thread(
                java.lang.Runnable {
                    this.shutdownHook = null
                    this.destroy()
                })
        java.lang.Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /**
     * Makes sure that the executable (first element of argument list) is an absolute path. Necessary
     * on Windows (https://github.com/bazelbuild/bazel/commit/8efc3ef0)
     */
    protected fun makeExecPathAbsolute(args: com.google.common.collect.ImmutableList<String?>): com.google.common.collect.ImmutableList<String?> {
        val executable: java.io.File = java.io.File(args.get(0))
        if (!executable.isAbsolute() && executable.getParent() != null) {
            return com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(args.size())
                .add(java.io.File(workDir.getPathFile(), args.get(0)).getAbsolutePath())
                .addAll(args.subList(1, args.size()))
                .build()
        } else {
            return args
        }
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun putRequest(request: WorkRequest?) {
        if (workerProtocol == null) {
            throw IOException("Worker has been destroyed.")
        }
        workerProtocol.putRequest(request)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun getResponse(requestId: Int): WorkResponse? {
        recordingInputStream.startRecording(4096)
        while (recordingInputStream.available() == 0) {
            java.lang.Thread.sleep(10)
            if (!process.isAlive) {
                throw IOException(
                    java.lang.String.format(
                        "Worker process for %s died while waiting for response", workerKey.getMnemonic()
                    )
                )
            }
        }
        // We only want to synchronize on the getResponse() call, and not in the loop above to avoid
        // locking this worker.
        synchronized(this) {
            if (workerProtocol == null) {
                throw IOException("Worker has been destroyed.")
            }
            return workerProtocol.getResponse()
        }
    }

    @kotlin.jvm.Synchronized
    override fun destroy() {
        if (workerProtocol != null) {
            try {
                workerProtocol.close()
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Caught IOException while closing worker protocol.")
            }
            workerProtocol = null
        }
        if (shutdownHook != null) {
            try {
                java.lang.Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: java.lang.IllegalStateException) {
                // Can only happen if we're already in shutdown, in which case we don't care.
            }
        }
        if (process != null) {
            wasDestroyed = true
            process.destroyAndWait()
        }
        if (cgroupFactory != null) {
            cgroupFactory.remove(workerId)
        }
        status.setKilled()
    }

    /** Returns true if this process is dead but we didn't deliberately kill it.  */
    override fun diedUnexpectedly(): Boolean {
        return process != null && !wasDestroyed && !process.isAlive
    }

    val exitValue: java.util.Optional<Int?>
        get() = if (process != null && !process.isAlive)
            java.util.Optional.of<T?>(process.exitValue())
        else
            java.util.Optional.empty<Int?>()

    val recordingStreamMessage: String?
        get() {
            recordingInputStream.readRemaining()
            return recordingInputStream.getRecordedDataAsString()
        }

    override fun toString(): String {
        return workerKey.getMnemonic() + " worker #" + workerId
    }

    val processId: Long
        get() {
            if (process == null) {
                return -1
            }

            return process.processId
        }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
