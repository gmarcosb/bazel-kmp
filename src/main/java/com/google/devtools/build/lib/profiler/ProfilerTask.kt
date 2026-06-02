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
package com.google.devtools.build.lib.profiler

/**
 * All possible types of profiler tasks. Each type also defines description and minimum duration in
 * nanoseconds for it to be recorded as separate event and not just be aggregated into the parent
 * event.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
enum class ProfilerTask @kotlin.jvm.JvmOverloads constructor(
    description: String?,
    minDuration: java.time.Duration? = null,
    collectsSlowestInstances: Boolean = false
) {
    PHASE("build phase marker"),
    ACTION("action processing"),
    DISCOVER_INPUTS("discover inputs"),
    ACTION_CHECK(
        "action dependency checking",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS
    ),
    ACTION_LOCK("action resource lock", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    ACTION_UPDATE(
        "update action information",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS
    ),
    ACTION_COMPLETE("complete action execution"),
    ACTION_REWINDING("prepare action rewind plan"),
    BZLMOD("bazel module processing"),
    INFO("general information"),
    CREATE_PACKAGE("package creation"),
    REMOTE_EXECUTION("remote action execution"),
    LOCAL_EXECUTION("local action execution"),
    SCANNER("include scanner"),
    LOCAL_PARSE(
        "Local parse to prepare for remote execution",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    UPLOAD_TIME(
        "Remote execution upload time",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    REMOTE_PROCESS_TIME(
        "Remote execution process wall time",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    REMOTE_QUEUE(
        "Remote execution queuing time",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    REMOTE_SETUP("Remote execution setup", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS),
    FETCH("Remote execution file fetching", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS),
    LOCAL_PROCESS_TIME(
        "Local execution process wall time",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    VFS_STAT(
        "VFS stat",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_DIR(
        "VFS readdir",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_READLINK(
        "VFS readlink",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),

    // TODO(olaola): rename to VFS_DIGEST. This refers to all digest function computations.
    VFS_MD5(
        "VFS md5",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_XATTR(
        "VFS xattr",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_DELETE("VFS delete", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    VFS_OPEN(
        "VFS open",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_READ(
        "VFS read",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_WRITE(
        "VFS write",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS,  /* collectsSlowestInstances= */
        true
    ),
    VFS_GLOB("globbing", null,  /* collectsSlowestInstances= */true),
    VFS_VMFS_STAT("VMFS stat", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    VFS_VMFS_DIR("VMFS readdir", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    VFS_VMFS_READ("VMFS read", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    WAIT("thread wait", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    THREAD_NAME("thread name"),  // Do not use directly!
    THREAD_SORT_INDEX("thread sort index"),
    SKYFRAME_EVAL("skyframe evaluator"),
    SKYFUNCTION("skyfunction"),
    CRITICAL_PATH("critical path"),
    CRITICAL_PATH_COMPONENT("critical path component"),
    HANDLE_GC_NOTIFICATION("gc notification"),
    LOCAL_ACTION_COUNTS("action count (local)"),
    STARLARK_PARSER("Starlark Parser", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS),
    STARLARK_USER_FN(
        "Starlark user function call",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    STARLARK_BUILTIN_FN(
        "Starlark builtin function call",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    STARLARK_USER_COMPILED_FN(
        "Starlark compiled user function call",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    STARLARK_REPOSITORY_FN(
        "Starlark repository function call",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    STARLARK_THREAD_CONTEXT(
        "Starlark thread context",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    ACTION_FS_STAGING("Staging per-action file system"),
    REMOTE_CACHE_CHECK("remote action cache check"),
    REMOTE_DOWNLOAD("remote output download", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    REMOTE_NETWORK("remote network"),
    FILESYSTEM_TRAVERSAL("filesystem traversal"),
    WORKER_EXECUTION("local execution in worker"),
    WORKER_SETUP("setting up inputs for worker"),
    WORKER_BORROW("borrowing a worker"),
    WORKER_WORKING("waiting for response from worker"),
    WORKER_COPYING_OUTPUTS("copying outputs from worker"),
    CREDENTIAL_HELPER("calling credential helper"),
    CONFLICT_CHECK("Conflict checking"),
    DYNAMIC_LOCK(
        "Acquiring dynamic execution output lock",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.FIFTY_MILLIS
    ),
    REPOSITORY_FETCH("Fetching repository"),
    REPOSITORY_VENDOR("Vendoring repository"),
    REPO_CACHE_GC_WAIT(
        "blocked on repo contents cache GC",
        com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS
    ),
    SPAWN_LOG("logging spawn", com.google.devtools.build.lib.profiler.ProfilerTask.Threshold.TEN_MILLIS),
    RPC("RPC"),
    SKYCACHE("Skycache"),
    WASM_LOAD("load WebAssembly module"),
    WASM_EXEC("execute WebAssembly function"),

    UNKNOWN("Unknown event");

    private object Threshold {
        private val TEN_MILLIS: java.time.Duration? = java.time.Duration.ofMillis(10)
        private val FIFTY_MILLIS: java.time.Duration? = java.time.Duration.ofMillis(50)
    }

    /** Human readable description for the task.  */
    @kotlin.jvm.JvmField
    val description: String?

    /**
     * Threshold for skipping tasks in the profile in nanoseconds, unless --record_full_profiler_data
     * is used.
     */
    @kotlin.jvm.JvmField
    val minDuration: Long

    /** Whether this task collects the slowest instances.  */
    val collectsSlowestInstances: Boolean

    /** True if the metric records VFS operations  */
    @kotlin.jvm.JvmField
    private val vfs: Boolean

    init {
        this.description = description
        this.minDuration = if (minDuration == null) -1 else minDuration.toNanos()
        this.collectsSlowestInstances = collectsSlowestInstances
        this.vfs = this.name().startsWith("VFS")
    }

    /** Whether the Profiler collects the slowest instances of this task.  */
    fun collectsSlowestInstances(): Boolean {
        return collectsSlowestInstances
    }

    fun isVfs(): Boolean {
        return vfs
    }
}
