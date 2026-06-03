// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import com.google.devtools.build.lib.actions.ActionContext

/** Execution context for tests  */
class FakeSpawnExecutionContext(
    spawn: Spawn?,
    inputMetadataProvider: InputMetadataProvider?,
    execRoot: Path?,
    outErr: FileOutErr?,
    actionContextRegistry: com.google.common.collect.ClassToInstanceMap<ActionContext?>,
    actionFileSystem: RemoteActionFileSystem?
) : SpawnExecutionContext {
    var isLockOutputFilesCalled: Boolean = false
        private set

    private val spawn: Spawn?
    private val inputMetadataProvider: InputMetadataProvider?
    private val execRoot: Path?
    private val outErr: FileOutErr?
    private val actionContextRegistry: com.google.common.collect.ClassToInstanceMap<ActionContext?>
    private val actionFileSystem: RemoteActionFileSystem?

    private var digest: Digest? = null

    init {
        this.spawn = spawn
        this.inputMetadataProvider = inputMetadataProvider
        this.execRoot = execRoot
        this.outErr = outErr
        this.actionContextRegistry = actionContextRegistry
        this.actionFileSystem = actionFileSystem
    }

    val id: Int
        get() = 0

    public override fun setDigest(digest: Digest?) {
        .also {
            this.digest = it
        }<Digest> com . google . common . base . Preconditions . checkNotNull < kotlin . Any ? > (digest)
    }

    public override fun getDigest(): Digest? {
        return digest
    }

    public override fun prefetchInputs(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? {
        throw java.lang.UnsupportedOperationException()
    }

    public override fun lockOutputFiles(exitCode: Int, errorMessage: String?, outErr: FileOutErr?) {
        this.isLockOutputFilesCalled = true
    }

    public override fun speculating(): Boolean {
        return false
    }

    public override fun getInputMetadataProvider(): InputMetadataProvider? {
        return inputMetadataProvider
    }

    val pathResolver: ArtifactPathResolver
        get() = ArtifactPathResolver.forExecRoot(execRoot)

    val timeout: java.time.Duration
        get() = java.time.Duration.ZERO

    val fileOutErr: FileOutErr?
        get() = outErr

    public override fun getInputMapping(
        baseDirectory: PathFragment?, willAccessRepeatedly: Boolean
    ): SortedMap<PathFragment?, ActionInput?> {
        return SpawnInputExpander().getInputMapping(spawn, inputMetadataProvider, baseDirectory)
    }

    public override fun report(progress: ProgressStatus?) {
        // Intentionally left empty.
    }

    public override fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>): T? {
        return actionContextRegistry.getInstance<T?>(identifyingType)
    }

    val isRewindingEnabled: Boolean
        get() = false

    public override fun checkForLostInputs() {}

    public override fun getActionFileSystem(): RemoteActionFileSystem? {
        return actionFileSystem
    }

    val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.of<String?, String?>()
}
