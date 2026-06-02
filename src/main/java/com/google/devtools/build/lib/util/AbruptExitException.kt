// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.server.FailureDetails.Command

/**
 * An exception thrown by various error conditions that are severe enough to halt the command (e.g.
 * even a --keep_going build). These typically need to signal to the handling code what happened.
 * Therefore, these exceptions contain a [DetailedExitCode] specifying a numeric exit code and
 * a detailed failure for the command to return.
 * 
 * 
 * When an instance of this exception is thrown, Bazel will try to halt the command as soon as
 * reasonably possible.
 */
class AbruptExitException : java.lang.Exception {
    private val detailedExitCode: DetailedExitCode

    constructor(detailedExitCode: DetailedExitCode) : super(detailedExitCode.getFailureDetail().getMessage()) {
        this.detailedExitCode = detailedExitCode
    }

    constructor(detailedExitCode: DetailedExitCode, cause: Throwable?) : super(
        detailedExitCode.getFailureDetail().getMessage(), cause
    ) {
        this.detailedExitCode = detailedExitCode
    }

    val exitCode: ExitCode?
        get() = detailedExitCode.getExitCode()

    fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    fun toSerialized(): com.google.devtools.build.lib.util.SerializedAbruptExitException {
        val serializedFailureDetail: ByteArray? = detailedExitCode.getFailureDetail().toByteArray()
        return com.google.devtools.build.lib.util.SerializedAbruptExitException(
            getMessage(),
            serializedFailureDetail,
            this
        )
    }

    companion object {
        fun fromSerialized(e: com.google.devtools.build.lib.util.SerializedAbruptExitException): AbruptExitException {
            try {
                val failureDetail: FailureDetail? =
                    FailureDetail.parseFrom(
                        e.getSerializedFailureDetail(), ExtensionRegistryLite.getEmptyRegistry()
                    )
                return AbruptExitException(DetailedExitCode.Companion.of(failureDetail), e)
            } catch (ipbe: InvalidProtocolBufferException) {
                return AbruptExitException(
                    DetailedExitCode.Companion.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                "Failed to parse FailureDetail from SerializedAbruptExitException: "
                                        + ipbe.getMessage()
                            )
                            .setCommand(Command.newBuilder().setCode(Command.Code.COMMAND_FAILURE_UNKNOWN))
                            .build()
                    ),
                    ipbe
                )
            }
        }
    }
}
