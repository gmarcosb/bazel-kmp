// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox

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

/** This class manages cgroups directories for memory-limiting sandboxed processes.  */
abstract class CgroupsInfo(@kotlin.jvm.JvmField var type: Type?, @kotlin.jvm.JvmField var version: Version?, cgroupDir: java.io.File?) : Cgroup {
    /**
     * Creates a cgroups directory for Blaze to place spawns in.
     * 
     * 
     * This cgroups directory is created at most once per Blaze instance.
     * 
     * @param procSelfCgroupPath path to the `/proc/self/cgroup` file
     * @return A CgroupsInfo object representing the created cgroup that Blaze can use for
     * sub-processes (the Blaze process itself is not moved into this directory). If unable to
     * create, returns an [InvalidCgroupsInfo] containing the exception.
     */
    abstract fun createBlazeSpawnsCgroup(procSelfCgroupPath: String?): CgroupsInfo?

    /** The version of Cgroups that is currently being used.  */
    enum class Version {
        V1,
        V2,
    }

    /**
     * The types of cgroups relevant to Blaze:
     * 
     * 
     *  * ROOT: corresponds to the root cgroup where * the hierarchy is mounted at; one of
     * "/dev/cgroup/{controller}" or "/sys/fs/cgroup".
     *  * BLAZE_SPAWNS: corresponds the overarching cgroup that contains children [       ] cgroups.
     *  * SPAWN: corresponds to the cgroup for a single spawn - this could be a locally executed
     * action or a worker process.
     * 
     */
    enum class Type {
        ROOT,
        BLAZE_SPAWNS,
        SPAWN,
    }

    /**
     * This is the directory where the cgroup is in, any related files pertaining to limits / resource
     * usage or child cgroups (nested directories) are found here.
     */
    protected val cgroupDir: java.io.File?

    init {
        this.cgroupDir = cgroupDir
        // Valid.
        if (exists()) {
            logger.atInfo().log(
                "Successfully found / created %s (%s) cgroup at %s", version, type, cgroupDir.getPath()
            )
        }
    }

    /** Returns whether the cgroup at `cgroupDir` exists.  */
    override fun exists(): Boolean {
        return cgroupDir != null && cgroupDir.exists() && cgroupDir.isDirectory()
    }

    /** Returns whether Blaze can write to the current cgroup at `cgroupDir`.  */
    open fun canWrite(): Boolean {
        return exists() && cgroupDir.canWrite()
    }

    /** A cgroups directory for this Blaze instance to put sandboxes in.  */
    fun getCgroupDir(): java.io.File? {
        return cgroupDir
    }

    override fun paths(): com.google.common.collect.ImmutableSet<java.nio.file.Path?> {
        return com.google.common.collect.ImmutableSet.of<java.nio.file.Path?>(getCgroupDir().toPath())
    }

    val memoryUsageInKb: Int
        get() = 0

    fun getMemoryUsageInKbFromFile(filename: String): Int {
        try {
            val `val`: String = com.google.common.io.Files.readLines(
                java.io.File(cgroupDir, filename),
                java.nio.charset.StandardCharsets.UTF_8
            ).get(0)
            return (`val`.toLong() / 1024).toInt()
        } catch (e: IOException) {
            return 0
        }
    }

    @Throws(IOException::class)
    override fun addProcess(pid: Long) {
        com.google.common.io.Files.asCharSink(
            java.io.File(cgroupDir, "cgroup.procs"),
            java.nio.charset.StandardCharsets.UTF_8
        ).write(pid.toString())
    }

    override fun destroy() {
        getCgroupDir().delete()
    }

    /**
     * Creates a cgroups directory for individual spawns (local / workers).
     * 
     * 
     * Has to be called from a [Type.BLAZE_SPAWNS] cgroup.
     * 
     * @param dirName the directory name of the spawn's cgroup.
     * @param memoryLimitMb memory limit in Mb to set on the cgroup. If 0, no limit is set.
     * @return an instance of the spawn's cgroup; if unable to create, returns an [     ] containing the exception.
     */
    abstract fun createIndividualSpawnCgroup(dirName: String?, memoryLimitMb: Int): CgroupsInfo?

    /**
     * Represents an invalid cgroup so that we can distinguish between whether a cgroup was not meant
     * to be created (null) or if it was attempted but failed.
     */
    class InvalidCgroupsInfo : CgroupsInfo {
        private val exception: java.lang.Exception?

        constructor(type: Type?, version: Version?, errorMessage: String?) : super(type, version, null) {
            this.exception = java.lang.IllegalStateException(errorMessage)
            logger.atInfo().withCause(exception).log("Unable to create cgroup.")
        }

        constructor(type: Type?, version: Version?, exception: java.lang.Exception?) : super(type, version, null) {
            logger.atInfo().withCause(exception).log("Unable to create cgroup.")
            this.exception = exception
        }

        override fun exists(): Boolean {
            return false
        }

        override fun canWrite(): Boolean {
            return false
        }

        fun getException(): java.lang.Exception? {
            return exception
        }

        override fun createBlazeSpawnsCgroup(procSelfCgroupPath: String?): CgroupsInfo {
            return InvalidCgroupsInfo(
                com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.BLAZE_SPAWNS,
                this.version,
                "Unable to create BLAZE_SPAWNS cgroup from an invalid cgroup."
            )
        }

        override fun createIndividualSpawnCgroup(dirName: String?, memoryLimitMb: Int): CgroupsInfo {
            return InvalidCgroupsInfo(
                com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.SPAWN,
                this.version,
                "Unable to create SPAWN cgroup from an invalid cgroup."
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * A regexp that matches cgroups entries in `/proc/mounts`.
         * 
         * 
         * Group 1 is empty (cgroups v1) or '2' (cgroups v2) Group 2 is the mount point. Group 3 is the
         * options, which for v1 includes which hierarchies are mounted here.
         */
        private val CGROUPS_MOUNT_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("^cgroup(|2)\\s+(\\S*)\\s+cgroup2?\\s+(\\S*).*")

        private const val PROC_SELF_MOUNTS_PATH = "/proc/self/mounts"
        private const val PROC_SELF_CGROUP_PATH = "/proc/self/cgroup"

        private val rootCgroup = getRootCgroup(java.io.File(PROC_SELF_MOUNTS_PATH))

        private val blazeSpawnsCgroupSupplier: java.util.function.Supplier<CgroupsInfo?> =
            com.google.common.base.Suppliers.memoize<CgroupsInfo?>(com.google.common.base.Supplier { createBlazeSpawnsCgroup() })

        @kotlin.jvm.JvmStatic
        val isSupported: Boolean
            /** Returns whether the local machine supports cgroups.  */
            get() = com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX && blazeSpawnsCgroup!!.canWrite()

        /**
         * Returns an instance of the root cgroup of the hierarchy, [InvalidCgroupsInfo] if invalid.
         * 
         * 
         * For v1, we only care about the memory hierarchy.
         * 
         * @param procMountsFile the /proc/self/mounts file.
         */
        @com.google.common.annotations.VisibleForTesting
        fun getRootCgroup(procMountsFile: java.io.File): CgroupsInfo {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
                return InvalidCgroupsInfo(
                    com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.ROOT,  /* version= */
                    null,
                    "Croups is not supported on non-linux environments."
                )
            }

            val procMountsContents: MutableList<String>?
            try {
                procMountsContents =
                    com.google.common.io.Files.readLines(procMountsFile, java.nio.charset.StandardCharsets.UTF_8)
            } catch (e: IOException) {
                return InvalidCgroupsInfo(
                    com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.ROOT,  /* version= */
                    null,
                    e
                )
            }
            var v1RootDir: java.io.File? = null
            var v2RootDir: java.io.File? = null
            for (s in procMountsContents!!) {
                val m: java.util.regex.Matcher = CGROUPS_MOUNT_PATTERN.matcher(s)
                if (m.matches()) {
                    if (m.group(1).isEmpty()) {
                        // v1
                        if (m.group(3).contains("memory")) {
                            // For now, we only care about the memory cgroup
                            v1RootDir = java.io.File(m.group(2))
                        }
                    } else {
                        v2RootDir = java.io.File(m.group(2))
                    }
                }
            }
            // If we found the memory controller in v1, we use that, just in case we have a hybrid system
            // where some controllers are v1 and some are v2. It would be harder to detect if v2 has the
            // memory controller
            if (v1RootDir != null) {
                return CgroupsInfoV1(com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.ROOT, v1RootDir)
            }
            if (v2RootDir != null) {
                return CgroupsInfoV2(com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.ROOT, v2RootDir)
            }
            return InvalidCgroupsInfo(
                com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.ROOT,  /* version= */
                null,
                String.format(
                    "No cgroups mounted in %s: %s", procMountsFile.getPath(), procMountsContents
                )
            )
        }

        @kotlin.jvm.JvmStatic
        val blazeSpawnsCgroup: CgroupsInfo?
            /**
             * Returns the singleton [Type.BLAZE_SPAWNS] cgroup created under the root cgroup, [ ] if invalid.
             */
            get() = blazeSpawnsCgroupSupplier.get()

        private fun createBlazeSpawnsCgroup(): CgroupsInfo? {
            if (!rootCgroup.exists()) {
                return InvalidCgroupsInfo(
                    com.google.devtools.build.lib.sandbox.CgroupsInfo.Type.BLAZE_SPAWNS,
                    rootCgroup.version, "Root cgroup does not exist."
                )
            }
            return rootCgroup.createBlazeSpawnsCgroup(PROC_SELF_CGROUP_PATH)
        }
    }
}
