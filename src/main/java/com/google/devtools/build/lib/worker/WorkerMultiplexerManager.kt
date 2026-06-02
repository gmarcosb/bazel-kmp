// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.UserExecException

/**
 * A manager to instantiate and destroy multiplexers. There should only be one `WorkerMultiplexer` corresponding to workers with the same `WorkerKey`. If the `WorkerMultiplexer` has been constructed, other workers should point to the same one.
 */
object WorkerMultiplexerManager {
    /**
     * A map from the hash of `WorkerKey` objects to the corresponding information about the
     * multiplexer instance.
     */
    private val multiplexerInstance: MutableMap<WorkerKey?, InstanceInfo> = HashMap<WorkerKey?, InstanceInfo>()

    /**
     * A counter used to provide unique IDs across sandboxed multiplexer instances. It is used in
     * determining the workdir for the multiplexer process. This is analogous to the `pidCounter` in `WorkerFactory`. It is ok to use an `AtomicInteger` here for the
     * same reasons as it is there: the counter is only incremented when spawning a new multiplexer,
     * so even in the worst case of workers quitting after each action it shouldn't overflow.
     */
    private val multiplexerIdCounter: AtomicInteger = AtomicInteger(1)

    /**
     * Returns a `WorkerMultiplexer` instance to `WorkerProxy`. `WorkerProxy`
     * objects with the same `WorkerKey` talk to the same `WorkerMultiplexer`. Also,
     * record how many `WorkerProxy` objects are talking to this `WorkerMultiplexer`.
     */
    @kotlin.jvm.Synchronized
    fun getInstance(key: WorkerKey?, logFile: com.google.devtools.build.lib.vfs.Path?): WorkerMultiplexer {
        val instanceInfo: InstanceInfo =
            multiplexerInstance.computeIfAbsent(
                key,
                java.util.function.Function { k: WorkerKey? ->
                    InstanceInfo(
                        WorkerMultiplexer(logFile, k, multiplexerIdCounter.getAndIncrement())
                    )
                })
        instanceInfo.increaseRefCount()
        return instanceInfo.getWorkerMultiplexer()
    }

    fun beforeCommand(reporter: Reporter?) {
        setReporter(reporter)
    }

    fun afterCommand() {
        setReporter(null)
    }

    /**
     * Sets the reporter for all existing multiplexer instances. This allows reporting problems
     * encountered while fetching an instance, e.g. during WorkerProxy validation.
     */
    @kotlin.jvm.Synchronized
    private fun setReporter(reporter: EventHandler?) {
        for (m in multiplexerInstance.values()) {
            m.workerMultiplexer.setReporter(reporter)
        }
    }

    /** Removes a `WorkerProxy` instance and reference count since it is no longer in use.  */
    @kotlin.jvm.Synchronized
    @Throws(UserExecException::class)
    fun removeInstance(key: WorkerKey) {
        val instanceInfo: InstanceInfo = multiplexerInstance.get(key)!!
        if (instanceInfo == null) {
            throw createUserExecException(
                java.lang.String.format(
                    "Attempting to remove non-existent %s multiplexer instance.", key.getMnemonic()
                ),
                Code.MULTIPLEXER_INSTANCE_REMOVAL_FAILURE
            )
        }
        instanceInfo.decreaseRefCount()
        if (instanceInfo.refCount == 0) {
            instanceInfo.getWorkerMultiplexer().destroyMultiplexer()
            multiplexerInstance.remove(key)
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(UserExecException::class)
    fun getMultiplexer(key: WorkerKey?): WorkerMultiplexer {
        val instanceInfo: InstanceInfo = multiplexerInstance.get(key)!!
        if (instanceInfo == null) {
            throw createUserExecException(
                "Accessing non-existent multiplexer instance.", Code.MULTIPLEXER_DOES_NOT_EXIST
            )
        }
        return instanceInfo.getWorkerMultiplexer()
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(UserExecException::class)
    fun getRefCount(key: WorkerKey?): Int {
        val instanceInfo: InstanceInfo = multiplexerInstance.get(key)!!
        if (instanceInfo == null) {
            throw createUserExecException(
                "Accessing non-existent multiplexer instance.", Code.MULTIPLEXER_DOES_NOT_EXIST
            )
        }
        return instanceInfo.refCount
    }

    @kotlin.jvm.JvmStatic
    @get:com.google.common.annotations.VisibleForTesting
    val instanceCount: Int
        get() = multiplexerInstance.keySet().size()

    private fun createUserExecException(message: String?, detailedCode: Code?): UserExecException {
        return UserExecException(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setWorker(FailureDetails.Worker.newBuilder().setCode(detailedCode))
                .build()
        )
    }

    /** Resets the instances. For testing only.  */
    @kotlin.jvm.JvmStatic
    @com.google.common.annotations.VisibleForTesting
    fun resetForTesting() {
        for (i in multiplexerInstance.values()) {
            i.workerMultiplexer.destroyMultiplexer()
        }
        multiplexerInstance.clear()
    }

    /** Injects a given WorkerMultiplexer into the instance map with refcount 0. For testing only.  */
    @com.google.common.annotations.VisibleForTesting
    @kotlin.jvm.Synchronized
    fun injectForTesting(key: WorkerKey?, multiplexer: WorkerMultiplexer) {
        multiplexerInstance.put(key, InstanceInfo(multiplexer))
    }

    /** Contains the WorkerMultiplexer instance and reference count.  */
    internal class InstanceInfo(workerMultiplexer: WorkerMultiplexer) {
        private val workerMultiplexer: WorkerMultiplexer
        var refCount: Int
            private set

        init {
            this.workerMultiplexer = workerMultiplexer
            this.refCount = 0
        }

        fun increaseRefCount() {
            refCount = refCount + 1
        }

        fun decreaseRefCount() {
            refCount = refCount - 1
        }

        fun getWorkerMultiplexer(): WorkerMultiplexer {
            return workerMultiplexer
        }
    }
}
