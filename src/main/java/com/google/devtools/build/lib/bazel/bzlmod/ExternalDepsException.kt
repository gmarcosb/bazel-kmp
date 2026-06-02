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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps

/** Exception indicates that something went wrong while processing external dependencies.  */
class ExternalDepsException private constructor(message: String?, cause: Throwable?, code: ExternalDeps.Code?) :
    java.lang.Exception(message, cause), DetailedException {
    private val detailedExitCode: DetailedExitCode

    init {
        detailedExitCode =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExternalDeps(ExternalDeps.newBuilder().setCode(code).build())
                    .build()
            )
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    companion object {
        @com.google.errorprone.annotations.FormatMethod
        fun withMessage(
            code: ExternalDeps.Code?, @com.google.errorprone.annotations.FormatString format: String, vararg args: Any?
        ): ExternalDepsException {
            return ExternalDepsException(java.lang.String.format(format, *args), null, code)
        }

        @com.google.errorprone.annotations.FormatMethod
        fun withCallStackAndMessage(
            code: ExternalDeps.Code?,
            callStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>,
            @com.google.errorprone.annotations.FormatString format: String,
            vararg args: Any?
        ): ExternalDepsException {
            return ExternalDepsException(
                net.starlark.java.eval.EvalException.formatCallStack(
                    callStack,
                    java.lang.String.format(format, *args),
                    net.starlark.java.eval.EvalException.newSourceReader()
                ),
                null,
                code
            )
        }

        @com.google.errorprone.annotations.FormatMethod
        fun withCauseAndMessage(
            code: ExternalDeps.Code?,
            cause: Throwable,
            @com.google.errorprone.annotations.FormatString format: String,
            vararg args: Any?
        ): ExternalDepsException {
            return ExternalDepsException(
                java.lang.String.format(format, *args) + ": " + cause.getMessage(), cause, code
            )
        }

        fun withCause(code: ExternalDeps.Code?, cause: Throwable): ExternalDepsException {
            return ExternalDepsException(cause.getMessage(), cause, code)
        }
    }
}
