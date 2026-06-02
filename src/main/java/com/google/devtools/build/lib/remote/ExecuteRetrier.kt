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
package com.google.devtools.build.lib.remote

/** Specific retry logic for execute request with gapi Status.  */
internal class ExecuteRetrier(
    maxRetryAttempts: Int,
    retryService: com.google.common.util.concurrent.ListeningScheduledExecutorService?,
    circuitBreaker: com.google.devtools.build.lib.remote.Retrier.CircuitBreaker?
) : RemoteRetrier(
    java.util.function.Supplier { if (maxRetryAttempts > 0) RetryInfoBackoff(maxRetryAttempts) else Retrier.Companion.RETRIES_DISABLED },
    ResultClassifier { e: java.lang.Exception? -> resultClassifier(e) },
    retryService,
    circuitBreaker
) {
    private class RetryInfoBackoff(private val maxRetryAttempts: Int) : Backoff {
        var retryAttempts: Int = 0

        override fun nextDelayMillis(e: java.lang.Exception?): Long {
            if (this.retryAttempts >= maxRetryAttempts) {
                return -1
            }
            val retryInfo: RetryInfo = getRetryInfo(e)
            this.retryAttempts++
            return Durations.toMillis(retryInfo.getRetryDelay())
        }

        fun getRetryInfo(e: java.lang.Exception?): RetryInfo {
            var retryInfo: RetryInfo = RetryInfo.getDefaultInstance()
            val status: Status? = StatusProto.fromThrowable(e)
            if (status != null) {
                for (detail in status.getDetailsList()) {
                    if (detail.`is`(RetryInfo::class.java)) {
                        try {
                            retryInfo = detail.unpack(RetryInfo::class.java)
                        } catch (protoEx: InvalidProtocolBufferException) {
                            // really shouldn't happen, ignore
                        }
                    }
                }
            }
            return retryInfo
        }
    }

    companion object {
        private const val VIOLATION_TYPE_MISSING = "MISSING"

        private fun resultClassifier(e: java.lang.Exception?): com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result {
            if (BulkTransferException.Companion.allCausedByCacheNotFoundException(e)) {
                return com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
            }
            val status: Status? = StatusProto.fromThrowable(e)
            if (status == null || status.getDetailsCount() === 0) {
                return com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.SUCCESS
            }
            var failedPrecondition = status.getCode() === io.grpc.Status.Code.FAILED_PRECONDITION.value()
            for (detail in status.getDetailsList()) {
                if (detail.`is`(RetryInfo::class.java)) {
                    // server says we can retry, regardless of other details
                    return com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE
                } else if (failedPrecondition) {
                    if (detail.`is`(PreconditionFailure::class.java)) {
                        try {
                            val f: PreconditionFailure = detail.unpack(PreconditionFailure::class.java)
                            if (f.getViolationsCount() === 0) {
                                failedPrecondition = false
                            }
                            for (v in f.getViolationsList()) {
                                if (!v.getType().equals(VIOLATION_TYPE_MISSING)) {
                                    failedPrecondition = false
                                }
                            }
                            // if *all* > 0 precondition failure violations have type MISSING, failedPrecondition
                            // remains true
                        } catch (protoEx: InvalidProtocolBufferException) {
                            // really shouldn't happen
                            return com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE
                        }
                    } else if (!(detail.`is`(DebugInfo::class.java)
                                || detail.`is`(Help::class.java)
                                || detail.`is`(LocalizedMessage::class.java)
                                || detail.`is`(RequestInfo::class.java)
                                || detail.`is`(ResourceInfo::class.java))
                    ) { // ignore benign details
                        // consider all other details as failures
                        failedPrecondition = false
                    }
                }
            }
            return if (failedPrecondition) com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.TRANSIENT_FAILURE else com.google.devtools.build.lib.remote.Retrier.ResultClassifier.Result.PERMANENT_FAILURE
        }
    }
}
