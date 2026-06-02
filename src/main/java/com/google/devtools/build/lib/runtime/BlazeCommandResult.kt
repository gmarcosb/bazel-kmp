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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * The result of a Blaze command. It is usually a [ExitCode] with optional [ ], but can be an instruction to the client to execute a particular binary for "blaze
 * run".
 */
@Immutable
class BlazeCommandResult private constructor(
    detailedExitCode: DetailedExitCode?,
    execDescription: ExecRequest?,
    shutdown: Boolean,
    responseExtensions: com.google.common.collect.ImmutableList<Any?>?,
    idleTasks: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?
) {
    private val detailedExitCode: DetailedExitCode

    private val execDescription: ExecRequest?
    private val responseExtensions: com.google.common.collect.ImmutableList<Any?>?
    private val shutdown: Boolean
    private val idleTasks: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?

    init {
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
        this.execDescription = execDescription
        this.shutdown = shutdown
        this.responseExtensions = responseExtensions
        this.idleTasks = idleTasks
    }

    private constructor(detailedExitCode: DetailedExitCode?, execDescription: ExecRequest?, shutdown: Boolean) : this(
        detailedExitCode,
        execDescription,
        shutdown,
        com.google.common.collect.ImmutableList.of<Any?>(),
        com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.server.IdleTask?>()
    )

    val exitCode: ExitCode?
        get() = detailedExitCode.getExitCode()

    fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    val failureDetail: FailureDetail?
        get() = detailedExitCode.getFailureDetail()

    fun shutdown(): Boolean {
        return shutdown
    }

    val execRequest: ExecRequest?
        get() = execDescription

    val isSuccess: Boolean
        get() = detailedExitCode.isSuccess()

    fun getResponseExtensions(): com.google.common.collect.ImmutableList<Any?>? {
        return responseExtensions
    }

    fun getIdleTasks(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>? {
        return idleTasks
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("exitCode", this.exitCode)
            .add("failureDetail", this.failureDetail)
            .add("execDescription", execDescription)
            .add("shutdown", shutdown)
            .add("responseExtensions", responseExtensions)
            .toString()
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun shutdownOnSuccess(): BlazeCommandResult {
            return BlazeCommandResult(DetailedExitCode.success(), null, true)
        }

        fun createShutdown(crash: Crash): BlazeCommandResult {
            return BlazeCommandResult(crash.detailedExitCode, null, true)
        }

        @kotlin.jvm.JvmStatic
        fun success(): BlazeCommandResult {
            return BlazeCommandResult(DetailedExitCode.success(), null, false)
        }

        fun failureDetail(failureDetail: FailureDetail?): BlazeCommandResult {
            return BlazeCommandResult(DetailedExitCode.of(failureDetail), null, false)
        }

        fun detailedExitCode(detailedExitCode: DetailedExitCode?): BlazeCommandResult {
            return BlazeCommandResult(detailedExitCode, null, false)
        }

        fun withResponseExtensions(
            result: BlazeCommandResult, responseExtensions: com.google.common.collect.ImmutableList<Any?>?
        ): BlazeCommandResult {
            return BlazeCommandResult(
                result.detailedExitCode,
                result.execDescription,
                result.shutdown,
                responseExtensions,
                result.idleTasks
            )
        }

        fun withIdleTasks(
            result: BlazeCommandResult,
            idleTasks: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.server.IdleTask?>?
        ): BlazeCommandResult {
            return BlazeCommandResult(
                result.detailedExitCode,
                result.execDescription,
                result.shutdown,
                result.responseExtensions,
                idleTasks
            )
        }

        fun execute(execDescription: ExecRequest?): BlazeCommandResult {
            return BlazeCommandResult(
                DetailedExitCode.success(),
                com.google.common.base.Preconditions.checkNotNull<ExecRequest?>(execDescription),
                false
            )
        }

        fun execute(
            execDescription: ExecRequest?, detailedExitCode: DetailedExitCode?
        ): BlazeCommandResult {
            return BlazeCommandResult(
                com.google.common.base.Preconditions.checkNotNull<DetailedExitCode?>(detailedExitCode),
                com.google.common.base.Preconditions.checkNotNull<ExecRequest?>(execDescription),
                false
            )
        }
    }
}
