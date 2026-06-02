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

import com.google.devtools.build.lib.actions.Action

/**
 * A specialization of [ExecException] that indicates something went wrong when trying to
 * execute a [com.google.devtools.build.lib.actions.Spawn].
 */
// Non-final only for tests, do not subclass!
class SpawnExecException : ExecException {
    protected val result: SpawnResult
    protected val forciblyRunRemotely: Boolean

    constructor(message: String?, result: SpawnResult, forciblyRunRemotely: Boolean) : super(
        message,
        result.isCatastrophe()
    ) {
        com.google.common.base.Preconditions.checkArgument(
            !Status.SUCCESS.equals(result.status()),
            "Can't create exception with successful spawn result."
        )
        this.result = com.google.common.base.Preconditions.checkNotNull<SpawnResult>(result)
        this.forciblyRunRemotely = forciblyRunRemotely
    }

    @com.google.common.annotations.VisibleForTesting
    constructor(message: String?, result: SpawnResult?, forciblyRunRemotely: Boolean, catastrophe: Boolean) : super(
        message,
        catastrophe
    ) {
        this.result = com.google.common.base.Preconditions.checkNotNull<SpawnResult>(result)
        this.forciblyRunRemotely = forciblyRunRemotely
    }

    val spawnResult: SpawnResult
        /** Returns the spawn result.  */
        get() = result

    fun hasTimedOut(): Boolean {
        return this.spawnResult.status() === Status.TIMEOUT
    }

    protected val messageForActionExecutionException: String
        get() = result.getDetailMessage(getMessage(), isCatastrophic(), forciblyRunRemotely)

    protected override fun getFailureDetail(message: String?): FailureDetail? {
        return checkNotNull(result.failureDetail(), this)
    }

    fun toActionExecutionException(action: Action): SpawnActionExecutionException? {
        val message = this.messageForActionExecutionException
        val code: DetailedExitCode =
            DetailedExitCode.of(this.getFailureDetail(action.describe() + " failed: " + message))
        return SpawnActionExecutionException(this, message, action, code, this.spawnResult)
    }
}
