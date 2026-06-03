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

import com.google.devtools.build.lib.server.FailureDetails.Execution

/**
 * An [ExecException] thrown when an action fails to execute because one or more of its inputs
 * was lost. In some cases, Bazel may know how to fix this on its own.
 */
class LostInputsExecException @kotlin.jvm.JvmOverloads constructor(
    lostInputs: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>,
    cause: Throwable? = null
) : ExecException("lost inputs with digests: " + java.lang.String.join(",", lostInputs.keySet()), cause) {
    /** Maps lost input digests to their [ActionInput]s.  */
    private val lostInputs: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>

    init {
        com.google.common.base.Preconditions.checkArgument(!lostInputs.isEmpty(), "No inputs were lost")
        this.lostInputs = lostInputs
    }

    @com.google.common.annotations.VisibleForTesting
    fun getLostInputs(): com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?> {
        return lostInputs
    }

    fun fromExecException(
        message: String?,
        action: com.google.devtools.build.lib.actions.Action?,
        code: DetailedExitCode?
    ): ActionExecutionException {
        return LostInputsActionExecutionException(
            message, lostInputs, action,  /* cause= */this, code
        )
    }

    protected override fun getFailureDetail(message: String?): FailureDetail {
        return FailureDetail.newBuilder()
            .setExecution(Execution.newBuilder().setCode(Code.ACTION_INPUT_LOST))
            .setMessage(message)
            .build()
    }

    fun combine(other: LostInputsExecException): LostInputsExecException {
        val combinedLostInputs: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?> =
            com.google.common.collect.ImmutableSetMultimap.builder<String?, ActionInput?>()
                .putAll(lostInputs)
                .putAll(other.lostInputs)
                .build()
        val combined =
            LostInputsExecException(combinedLostInputs,  /* cause= */this)
        combined.addSuppressed(other)
        return combined
    }

    @Throws(LostInputsExecException::class)
    fun combineAndThrow(other: LostInputsExecException) {
        throw combine(other)
    }
}
