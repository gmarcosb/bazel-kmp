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
package com.google.devtools.build.lib.server

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

/** A class that watches if the PID file changes and shuts down the server immediately if so.  */
class PidFileWatcher @com.google.common.annotations.VisibleForTesting internal constructor(
    pidFile: com.google.devtools.build.lib.vfs.Path?,
    serverPid: Int,
    onPidFileChange: java.lang.Runnable
) {
    private val pidFile: com.google.devtools.build.lib.vfs.Path?
    private val serverPid: Int
    private val onPidFileChange: java.lang.Runnable
    private val shuttingDown: AtomicBoolean

    private var iteration: java.util.concurrent.ScheduledFuture<*>? = null

    private val executorService: ScheduledExecutorService

    constructor(pidFile: com.google.devtools.build.lib.vfs.Path?, serverPid: Int) : this(
        pidFile,
        serverPid,
        java.lang.Runnable { java.lang.Runtime.getRuntime().halt(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode()) })

    /** Test-only constructor allowing a more gentle [.onPidFileChange] callback.  */
    init {
        this.pidFile = pidFile
        this.serverPid = serverPid
        this.onPidFileChange = onPidFileChange
        this.shuttingDown = AtomicBoolean(false)
        this.executorService =
            Executors.newSingleThreadScheduledExecutor(
                com.google.common.util.concurrent.ThreadFactoryBuilder()
                    .setNameFormat("pid-file-watcher-%d")
                    .setDaemon(true)
                    .build()
            )
    }

    /** Starts the watching. Can only be called once per instance.  */
    fun start() {
        com.google.common.base.Preconditions.checkState(iteration == null) // Can only be called once
        iteration =
            executorService.scheduleWithFixedDelay(java.lang.Runnable { this.iteration() }, 3, 3, TimeUnit.SECONDS)
    }

    /**
     * Signals a shutdown is in progress, thus PID file changes can be expected and should not trigger
     * an aggressive shutdown.
     */
    fun signalShutdown() {
        shuttingDown.set(true)
    }

    /**
     * Stops the watching. Once this method returns, no more activity will happen.
     * 
     * 
     * This should invoked before the PID file is deleted by the Blaze server on an orderly
     * shutdown.
     */
    fun endWatch() {
        executorService.shutdownNow()
        com.google.common.util.concurrent.Uninterruptibles.awaitTerminationUninterruptibly(executorService)
    }

    private fun iteration() {
        if (runPidFileChecks()) {
            return
        }

        iteration.cancel(false)
    }

    /**
     * Runs one iteration of PID file checks. Returns whether or not the main loop should continue, or
     * crashes the program via [.onPidFileChange] callback.
     */
    @com.google.common.annotations.VisibleForTesting
    fun runPidFileChecks(): Boolean {
        if (pidFileValid(pidFile, serverPid)) {
            return true
        }

        if (shuttingDown.get()) {
            logger.atWarning().log("PID file deleted or overwritten but shutdown is already in progress")
            return false
        }

        // Someone overwrote the PID file. Maybe it's another server, so shut down as quickly
        // as possible without even running the shutdown hooks (that would delete it)
        logger.atSevere().log("PID file deleted or overwritten, exiting as quickly as possible")
        onPidFileChange.run()
        throw java.lang.IllegalStateException("Should have crashed.")
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun pidFileValid(pidFile: com.google.devtools.build.lib.vfs.Path?, expectedPid: Int): Boolean {
            val pidFileContents: String?
            try {
                pidFileContents = String(com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1(pidFile))
            } catch (e: IOException) {
                logger.atInfo().log("Cannot read PID file: %s", e.message)
                return false
            }

            try {
                return pidFileContents.toInt() == expectedPid
            } catch (e: java.lang.NumberFormatException) {
                logger.atWarning().withCause(e).log("Pid was invalid: %s", pidFileContents)
                return false
            }
        }
    }
}
