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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.ExecuteResponse

/**
 * Exception to signal that a remote execution has failed with a certain status received from the
 * server, and other details, such as the action result and the server logs. The exception may be
 * retriable or not, depending on the status/details.
 */
class ExecutionStatusException internal constructor(
    e: StatusRuntimeException,
    original: Status,
    response: ExecuteResponse?
) : StatusRuntimeException(e.getStatus(), e.getTrailers()) {
    private val status: Status
    private val response: ExecuteResponse?

    init {
        this.status = original
        this.response = response
    }

    constructor(status: Status, response: ExecuteResponse?) : this(
        StatusProto.toStatusRuntimeException(
            convertStatus(
                status,
                response
            )
        ), status, response
    )

    val isExecutionTimeout: Boolean
        get() = isExecutionTimeout(status, response)

    fun getResponse(): ExecuteResponse? {
        return response
    }

    val originalStatus: Status
        get() = status

    companion object {
        private fun convertStatus(status: Status, response: ExecuteResponse?): Status {
            val result: Status.Builder = status.toBuilder()
            if (isExecutionTimeout(status, response)) {
                // Hack: convert to non-retriable exception on timeouts.
                result.setCode(io.grpc.Status.Code.FAILED_PRECONDITION.value())
            }
            return result.build()
        }

        private fun isExecutionTimeout(status: Status, response: ExecuteResponse?): Boolean {
            return response != null && response.getStatus().equals(status)
                    && status.getCode() === io.grpc.Status.Code.DEADLINE_EXCEEDED.value()
        }
    }
}
