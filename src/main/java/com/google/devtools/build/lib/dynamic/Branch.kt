// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import java.util.concurrent.Callable
import java.util.concurrent.Future

/**
 * Wraps the execution of a function that is supposed to execute a spawn via a strategy and only
 * updates the stdout/stderr files if this spawn succeeds.
 */
internal abstract class Branch(
    context: ActionExecutionContext,
    spawn: Spawn?,
    strategyThatCancelled: AtomicReference<DynamicMode?>?,
    options: DynamicExecutionOptions
) : Callable<ImmutableList<SpawnResult?>?> {
    /**
     * True if this branch is still starting up, i.e. didn't get to the inner part of [ ][.callImpl] yet.
     */
    protected val starting: AtomicBoolean = AtomicBoolean(true)

    /** The [Spawn] this branch is running.  */
    protected val spawn: Spawn?

    /**
     * The [SettableFuture] with the results from running the spawn. Must not be null if
     * execution succeeded.
     */
    val future: SettableFuture<ImmutableList<SpawnResult?>?> = SettableFuture.create<ImmutableList<SpawnResult?>?>()

    /**
     * The strategy (local or remote) that cancelled the other one. Null until one has been cancelled.
     * This object is shared between the local and remote branch of an action.
     */
    protected val strategyThatCancelled: AtomicReference<DynamicMode?>?

    /**
     * Semaphore that indicates whether this branch is done, i.e. either completed or cancelled. This
     * is needed to wait for the branch to finish its own cleanup (e.g. terminating subprocesses) once
     * it has been cancelled.
     */
    protected val done: Semaphore = Semaphore(0)

    protected val options: DynamicExecutionOptions
    protected val context: ActionExecutionContext

    protected var otherBranch: Branch? = null

    /**
     * Creates a new branch of dynamic execution.
     * 
     * @param context the action execution context given to the dynamic strategy, used to obtain the
     * final location of the stdout/stderr
     */
    init {
        this.context = context
        this.spawn = spawn
        this.strategyThatCancelled = strategyThatCancelled
        this.options = options
    }

    fun isDone(): Boolean {
        return future.isDone()
    }

    val doneSemaphore: Semaphore
        /** Returns the `Semaphore` indicating whether this branch is done.  */
        get() = done

    val isCancelled: Boolean
        /** Returns whether this branch has already been cancelled.  */
        get() = future.isCancelled()

    /** Cancels this branch. Equivalent to `Future.cancel(true)`.  */
    fun cancel(): Boolean {
        return future.cancel(true)
    }

    @get:Throws(ExecutionException::class, InterruptedException::class)
    val results: ImmutableList<SpawnResult>?
        /** Gets the results from this branch, when available. Behaves like [Future.get]  */
        get() = future.get()

    fun getSpawn(): Spawn? {
        return spawn
    }

    abstract val mode: DynamicMode?

    /** Returns a human-readable description of what we can tell about the state of this Future.  */
    fun branchState(): String {
        return ((if (this.isCancelled) "cancelled" else "not cancelled")
                + " and "
                + (if (isDone()) "done" else "not done"))
    }

    /** Executes this branch using the provided executor.  */
    fun execute(executor: ListeningExecutorService) {
        future.setFuture(executor.submit<ImmutableList<SpawnResult?>?>(this))
    }

    /** Sets up the [Future] used in the current branch to know what other branch to cancel.  */
    fun prepareFuture(otherBranch: Branch) {
        this.otherBranch = otherBranch
        future.addListener(
            Runnable {
                if (starting.compareAndSet(true, false)) {
                    // If the current branch got cancelled before even starting, we release its semaphore
                    // for it.
                    done.release()
                }
                // If the current branch succeeds, there is no need to keep the other branch running.
                // If the current branch fails, cancel the other branch as well. However, that one may
                // in turn cancel us, thus causing an interruption. Don't consider that a failure as
                // we otherwise risk canceling both branches.
                val state = future.state()
                if (state == Future.State.SUCCESS
                    || (state == Future.State.FAILED
                            && future.exceptionNow() !is InterruptedException)
                ) {
                    otherBranch.cancel()
                }
                if (options.getDebugSpawnScheduler()) {
                    logger.atInfo().log(
                        "In listener callback, the future of the remote branch is %s",
                        future.state().name()
                    )
                    try {
                        future.get()
                    } catch (e: InterruptedException) {
                        logger.atInfo().withCause(e).log(
                            "The future of the remote branch failed with an exception."
                        )
                    } catch (e: ExecutionException) {
                        logger.atInfo().withCause(e).log(
                            "The future of the remote branch failed with an exception."
                        )
                    }
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * Hook to execute a spawn using an arbitrary strategy.
     * 
     * @param context the action execution context where the spawn can write its stdout/stderr. The
     * location of these files is specific to this branch.
     * @return the spawn results if execution was successful
     * @throws InterruptedException if the branch was cancelled or an interrupt was caught
     * @throws ExecException if the spawn execution fails
     */
    @Throws(InterruptedException::class, ExecException::class)
    abstract fun callImpl(context: ActionExecutionContext?): ImmutableList<SpawnResult?>?

    /**
     * Executes the [.callImpl] hook and handles stdout/stderr.
     * 
     * @return the spawn results if execution was successful
     * @throws InterruptedException if the branch was cancelled or an interrupt was caught
     * @throws ExecException if the spawn execution fails
     */
    @Throws(InterruptedException::class, ExecException::class)
    override fun call(): ImmutableList<SpawnResult?> {
        val fileOutErr: FileOutErr = getSuffixedFileOutErr(context.getFileOutErr(), "." + this.mode.name())

        var results: ImmutableList<SpawnResult?>? = null
        var exception: ExecException? = null
        try {
            results = callImpl(context.withFileOutErr(fileOutErr))
        } catch (e: ExecException) {
            exception = e
        } finally {
            try {
                fileOutErr.close()
            } catch (ignored: IOException) {
                // Nothing we can do here.
            }
        }

        moveFileOutErr(fileOutErr, context.getFileOutErr())

        if (exception != null) {
            throw exception
        } else {
            Preconditions.checkNotNull<ImmutableList<SpawnResult?>?>(results)
            return results
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Moves a set of stdout/stderr files over another one. Errors during the move are logged and
         * swallowed.
         * 
         * @param from the source location
         * @param to the target location
         */
        private fun moveFileOutErr(from: FileOutErr, to: FileOutErr) {
            try {
                if (from.getOutputPath().exists()) {
                    FileSystemUtils.moveFile(from.getOutputPath(), to.getOutputPath())
                }
                if (from.getErrorPath().exists()) {
                    FileSystemUtils.moveFile(from.getErrorPath(), to.getErrorPath())
                }
            } catch (e: IOException) {
                logger.atWarning().withCause(e).log("Could not move action logs from execution")
            }
        }

        private fun getSuffixedFileOutErr(fileOutErr: FileOutErr, suffix: String?): FileOutErr {
            val outDir = Preconditions.checkNotNull<Path>(fileOutErr.getOutputPath().getParentDirectory())
            val outBaseName: String? = fileOutErr.getOutputPath().getBaseName()
            val errDir = Preconditions.checkNotNull<Path>(fileOutErr.getErrorPath().getParentDirectory())
            val errBaseName: String? = fileOutErr.getErrorPath().getBaseName()
            return FileOutErr(
                outDir.getChild(outBaseName + suffix), errDir.getChild(errBaseName + suffix)
            )
        }
    }
}
