// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Action

/**
 * A value class representing an action which can be executed remotely.
 * 
 * 
 * Terminology note: "action" is used here in the remote execution protocol sense, which is
 * equivalent to a Bazel "spawn" (a Bazel "action" being a higher-level concept).
 */
class RemoteAction internal constructor(
    spawn: Spawn?,
    spawnExecutionContext: SpawnExecutionContext,
    remoteActionExecutionContext: RemoteActionExecutionContext,
    remotePathResolver: RemotePathResolver,
    merkleTree: MerkleTree,
    commandHash: Digest?,
    command: Command?,
    action: Action?,
    actionKey: ActionKey
) {
    private val spawn: Spawn?
    private val spawnExecutionContext: SpawnExecutionContext
    private val remoteActionExecutionContext: RemoteActionExecutionContext
    private val remotePathResolver: RemotePathResolver
    private val merkleTree: MerkleTree
    private val commandHash: Digest?
    private val command: Command?
    private val action: Action?
    private val actionKey: ActionKey

    init {
        this.spawn = spawn
        this.spawnExecutionContext = spawnExecutionContext
        this.remoteActionExecutionContext = remoteActionExecutionContext
        this.remotePathResolver = remotePathResolver
        this.merkleTree = merkleTree
        this.commandHash = commandHash
        this.command = command
        this.action = action
        this.actionKey = actionKey
    }

    fun getRemoteActionExecutionContext(): RemoteActionExecutionContext {
        return remoteActionExecutionContext
    }

    fun getSpawnExecutionContext(): SpawnExecutionContext {
        return spawnExecutionContext
    }

    /** Returns the [Spawn] that owns this action.  */
    fun getSpawn(): Spawn? {
        return spawn
    }

    val inputBytes: Long
        /**
         * Returns the sum of file sizes plus protobuf sizes used to represent the inputs of this action.
         */
        get() = merkleTree.inputBytes()

    val inputFiles: Long
        /** Returns the number of input files of this action.  */
        get() = merkleTree.inputFiles()

    val actionId: String
        /** Returns the id this is action.  */
        get() = actionKey.digest.getHash()

    /** Returns the [ActionKey] of this action.  */
    fun getActionKey(): ActionKey {
        return actionKey
    }

    /** Returns underlying [Action] of this remote action.  */
    fun getAction(): Action? {
        return action
    }

    fun getCommandHash(): Digest? {
        return commandHash
    }

    fun getCommand(): Command? {
        return command
    }

    fun getRemotePathResolver(): RemotePathResolver {
        return remotePathResolver
    }

    fun getMerkleTree(): MerkleTree {
        return merkleTree
    }

    /**
     * Returns a [SortedMap] which maps from input paths for remote action to [ ].
     */
    fun getInputMap(willAccessRepeatedly: Boolean): SortedMap<PathFragment?, ActionInput?>? {
        return remotePathResolver.getInputMapping(spawnExecutionContext, willAccessRepeatedly)
    }

    val networkTime: NetworkTime?
        /**
         * Returns the [NetworkTime] instance used to measure the network time during the action
         * execution.
         */
        get() = remoteActionExecutionContext.getNetworkTime()
}
