// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler.memory

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * A [BlazeModule] that can be used to record interesting information about all allocations
 * done during every command on the current blaze server.
 * 
 * 
 * To enable tracking, you must pass:
 * 
 * 
 *  1. --host_jvm_args=-javaagent:(path to Google's java agent jar)
 * 
 *  * For Bazel use [java-allocation-instrumenter-3.3.4.jar](https://github.com/bazelbuild/bazel/tree/master/third_party/allocation_instrumenter)
 * 
 *  1. --host_jvm_args=-DRULE_MEMORY_TRACKER=1
 * 
 * 
 * 
 * The memory tracking information is accessible via blaze dump --rules and blaze dump
 * --skylark_memory=(path)
 */
class AllocationTrackerModule : BlazeModule() {
    private var enabled = false

    // Always AllocationTracker, but we don't refer to the type as it is supplied manually via a Java
    // agent.
    private var tracker: Any? = null

    override fun blazeStartup(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        versionInfo: BlazeVersionInfo?,
        instanceId: UUID?,
        fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
        directories: ServerDirectories?,
        clock: com.google.devtools.build.lib.clock.Clock?
    ) {
        enabled = isRequested()
        if (enabled) {
            try {
                java.lang.Class.forName("com.google.monitoring.runtime.instrumentation.Sampler")
            } catch (e: java.lang.ClassNotFoundException) {
                enabled = false
                return
            }
            tracker = AllocationTracker(SAMPLE_SIZE, VARIANCE)
            net.starlark.java.eval.Debug.setThreadHook(tracker as AllocationTracker)
            CurrentRuleTracker.setEnabled(true)
            AllocationTrackerInstaller.installAllocationTracker(tracker as AllocationTracker?)
        }
    }

    override fun workspaceInit(
        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
    ) {
        if (enabled) {
            builder.setAllocationTracker(tracker as AllocationTracker?)
        }
    }

    override fun beforeCommand(env: CommandEnvironment) {
        if (!enabled && isRequested()) {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        ("Failed to enable memory tracking, ensure that you set"
                                + " --host_jvm_args=-javaagent:<path to"
                                + " java-allocation-instrumenter-3.3.4.jar>")
                    )
                )
        }
    }

    companion object {
        /** Sample allocations every N bytes for performance.  */
        private val SAMPLE_SIZE = 256 * 1024

        /**
         * Add some variance to how often we sample, to avoid sampling the same callstack all the time due
         * to overly regular allocation patterns.
         */
        private const val VARIANCE = 100

        private fun isRequested(): Boolean {
            val memoryTrackerProperty: String? = java.lang.System.getProperty("RULE_MEMORY_TRACKER")
            return memoryTrackerProperty != null && memoryTrackerProperty == "1"
        }
    }
}
