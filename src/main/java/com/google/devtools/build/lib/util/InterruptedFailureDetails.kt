// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/** Factory method for producing [Interrupted]-type [FailureDetail] messages.  */
object InterruptedFailureDetails {
    /**
     * Returns a [DetailedExitCode] with [ExitCode.INTERRUPTED], [ ][Interrupted.Code.INTERRUPTED], and the provided detail message.
     */
    @kotlin.jvm.JvmStatic
    fun detailedExitCode(message: String?): DetailedExitCode {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                .build()
        )
    }

    /**
     * Returns an [AbruptExitException] with a [DetailedExitCode] from [ ][.detailedExitCode].
     */
    fun abruptExitException(message: String?): AbruptExitException {
        return AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                    .build()
            )
        )
    }

    /**
     * Returns an [AbruptExitException] with a [DetailedExitCode] from [ ][.detailedExitCode] and the provided `cause`.
     */
    @kotlin.jvm.JvmStatic
    fun abruptExitException(message: String?, cause: java.lang.Exception?): AbruptExitException {
        return AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                    .build()
            ),
            cause
        )
    }
}
