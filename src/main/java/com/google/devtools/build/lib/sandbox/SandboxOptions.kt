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

/** Options for sandboxed execution.  */
@com.google.devtools.common.options.OptionsClass
abstract class SandboxOptions : com.google.devtools.common.options.OptionsBase() {
    /**
     * A converter for customized path mounting pair from the parameter list of a bazel command
     * invocation. Pairs are expected to have the form 'source:target'.
     */
    class MountPairConverter

        : com.google.devtools.common.options.Converter.Contextless<MutableMap.MutableEntry<String?, String?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableMap.MutableEntry<String?, String?> {
            val paths: MutableList<String?> = java.util.ArrayList<String?>()
            for (path in input.split("(?<!\\\\):".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) { // Split on ':' but not on '\:'
                if (path != null && !path.trim { it <= ' ' }.isEmpty()) {
                    paths.add(path.replace("\\:", ":"))
                } else {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        ("Input "
                                + input
                                + " contains one or more empty paths. "
                                + "Input must be a single path to mount inside the sandbox or "
                                + "a mounting pair in the form of 'source:target'")
                    )
                }
            }

            if (paths.size < 1 || paths.size > 2) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    "Input must be a single path to mount inside the sandbox or "
                            + "a mounting pair in the form of 'source:target'"
                )
            }

            return if (paths.size == 1)
                com.google.common.collect.Maps.immutableEntry<String?, String?>(paths.get(0), paths.get(0))
            else
                com.google.common.collect.Maps.immutableEntry<String?, String?>(paths.get(0), paths.get(1))
        }

        val typeDescription: String
            get() = "a single path or a 'source:target' pair"
    }

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_debug",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Enables debugging features for the sandboxing feature. This includes two things: first, "
                + "the sandbox root contents are left untouched after a build; and second, prints "
                + "extra debugging information on execution. This can help developers of Bazel or "
                + "Starlark rules with debugging failures due to missing input files, etc.")
    )
    abstract val sandboxDebug: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "sandbox_base",
        oldName = "experimental_sandbox_base",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Lets the sandbox create its sandbox directories underneath this path. Specify a path on"
                + " tmpfs (like /run/shm) to possibly improve performance a lot when your build /"
                + " tests have many input files. Note: You need enough RAM and free space on the"
                + " tmpfs to hold output and intermediate files generated by running actions.")
    )
    abstract val sandboxBase: String?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_fake_hostname",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Change the current hostname to 'localhost' for sandboxed actions."
    )
    abstract val sandboxFakeHostname: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_fake_username",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Change the current username to 'nobody' for sandboxed actions."
    )
    abstract val sandboxFakeUsername: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "sandbox_explicit_pseudoterminal",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Explicitly enable the creation of pseudoterminals for sandboxed actions."
                + " Some linux distributions require setting the group id of the process to 'tty'"
                + " inside the sandbox in order for pseudoterminals to function. If this is"
                + " causing issues, this flag can be disabled to enable other groups to be used.")
    )
    abstract val sandboxExplicitPseudoterminal: Boolean

    /** Sets the list of paths to block in the sandbox.  */
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_block_path",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "For sandboxed actions, disallow access to this path."
    )
    abstract var sandboxBlockPath: MutableList<String?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_tmpfs_path",
        allowMultiple = true,
        converter = AbsolutePathFragmentConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("For sandboxed actions, mount an empty, writable directory at this absolute path"
                + " (if supported by the sandboxing implementation, ignored otherwise).")
    )
    abstract val sandboxTmpfsPath: MutableList<PathFragment>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_writable_path",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("For sandboxed actions, make an existing directory writable in the sandbox"
                + " (if supported by the sandboxing implementation, ignored otherwise).")
    )
    abstract val sandboxWritablePath: MutableList<String?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "sandbox_add_mount_pair",
        allowMultiple = true,
        converter = MountPairConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Add additional path pair to mount in sandbox."
    )
    abstract val sandboxAdditionalMounts: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_sandboxfs_map_symlink_targets",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "No-op"
    )
    abstract val sandboxfsMapSymlinkTargets: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_use_windows_sandbox",
        converter = com.google.devtools.common.options.Converters.TriStateConverter::class,
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Use Windows sandbox to run actions. "
                + "If \"yes\", the binary provided by --experimental_windows_sandbox_path must be "
                + "valid and correspond to a supported version of sandboxfs. If \"auto\", the binary "
                + "may be missing or not compatible.")
    )
    abstract val useWindowsSandbox: com.google.devtools.common.options.TriState?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_windows_sandbox_path",
        defaultValue = "BazelSandbox.exe",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Path to the Windows sandbox binary to use when --experimental_use_windows_sandbox is"
                + " true. If a bare name, use the first binary of that name found in the PATH.")
    )
    abstract val windowsSandboxPath: String?

    fun getInaccessiblePaths(fs: com.google.devtools.build.lib.vfs.FileSystem): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?> {
        val inaccessiblePaths: MutableList<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
        for (path in this.sandboxBlockPath!!) {
            val blockedPath: com.google.devtools.build.lib.vfs.Path = fs.getPath(path)
            try {
                inaccessiblePaths.add(blockedPath.resolveSymbolicLinks())
            } catch (e: IOException) {
                // It's OK to block access to an invalid symlink. In this case we'll just make the symlink
                // itself inaccessible, instead of the target, though.
                inaccessiblePaths.add(blockedPath)
            }
        }
        return com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.vfs.Path?>(inaccessiblePaths)
    }

    @get:com.google.devtools.common.options.Option(
        name = "experimental_enable_docker_sandbox",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "Enable Docker-based sandboxing. This option has no effect if Docker is not installed."
    )
    abstract val enableDockerSandbox: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_docker_image",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Specify a Docker image name (e.g. \"ubuntu:latest\") that should be used to execute a"
                + " sandboxed action when using the docker strategy and the action itself doesn't"
                + " already have a container-image attribute in its exec_properties in the platform"
                + " description. The value of this flag is passed verbatim to 'docker run', so it"
                + " supports the same syntax and mechanisms as Docker itself.")
    )
    abstract val dockerImage: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_docker_use_customized_images",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, injects the uid and gid of the current user into the Docker image before"
                + " using it. This is required if your build / tests depend on the user having a name"
                + " and home directory inside the container. This is on by default, but you can"
                + " disable it in case the automatic image customization feature doesn't work in your"
                + " case or you know that you don't need it.")
    )
    abstract val dockerUseCustomizedImages: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_docker_verbose",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "If enabled, Bazel will print more verbose messages about the Docker sandbox strategy."
    )
    abstract val dockerVerbose: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_docker_privileged",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If enabled, Bazel will pass the --privileged flag to 'docker run' when running actions. "
                + "This might be required by your build, but it might also result in reduced "
                + "hermeticity.")
    )
    abstract val dockerPrivileged: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "sandbox_default_allow_network",
        oldName = "experimental_sandbox_default_allow_network",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("Allow network access by default for actions; this may not work with all sandboxing "
                + "implementations.")
    )
    abstract val defaultSandboxAllowNetwork: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_sandbox_async_tree_delete_idle_threads",
        defaultValue = "4",
        converter = AsyncTreeDeletesConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If 0, sandboxes are deleted as soon as actions finish, blocking action completion. If"
                + " greater than 0, sandboxes are deleted asynchronously in the background without"
                + " blocking action completion. Asynchronous deletion uses a single thread while a"
                + " command is running, but ramps up to as many threads as the value of this flag"
                + " once the server becomes idle. Set to `auto` to use as many threads as the number"
                + " of CPUs. A server shutdown blocks on any pending asynchronous deletions.")
    )
    abstract val asyncTreeDeleteIdleThreads: Int

    @get:com.google.devtools.common.options.Option(
        name = "reuse_sandbox_directories",
        oldName = "experimental_reuse_sandbox_directories",
        oldNameWarning = false,
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If set to true, directories used by sandboxed non-worker execution may be reused to"
                + " avoid unnecessary setup costs.")
    )
    abstract val reuseSandboxDirectories: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_inmemory_sandbox_stashes",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If set to true, the contents of stashed sandboxes for reuse_sandbox_directories will be"
                + " tracked in memory. This reduces the amount of I/O needed during reuse. Depending"
                + " on the build this flag may improve wall time. Depending on the build as well this"
                + " flag may use a significant amount of additional memory.")
    )
    abstract val experimentalInMemorySandboxStashes: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_use_hermetic_linux_sandbox",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("If set to true, do not mount root, only mount whats provided with "
                + "sandbox_add_mount_pair. Input files will be hardlinked to the sandbox instead of "
                + "symlinked to from the sandbox. "
                + "If action input files are located on a filesystem different from the sandbox, "
                + "then the input files will be copied instead.")
    )
    abstract val useHermetic: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "experimental_sandbox_memory_limit_mb",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = RamResourceConverter::class,
        help = ("If > 0, each Linux sandbox will be limited to the given amount of memory (in MB)."
                + " Requires cgroups v1 or v2 and permissions for the users to the cgroups dir.")
    )
    abstract val memoryLimitMb: Int

    @get:com.google.devtools.common.options.Option(
        name = "experimental_sandbox_limits",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = ResourceConverter.AssignmentConverter::class,
        allowMultiple = true,
        help = ("If > 0, each Linux sandbox will be limited to the given amount"
                + " for the specified resource. Requires --incompatible_use_new_cgroup_implementation"
                + " and overrides --experimental_sandbox_memory_limit_mb."
                + " Requires cgroups v1 or v2 and permissions for the users to the cgroups dir.")
    )
    abstract val limits: MutableList<MutableMap.MutableEntry<String?, Double?>?>?

    val limitsMap: com.google.common.collect.ImmutableMap<String?, Double?>
        get() = com.google.common.collect.ImmutableMap.builder<String?, Double?>()
            .put("memory", this.memoryLimitMb.toDouble())
            .putAll(this.limits)
            .buildKeepingLast()

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "incompatible_use_new_cgroup_implementation",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.common.options.Converters.BooleanConverter::class,
        help = ("If true, use the new implementation for cgroups. The old implementation only supports"
                + " the memory controller and ignores the value of --experimental_sandbox_limits.")
    )
    abstract val useNewCgroupImplementation: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_sandbox_enforce_resources_regexp",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.common.options.Converters.RegexPatternConverter::class,
        help = ("If true, actions whose mnemonic matches the input regex will have their resources"
                + " request enforced as limits, overriding the value of"
                + " --experimental_sandbox_limits, if the resource type supports it. For example a"
                + " test that declares cpu:3 and resources:memory:10, will run with at most 3 cpus"
                + " and 10 megabytes of memory.")
    )
    abstract val enforceResources: com.google.devtools.common.options.RegexPatternOption?

    @get:com.google.devtools.common.options.Option(
        name = "sandbox_enable_loopback_device",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        converter = com.google.devtools.common.options.Converters.BooleanConverter::class,
        help = ("If true, a loopback device will be set up in the linux-sandbox network namespace for"
                + " local actions.")
    )
    abstract val sandboxEnableLoopbackDevice: Boolean

    /** Converter for the number of threads used for asynchronous tree deletion.  */
    class AsyncTreeDeletesConverter :
        ResourceConverter.IntegerConverter( /* auto= */ResourceConverter.HOST_CPUS_SUPPLIER,  /* minValue= */
            0,  /* maxValue= */
            Int.Companion.MAX_VALUE
        )
}
