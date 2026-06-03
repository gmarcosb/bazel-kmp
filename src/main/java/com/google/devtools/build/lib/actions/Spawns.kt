// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.server.FailureDetails

/** Helper methods relating to implementations of [Spawn].  */
object Spawns {
    /** Returns `true` if the result of `spawn` may be cached.  */
    fun mayBeCached(spawn: Spawn): Boolean {
        return Spawns.mayBeCached(spawn.getExecutionInfo())
    }

    /** Returns `true` if the result of `spawn` may be cached.  */
    fun mayBeCached(executionInfo: MutableMap<String?, String?>): Boolean {
        return !executionInfo.containsKey(ExecutionRequirements.NO_CACHE)
                && !executionInfo.containsKey(ExecutionRequirements.LOCAL)
    }

    /** Returns `true` if the result of `spawn` may be cached remotely.  */
    fun mayBeCachedRemotely(spawn: Spawn): Boolean {
        return Spawns.mayBeCachedRemotely(spawn.getExecutionInfo())
    }

    /** Returns `true` if the result of `spawn` may be cached remotely.  */
    fun mayBeCachedRemotely(executionInfo: MutableMap<String?, String?>): Boolean {
        return Spawns.mayBeCached(executionInfo)
                && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE) && !executionInfo.containsKey(
            ExecutionRequirements.NO_REMOTE_CACHE
        )
    }

    /** Returns `true` if `spawn` may be executed remotely.  */
    fun mayBeExecutedRemotely(spawn: Spawn): Boolean {
        return !spawn.getExecutionInfo().containsKey(ExecutionRequirements.LOCAL) && !spawn.getExecutionInfo()
            .containsKey(ExecutionRequirements.NO_REMOTE) && !spawn.getExecutionInfo()
            .containsKey(ExecutionRequirements.NO_REMOTE_EXEC)
    }

    /** Returns `true` if `spawn` may be executed locally.  */
    fun mayBeExecutedLocally(spawn: Spawn): Boolean {
        return !spawn.getExecutionInfo().containsKey(ExecutionRequirements.NO_LOCAL)
    }

    /** Returns whether a Spawn can be executed in a sandbox environment.  */
    fun mayBeSandboxed(spawn: Spawn): Boolean {
        return !spawn.getExecutionInfo()
            .containsKey(ExecutionRequirements.LEGACY_NOSANDBOX) && !spawn.getExecutionInfo()
            .containsKey(ExecutionRequirements.NO_SANDBOX) && !spawn.getExecutionInfo()
            .containsKey(ExecutionRequirements.LOCAL)
    }

    /**
     * Returns whether a Spawn must be executed on a separate exec root (i.e., in a sandbox) since it
     * references rewritten input and output paths.
     */
    fun usesPathMapping(spawn: Spawn): Boolean {
        return !spawn.getPathMapper().isNoop()
    }

    /** Returns whether a Spawn needs network access in order to run successfully.  */
    fun requiresNetwork(spawn: Spawn, defaultSandboxDisallowNetwork: Boolean): Boolean {
        if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.BLOCK_NETWORK)) {
            return false
        }
        if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_NETWORK)) {
            return true
        }

        return defaultSandboxDisallowNetwork
    }

    /**
     * Returns whether a Spawn claims to support being executed with the persistent worker strategy
     * according to its execution info tags.
     */
    fun supportsWorkers(spawn: Spawn): Boolean {
        return "1" == spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_WORKERS)
    }

    /**
     * Returns whether a Spawn claims to support being executed with the persistent multiplex worker
     * strategy according to its execution info tags.
     */
    fun supportsMultiplexWorkers(spawn: Spawn): Boolean {
        return ("1"
                == spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS))
    }

    fun supportsWorkerCancellation(spawn: Spawn): Boolean {
        return ("1"
                == spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION))
    }

    /**
     * Returns whether the [Spawn] supports sandboxing for multiplex workers through the `WorkRequest.sandbox_dir` field.
     */
    fun supportsMultiplexSandboxing(spawn: Spawn): Boolean {
        return ("1"
                == spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING))
    }

    /**
     * Returns which worker protocol format a Spawn claims a persistent worker uses. Defaults to proto
     * if the protocol format is not specified.
     */
    @Throws(IOException::class)
    fun getWorkerProtocolFormat(spawn: Spawn): WorkerProtocolFormat {
        val protocolFormat: String? =
            spawn.getExecutionInfo().get(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL)

        if (protocolFormat != null) {
            return when (protocolFormat) {
                "json" -> ExecutionRequirements.WorkerProtocolFormat.JSON
                "proto" -> ExecutionRequirements.WorkerProtocolFormat.PROTO
                else -> throw IOException(
                    "requires-worker-protocol must be set to a valid worker protocol format: json or"
                            + " proto"
                )
            }
        } else {
            return ExecutionRequirements.WorkerProtocolFormat.PROTO
        }
    }

    /** Returns the mnemonic that should be used in the worker's key.  */
    fun getWorkerKeyMnemonic(spawn: Spawn): String? {
        val customValue: String? = spawn.getExecutionInfo().get(ExecutionRequirements.WORKER_KEY_MNEMONIC)
        return if (customValue != null) customValue else spawn.getMnemonic()
    }

    /**
     * Parse the timeout key in the spawn execution info, if it exists. Otherwise, return [ ][Duration.ZERO].
     */
    @Throws(ExecException::class)
    fun getTimeout(spawn: Spawn): java.time.Duration? {
        return getTimeout(spawn, java.time.Duration.ZERO)
    }

    /**
     * Parse the timeout key in the spawn execution info, if it exists. Otherwise, return
     * defaultTimeout, or `Duration.ZERO` if that is null.
     */
    @Throws(ExecException::class)
    fun getTimeout(spawn: Spawn, defaultTimeout: java.time.Duration?): java.time.Duration? {
        val timeoutStr: String? = spawn.getExecutionInfo().get(ExecutionRequirements.TIMEOUT)
        if (timeoutStr == null) {
            return if (defaultTimeout == null) java.time.Duration.ZERO else defaultTimeout
        }
        try {
            return java.time.Duration.ofSeconds(java.lang.Integer.parseInt(timeoutStr).toLong())
        } catch (e: java.lang.NumberFormatException) {
            throw UserExecException(
                e,
                FailureDetail.newBuilder()
                    .setMessage("could not parse timeout")
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.INVALID_TIMEOUT))
                    .build()
            )
        }
    }

    /**
     * Returns whether a local [Spawn] runner implementation should prefetch the inputs before
     * execution, based on the spawns execution info.
     */
    fun shouldPrefetchInputsForLocalExecution(spawn: Spawn): Boolean {
        val disablePrefetchRequest: String? =
            spawn.getExecutionInfo().get(ExecutionRequirements.DISABLE_LOCAL_PREFETCH)
        return (disablePrefetchRequest == null) || disablePrefetchRequest == "0"
    }

    /**
     * Returns a (somewhat) human-readable string for the given `Spawn`. Meant to be used in
     * `toString()` of Spawns.
     */
    fun prettyPrint(spawn: Spawn): String {
        if (spawn.getResourceOwner().getPrimaryOutput() != null) {
            return (spawn.getClass().getSimpleName()
                    + " for "
                    + spawn.getResourceOwner().getPrimaryOutput().prettyPrint())
        } else {
            return (spawn.getClass().getSimpleName()
                    + " for "
                    + spawn.getMnemonic()
                    + " action without primary output")
        }
    }
}
