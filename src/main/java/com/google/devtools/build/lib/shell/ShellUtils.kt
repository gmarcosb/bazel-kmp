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
 * Utility functions for Bourne shell commands, including escaping and
 * tokenizing.
 */
object ShellUtils {
    /**
     * Characters that have no special meaning to the shell.
     */
    private const val SAFE_PUNCTUATION = "@%-_+:,./"

    /**
     * Quotes a word so that it can be used, without further quoting,
     * as an argument (or part of an argument) in a shell command.
     */
    @kotlin.jvm.JvmStatic
    fun shellEscape(word: String): String {
        val len = word.length
        if (len == 0) {
            // Empty string is a special case: needs to be quoted to ensure that it gets
            // treated as a separate argument.
            return "''"
        }
        for (ii in 0..<len) {
            val c = word.get(ii)
            // We do this positively so as to be sure we don't inadvertently forget
            // any unsafe characters.
            if (!java.lang.Character.isLetterOrDigit(c) && SAFE_PUNCTUATION.indexOf(c) == -1) {
                // replace() actually means "replace all".
                return "'" + word.replace("'", "'\\''") + "'"
            }
        }
        return word
    }

    /**
     * Given an argv array such as might be passed to execve(2), returns a string
     * that can be copied and pasted into a Bourne shell for a similar effect.
     */
    fun prettyPrintArgv(argv: MutableList<String>): String {
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        for (arg in argv) {
            if (buf.length > 0) {
                buf.append(' ')
            }
            buf.append(shellEscape(arg))
        }
        return buf.toString()
    }


    /**
     * Populates the passed list of command-line options extracted from `optionString`, which is a string containing multiple options, delimited in
     * a Bourne shell-like manner.
     * 
     * @param options the list to be populated with tokens.
     * @param optionString the string to be tokenized.
     * @throws TokenizationException if there was an error (such as an
     * unterminated quotation).
     */
    @Throws(TokenizationException::class)
    fun tokenize(options: MutableList<String?>, optionString: String) {
        // See test suite for examples.
        //
        // Note: backslash escapes the following character, except within a
        // single-quoted region where it is literal.

        var token: java.lang.StringBuilder = java.lang.StringBuilder()
        var forceToken = false
        var quotation = '\u0000' // NUL, '\'' or '"'
        var ii = 0
        val len = optionString.length
        while (ii < len) {
            var c = optionString.get(ii)
            if (quotation != '\u0000') { // in quotation
                if (c == quotation) { // end of quotation
                    quotation = '\u0000'
                } else if (c == '\\' && quotation == '"') { // backslash in "-quotation
                    if (++ii == len) {
                        throw TokenizationException("backslash at end of string")
                    }
                    c = optionString.get(ii)
                    if (c != '\\' && c != '"') {
                        token.append('\\')
                    }
                    token.append(c)
                } else { // regular char, in quotation
                    token.append(c)
                }
            } else { // not in quotation
                if (c == '\'' || c == '"') { // begin single/double quotation
                    quotation = c
                    forceToken = true
                } else if (c == ' ' || c == '\t') { // space, not quoted
                    if (forceToken || token.length > 0) {
                        options.add(token.toString())
                        token = java.lang.StringBuilder()
                        forceToken = false
                    }
                } else if (c == '\\') { // backslash, not quoted
                    if (++ii == len) {
                        throw TokenizationException("backslash at end of string")
                    }
                    token.append(optionString.get(ii))
                } else { // regular char, not quoted
                    token.append(c)
                }
            }
            ii++
        }
        if (quotation != '\u0000') {
            throw TokenizationException("unterminated quotation")
        }
        if (forceToken || token.length > 0) {
            options.add(token.toString())
        }
    }

    /**
     * Escape command line arguments for `CreateProcessW` on Windows.
     * 
     * 
     * This method implements the same algorithm as the native xx_binary launcher does (see
     * https://github.com/bazelbuild/bazel/pull/7411).
     * 
     * 
     * A similar algorithm with lots of background information is described here:
     * https://blogs.msdn.microsoft.com/twistylittlepassagesallalike/2011/04/23/
     */
    @kotlin.jvm.JvmStatic
    fun windowsEscapeArg(s: String): String {
        if (s.isEmpty()) {
            return "\"\""
        } else {
            var needsEscape = false
            for (i in 0..<s.length) {
                val c = s.get(i)
                if (c == ' ' || c == '"') {
                    needsEscape = true
                    break
                }
            }
            if (!needsEscape) {
                return s
            }
        }

        val result: java.lang.StringBuilder = java.lang.StringBuilder()
        result.append('"')
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s.get(i)
            if (c == '"' || c == '\\') {
                // Copy the segment since the last special character.
                if (start >= 0) {
                    result.append(s, start, i)
                    start = -1
                }

                // Handle the current special character.
                if (c == '"') {
                    // This is a quote character. Escape it with a single backslash.
                    result.append("\\\"")
                } else {
                    // This is a backslash (or the first one in a run of backslashes).
                    // Whether we escape it depends on whether the run ends with a quote.
                    var runLen = 1
                    var j = i + 1
                    while (j < s.length && s.get(j) == '\\') {
                        runLen++
                        j++
                    }
                    if (j == s.length) {
                        // The run of backslashes goes to the end.
                        // We have to escape every backslash with another backslash.
                        for (k in 0..<runLen * 2) {
                            result.append('\\')
                        }
                        break
                    } else if (j < s.length && s.get(j) == '"') {
                        // The run of backslashes is terminated by a quote.
                        // We have to escape every backslash with another backslash, and
                        // escape the quote with one backslash.
                        for (k in 0..<runLen * 2) {
                            result.append('\\')
                        }
                        result.append("\\\"")
                        i += runLen // 'i' is also increased in the loop iteration step
                    } else {
                        // No quote found. Each backslash counts for itself, they must not be
                        // escaped.
                        for (k in 0..<runLen) {
                            result.append('\\')
                        }
                        i += runLen - 1 // 'i' is also increased in the loop iteration step
                    }
                }
            } else {
                // This is not a special character. Start the segment if necessary.
                if (start < 0) {
                    start = i
                }
            }
            ++i
        }
        // Save final segment after the last special character.
        if (start != -1) {
            result.append(s, start, s.length)
        }
        result.append('"')
        return result.toString()
    }

    /**
     * Thrown by tokenize method if there is an error
     */
    class TokenizationException internal constructor(message: String?) : java.lang.Exception(message)
}
