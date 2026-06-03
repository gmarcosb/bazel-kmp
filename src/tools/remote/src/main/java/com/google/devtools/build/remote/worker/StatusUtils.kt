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
package com.google.devtools.build.remote.worker

import build.bazel.remote.execution.v2.Digest

/** Some utility methods to convert exceptions to Status results.  */
internal object StatusUtils {
    fun internalError(e: java.lang.Exception): StatusException {
        return StatusProto.toStatusException(internalErrorStatus(e))
    }

    fun internalErrorStatus(e: java.lang.Exception): Status? {
        // StatusProto.fromThrowable returns null on non-status errors or errors with no trailers,
        // unlike Status.fromThrowable which returns the UNKNOWN code for these.
        val st: Status? = StatusProto.fromThrowable(e)
        return if (st != null)
            st
        else
            Status.newBuilder().setCode(Code.INTERNAL.getNumber()).setMessage(e.message).build()
    }

    fun notFoundError(digest: Digest?): StatusException {
        return StatusProto.toStatusException(notFoundStatus(digest))
    }

    fun notFoundStatus(digest: Digest?): Status {
        return Status.newBuilder()
            .setCode(Code.NOT_FOUND.getNumber())
            .setMessage("Digest not found:" + digest)
            .build()
    }

    fun interruptedError(digest: Digest?): StatusException {
        return StatusProto.toStatusException(interruptedStatus(digest))
    }

    fun interruptedStatus(digest: Digest?): Status {
        return Status.newBuilder()
            .setCode(Code.CANCELLED.getNumber())
            .setMessage("Server operation was interrupted for " + digest)
            .build()
    }

    fun invalidArgumentError(field: String?, desc: String?): StatusException {
        return StatusProto.toStatusException(invalidArgumentStatus(field, desc))
    }

    fun invalidArgumentStatus(field: String?, desc: String?): Status {
        val v: FieldViolation? = FieldViolation.newBuilder().setField(field).setDescription(desc).build()
        return Status.newBuilder()
            .setCode(Code.INVALID_ARGUMENT.getNumber())
            .setMessage("invalid argument(s): " + field + ": " + desc)
            .addDetails(Any.pack(BadRequest.newBuilder().addFieldViolations(v).build()))
            .build()
    }

    fun preconditionError(e: java.lang.Exception): StatusException {
        return StatusProto.toStatusException(preconditionStatus(e))
    }

    fun preconditionStatus(e: java.lang.Exception): Status {
        return Status.newBuilder()
            .setCode(Code.FAILED_PRECONDITION.getNumber())
            .setMessage(e.message)
            .build()
    }

    fun missingBlobError(digest: Digest): StatusException {
        return StatusProto.toStatusException(missingBlobStatus(digest))
    }

    fun missingBlobStatus(digest: Digest): com.google.rpc.Status {
        return Status.newBuilder()
            .setCode(Code.FAILED_PRECONDITION.getNumber())
            .setMessage("Missing Blob: " + digest)
            .addDetails(
                Any.pack(
                    PreconditionFailure.newBuilder()
                        .addViolations(
                            PreconditionFailure.Violation.newBuilder()
                                .setType("MISSING")
                                .setSubject("blobs/" + digest.getHash() + "/" + digest.getSizeBytes())
                        )
                        .build()
                )
            )
            .build()
    }
}
