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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/**
 * A runner for spawns. Implementations can execute spawns on the local machine as a subprocess with
 * or without sandboxing, on a remote machine, or only consult a remote cache.
 * 
 * <h2>Environment Variables</h2>
 * 
 * 
 *  * Implementations MUST set the specified environment variables.
 *  * Implementations MAY add TMPDIR as an additional env variable, if it is not set already.
 *  * If an implementation sets TMPDIR, it MUST be set to an absolute path.
 *  * Implementations MUST NOT add any other environment variables.
 * 
 * 
 * <h2>Command line</h2>
 * 
 * 
 *  * Implementations MUST use the specified command line unmodified by default.
 *  * Implementations MAY modify the specified command line if explicitly requested by the user.
 * 
 * 
 * <h2>Process</h2>
 * 
 * 
 *  * Implementations MUST be thread-safe.
 *  * Implementations MUST ensure that all child processes (including transitive) exit in all
 * cases, including successful completion, interruption, and timeout
 *  * Implementations MUST return the exit code as observed from the subprocess if the subprocess
 * exits naturally; they MUST not throw an exception for non-zero exit codes
 *  * Implementations MUST be interruptible; they MUST throw [InterruptedException] from
 * [.exec] when interrupted
 *  * Implementations MUST apply the specified timeout to the execution of the subprocess
 * 
 *  * If no timeout is specified, the implementation MAY apply an implementation-specific
 * timeout
 *  * If the specified timeout is larger than an implementation-dependent maximum, then the
 * implementation MUST throw [IllegalArgumentException]; it MUST not silently
 * change the timeout to a smaller value
 *  * If the timeout is exceeded, the implementation MUST throw TimeoutException, with the
 * timeout that was applied to the subprocess (TODO)
 * 
 * 
 * 
 * <h2>Optimistic Concurrency</h2>
 * 
 * Bazel may choose to execute a spawn using multiple [SpawnRunner] implementations
 * simultaneously in order to minimize total latency. This is especially useful for builds with few
 * actions where remotely executing the actions incurs high round trip times.
 * 
 * 
 *  * All implementations MUST call [SpawnExecutionContext.lockOutputFiles] before writing
 * to any of the output files, but may write to stdout and stderr without calling it. Instead,
 * all callers must provide temporary locations for stdout & stderr if they ever call multiple
 * [SpawnRunner] implementations concurrently. Spawn runners that use the local machine
 * MUST either call it before starting the subprocess, or ensure that subprocesses write to
 * temporary locations (for example by running in a mount namespace) and then copy or move the
 * outputs into place.
 *  * Implementations SHOULD delay calling [SpawnExecutionContext.lockOutputFiles] until
 * just before writing.
 * 
 */
interface SpawnRunner {
    /**
     * Used to report progress on the current spawn. This is mainly used to report the current state
     * of the subprocess to the user, but may also be used to trigger parallel execution. For example,
     * a dynamic scheduler may use the signal that there was a cache miss to start parallel execution
     * of the same Spawn - also see the [SpawnRunner] documentation section on "optimistic
     * concurrency".
     * 
     * 
     * [SpawnRunner] implementations should post a progress status before any potentially
     * long-running operation.
     */
    interface ProgressStatus {
        /** Post this progress event to the given [ExtendedEventHandler].  */
        fun postTo(
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
            action: ActionExecutionMetadata?
        )
    }

    /**
     * A context that binds a [Spawn] to a [SpawnRunner].
     * 
     * 
     * This interface may change without notice.
     * 
     * 
     * Implementations must be at least thread-compatible, i.e., they must be safe as long as each
     * instance is only used within a single thread. Different instances of the same class may be used
     * by different threads, so they MUST not call any shared non-thread-safe objects.
     */
    interface SpawnExecutionContext {
        /**
         * Returns an id for this spawn, unique within the context of this Bazel server instance, to be
         * used for logging. Note that a single spawn may be passed to multiple [SpawnRunner]
         * implementations, so any log entries should also contain the identity of the spawn runner
         * implementation.
         */
        @kotlin.jvm.JvmField
        val id: Int

        /**
         * Sets the remote or disk cache digest for this spawn.
         * 
         * 
         * This is the digest that identifies a spawn result stored in a remote or disk cache. It
         * should be set whenever the spawn is looked up in the cache, and later retrieved via [ ][.getDigest] to be incorporated in the [SpawnResult] for a spawn that was executed due
         * to a cache miss.
         * 
         * @throws IllegalStateException if called multiple times with different digests.
         */
        fun setDigest(digest: Digest?)

        /**
         * Returns the remote or disk cache digest for this spawn.
         * 
         * 
         * Only available if [.setDigest] has been previously called.
         */
        @kotlin.jvm.JvmField
        val digest: Digest?

        /**
         * Prefetches the Spawns input files to the local machine. There are cases where Bazel runs on a
         * network file system, and prefetching the files in parallel is a significant performance win.
         * This should only be called by local strategies when local execution is imminent.
         */
        fun prefetchInputs(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>

        /**
         * Prefetches the Spawns input files to the local machine and wait to finish.
         * 
         * @see .prefetchInputs
         */
        @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
        fun prefetchInputsAndWait() {
            val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> = prefetchInputs()
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(com.google.devtools.build.lib.profiler.ProfilerTask.REMOTE_DOWNLOAD, "stage remote inputs")
                    .use { s ->
                        future.get()
                    }
            } catch (e: ExecutionException) {
                val cause: Throwable? = e.getCause()
                if (cause != null) {
                    if (cause is BulkTransferException) {
                        cause
                            .getLostArtifacts(this.inputMetadataProvider::getInput)
                            .throwIfNotEmpty()
                        throw EnvironmentalExecException(
                            cause,
                            FailureDetail.newBuilder()
                                .setMessage("Failed to fetch blobs because of a remote cache error.")
                                .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.REMOTE_CACHE_EVICTED))
                                .build()
                        )
                    }
                    com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                    com.google.common.base.Throwables.throwIfInstanceOf<X?>(cause, ExecException::class.java)
                    com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                        cause,
                        java.lang.InterruptedException::class.java
                    )
                    com.google.common.base.Throwables.throwIfInstanceOf<java.lang.RuntimeException?>(
                        cause,
                        java.lang.RuntimeException::class.java
                    )
                }
                throw IOException(e)
            } catch (e: java.lang.InterruptedException) {
                future.cancel( /* mayInterruptIfRunning= */true)
                throw e
            }
        }

        /**
         * The input file metadata cache for this specific spawn, which can be used to efficiently
         * obtain file digests and sizes.
         */
        @kotlin.jvm.JvmField
        val inputMetadataProvider: InputMetadataProvider?

        val pathResolver: ArtifactPathResolver
            /** The [ArtifactPathResolver] to use when directly writing output files.  */
            get() = ArtifactPathResolver.IDENTITY

        /**
         * All implementations must call this method before writing to the provided stdout / stderr or
         * to any of the output file locations. This method is used to coordinate - implementations must
         * throw an [InterruptedException] for all but one caller.
         * 
         * 
         * This method may look at various outputs from the finished action to decide whether to grab
         * the lock. It may decide that the failure is of a character where the other branch should be
         * allowed to finish this action. In that case, this method will throw [ ] to stop itself.
         * 
         * @param exitCode The exit code from running the command. This and the other parameters are
         * used only to determine whether to ignore failures, so pass 0 if you know the command was
         * successful or you don't yet have success information. The exit code may be from a single
         * action process or from a worker that died.
         * @param errorMessage The error messages returned from the command, possibly in other ways than
         * through stdout/err.
         * @param outErr The location of the stdout and stderr files from the command. May be null.
         * @throws InterruptedException if the error info indicates an error we can ignore or if we got
         * interrupted before we finished.
         */
        @Throws(java.lang.InterruptedException::class)
        fun lockOutputFiles(exitCode: Int, errorMessage: String?, outErr: FileOutErr?)

        /**
         * Returns whether this spawn may be executing concurrently under multiple spawn runners. If so,
         * [.lockOutputFiles] may raise [InterruptedException].
         */
        fun speculating(): Boolean

        /** Returns the timeout that should be applied for the given [Spawn] instance.  */
        @kotlin.jvm.JvmField
        val timeout: java.time.Duration?

        /** The files to which to write stdout and stderr.  */
        @kotlin.jvm.JvmField
        val fileOutErr: FileOutErr?

        /**
         * Returns a sorted map from input paths to action inputs.
         * 
         * 
         * Resolves cases where a single input of the [Spawn] gives rise to multiple files in
         * the input tree, for example, tree artifacts, runfiles trees and `Fileset` input
         * manifests.
         * 
         * 
         * `baseDirectory` is prepended to every path in the input key. This is useful if the
         * mapping is used in a context where the directory relative to which the keys are interpreted
         * is not the same as the execroot.
         */
        fun getInputMapping(
            baseDirectory: PathFragment?, willAccessRepeatedly: Boolean
        ): SortedMap<PathFragment?, ActionInput?>?

        /** Reports a progress update to the Spawn strategy.  */
        fun report(progress: ProgressStatus?)

        /**
         * Returns the context registered for the given identifying type or `null` if none was
         * registered.
         */
        fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>?): T?

        /** Returns whether rewinding is enabled.  */
        val isRewindingEnabled: Boolean

        /** Throws if rewinding is enabled and lost inputs have been detected.  */
        @Throws(LostInputsExecException::class)
        fun checkForLostInputs()

        /** Returns action-scoped file system or `null` if it doesn't exist.  */
        @kotlin.jvm.JvmField
        val actionFileSystem: com.google.devtools.build.lib.vfs.FileSystem?

        /** Returns the environment of the Bazel client.  */
        @kotlin.jvm.JvmField
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?
    }

    /** Partial implementation of [SpawnExecutionContext].  */
    class AbstractSpawnExecutionContext protected constructor(
        spawn: Spawn?,
        actionExecutionContext: ActionExecutionContext?
    ) : SpawnExecutionContext {
        protected val spawn: Spawn
        protected val actionExecutionContext: ActionExecutionContext

        init {
            this.spawn = com.google.common.base.Preconditions.checkNotNull<Spawn>(spawn)
            this.actionExecutionContext =
                com.google.common.base.Preconditions.checkNotNull<ActionExecutionContext>(actionExecutionContext)
        }

        override fun prefetchInputs(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
            if (Spawns.shouldPrefetchInputsForLocalExecution(spawn)) {
                return actionExecutionContext
                    .getActionInputPrefetcher()
                    .prefetchFiles(
                        spawn.getResourceOwner(),
                        spawn,
                        {
                            getInputMapping(PathFragment.EMPTY_FRAGMENT,  /* willAccessRepeatedly= */true)
                                .values()
                        },
                        this.inputMetadataProvider,
                        Priority.MEDIUM,
                        Reason.INPUTS
                    )
            }

            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        override fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>?): T? {
            return actionExecutionContext.getContext(identifyingType)
        }

        override fun getPathResolver(): ArtifactPathResolver {
            return actionExecutionContext.getPathResolver()
        }

        override fun getFileOutErr(): FileOutErr {
            return actionExecutionContext.getFileOutErr()
        }

        override fun isRewindingEnabled(): Boolean {
            return actionExecutionContext.isRewindingEnabled()
        }

        @Throws(LostInputsExecException::class)
        override fun checkForLostInputs() {
            try {
                actionExecutionContext.checkForLostInputs()
            } catch (e: LostInputsActionExecutionException) {
                throw e.toExecException()
            }
        }

        override fun getActionFileSystem(): com.google.devtools.build.lib.vfs.FileSystem? {
            return actionExecutionContext.getActionFileSystem()
        }

        override fun getClientEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
            return actionExecutionContext.getClientEnv()
        }
    }

    /**
     * Run the given spawn.
     * 
     * @param spawn the spawn to run
     * @param context the spawn execution context
     * @return the result from running the spawn
     * @throws InterruptedException if the calling thread was interrupted, or if the runner could not
     * lock the output files (see [SpawnExecutionContext.lockOutputFiles])
     * @throws IOException if something went wrong reading or writing to the local file system
     * @throws ExecException if the request is malformed
     */
    @Throws(java.lang.InterruptedException::class, IOException::class, ExecException::class)
    fun exec(spawn: Spawn?, context: SpawnExecutionContext?): SpawnResult?

    /** Returns whether this SpawnRunner supports executing the given Spawn.  */
    fun canExec(spawn: Spawn?): Boolean

    /** Returns whether this SpawnRunner handles caching of actions internally.  */
    fun handlesCaching(): Boolean

    /** Returns the name of the SpawnRunner.  */
    @kotlin.jvm.JvmField
    val name: String?

    /**
     * Removes any files or directories that this spawn runner may have put in the sandbox base.
     * 
     * 
     * It is important that this function only removes entries that may have been generated by this
     * build, not any possible entries that a future build may generate.
     * 
     * @param sandboxBase path to the base of the sandbox tree where the spawn runner may have created
     * entries
     * @param treeDeleter scheduler for tree deletions
     * @throws IOException if there are problems deleting the entries
     */
    @Throws(IOException::class)
    fun cleanupSandboxBase(sandboxBase: com.google.devtools.build.lib.vfs.Path?, treeDeleter: TreeDeleter?) {
    }

    /**
     * Returns a [SpawnResult.Builder] prepopulated with the runner name and the spawn digest.
     */
    fun getSpawnResultBuilder(context: SpawnExecutionContext): SpawnResult.Builder {
        return Builder().setRunnerName(this.name).setDigest(context.digest)
    }
}
