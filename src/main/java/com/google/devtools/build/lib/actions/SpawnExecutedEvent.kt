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
package com.google.devtools.build.lib.actions

/** This event is fired during the build, when a subprocess is executed.  */
class SpawnExecutedEvent(
    spawn: Spawn?,
    inputMetadataProvider: InputMetadataProvider?,
    actionFileSystem: FileSystem?,
    fileOutErr: FileOutErr?,
    result: SpawnResult?,
    startTimeInstant: Instant?,
    spawnIdentifier: String?
) : Postable {
    private val spawn: Spawn
    private val inputMetadataProvider: InputMetadataProvider?
    private val actionFileSystem: FileSystem?
    private val fileOutErr: FileOutErr?
    private val result: SpawnResult
    private val startTimeInstant: Instant?
    private val spawnIdentifier: String?

    init {
        this.spawn = com.google.common.base.Preconditions.checkNotNull<Spawn>(spawn)
        this.inputMetadataProvider = inputMetadataProvider
        this.actionFileSystem = actionFileSystem
        this.fileOutErr = fileOutErr
        this.result = com.google.common.base.Preconditions.checkNotNull<SpawnResult>(result)
        this.startTimeInstant = startTimeInstant
        this.spawnIdentifier = spawnIdentifier
    }

    /** Returns the Spawn.  */
    fun getSpawn(): Spawn {
        return spawn
    }

    /** Returns the input metadata provider containing information about the inputs of the Spawn.  */
    fun getInputMetadataProvider(): InputMetadataProvider? {
        return inputMetadataProvider
    }

    fun getActionFileSystem(): FileSystem? {
        return actionFileSystem
    }

    /** Returns the action.  */
    fun getActionMetadata(): ActionAnalysisMetadata? {
        return spawn.getResourceOwner()
    }

    /** Returns the action exit code.  */
    fun getExitCode(): Int {
        return result.exitCode()
    }

    /** Returns the distributor reply.  */
    fun getSpawnResult(): SpawnResult {
        return result
    }

    /** Returns the instant in time when the spawn starts.  */
    fun getStartTimeInstant(): Instant? {
        return startTimeInstant
    }

    /** Returns the id used by the spawn runner to uniquely identify the spawn.  */
    fun getSpawnIdentifier(): String? {
        return spawnIdentifier
    }

    /** Returns the FileOutErr used by the Spawn.  */
    fun getFileOutErr(): FileOutErr? {
        return fileOutErr
    }

    /**
     * This event is fired to differentiate actions with multiple spawns that are run sequentially
     * versus parallel. An example of a use case of why this would be important is if we have flaky
     * tests. We want to tell the [ ] that all the failed
     * test spawns should have their Duration metrics aggregated so the test runtime matches the
     * runtime of the entire CriticalPathComponent.
     */
    class ChangePhase(action: ActionAnalysisMetadata?) : Postable {
        private val action: ActionAnalysisMetadata?

        init {
            this.action = action
        }

        fun getAction(): ActionAnalysisMetadata? {
            return this.action
        }
    }
}
