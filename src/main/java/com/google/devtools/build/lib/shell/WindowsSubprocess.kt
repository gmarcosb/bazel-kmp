// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.shell

import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent.getResult
import com.google.devtools.build.lib.clock.Clock.currentTimeMillis
import com.google.devtools.build.lib.clock.Clock.nanoTime
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent.getTestTargets
import com.google.devtools.build.lib.bugreport.BugReport.sendBugReport
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.clock.Clock.now
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import com.google.devtools.build.lib.runtime.UiStateTracker.StrategyIds
import com.google.devtools.build.lib.runtime.UiStateTracker.ActionPhase
import java.util.LinkedHashMap
import com.google.devtools.build.lib.runtime.UiStateTracker.ActionState.ProgressState
import com.google.devtools.build.lib.runtime.UiStateTracker
import com.google.devtools.build.lib.runtime.UiStateTracker.ActionState
import com.google.devtools.build.lib.runtime.UiStateTracker.DownloadData
import com.google.devtools.build.lib.events.ExtendedEventHandler.FetchProgress
import com.google.devtools.build.lib.skyframe.PackageProgressReceiver
import com.google.devtools.build.lib.skyframe.AnalysisProgressReceiver
import java.util.HashSet
import java.time.Instant
import com.google.devtools.build.lib.skyframe.LoadingPhaseStartedEvent
import com.google.devtools.build.lib.skyframe.ConfigurationPhaseStartedEvent
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionProgressReceiverAvailableEvent
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent
import java.util.function.ToIntFunction
import java.io.IOException
import com.google.devtools.build.lib.util.io.AnsiTerminalWriter
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TestAnalyzedEvent
import com.google.devtools.build.lib.util.io.PositionAwareAnsiTerminalWriter
import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.sandbox.SandboxOptions
import com.google.devtools.build.lib.sandbox.SandboxedSpawn
import com.google.devtools.build.lib.sandbox.SandboxHelpers
import com.google.devtools.build.lib.util.CommandFailureUtils
import com.google.devtools.build.lib.sandbox.AbstractSandboxSpawnRunner
import com.google.devtools.build.lib.util.io.FileOutErr
import com.google.devtools.build.lib.shell.SubprocessBuilder
import com.google.devtools.build.lib.shell.TerminationStatus
import com.google.devtools.build.lib.shell.Subprocess
import java.util.stream.Collectors
import java.nio.file.Paths
import com.google.devtools.build.lib.sandbox.cgroups.Mount
import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.sandbox.Cgroup
import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Cpu
import com.google.devtools.build.lib.sandbox.cgroups.VirtualCgroup
import java.util.concurrent.ConcurrentLinkedQueue
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedMemory
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedCpu
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyMemory
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyCpu
import com.google.devtools.build.lib.sandbox.cgroups.VirtualCgroupFactory
import com.google.devtools.build.lib.sandbox.cgroups.controller.v1.LegacyController
import com.google.devtools.build.lib.sandbox.cgroups.controller.v2.UnifiedController
import com.google.devtools.build.lib.sandbox.CgroupsInfo
import com.google.devtools.build.lib.sandbox.CgroupsInfo.InvalidCgroupsInfo
import com.google.devtools.build.lib.sandbox.CgroupsInfoV1
import com.google.devtools.build.lib.sandbox.CgroupsInfoV2
import com.google.devtools.build.lib.sandbox.DarwinSandboxedSpawnRunner
import com.google.devtools.build.lib.util.StringEncoding
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs
import ProcessWrapper.CommandLineBuilder
import com.google.devtools.build.lib.sandbox.SymlinkedSandboxedSpawn
import java.io.PrintWriter
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.UUID
import com.google.devtools.build.lib.sandbox.DockerCommandLineBuilder
import com.google.devtools.build.lib.unix.ProcessUtilsService
import java.util.Collections
import com.google.devtools.build.lib.sandbox.CopyingSandboxedSpawn
import java.util.concurrent.atomic.AtomicReference
import com.google.devtools.build.lib.sandbox.DockerSandboxedSpawnRunner
import java.io.ByteArrayInputStream
import com.google.devtools.build.lib.remote.options.RemoteOptions
import com.google.devtools.build.lib.sandbox.LinuxSandboxUtil
import com.google.devtools.build.lib.vfs.Root
import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder
import com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace
import com.google.devtools.build.lib.sandbox.HardlinkedSandboxedSpawn
import java.util.TreeSet
import java.util.SortedMap
import java.util.TreeMap
import com.google.devtools.build.lib.vfs.Symlinks
import com.google.devtools.build.lib.vfs.FileStatus
import java.util.HashMap
import com.google.devtools.build.lib.sandbox.LinuxSandboxedSpawnRunner
import com.google.devtools.build.lib.util.OsUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.devtools.build.lib.sandbox.SandboxHelpers.DirectoryCopier
import com.google.devtools.build.lib.vfs.FileAccessException
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxContents
import java.io.UncheckedIOException
import com.google.devtools.build.lib.util.AbruptExitException
import com.google.devtools.build.lib.util.DetailedExitCode
import com.google.devtools.build.lib.sandbox.SandboxModule
import com.google.devtools.build.lib.sandbox.AsynchronousTreeDeleter
import com.google.devtools.build.lib.sandbox.SynchronousTreeDeleter
import com.google.devtools.build.lib.sandbox.SandboxStash
import com.google.devtools.build.lib.sandbox.ProcessWrapperSandboxedSpawnRunner
import com.google.devtools.build.lib.sandbox.ProcessWrapperSandboxedStrategy
import com.google.devtools.build.lib.sandbox.DockerSandboxedStrategy
import com.google.devtools.build.lib.sandbox.LinuxSandboxedStrategy
import com.google.devtools.build.lib.sandbox.DarwinSandboxedStrategy
import com.google.devtools.build.lib.sandbox.WindowsSandboxedSpawnRunner
import com.google.devtools.build.lib.sandbox.WindowsSandboxedStrategy
import com.google.devtools.build.lib.runtime.commands.events.CleanStartingEvent
import com.google.devtools.build.lib.util.Fingerprint
import com.google.devtools.build.lib.sandbox.WindowsSandboxUtil
import com.google.devtools.build.lib.util.OptionsUtils.AbsolutePathFragmentConverter
import com.google.devtools.build.lib.sandbox.SandboxOptions.MountPairConverter
import com.google.devtools.build.lib.sandbox.SandboxOptions.AsyncTreeDeletesConverter
import com.google.devtools.build.lib.util.RamResourceConverter
import com.google.devtools.build.lib.util.ResourceConverter
import java.util.LinkedHashSet
import com.google.devtools.build.lib.sandbox.AbstractContainerizingSandboxedSpawn
import com.google.devtools.build.lib.util.CommandDescriptionForm
import com.google.devtools.build.lib.util.DescribableExecutionUnit
import java.io.FileNotFoundException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import com.google.devtools.build.lib.sandbox.WindowsSandboxUtil.CommandLineBuilder
import com.google.devtools.build.lib.sandbox.WindowsSandboxedSpawn
import com.google.devtools.build.lib.shell.SubprocessBuilder.StreamAction
import com.google.devtools.build.lib.server.CommandManager.RunningCommand
import com.google.devtools.build.lib.server.IdleTaskManager
import java.util.concurrent.atomic.AtomicLong
import com.google.devtools.build.lib.server.CommandManager
import com.google.devtools.build.lib.server.GrpcCommandServer
import com.google.devtools.build.lib.server.PidFileWatcher
import com.google.devtools.build.lib.server.GrpcCommandServer.Responder
import com.google.devtools.build.lib.util.io.CommandExtensionReporter
import com.google.protobuf.ByteString
import com.google.devtools.build.lib.server.CommandServer.StreamType
import com.google.devtools.build.lib.server.CommandServer.RpcOutputStream
import com.google.devtools.build.lib.bugreport.BugReport
import com.google.devtools.build.lib.server.CommandServer
import com.google.devtools.build.lib.server.ServerWatcherRunnable
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.Inet6Address
import com.google.protobuf.InvalidProtocolBufferException
import com.google.devtools.build.lib.util.ExitCode
import com.google.devtools.build.lib.util.io.OutErr
import com.google.devtools.common.options.InvocationPolicyParser
import com.google.devtools.build.lib.server.CommandServer.RpcCommandExtensionReporter
import com.google.devtools.build.lib.util.InterruptedFailureDetails
import java.security.MessageDigest
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils
import com.google.devtools.build.lib.concurrent.PooledInterner
import com.google.devtools.build.lib.server.GcAndInternerShrinkingIdleTask
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import io.grpc.StatusRuntimeException
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.epoll.Epoll
import com.google.devtools.build.lib.server.GrpcCommandServerImpl.BlockingStreamObserver
import com.google.devtools.build.lib.runtime.BlazeService
import com.google.devtools.build.lib.server.GrpcCommandServerService
import com.google.devtools.build.lib.server.GrpcCommandServerImpl
import java.util.concurrent.ScheduledThreadPoolExecutor
import com.google.devtools.build.lib.server.IdleTaskManager.IdleTaskWrapper
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import com.google.devtools.build.lib.server.InstallBaseGarbageCollector
import com.google.devtools.build.lib.util.FileSystemLock
import com.google.devtools.build.lib.util.FileSystemLock.LockMode
import com.google.devtools.build.lib.util.FileSystemLock.LockAlreadyHeldException
import java.util.function.IntPredicate
import com.google.devtools.build.lib.server.InstallBaseGarbageCollectorIdleTask
import java.util.concurrent.ScheduledExecutorService
import com.google.devtools.build.lib.server.ServerWatcherRunnable.LowMemoryChecker
import com.google.devtools.build.lib.server.ServerWatcherRunnable.ProcMeminfoLowMemoryChecker
import com.google.devtools.build.lib.server.ServerWatcherRunnable.ProcMeminfoLowMemoryChecker.ProcMeminfoParserSupplier
import com.google.devtools.build.lib.server.ServerWatcherRunnable.MemoryPressureLowMemoryChecker
import com.google.devtools.build.lib.unix.ProcMeminfoParser
import com.google.devtools.build.lib.server.signal.InterruptSignalHandler
import com.google.devtools.build.lib.shell.CommandResult
import com.google.devtools.build.lib.shell.AbnormalTerminationException
import com.google.common.flogger.LazyArgs
import com.google.common.flogger.LazyArg
import com.google.devtools.build.lib.shell.LogUtil
import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.shell.Consumers.AccumulatorThreadFactory
import com.google.devtools.build.lib.shell.Consumers.OutErrConsumers
import com.google.devtools.build.lib.shell.Consumers.AccumulatingConsumer
import com.google.devtools.build.lib.shell.Consumers.StreamingConsumer
import com.google.devtools.build.lib.shell.Consumers.OutputConsumer
import com.google.devtools.build.lib.shell.Consumers.FutureConsumption
import com.google.devtools.build.lib.shell.Consumers.ClosingSink
import com.google.devtools.build.lib.shell.InputStreamSink
import com.google.devtools.build.lib.shell.ExecutionStatistics
import java.io.BufferedInputStream
import Protos.ExecutionStatistics
import com.google.devtools.build.lib.shell.FutureCommandResult
import com.google.devtools.build.lib.shell.BadExitStatusException
import com.google.devtools.build.lib.shell.InputStreamSink.NullSink
import com.google.devtools.build.lib.shell.InputStreamSink.CopySink
import com.google.devtools.build.lib.shell.SubprocessFactory
import java.util.concurrent.locks.ReentrantLock
import com.google.devtools.build.lib.shell.JavaSubprocessFactory.JavaSubprocess
import com.google.devtools.build.lib.shell.JavaSubprocessFactory
import com.google.devtools.build.lib.shell.ExecFailedException
import com.google.devtools.build.lib.shell.ShellUtils
import com.google.devtools.build.lib.shell.ShellUtils.TokenizationException
import com.google.devtools.build.lib.windows.WindowsProcesses
import com.google.devtools.build.lib.shell.WindowsSubprocess.ProcessOutputStream
import com.google.devtools.build.lib.shell.WindowsSubprocess.ProcessInputStream
import com.google.devtools.build.lib.shell.WindowsSubprocess.NativeState
import com.google.devtools.build.lib.shell.WindowsSubprocess.WaitResult
import com.google.devtools.build.lib.util.BazelCleaner
import com.google.devtools.build.lib.shell.WindowsSubprocess
import com.google.devtools.build.lib.shell.WindowsSubprocessFactory
import com.google.devtools.build.skyframe.CyclesReporter.SingleCycleReporter
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.CycleInfo
import com.google.devtools.build.lib.skyframe.AbstractLabelCycleReporter
import com.google.devtools.build.lib.skyframe.ActionArtifactCycleReporter
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.TopLevelActionLookupKeyWrapper
import com.google.devtools.build.lib.skyframe.TestCompletionValue.TestCompletionKey
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.lib.skyframe.AspectCompletionValue.AspectCompletionKey
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.lib.skyframe.PrecomputedValue
import com.google.devtools.build.lib.skyframe.EnvironmentVariableValue
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.lib.skyframe.ActionEnvironmentFunction
import com.google.devtools.build.skyframe.SkyframeLookupResult
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog.InactivityMonitor
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog.InactivityReporter
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog.Sleep
import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog.WaitTime
import com.google.devtools.build.lib.skyframe.ActionExecutionState.ActionStepOrResult
import com.google.devtools.build.lib.skyframe.ActionExecutionState.SharedActionCallback
import com.google.devtools.build.lib.skyframe.ActionExecutionValue
import com.google.devtools.build.lib.skyframe.ActionExecutionState
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.ActionTransformException
import java.util.concurrent.ConcurrentMap
import com.google.devtools.build.lib.skyframe.ActionExecutionState.Exceptional
import com.google.devtools.build.lib.skyframe.TreeArtifactValue
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.ArchivedRepresentation
import com.google.devtools.build.lib.util.HashCodes
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.SingleOutputFile
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.MultiOutputFile
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.WithRichData
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.ModuleDiscovering
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.SingleTree
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.MultiTree
import com.google.devtools.build.lib.skyframe.ActionOutputMetadataStore
import ExtendedEventHandler.Postable
import com.google.devtools.build.lib.skyframe.ActionInputCollectedEvent
import com.google.devtools.build.lib.skyframe.MetadataConsumerForMetrics
import com.google.devtools.build.lib.skyframe.ActionInputMapHelper
import com.google.devtools.build.lib.skyframe.ActionInputMetadataProvider
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.skyframe.ActionLookupConflictFindingValue
import com.google.devtools.build.lib.vfs.OutputPermissions
import com.google.devtools.build.lib.vfs.XattrProvider
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor
import com.google.devtools.build.lib.skyframe.TreeArtifactValue.TreeArtifactVisitor
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter
import com.google.devtools.build.lib.vfs.FileStatusWithDigest
import com.google.devtools.build.lib.skyframe.ActionOutputMetadataStore.FileArtifactStatAndValue
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionFunction.ActionTemplateExpansionFunctionException
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionFunction
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue
import com.google.devtools.build.skyframe.SkyFunctionException
import com.google.devtools.build.skyframe.SkyFunctionException.Transience
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionFunction.MapBasedImmutableActionGraph

/** A Windows subprocess backed by a native object.  */
class WindowsSubprocess internal constructor(
    nativeProcess: Long,
// For debugging purposes.
    private val commandLine: String?,
    stdoutRedirected: Boolean,
    stderrRedirected: Boolean,
    timeoutMillis: Long
) : Subprocess {
    private enum class WaitResult {
        SUCCESS,
        TIMEOUT
    }

    /** Output stream for writing to the stdin of a Windows process.  */
    private inner class ProcessOutputStream : java.io.OutputStream() {
        @Throws(IOException::class)
        override fun write(b: Int) {
            val buf = byteArrayOf(b.toByte())
            write(buf, 0, 1)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            writeStdin(b, off, len)
        }

        override fun close() {
            closeStdin()
        }
    }

    /**
     * Input stream for reading the stdout or stderr of a Windows process.
     * 
     * 
     * This class is non-static for debugging purposes.
     */
    private class ProcessInputStream(private var nativeStream: Long) : java.io.InputStream() {
        @Throws(IOException::class)
        override fun available(): Int {
            check(nativeStream != WindowsProcesses.INVALID) { "Stream already closed" }

            val result: Int = WindowsProcesses.streamBytesAvailable(nativeStream)
            if (result == -1) {
                throw IOException(WindowsProcesses.streamGetLastError(nativeStream))
            }

            return result
        }

        @Throws(IOException::class)
        override fun read(): Int {
            val buf = ByteArray(1)
            if (read(buf, 0, 1) != 1) {
                return -1
            } else {
                return buf[0].toInt() and 0xff
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            check(nativeStream != WindowsProcesses.INVALID)

            val result: Int = WindowsProcesses.readStream(nativeStream, b, off, len)

            if (result == 0) {
                return -1 // EOF
            }
            if (result == -1) {
                throw IOException(WindowsProcesses.streamGetLastError(nativeStream))
            }

            return result
        }

        @kotlin.jvm.Synchronized
        override fun close() {
            if (nativeStream != WindowsProcesses.INVALID) {
                WindowsProcesses.closeStream(nativeStream)
                nativeStream = WindowsProcesses.INVALID
            }
        }
    }

    private class NativeState(
        @field:kotlin.concurrent.Volatile private var nativeProcess: Long,
        private val stdinStream: ProcessOutputStream?,
        private val stdoutStream: ProcessInputStream?,
        private val stderrStream: ProcessInputStream?
    ) : java.lang.Runnable {
        override fun run() {
            if (nativeProcess == WindowsProcesses.INVALID) {
                return
            }
            // The streams are null when redirected from/to files.
            if (stdinStream != null) {
                stdinStream.close()
            }
            if (stdoutStream != null) {
                stdoutStream.close()
            }
            if (stderrStream != null) {
                stderrStream.close()
            }
            val process = nativeProcess
            nativeProcess = WindowsProcesses.INVALID
            WindowsProcesses.deleteProcess(process)
        }
    }

    private val nativeState: NativeState
    private val cleanable: java.lang.ref.Cleaner.Cleanable
    private val processFuture: java.util.concurrent.Future<WaitResult?>
    private val timeoutMillis: Long
    private var timedout = false

    init {
        nativeState =
            NativeState(
                nativeProcess,
                ProcessOutputStream(),
                if (stdoutRedirected)
                    null
                else
                    ProcessInputStream(WindowsProcesses.getStdout(nativeProcess)),
                if (stderrRedirected)
                    null
                else
                    ProcessInputStream(WindowsProcesses.getStderr(nativeProcess))
            )
        cleanable = BazelCleaner.CLEANER.register(this, nativeState)
        // As per the spec of Command, we should only apply timeouts that are > 0.
        this.timeoutMillis = if (timeoutMillis <= 0) -1 else timeoutMillis
        // Every Windows process we start consumes a thread here. This is suboptimal, but seems to be
        // the sanest way to reconcile WaitForMultipleObjects() and Java-style interruption.
        processFuture = WAITER_POOL.submit<WaitResult?>(java.util.concurrent.Callable { this.waiterThreadFunc() })
    }

    // Waits for the process to finish.
    private fun waiterThreadFunc(): WaitResult {
        when (WindowsProcesses.waitFor(nativeState.nativeProcess, timeoutMillis)) {
            0 ->         // Excellent, process finished in time.
                return WaitResult.SUCCESS

            1 ->         // Timeout. We don't need to call `terminate` here, because waitFor
                // automatically terminates the process in case of a timeout.
                return WaitResult.TIMEOUT

            else -> {
                // Error. There isn't a lot we can do -- the process is still alive but
                // WaitForSingleObject() failed for some odd reason. This should
                // basically never happen, but if it does... let's get a stack trace.
                val errorMessage: String = WindowsProcesses.processGetLastError(nativeState.nativeProcess)
                throw java.lang.IllegalStateException(
                    ("Waiting for process "
                            + WindowsProcesses.getProcessPid(nativeState.nativeProcess)
                            + " failed: "
                            + errorMessage)
                )
            }
        }
    }

    @kotlin.jvm.Synchronized
    override fun destroy(): Boolean {
        checkLiveness()
        return WindowsProcesses.terminate(nativeState.nativeProcess)
    }

    @kotlin.jvm.Synchronized
    override fun exitValue(): Int {
        checkLiveness()

        val result: Int = WindowsProcesses.getExitCode(nativeState.nativeProcess)
        val error: String = WindowsProcesses.processGetLastError(nativeState.nativeProcess)
        check(error.isEmpty()) { error }

        return result
    }

    override fun finished(): Boolean {
        return processFuture.isDone()
    }

    val isAlive: Boolean
        get() = !processFuture.isDone()

    override fun timedout(): Boolean {
        return timedout
    }

    @Throws(java.lang.InterruptedException::class)
    override fun waitFor() {
        try {
            timedout = processFuture.get() == WaitResult.TIMEOUT
        } catch (e: ExecutionException) {
            com.google.common.base.Throwables.throwIfUnchecked(e.cause)
            // This should never happen, because waiterThreadFunc does not throw any
            // checked exceptions.
            throw java.lang.IllegalStateException("Unexpected exception", e)
        }
    }

    @kotlin.jvm.Synchronized
    override fun close() {
        cleanable.clean()
    }

    val outputStream: java.io.OutputStream?
        get() = nativeState.stdinStream

    val inputStream: java.io.InputStream?
        get() = nativeState.stdoutStream

    val errorStream: java.io.InputStream?
        get() = nativeState.stderrStream

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    private fun writeStdin(b: ByteArray?, off: Int, len: Int) {
        checkLiveness()

        var remaining = len
        var currentOffset = off
        while (remaining != 0) {
            val written: Int =
                WindowsProcesses.writeStdin(nativeState.nativeProcess, b, currentOffset, remaining)
            // I think the Windows API never returns 0 in dwNumberOfBytesWritten
            // Verify.verify(written != 0);
            if (written == -1) {
                throw IOException(WindowsProcesses.processGetLastError(nativeState.nativeProcess))
            }

            remaining -= written
            currentOffset += written
        }
    }

    @kotlin.jvm.Synchronized
    private fun closeStdin() {
        checkLiveness()
        WindowsProcesses.closeStdin(nativeState.nativeProcess)
    }

    private fun checkLiveness() {
        check(nativeState.nativeProcess != WindowsProcesses.INVALID)
    }

    override fun toString(): String {
        return String.format("%s:[%s]", super.toString(), commandLine)
    }

    val processId: Long
        get() = nativeState.nativeProcess

    companion object {
        private val THREAD_SEQUENCE_NUMBER: AtomicInteger = AtomicInteger(1)
        private val WAITER_POOL: ExecutorService = Executors.newCachedThreadPool(
            object : ThreadFactory {
                override fun newThread(runnable: java.lang.Runnable?): java.lang.Thread {
                    val thread: java.lang.Thread =
                        java.lang.Thread(
                            null,
                            runnable,
                            "Windows-Process-Waiter-Thread-" + THREAD_SEQUENCE_NUMBER.getAndIncrement()
                        )
                    thread.setDaemon(true)
                    return thread
                }
            })
    }
}
