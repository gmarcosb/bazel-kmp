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

import com.google.devtools.build.lib.sandbox.AsynchronousTreeDeleter

/** Factory used by the pool to create / destroy / validate worker processes.  */
class WorkerFactory @kotlin.jvm.JvmOverloads constructor(
    workerBaseDir: com.google.devtools.build.lib.vfs.Path,
    workerOptions: WorkerOptions,
    hardenedSandboxOptions: WorkerSandboxOptions? = null,
    treeDeleter: AsynchronousTreeDeleter? = null,
    cgroupFactory: VirtualCgroupFactory? = null
) {
    protected val workerOptions: WorkerOptions

    private val workerBaseDir: com.google.devtools.build.lib.vfs.Path
    private val treeDeleter: AsynchronousTreeDeleter?
    private val cgroupFactory: VirtualCgroupFactory?
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null

    /**
     * Options specific to hardened sandbox. Null if `--experimental_worker_sandbox_hardening`
     * is not set.
     */
    private val hardenedSandboxOptions: WorkerSandboxOptions?

    init {
        this.workerBaseDir = workerBaseDir
        this.workerOptions = workerOptions
        this.hardenedSandboxOptions = hardenedSandboxOptions
        this.treeDeleter = treeDeleter
        this.cgroupFactory = cgroupFactory
    }

    fun setReporter(reporter: com.google.devtools.build.lib.events.Reporter?) {
        this.reporter = reporter
    }

    @Throws(IOException::class)
    fun create(key: WorkerKey): com.google.devtools.build.lib.worker.Worker {
        val workerId: Int = pidCounter.getAndIncrement()
        val workTypeName: String = key.getWorkerTypeName()
        if (!workerBaseDir.isDirectory()) {
            workerBaseDir.createDirectoryAndParents()
            val deleterTrashBase: com.google.devtools.build.lib.vfs.Path? =
                if (treeDeleter == null) null else treeDeleter.getTrashBase()
            if (deleterTrashBase != null) {
                deleterTrashBase.createDirectory()
            }
        }
        val logFile: com.google.devtools.build.lib.vfs.Path? =
            workerBaseDir.getRelative(workTypeName + "-" + workerId + "-" + key.getMnemonic() + ".log")

        val worker: com.google.devtools.build.lib.worker.Worker
        if (key.isSandboxed()) {
            if (key.isMultiplex()) {
                val workerMultiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(key, logFile)
                val multiplexerId: Int = workerMultiplexer.getMultiplexerId()
                val workDir: com.google.devtools.build.lib.vfs.Path? =
                    getMultiplexSandboxedWorkerPath(key, multiplexerId)
                worker =
                    SandboxedWorkerProxy(
                        key,
                        workerId,
                        workerMultiplexer.getLogFile(),
                        workerMultiplexer,
                        workDir,
                        treeDeleter
                    )
                workerMultiplexer.setWorkDir(workDir)
            } else {
                val workDir: com.google.devtools.build.lib.vfs.Path? = getSandboxedWorkerPath(key, workerId)
                worker =
                    SandboxedWorker(
                        key,
                        workerId,
                        workDir,
                        logFile,
                        workerOptions,
                        hardenedSandboxOptions,
                        treeDeleter,
                        key.useInMemoryTracking(),
                        cgroupFactory
                    )
            }
        } else if (key.isMultiplex()) {
            val workerMultiplexer: WorkerMultiplexer = WorkerMultiplexerManager.getInstance(key, logFile)
            worker =
                WorkerProxy(
                    key, workerId, workerMultiplexer.getLogFile(), workerMultiplexer, key.getExecRoot()
                )
        } else {
            worker =
                SingleplexWorker(
                    key, workerId, key.getExecRoot(), logFile, workerOptions, cgroupFactory
                )
        }

        val msg: String? =
            java.lang.String.format(
                "Created new %s %s %s %s (id %d, key hash %d), logging to %s",
                if (key.isSandboxed()) "sandboxed" else "non-sandboxed",
                if (key.isMultiplex()) "multiplex" else "singleplex",
                key.getMnemonic(),
                workTypeName,
                workerId,
                key.hashCode(),
                worker.getLogFile()
            )
        WorkerLoggingHelper.logMessage(
            reporter,
            com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.INFO,
            msg
        )
        return worker
    }

    fun getSandboxedWorkerPath(key: WorkerKey, workerId: Int): com.google.devtools.build.lib.vfs.Path? {
        val workspaceName: String? = key.getExecRoot().getBaseName()
        return workerBaseDir
            .getRelative(key.getWorkerTypeName() + "-" + workerId + "-" + key.getMnemonic())
            .getRelative(workspaceName)
    }

    fun getMultiplexSandboxedWorkerPath(key: WorkerKey, multiplexerId: Int): com.google.devtools.build.lib.vfs.Path? {
        val workspaceName: String? = key.getExecRoot().getBaseName()
        return workerBaseDir
            .getRelative(
                key.getMnemonic() + "-" + key.getWorkerTypeName() + "-" + multiplexerId + "-workdir"
            )
            .getRelative(workspaceName)
    }

    fun destroyWorker(key: WorkerKey, worker: com.google.devtools.build.lib.worker.Worker) {
        val workerId: Int = worker.getWorkerId()
        var workerFailureCode: String? = ""
        val code: java.util.Optional<Code?> = worker.getStatus().getWorkerCode()
        if (code.isPresent()) {
            workerFailureCode = java.lang.String.format("(code: %s)", code.get())
        }
        val msg: String? =
            java.lang.String.format(
                "Destroying %s %s (id %d, key hash %d) with cause: %s %s\n",
                key.getMnemonic(),
                key.getWorkerTypeName(),
                workerId,
                key.hashCode(),
                worker.getStatus().get(),
                workerFailureCode
            )
        WorkerLoggingHelper.logMessage(
            reporter,
            com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.INFO,
            msg
        )
        worker.destroy()
    }

    fun validateWorker(key: WorkerKey, worker: com.google.devtools.build.lib.worker.Worker): Boolean {
        // Status is invalid if the status is either killed or pending killed.
        if (!worker.getStatus().isValid()) {
            return false
        }
        val exitValue: java.util.Optional<Int?> = worker.getExitValue()
        if (exitValue.isPresent()) {
            // At this point, the worker factory has no idea what caused the process to be killed - so we
            // set the status to be KILLED_UNKNOWN.
            worker.getStatus()
                .maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_UNKNOWN)
            if (worker.diedUnexpectedly()) {
                val msg: String? =
                    java.lang.String.format(
                        "%s %s (id %d) has unexpectedly died with exit code %d.",
                        key.getMnemonic(), key.getWorkerTypeName(), worker.getWorkerId(), exitValue.get()
                    )
                val errorMessage: ErrorMessage =
                    ErrorMessage.Companion.builder()
                        .message(msg)
                        .logFile(worker.getLogFile())
                        .logSizeLimit(4096)
                        .build()
                WorkerLoggingHelper.logMessage(
                    reporter,
                    com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.WARNING,
                    errorMessage.toString()
                )
            }
            return false
        }
        val filesChanged = key.getWorkerFilesCombinedHash() != worker.getWorkerFilesCombinedHash()

        if (filesChanged) {
            val msg: java.lang.StringBuilder = java.lang.StringBuilder()
            msg.append(
                java.lang.String.format(
                    "%s %s (id %d) can no longer be used, because its files have changed on disk:",
                    key.getMnemonic(), key.getWorkerTypeName(), worker.getWorkerId()
                )
            )
            val files: TreeSet<PathFragment> = TreeSet<PathFragment>()
            files.addAll(key.getWorkerFilesWithDigests().keySet())
            files.addAll(worker.getWorkerFilesWithDigests().keySet())
            for (file in files) {
                val oldDigest: ByteArray? = worker.getWorkerFilesWithDigests().get(file)
                val newDigest: ByteArray? = key.getWorkerFilesWithDigests().get(file)
                if (!java.util.Arrays.equals(oldDigest, newDigest)) {
                    msg.append("\n")
                        .append(file.getPathString())
                        .append(": ")
                        .append(hexStringForDebugging(oldDigest))
                        .append(" -> ")
                        .append(hexStringForDebugging(newDigest))
                }
            }

            WorkerLoggingHelper.logMessage(
                reporter,
                com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.WARNING,
                msg.toString()
            )
        }

        return !filesChanged
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is WorkerFactory) {
            return false
        }
        return workerBaseDir == o.workerBaseDir
                && workerOptions.getUseCgroupsOnLinux() == o.workerOptions.getUseCgroupsOnLinux() && this.hardenedSandboxOptions == o.hardenedSandboxOptions
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(workerBaseDir, hardenedSandboxOptions)
    }

    /** This class simultaneously sends messages to a logger and an event reporter.  */
    private object WorkerLoggingHelper {
        fun logMessage(reporter: com.google.devtools.build.lib.events.Reporter?, level: LogLevel, message: String?) {
            when (level) {
                com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.INFO -> {
                    logger.atInfo().log("%s", message)
                    if (reporter != null) {
                        reporter.handle(com.google.devtools.build.lib.events.Event.info(message))
                    }
                    return
                }

                com.google.devtools.build.lib.worker.WorkerFactory.WorkerLoggingHelper.LogLevel.WARNING -> {
                    logger.atWarning().log("%s", message)
                    if (reporter != null) {
                        reporter.handle(com.google.devtools.build.lib.events.Event.warn(message))
                    }
                    return
                }
            }
            throw java.lang.IllegalStateException(java.lang.String.format("illegal logging level %s", level))
        }

        enum class LogLevel(level: String) {
            INFO("INFO"),
            WARNING("WARNING");

            private val level: String?

            init {
                this.level = level
            }

            override fun toString(): String {
                return level!!
            }
        }
    }

    companion object {
        // It's fine to use an AtomicInteger here (which is 32-bit), because it is only incremented when
        // spawning a new worker, thus even under worst-case circumstances and buggy workers quitting
        // after each action, this should never overflow.
        // This starts at 1 to avoid hiding latent problems of multiplex workers not returning a
        // request_id (which is indistinguishable from 0 in proto3).
        private val pidCounter: AtomicInteger = AtomicInteger(1)

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private fun hexStringForDebugging(bytes: ByteArray?): String {
            return if (bytes != null) com.google.common.io.BaseEncoding.base16().encode(bytes)
                .toLowerCase(Locale.ROOT) else "<none>"
        }
    }
}
