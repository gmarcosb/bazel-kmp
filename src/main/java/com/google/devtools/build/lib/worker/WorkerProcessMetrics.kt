// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics

/**
 * Contains data about worker statistics during execution. This class contains data for [ ]
 */
class WorkerProcessMetrics(
  private val workerIds: MutableList<Int?>,
  @kotlin.jvm.JvmField val processId: Long,
  status: WorkerProcessStatus,
  mnemonic: String?,
  isMultiplex: Boolean,
  isSandbox: Boolean,
  workerKeyHash: Int
) {
    @kotlin.jvm.JvmField
    val mnemonic: String?

    @kotlin.jvm.JvmField
    val isMultiplex: Boolean

    val isSandboxed: Boolean

    var isMeasurable: Boolean = false
        private set

    @kotlin.jvm.JvmField
    val workerKeyHash: Int

    var usedMemoryInKb: Int = 0
        private set

    // Memory usage prior to this invocation, useful to calculate memory deltas as a result of a
    // particular invocations.
    private var priorMemoryInKb = 0

    private var lastCallTime: java.util.Optional<Instant?> = java.util.Optional.empty<Instant?>()

    private var lastCollectedTime: java.util.Optional<Instant?> = java.util.Optional.empty<Instant?>()

    private val status: WorkerProcessStatus

    /** Whether the worker process was created during the current invocation.  */
    var isNewlyCreated: Boolean = true
        private set
    private val actionsExecuted: AtomicInteger = AtomicInteger(0)

    private var priorActionsExecuted = 0

    init {
        this.status = status
        this.mnemonic = mnemonic
        this.isMultiplex = isMultiplex
        this.isSandboxed = isSandbox
        this.workerKeyHash = workerKeyHash
    }

    constructor(
        workerId: Int,
        processId: Long,
        status: WorkerProcessStatus,
        mnemonic: String?,
        isMultiplex: Boolean,
        isSandbox: Boolean,
        workerKeyHash: Int
    ) : this(
        java.util.ArrayList<Int?>(java.util.Arrays.asList<Int?>(workerId)),
        processId,
        status,
        mnemonic,
        isMultiplex,
        isSandbox,
        workerKeyHash
    )

    fun maybeAddWorkerId(workerId: Int, status: WorkerProcessStatus?) {
        // Multiplex workers have multiple worker ids, make sure not to include duplicate worker ids.
        if (workerIds.contains(workerId)) {
            return
        }
        workerIds.add(workerId)
    }

    fun addCollectedMetrics(memoryInKb: Int, collectionTime: Instant) {
        this.usedMemoryInKb = memoryInKb
        this.isMeasurable = true
        this.lastCollectedTime = java.util.Optional.of<Instant?>(collectionTime)
    }

    /** Reset relevant internal states before each command.  */
    fun onBeforeCommand() {
        this.isNewlyCreated = false
        priorActionsExecuted = actionsExecuted.get()
        priorMemoryInKb = this.usedMemoryInKb
    }

    fun incrementActionsExecuted() {
        actionsExecuted.incrementAndGet()
    }

    fun getActionsExecuted(): Int {
        return actionsExecuted.get()
    }

    fun getLastCallTime(): java.util.Optional<Instant?> {
        return lastCallTime
    }

    fun getLastCollectedTime(): java.util.Optional<Instant?> {
        return lastCollectedTime
    }

    fun setLastCallTime(lastCallTime: Instant) {
        this.lastCallTime = java.util.Optional.of<Instant?>(lastCallTime)
    }

    fun getWorkerIds(): com.google.common.collect.ImmutableList<Int?> {
        return com.google.common.collect.ImmutableList.copyOf<Int?>(workerIds)
    }

    fun getStatus(): WorkerProcessStatus {
        return status
    }

    fun toProto(): WorkerMetrics {
        val statsBuilder: WorkerStats.Builder =
            WorkerStats.newBuilder()
                .setWorkerMemoryInKb(this.usedMemoryInKb)
                .setPriorWorkerMemoryInKb(priorMemoryInKb)
        if (lastCollectedTime.isPresent()) {
            statsBuilder.setCollectTimeInMs(lastCollectedTime.get().toEpochMilli())
        }
        if (lastCallTime.isPresent()) {
            statsBuilder.setLastActionStartTimeInMs(lastCallTime.get().toEpochMilli())
        }

        val builder: WorkerMetrics.Builder =
            WorkerMetrics.newBuilder()
                .addAllWorkerIds(workerIds)
                .setProcessId(processId.toInt())
                .setMnemonic(mnemonic)
                .setIsSandbox(this.isSandboxed)
                .setIsMultiplex(isMultiplex)
                .setIsMeasurable(isMeasurable)
                .setWorkerKeyHash(workerKeyHash)
                .setWorkerStatus(status.toWorkerStatus())
                .setActionsExecuted(actionsExecuted.get())
                .setPriorActionsExecuted(priorActionsExecuted)
                .addWorkerStats(statsBuilder.build())

        if (status.getWorkerCode().isPresent()) {
            builder.setCode(status.getWorkerCode().get())
        }

        return builder.build()
    }
}
