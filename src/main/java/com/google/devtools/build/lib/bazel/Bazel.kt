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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule
import com.google.devtools.build.lib.bazel.BazelBuiltinCommandModule
import com.google.devtools.build.lib.bazel.BazelDiffAwarenessModule
import com.google.devtools.build.lib.bazel.BazelFileSystemModule
import com.google.devtools.build.lib.bazel.BazelRepositoryModule
import com.google.devtools.build.lib.bazel.BazelServices
import com.google.devtools.build.lib.bazel.BazelStartupOptionsModule
import com.google.devtools.build.lib.bazel.BazelWorkspaceStatusModule
import com.google.devtools.build.lib.bazel.CacheHitReportingModule
import com.google.devtools.build.lib.bazel.SpawnLogModule
import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileModule
import com.google.devtools.build.lib.bazel.coverage.BazelCoverageReportModule
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleModule
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryDebugModule
import com.google.devtools.build.lib.bazel.rules.BazelRulesModule
import com.google.devtools.build.lib.bazel.rules.BazelStrategyModule
import com.google.devtools.build.lib.buildeventservice.BazelBuildEventServiceModule
import com.google.devtools.build.lib.dynamic.DynamicExecutionModule
import com.google.devtools.build.lib.includescanning.IncludeScanningModule
import com.google.devtools.build.lib.metrics.MetricsModule
import com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.GcAfterBuildModule
import com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.PostGCMemoryUseRecorderModule
import com.google.devtools.build.lib.network.NoOpConnectivityModule
import com.google.devtools.build.lib.outputfilter.OutputFilteringModule
import com.google.devtools.build.lib.packages.metrics.PackageMetricsModule
import com.google.devtools.build.lib.platform.SleepPreventionModule
import com.google.devtools.build.lib.platform.SystemSuspensionModule
import com.google.devtools.build.lib.profiler.CommandProfilerModule
import com.google.devtools.build.lib.profiler.memory.AllocationTrackerModule
import com.google.devtools.build.lib.remote.RemoteModule
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.BlazeRuntime
import com.google.devtools.build.lib.runtime.BlockWaitingModule
import com.google.devtools.build.lib.runtime.BuildSummaryStatsModule
import com.google.devtools.build.lib.runtime.CacheFileDigestsModule
import com.google.devtools.build.lib.runtime.CommandLogModule
import com.google.devtools.build.lib.runtime.ExecutionGraphModule
import com.google.devtools.build.lib.runtime.MemoryPressureModule
import com.google.devtools.build.lib.runtime.NoSpawnCacheModule
import com.google.devtools.build.lib.runtime.ThreadDumpModule
import com.google.devtools.build.lib.runtime.mobileinstall.MobileInstallModule
import com.google.devtools.build.lib.sandbox.SandboxModule
import com.google.devtools.build.lib.shell.WindowsSubprocessFactory
import com.google.devtools.build.lib.skyframe.SkymeldModule
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule
import com.google.devtools.build.lib.standalone.StandaloneModule
import com.google.devtools.build.lib.starlarkdebug.module.StarlarkDebuggerModule
import com.google.devtools.build.lib.starlarkprofiler.CpuProfilerModule
import com.google.devtools.build.lib.worker.WorkerModule
import java.io.IOException
import java.util.Properties

/** The main class.  */
object Bazel {
    private const val BUILD_DATA_PROPERTIES = "/build-data.properties"

    /**
     * The list of modules to load. Note that the order is important: In case multiple modules provide
     * strategies for the same things, the last module wins and its strategy becomes the default.
     * 
     * 
     * Example: To make the "standalone" execution strategy the default for spawns, put it after
     * all the other modules that provider spawn strategies (e.g. WorkerModule and SandboxModule).
     */
    // Class names fully qualified for clarity.
    @kotlin.jvm.JvmField
    val BAZEL_MODULES: com.google.common.collect.ImmutableList<java.lang.Class<out BlazeModule?>?> =
        com.google.common.collect.ImmutableList.of<java.lang.Class<out BlazeModule?>?>(
            BazelStartupOptionsModule::class.java,  // This module is registered early so that profiles are as complete as possible.
            CommandProfilerModule::class.java,
            CpuProfilerModule::class.java,  // This module needs to be registered before any module providing a SpawnCache
            // implementation.
            NoSpawnCacheModule::class.java,  // This module needs to be registered before any module that uses the credential cache.
            CredentialModule::class.java,
            CommandLogModule::class.java,
            MemoryPressureModule::class.java,
            ThreadDumpModule::class.java,
            SleepPreventionModule::class.java,
            SystemSuspensionModule::class.java,
            BazelFileSystemModule::class.java,
            MobileInstallModule::class.java,
            BazelWorkspaceStatusModule::class.java,
            BazelDiffAwarenessModule::class.java,
            RemoteModule::class.java,
            BazelRepositoryModule::class.java,
            StarlarkRepositoryDebugModule::class.java,
            WorkspaceRuleModule::class.java,
            BazelCoverageReportModule::class.java,
            StarlarkDebuggerModule::class.java,
            CacheHitReportingModule::class.java,
            SpawnLogModule::class.java,
            BazelLockFileModule::class.java,
            OutputFilteringModule::class.java,
            WorkerModule::class.java,
            CacheFileDigestsModule::class.java,
            StandaloneModule::class.java,
            SandboxModule::class.java,
            BuildSummaryStatsModule::class.java,
            DynamicExecutionModule::class.java,
            BazelRulesModule::class.java,
            BazelStrategyModule::class.java,
            NoOpConnectivityModule::class.java,
            AllocationTrackerModule::class.java,
            PackageMetricsModule::class.java,
            ExecutionGraphModule::class.java,
            BazelBuiltinCommandModule::class.java,
            IncludeScanningModule::class.java,
            SkymeldModule::class.java,
            SerializationModule::class.java,  // This module needs to be registered after any module submitting tasks with its {@code
            // submit} method.
            BlockWaitingModule::class.java,  // This module needs to come after BlockWaitingModule so that the BES isn't closed until
            // the background tasks maintained by the module have completed.
            BazelBuildEventServiceModule::class.java,  // Modules that are involved in the collection of heap-related metrics of a build. They
            // need to be
            // last in the modules order, so when the GCs happen at the end of the build, we mitigate
            // the risk
            // that objects are still held onto by the other modules. This is a quick fix for
            // b/247613138.
            // TODO(b/253394502): remove this when we have a better solution.
            PostGCMemoryUseRecorderModule::class.java,
            GcAfterBuildModule::class.java,
            MetricsModule::class.java
        )

    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        // Sets the default subprocess factory to the Windows-specific implementation if the host OS is
        // Windows. We do this in Bazel.java to make sure that the global state is set before the first
        // use of SubprocessBuilder.
        WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        BlazeVersionInfo.setBuildInfo(tryGetBuildInfo())
        BlazeRuntime.main(
            BAZEL_MODULES,
            BazelServices.BAZEL_SERVICES,
            args,
            com.google.devtools.build.lib.jni.JniLoader.getJniLoadError()
        )
    }

    /**
     * Builds the standard build info map from the loaded properties. The returned value is the list
     * of "build.*" properties from the build-data.properties file. The final key is the original one
     * striped, dot replaced with a space and with first letter capitalized. If the file fails to load
     * the returned map is empty.
     */
    private fun tryGetBuildInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        try {
            Bazel::class.java.getResourceAsStream(BUILD_DATA_PROPERTIES).use { `in` ->
                if (`in` == null) {
                    return com.google.common.collect.ImmutableMap.of<String?, String?>()
                }
                val props: Properties = Properties()
                props.load(`in`)
                val buildData: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                    com.google.common.collect.ImmutableMap.builder<String?, String?>()
                for (key in props.keySet()) {
                    val stringKey = key.toString()
                    if (stringKey.startsWith("build.")) {
                        // build.label -> Build label, build.timestamp.as.int -> Build timestamp as int
                        val buildDataKey = "B" + stringKey.substring(1).replace('.', ' ')
                        buildData.put(buildDataKey, props.getProperty(stringKey, ""))
                    }
                }
                return buildData.buildOrThrow()
            }
        } catch (ignored: IOException) {
            return com.google.common.collect.ImmutableMap.of<String?, String?>()
        }
    }
}
