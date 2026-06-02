// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics.WorkerStatus

/**
 * This is a state machine instance that encapsulates the status of the worker process, and provides
 * a mechanism to signal to Bazel when to kill a worker.
 */
class WorkerProcessStatus {
    /** The status of the worker process.  */
    enum class Status {
        /**
         * Used as a starting value, before the worker process has been created by Bazel.
         * 
         * 
         * This state is not logged in the BEP as `WorkerSpawnRunner#initializeMetrics` is only
         * called after `#prepareExecution` (which sets the status to ALIVE).
         */
        NOT_STARTED( /* priority= */0, WorkerStatus.NOT_STARTED),

        /**
         * Worker process has been created by Bazel (the process might not be immediately measurable).
         */
        ALIVE( /* priority= */1, WorkerStatus.ALIVE),

        /** Intermediate states: Bazel has marked this worker process to be killed.  */
        PENDING_KILL_DUE_TO_UNKNOWN(2, WorkerStatus.ALIVE, "KILLED_UNKNOWN"),
        PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION( /* priority= */
            3, WorkerStatus.ALIVE, "KILLED_DUE_TO_INTERRUPTED_EXCEPTION"
        ),
        PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION( /* priority= */
            3, WorkerStatus.ALIVE, "KILLED_DUE_TO_USER_EXEC_EXCEPTION"
        ),
        PENDING_KILL_DUE_TO_IO_EXCEPTION( /* priority= */
            3, WorkerStatus.ALIVE, "KILLED_DUE_TO_IO_EXCEPTION"
        ),
        PENDING_KILL_DUE_TO_MEMORY_PRESSURE( /* priority= */
            4, WorkerStatus.ALIVE, "KILLED_DUE_TO_MEMORY_PRESSURE"
        ),

        /**
         * Semi-terminal status: Bazel has determined that worker process has already been killed due to
         * some unknown reason; if a more specific reason (below) comes along, we can transition to
         * those statuses.
         */
        KILLED_UNKNOWN( /* priority= */5, WorkerStatus.KILLED_UNKNOWN),

        /** Terminal statuses: The worker process has been killed and Bazel is aware of the reason.  */ // Bazel killed the worker due to an InterruptedException, can happen when the remote branch
        // interrupts the local branch after winning the race in dynamic execution.
        KILLED_DUE_TO_INTERRUPTED_EXCEPTION( /* priority= */
            6, WorkerStatus.KILLED_DUE_TO_INTERRUPTED_EXCEPTION
        ),

        // Bazel killed the worker due to a UserExecException.
        KILLED_DUE_TO_USER_EXEC_EXCEPTION( /* priority= */
            6, WorkerStatus.KILLED_DUE_TO_USER_EXEC_EXCEPTION
        ),

        // Bazel killed the worker due to an IOException.
        KILLED_DUE_TO_IO_EXCEPTION( /* priority= */6, WorkerStatus.KILLED_DUE_TO_IO_EXCEPTION),

        // This can be a result of Bazel forcibly killing the worker process, which might result in
        // other exceptions caught in the execution threads (as enumerated by the other killed statuses
        // above). Thus, this has the highest priority so that we can override that and set the correct
        // reason why the worker was killed.
        KILLED_DUE_TO_MEMORY_PRESSURE( /* priority= */7, WorkerStatus.KILLED_DUE_TO_MEMORY_PRESSURE);

        // The priority of a status determines whether another status can be used to override and
        // update it.
        private val priority: Int
        private val killedStatus: java.util.Optional<String?>
        private val workerStatus: WorkerStatus?

        constructor(priority: Int, workerStatus: WorkerStatus?, killedStatus: String) {
            this.priority = priority
            this.workerStatus = workerStatus
            this.killedStatus = java.util.Optional.of<String?>(killedStatus)
        }

        constructor(priority: Int, workerStatus: WorkerStatus?) {
            this.priority = priority
            this.workerStatus = workerStatus
            this.killedStatus = java.util.Optional.empty<String?>()
        }

        fun killedStatus(): Status {
            if (killedStatus.isEmpty()) {
                return this
            }
            return com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.valueOf(killedStatus.get())
        }

        fun toWorkerStatus(): WorkerStatus? {
            return workerStatus
        }
    }

    private var status: Status

    private var workerCode: java.util.Optional<Code?> = java.util.Optional.empty<Code?>()

    fun get(): Status {
        return status
    }

    @get:kotlin.jvm.Synchronized
    val isValid: Boolean
        // A status is invalid if it is killed or marked to be killed later.
        get() = status == com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.NOT_STARTED || status == com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.ALIVE

    val isPendingEviction: Boolean
        get() = status == com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE

    fun getWorkerCode(): java.util.Optional<Code?> {
        return workerCode
    }

    private val isPendingKill: Boolean
        get() = PENDING_KILL_STATUSES.contains(status)

    init {
        this.status = com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.NOT_STARTED
    }

    val isKilled: Boolean
        get() = KILLED_STATUSES.contains(status)

    /**
     * Attempts to update the status to its corresponding kill status. Should be called **after** the
     * process is destroyed by Bazel.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    fun setKilled(): Boolean {
        if (this.isPendingKill) {
            status = status.killedStatus()
            return true
        }
        return false
    }

    /**
     * Returns whether the WorkerStatus was successfully updated after attempting to update it to a
     * given Status.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    fun maybeUpdateStatus(toStatus: Status): Boolean {
        if (canTransitionTo(toStatus)) {
            this.status = toStatus
            return true
        }
        return false
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @kotlin.jvm.Synchronized
    fun maybeUpdateStatus(toStatus: Status, workerCode: Code): Boolean {
        this.workerCode = java.util.Optional.of<Code?>(workerCode)
        return maybeUpdateStatus(toStatus)
    }

    /**
     * Returns whether a state transition can occur.
     * 
     * @param toStatus the next state attempted to transition to.
     */
    private fun canTransitionTo(toStatus: Status): Boolean {
        return status.priority < toStatus.priority
    }

    fun toWorkerStatus(): WorkerStatus? {
        return status.toWorkerStatus()
    }

    companion object {
        private val PENDING_KILL_STATUSES: com.google.common.collect.ImmutableSet<Status?> =
            com.google.common.collect.ImmutableSet.of<Status?>(
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_UNKNOWN,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_IO_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE
            )

        private val KILLED_STATUSES: com.google.common.collect.ImmutableSet<Status?> =
            com.google.common.collect.ImmutableSet.of<Status?>(
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_UNKNOWN,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_DUE_TO_IO_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_DUE_TO_INTERRUPTED_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_DUE_TO_USER_EXEC_EXCEPTION,
                com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.KILLED_DUE_TO_MEMORY_PRESSURE
            )
    }
}
