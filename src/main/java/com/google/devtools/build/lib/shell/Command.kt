// Copyright 2014 The Bazel Authors. All rights reserved.
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

/**
 * An executable command, including its arguments and runtime environment (environment variables,
 * working directory). It lets a caller execute a command, get its results, and optionally forward
 * interrupts to the subprocess. This class creates threads to ensure timely reading of subprocess
 * outputs.
 * 
 * 
 * This class is immutable and thread-safe.
 * 
 * 
 * The use of "shell" in the package name of this class is a misnomer. In terms of the way its
 * arguments are interpreted, this class is closer to `execve(2)` than to `system(3)`.
 * No shell is executed.
 * 
 * <h4>Examples</h4>
 * 
 * 
 * The most basic use-case for this class is as follows:
 * 
 * <pre>
 * ImmutableList&lt;String&gt; args = ImmutableList.of("/bin/du", "-s", directory);
 * BlazeCommandResult result = new Command(args).execute();
 * String output = new String(result.getStdout());
</pre> * 
 * 
 * which writes the output of the `du(1)` command into `output`. More complex cases
 * might inspect the stderr stream, kill the subprocess asynchronously, feed input to its standard
 * input, handle the exceptions thrown if the command fails, or print the termination status (exit
 * code or signal name).
 * 
 * <h4>Other Features</h4>
 * 
 * 
 * A caller can optionally specify bytes to be written to the process's "stdin". The returned
 * [CommandResult] object gives the caller access to the exit status, as well as output from
 * "stdout" and "stderr". To use this class with processes that generate very large amounts of
 * input/output, consider [.execute], [ ][.executeAsync], or [.executeAsync].
 * 
 * 
 * This class ensures that stdout and stderr streams are read promptly, avoiding potential
 * deadlock if the output is large. See [when `Runtime.exec()
` *  won't](http://www.javaworld.com/javaworld/jw-12-2000/jw-1229-traps.html).
 * 
 * <h4>Caution: Invoking Shell Commands</h4>
 * 
 * 
 * Perhaps the most common command invoked programmatically is the UNIX shell, `/bin/sh`.
 * Because the shell is a general-purpose programming language, care must be taken to ensure that
 * variable parts of the shell command (e.g. strings entered by the user) do not contain shell
 * metacharacters, as this poses a correctness and/or security risk.
 * 
 * 
 * To execute a shell command directly, use the following pattern:
 * 
 * <pre>
 * ImmutableList&lt;String&gt; args = ImmutableList.of("/bin/sh", "-c", shellCommand);
 * BlazeCommandResult result = new Command(args).execute();
</pre> * 
 * 
 * `shellCommand` is a complete Bourne shell program, possibly containing all kinds of
 * unescaped metacharacters. For example, here's a shell command that enumerates the working
 * directories of all processes named "foo":
 * 
 * <pre>ps auxx | grep foo | awk '{print $1}' |
 * while read pid; do readlink /proc/$pid/cwd; done</pre>
 * 
 * It is the responsibility of the caller to ensure that this string means what they intend.
 * 
 * 
 * Consider the risk posed by allowing the "foo" part of the previous command to be some
 * arbitrary (untrusted) string called `processName`:
 * 
 * <pre>
 * // WARNING: unsafe!
 * String shellCommand = "ps auxx | grep " + processName + " | awk '{print $1}' | "
 * + "while read pid; do readlink /proc/$pid/cwd; done";</pre>
 * 
 * 
 * 
 * Passing this string to [Command] is unsafe because if the string `processName`
 * contains shell metacharacters, the meaning of the command can be arbitrarily changed; consider:
 * 
 * <pre>String processName = ". ; rm -fr $HOME & ";</pre>
 * 
 * 
 * To defend against this possibility, it is essential to properly quote the variable portions of
 * the shell command so that shell metacharacters are escaped. Use [ShellUtils.shellEscape]
 * for this purpose:
 * 
 * <pre>
 * // Safe.
 * String shellCommand = "ps auxx | grep " + ShellUtils.shellEscape(processName)
 * + " | awk '{print $1}' | while read pid; do readlink /proc/$pid/cwd; done";
</pre> * 
 * 
 * 
 * Tip: if you are only invoking a single known command, and no shell features (e.g. $PATH
 * lookup, output redirection, pipelines, etc) are needed, call it directly without using a shell,
 * as in the `du(1)` example above.
 */
class Command(
    commandLineElements: MutableList<String?>?,
    environmentVariables: MutableMap<String?, String?>?,
    workingDirectory: java.io.File?,
    timeout: java.time.Duration,
    clientEnv: MutableMap<String?, String?>?
) : DescribableExecutionUnit {
    private val subprocessBuilder: SubprocessBuilder

    /**
     * Creates a new [Command] for the given command line. The environment is inherited from the
     * current process, as is the working directory. No timeout is enforced. The command line is
     * executed exactly as given, without a shell. Subsequent calls to [.execute] will use the
     * JVM's working directory and the Bazel client's environment.
     * 
     * @param commandLineElements elements of raw command line to execute
     * @param clientEnv the client's environment variables which will be inherited by the subprocess
     * @throws IllegalArgumentException if commandLine is null or empty
     */
    constructor(commandLineElements: MutableList<String?>?, clientEnv: MutableMap<String?, String?>?) : this(
        commandLineElements,
        null,
        null,
        java.time.Duration.ZERO,
        clientEnv
    )

    /** Just like [.Command], but without a timeout.  */
    constructor(
        commandLineElements: MutableList<String?>?,
        environmentVariables: MutableMap<String?, String?>?,
        workingDirectory: java.io.File?,
        clientEnv: MutableMap<String?, String?>?
    ) : this(commandLineElements, environmentVariables, workingDirectory, java.time.Duration.ZERO, clientEnv)

    /**
     * Creates a new [Command] for the given command line elements. The command line is executed
     * without a shell.
     * 
     * 
     * The given environment variables and working directory are used in subsequent calls to [ ][.execute].
     * 
     * 
     * This command treats the 0-th element of `commandLineElement` (the name of an
     * executable to run) specially.
     * 
     * 
     *  * If it is an absolute path, it is used as it
     *  * If it is a single file name, the PATH lookup is performed
     *  * If it is a relative path that is not a single file name, the command will attempt to
     * execute the binary at that path relative to `workingDirectory`.
     * 
     * 
     * @param commandLineElements elements of raw command line to execute
     * @param environmentVariables environment variables for the child process, or null to inherit
     * them from the parent
     * @param workingDirectory working directory for the child process, or null to inherit it from the
     * parent
     * @param timeout timeout; a value less than or equal to 0 is treated as no timeout
     * @param clientEnv the client's environment variables, which may be inherited by the subprocess
     * @throws IllegalArgumentException if commandLine is null or empty
     */
    // TODO(ulfjack): Throw a special exception if there was a timeout.
    init {
        var commandLineElements = commandLineElements
        com.google.common.base.Preconditions.checkNotNull<MutableList<String?>?>(commandLineElements)
        com.google.common.base.Preconditions.checkArgument(
            !commandLineElements!!.isEmpty(),
            "cannot run an empty command line"
        )

        val executable: java.io.File = java.io.File(commandLineElements.get(0))
        if (!executable.isAbsolute() && executable.getParent() != null) {
            commandLineElements =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(commandLineElements.size)
                    .add(java.io.File(workingDirectory, commandLineElements.get(0)).getAbsolutePath())
                    .addAll(commandLineElements.subList(1, commandLineElements.size))
                    .build()
        }

        this.subprocessBuilder = SubprocessBuilder(clientEnv)
        subprocessBuilder.setArgv(com.google.common.collect.ImmutableList.copyOf<String?>(commandLineElements))
        subprocessBuilder.setEnv(environmentVariables)
        subprocessBuilder.setWorkingDirectory(workingDirectory)
        subprocessBuilder.setTimeoutMillis(timeout.toMillis())
    }

    val arguments: com.google.common.collect.ImmutableList<String?>?
        /** Returns the raw command line elements to be executed  */
        get() = subprocessBuilder.getArgv()

    val environment: com.google.common.collect.ImmutableMap<String?, String?>?
        /** Returns an (unmodifiable) [Map] view of command's environment variables or null.  */
        get() = subprocessBuilder.getEnv()

    val workingDirectory: java.io.File?
        /** Returns the working directory to be used for execution, or null.  */
        get() = subprocessBuilder.getWorkingDirectory()

    /**
     * Execute this command with no input to stdin, and with the output captured in memory. If the
     * current process is interrupted, then the subprocess is also interrupted. This call blocks until
     * the subprocess completes or an error occurs.
     * 
     * 
     * This method is a convenience wrapper for `executeAsync().get()`.
     * 
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class, java.lang.InterruptedException::class)
    fun execute(): CommandResult {
        return executeAsync().get()
    }

    /**
     * Execute this command with no input to stdin, and with the output streamed to the given output
     * streams, which must be thread-safe. If the current process is interrupted, then the subprocess
     * is also interrupted. This call blocks until the subprocess completes or an error occurs.
     * 
     * 
     * Note that the given output streams are never closed by this class.
     * 
     * 
     * This method is a convenience wrapper for `executeAsync(stdOut, stdErr).get()`.
     * 
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class, java.lang.InterruptedException::class)
    fun execute(stdOut: java.io.OutputStream?, stdErr: java.io.OutputStream?): CommandResult {
        return doExecute(
            com.google.devtools.build.lib.shell.Command.Companion.NO_INPUT,
            com.google.devtools.build.lib.shell.Consumers.createStreamingConsumers(stdOut, stdErr),
            com.google.devtools.build.lib.shell.Command.Companion.KILL_SUBPROCESS_ON_INTERRUPT
        )
            .get()
    }

    /**
     * Execute this command with no input to stdin, and with the output captured in memory. If the
     * current process is interrupted, then the subprocess is also interrupted. This call blocks until
     * the subprocess is started or throws an error if that fails, but does not wait for the
     * subprocess to exit.
     * 
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class)
    fun executeAsync(): FutureCommandResult {
        return doExecute(
            com.google.devtools.build.lib.shell.Command.Companion.NO_INPUT,
            com.google.devtools.build.lib.shell.Consumers.createAccumulatingConsumers(),
            com.google.devtools.build.lib.shell.Command.Companion.KILL_SUBPROCESS_ON_INTERRUPT
        )
    }

    /**
     * Execute this command with no input to stdin, and with the output streamed to the given output
     * streams, which must be thread-safe. If the current process is interrupted, then the subprocess
     * is also interrupted. This call blocks until the subprocess is started or throws an error if
     * that fails, but does not wait for the subprocess to exit.
     * 
     * 
     * Note that the given output streams are never closed by this class.
     * 
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class)
    fun executeAsync(stdOut: java.io.OutputStream?, stdErr: java.io.OutputStream?): FutureCommandResult {
        return doExecute(
            com.google.devtools.build.lib.shell.Command.Companion.NO_INPUT,
            com.google.devtools.build.lib.shell.Consumers.createStreamingConsumers(stdOut, stdErr),
            com.google.devtools.build.lib.shell.Command.Companion.KILL_SUBPROCESS_ON_INTERRUPT
        )
    }

    /**
     * Execute this command with no input to stdin, and with the output captured in memory. This call
     * blocks until the subprocess is started or throws an error if that fails, but does not wait for
     * the subprocess to exit.
     * 
     * @param killSubprocessOnInterrupt whether the subprocess should be killed if the current process
     * is interrupted. If this is true, the returned [FutureCommandResult] object may throw
     * [InterruptedException] on [FutureCommandResult.get] if the thread is
     * interrupted while waiting for the process to complete. Otherwise, it will not.
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class)
    fun executeAsync(stdinInput: java.io.InputStream?, killSubprocessOnInterrupt: Boolean): FutureCommandResult {
        return doExecute(
            stdinInput,
            com.google.devtools.build.lib.shell.Consumers.createAccumulatingConsumers(),
            killSubprocessOnInterrupt
        )
    }

    /**
     * Execute this command with no input to stdin, and with the output streamed to the given output
     * streams, which must be thread-safe. This call blocks until the subprocess is started or throws
     * an error if that fails, but does not wait for the subprocess to exit.
     * 
     * 
     * Note that the given output streams are never closed by this class.
     * 
     * @param killSubprocessOnInterrupt whether the subprocess should be killed if the current process
     * is interrupted
     * @return [CommandResult] representing result of the execution
     * @throws ExecFailedException if [Runtime.exec] fails for any reason
     * @throws AbnormalTerminationException if an [IOException] is encountered while reading
     * from the process, or the process was terminated due to a signal
     * @throws BadExitStatusException if the process exits with a non-zero status
     */
    @Throws(com.google.devtools.build.lib.shell.CommandException::class)
    fun executeAsync(
        stdinInput: java.io.InputStream?,
        stdOut: java.io.OutputStream?,
        stdErr: java.io.OutputStream?,
        killSubprocessOnInterrupt: Boolean
    ): FutureCommandResult {
        return doExecute(
            stdinInput,
            com.google.devtools.build.lib.shell.Consumers.createStreamingConsumers(stdOut, stdErr),
            killSubprocessOnInterrupt
        )
    }

    /**
     * A string representation of this command object which includes the arguments, the environment,
     * and the working directory. Avoid relying on the specifics of this format. Note that the size of
     * the result string will reflect the size of the command.
     */
    fun toDebugString(): String {
        val message: java.lang.StringBuilder = java.lang.StringBuilder(128)
        message.append("Executing (without brackets):")
        for (arg in subprocessBuilder.getArgv()) {
            message.append(" [")
            message.append(arg)
            message.append(']')
        }
        message.append("; environment: ")
        message.append(subprocessBuilder.getEnv())
        message.append("; working dir: ")
        val workingDirectory: java.io.File? = subprocessBuilder.getWorkingDirectory()
        message.append(if (workingDirectory == null) "(current)" else workingDirectory.toString())
        return message.toString()
    }

    @Throws(ExecFailedException::class)
    private fun doExecute(
        stdinInput: java.io.InputStream?, outErrConsumers: OutErrConsumers, killSubprocessOnInterrupt: Boolean
    ): FutureCommandResult {
        com.google.common.base.Preconditions.checkNotNull<java.io.InputStream?>(stdinInput, "stdinInput")
        logCommand()

        val process: Subprocess = startProcess()

        outErrConsumers.logConsumptionStrategy()
        outErrConsumers.registerInputs(
            process.getInputStream(), process.getErrorStream(),  /* closeStreams= */false
        )

        // TODO(ulfjack): This call blocks until all input is written. If stdinInput is large (or
        // unbounded), then the async calls can block for a long time, and the timeout is not properly
        // enforced.
        com.google.devtools.build.lib.shell.Command.Companion.processInput(stdinInput, process)

        return FutureCommandResult(this, process, outErrConsumers, killSubprocessOnInterrupt)
    }

    @Throws(ExecFailedException::class)
    private fun startProcess(): Subprocess {
        try {
            return subprocessBuilder.start()
        } catch (ioe: IOException) {
            throw ExecFailedException(this, ioe)
        }
    }

    private class NullInputStream : java.io.InputStream() {
        override fun read(): Int {
            return -1
        }

        override fun available(): Int {
            return 0
        }
    }

    private fun logCommand() {
        com.google.devtools.build.lib.shell.Command.Companion.logger.atFine()
            .log("%s", LazyArgs.lazy<String?>(LazyArg { this.toDebugString() }))
    }

    val mnemonic: String
        get() = "<shell command>"

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Pass this value to [.execute] to indicate that no input should be written to stdin.  */
        @kotlin.jvm.JvmField
        val NO_INPUT: java.io.InputStream = com.google.devtools.build.lib.shell.Command.NullInputStream()

        const val KILL_SUBPROCESS_ON_INTERRUPT: Boolean = true
        const val CONTINUE_SUBPROCESS_ON_INTERRUPT: Boolean = false

        private fun processInput(stdinInput: java.io.InputStream, process: Subprocess) {
            com.google.devtools.build.lib.shell.Command.Companion.logger.atFiner().log("%s", stdinInput)
            try {
                process.getOutputStream().use { out ->
                    com.google.common.io.ByteStreams.copy(stdinInput, out)
                }
            } catch (ioe: IOException) {
                // Note: this is not an error!  Perhaps the command just isn't hungry for our input and exited
                // with success. Process.waitFor (later) will tell us.
                //
                // (Unlike out/err streams, which are read asynchronously, the input stream is written
                // synchronously, in its entirety, before processInput returns.  If the input is infinite, and
                // is passed through e.g. "cat" subprocess and back into the ByteArrayOutputStream, that will
                // eventually run out of memory, causing the output stream to be closed, "cat" to terminate
                // with SIGPIPE, and processInput to receive an IOException.
            }
        }
    }
}
