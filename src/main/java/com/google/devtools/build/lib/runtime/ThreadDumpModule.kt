// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.actions.ActionExecutionInactivityEvent

/** A [BlazeModule] that dumps the state of all threads periodically.  */
class ThreadDumpModule : BlazeModule() {
    private val threadDumpTaskRef: AtomicReference<ThreadDumpTask?> = AtomicReference<ThreadDumpTask?>()

    @Throws(AbruptExitException::class)
    public override fun beforeCommand(env: CommandEnvironment) {
        val commandOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            env.getOptions().getOptions(CommonCommandOptions::class.java)
        if (commandOptions == null || !commandOptions.getEnableThreadDump()) {
            return
        }

        if (commandOptions.getThreadDumpInterval().isZero()
            && commandOptions.getThreadDumpActionExecutionInactivityDuration().isZero()
        ) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("--experimental_enable_thread_dump is set, but"
                                + " --experimental_thread_dump_interval and"
                                + " --experimental_thread_dump_action_execution_inactivity_duration are 0. No"
                                + " thread dumps will be written.")
                    )
                )
            return
        }

        val bepOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            env.getOptions().getOptions(BuildEventProtocolOptions::class.java)
        var uploader: BuildEventArtifactUploader? = null
        if (bepOptions != null && bepOptions.streamingLogFileUploads) {
            try {
                uploader = newUploader(env, bepOptions)
            } catch (e: InvalidPackagePathSymlinkException) {
                throw createAbruptExitException("Failed to create uploader", e)
            }
        }

        val outputBaseRelativeDumpDirectory: PathFragment = prepareDumpDirectory(env)
        val threadDumpTask =
            ThreadDumpTask(
                env,
                java.lang.ProcessHandle.current().pid(),
                env.getRuntime().getClock(),
                outputBaseRelativeDumpDirectory,
                commandOptions.getThreadDumpActionExecutionInactivityDuration(),
                commandOptions.getThreadDumpInterval(),
                uploader
            )
        val oldThreadDumpTask: ThreadDumpTask? = threadDumpTaskRef.getAndSet(threadDumpTask)
        com.google.common.base.Preconditions.checkState(oldThreadDumpTask == null)

        env.getEventBus().register(this)
    }

    @Throws(AbruptExitException::class)
    private fun prepareDumpDirectory(env: CommandEnvironment): PathFragment {
        val runtime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = env.getRuntime()
        val serverDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            runtime.getServerDirectory()
        val dumpDirectory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            serverDirectory.getChild("thread_dumps")
        try {
            dumpDirectory.deleteTree()
            dumpDirectory.createDirectoryAndParents()
        } catch (e: IOException) {
            throw createAbruptExitException("Failed to setup thread dump directory", e)
        }
        return dumpDirectory.relativeTo(env.getDirectories().getOutputBase())
    }

    @com.google.common.eventbus.Subscribe
    fun onActionExecutionInactivityEvent(event: ActionExecutionInactivityEvent) {
        val threadDumpTask: ThreadDumpTask? = threadDumpTaskRef.get()
        com.google.common.base.Preconditions.checkNotNull<ThreadDumpTask?>(threadDumpTask)
        threadDumpTask!!.onActionExecutionInactivityEvent(event)
    }

    @com.google.common.eventbus.Subscribe
    fun buildComplete(event: BuildCompleteEvent) {
        shutdown(event.getResult().buildToolLogCollection)
    }

    private fun shutdown(buildToolLogCollection: BuildToolLogCollection?) {
        // We might get concurrent call to shutdown (via afterCommand).
        val threadDumpTask: ThreadDumpTask? = threadDumpTaskRef.getAndSet(null)
        if (threadDumpTask != null) {
            threadDumpTask.shutdown(buildToolLogCollection)
        }
    }

    public override fun afterCommand() {
        // Defensively shut down in case we failed to do so under normal operation.
        shutdown( /* buildToolLogCollection= */null)
    }

    private class ThreadDumpTask(
        env: CommandEnvironment,
        pid: Long,
        clock: com.google.devtools.build.lib.clock.Clock,
        outputBaseRelativeDumpDirectory: PathFragment,
        threadDumpActionExecutionInactivityDuration: java.time.Duration,
        threadDumpInterval: java.time.Duration,
        uploader: BuildEventArtifactUploader?
    ) : java.lang.Runnable {
        private val env: CommandEnvironment
        private val pid: Long
        private val clock: com.google.devtools.build.lib.clock.Clock
        private val outputBaseRelativeDumpDirectory: PathFragment
        private val threadDumpActionExecutionInactivityDuration: java.time.Duration
        private val uploader: BuildEventArtifactUploader?
        private val scheduledExecutor: ScheduledExecutorService

        private val instrumentationOutputs: MutableList<InstrumentationOutput> =
            Collections.synchronizedList<InstrumentationOutput?>(java.util.ArrayList<InstrumentationOutput?>())

        private var lastDumpAt: Instant? = Instant.EPOCH

        init {
            this.env = env
            this.pid = pid
            this.clock = clock
            this.outputBaseRelativeDumpDirectory = outputBaseRelativeDumpDirectory
            this.threadDumpActionExecutionInactivityDuration =
                threadDumpActionExecutionInactivityDuration
            this.uploader = uploader
            this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

            if (!threadDumpInterval.isZero()) {
                val unused: java.util.concurrent.ScheduledFuture<*>? =
                    scheduledExecutor.scheduleAtFixedRate(
                        this, threadDumpInterval.toMillis(), threadDumpInterval.toMillis(), TimeUnit.MILLISECONDS
                    )
            }
        }

        override fun run() {
            val bos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("Dumping threads").use { sc ->
                    com.google.devtools.build.lib.util.ThreadDumper.dumpThreads(bos)
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to dump threads.")
            }

            val formattedTime: String =
                Instant.ofEpochMilli(clock.currentTimeMillis())
                    .atZone(ZoneOffset.UTC)
                    .format(TIME_FORMAT)
            val dumpOutput: InstrumentationOutput =
                createThreadDumpOutput(java.lang.String.format("thread_dump.%d.%s.txt", pid, formattedTime))
            instrumentationOutputs.add(dumpOutput)
            val analyzer: ThreadDumpAnalyzer = ThreadDumpAnalyzer()
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("Analyzing thread dump").use { sc ->
                    dumpOutput.createOutputStream().use { out ->
                        analyzer.analyze(ByteArrayInputStream(bos.toByteArray()), out)
                    }
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Failed to analyze threads.")
            }

            lastDumpAt = clock.now()
        }

        fun shouldDumpForActionExecutionInactivity(event: ActionExecutionInactivityEvent): Boolean {
            if (threadDumpActionExecutionInactivityDuration.isZero()) {
                return false
            }

            val now: Instant? = clock.now()
            if (now.isBefore(
                    event.lastActionCompletedAt().plus(threadDumpActionExecutionInactivityDuration)
                )
            ) {
                return false
            }

            return now.isAfter(lastDumpAt.plus(threadDumpActionExecutionInactivityDuration))
        }

        fun onActionExecutionInactivityEvent(event: ActionExecutionInactivityEvent) {
            if (shouldDumpForActionExecutionInactivity(event)) {
                val unused: java.util.concurrent.ScheduledFuture<*>? =
                    scheduledExecutor.schedule(this, 0, TimeUnit.MILLISECONDS)
            }
        }

        fun shutdown(buildToolLogCollection: BuildToolLogCollection?) {
            scheduledExecutor.shutdownNow()
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("Joining dump thread").use { sc ->
                com.google.common.util.concurrent.Uninterruptibles.awaitTerminationUninterruptibly(scheduledExecutor)
            }
            if (buildToolLogCollection != null) {
                for (output in instrumentationOutputs) {
                    output.publish(buildToolLogCollection)
                }
            }

            if (uploader != null) {
                uploader.release()
            }
        }

        fun createThreadDumpOutput(name: String?): InstrumentationOutput {
            val outputFactory: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                env.getRuntime().getInstrumentationOutputFactory()
            if (uploader != null) {
                return outputFactory.createBuildEventArtifactInstrumentationOutput(name, uploader)
            }
            return outputFactory.createInstrumentationOutput( /* name= */
                name,  /* destination= */
                outputBaseRelativeDumpDirectory.getRelative(name),
                DestinationRelativeTo.OUTPUT_BASE,
                env,
                env.getReporter(),  /* append= */
                null,  /* internal= */
                null,  /* createParent= */
                true
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        @Throws(InvalidPackagePathSymlinkException::class)
        private fun newUploader(
            env: CommandEnvironment, bepOptions: BuildEventProtocolOptions
        ): BuildEventArtifactUploader {
            return env.getRuntime()
                .getBuildEventArtifactUploaderFactoryMap()
                .select(bepOptions.buildEventUploadStrategy)
                .create(env)
        }

        private fun createAbruptExitException(message: String?, cause: Throwable?): AbruptExitException {
            return AbruptExitException(
                DetailedExitCode.of(
                    ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                    FailureDetail.newBuilder().setMessage(message).build()
                ),
                cause
            )
        }
    }
}
