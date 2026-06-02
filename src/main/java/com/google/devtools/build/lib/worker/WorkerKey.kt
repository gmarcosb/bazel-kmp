// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/**
 * Data container that uniquely identifies a kind of worker process and is used as the key for the
 * [WorkerPoolImpl].
 * 
 * 
 * We expect a small number of WorkerKeys per mnemonic. Unbounded creation of WorkerKeys will
 * break various things as well as render the workers less useful.
 */
class WorkerKey(
    args: com.google.common.collect.ImmutableList<String?>?,
    env: com.google.common.collect.ImmutableMap<String?, String?>?,
    execRoot: com.google.devtools.build.lib.vfs.Path?,
    mnemonic: String?,
    workerFilesCombinedHash: com.google.common.hash.HashCode?,
    workerFilesWithDigests: SortedMap<PathFragment?, ByteArray?>?,
    /** If true, the workers run inside a sandbox.  */
    val isSandboxed: Boolean,
    /** If true, the sandbox contents are tracked in memory to speed up cleanup.  */
    private val useInMemoryTracking: Boolean,
    /** A WorkerProxy will be instantiated if true, instantiate a regular Worker if false.  */
    val isMultiplex: Boolean,
    /** If true, the workers for this key are able to cancel work requests.  */
    private val cancellable: Boolean,
    protocolFormat: WorkerProtocolFormat
) {
    /** Build options.  */
    private val args: com.google.common.collect.ImmutableList<String?>

    /** Environment variables.  */
    private val env: com.google.common.collect.ImmutableMap<String?, String?>

    /** Execution root of Bazel process.  */
    private val execRoot: com.google.devtools.build.lib.vfs.Path

    /** Mnemonic of the worker.  */
    @kotlin.jvm.JvmField
    val mnemonic: String

    /**
     * These are used during validation whether a worker is still usable. They are not used to
     * uniquely identify a kind of worker, thus it is not to be used by the .equals() / .hashCode()
     * methods.
     */
    private val workerFilesCombinedHash: com.google.common.hash.HashCode

    /** Worker files with the corresponding digest.  */
    private val workerFilesWithDigests: SortedMap<PathFragment?, ByteArray?>

    /** Returns true if workers are sandboxed.  */

    /**
     * Cached value for the hash of this key, because the value is expensive to calculate
     * (ImmutableMap and ImmutableList do not cache their hashcodes.
     */
    private val hash: Int

    /** The format of the worker protocol sent to and read from the worker.  */
    private val protocolFormat: WorkerProtocolFormat

    init {
        this.args =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(args)
        this.env =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>>(
                env
            )
        this.execRoot =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(execRoot)
        this.mnemonic = com.google.common.base.Preconditions.checkNotNull<String>(mnemonic)
        this.workerFilesCombinedHash =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.hash.HashCode>(workerFilesCombinedHash)
        this.workerFilesWithDigests =
            com.google.common.base.Preconditions.checkNotNull<SortedMap<PathFragment?, ByteArray?>>(
                workerFilesWithDigests
            )
        this.protocolFormat = protocolFormat
        hash = calculateHashCode()
    }


    fun getArgs(): com.google.common.collect.ImmutableList<String?> {
        return args
    }

    fun getEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
        return env
    }

    fun getExecRoot(): com.google.devtools.build.lib.vfs.Path {
        return execRoot
    }

    fun getWorkerFilesCombinedHash(): com.google.common.hash.HashCode {
        return workerFilesCombinedHash
    }

    fun getWorkerFilesWithDigests(): SortedMap<PathFragment?, ByteArray?> {
        return workerFilesWithDigests
    }

    fun useInMemoryTracking(): Boolean {
        return useInMemoryTracking
    }

    /** Returns the format of the worker protocol.  */
    fun getProtocolFormat(): WorkerProtocolFormat {
        return protocolFormat
    }

    val workerTypeName: String
        /** Returns a user-friendly name for this worker type.  */
        get() =// Current implementation does not support sandboxing with multiplex workers, so keys
            // will only be proxied if they are not forced to be sandboxed due to dynamic execution.
            makeWorkerTypeName(this.isMultiplex, false)

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }

        val workerKey = o as WorkerKey
        if (this.hash != workerKey.hash) {
            return false
        }
        if (args != workerKey.args) {
            return false
        }
        if (this.isMultiplex != workerKey.isMultiplex) {
            return false
        }
        if (cancellable != workerKey.cancellable) {
            return false
        }
        if (this.isSandboxed != workerKey.isSandboxed) {
            return false
        }
        if (useInMemoryTracking != workerKey.useInMemoryTracking) {
            return false
        }
        if (env != workerKey.env) {
            return false
        }
        if (execRoot != workerKey.execRoot) {
            return false
        }
        if (!this.protocolFormat.equals(workerKey.protocolFormat)) {
            return false
        }
        return mnemonic == workerKey.mnemonic
    }

    /** Since all fields involved in the `hashCode` are final, we cache the result.  */
    override fun hashCode(): Int {
        return hash
    }

    private fun calculateHashCode(): Int {
        // Use the string representation of the protocolFormat because the hash of the same enum value
        // can vary across instances.
        return java.util.Objects.hash(
            args,
            env,
            execRoot,
            mnemonic,
            this.isMultiplex,
            cancellable,
            this.isSandboxed,
            useInMemoryTracking,
            protocolFormat.toString()
        )
    }

    override fun toString(): String {
        // We print this command out in such a way that it can safely be
        // copied+pasted as a Bourne shell command.  This is extremely valuable for
        // debugging.
        return CommandFailureUtils.describeCommand(
            CommandDescriptionForm.COMPLETE,  /* prettyPrintArgs= */
            false,
            args,
            env,  /* environmentVariablesToClear= */
            null,
            execRoot.getPathString(),  /* configurationChecksum= */
            null,  /* executionPlatformLabel= */
            null,  /* spawnRunner= */
            this.workerTypeName
        )
    }

    companion object {
        /** Returns a user-friendly name for this worker type.  */
        fun makeWorkerTypeName(proxied: Boolean, mustBeSandboxed: Boolean): String {
            if (proxied && !mustBeSandboxed) {
                return "multiplex-worker"
            } else {
                return "worker"
            }
        }
    }
}
