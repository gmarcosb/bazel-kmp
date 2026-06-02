// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus

/** Interface to the remote analysis cache.  */
interface RemoteAnalysisCacheClient {
    /** The key for memoizing top-level targets lookup results.  */
    @kotlin.jvm.JvmRecord
    data class TopLevelTargetsCacheKey(
        val evaluatingVersion: Long,
        val configurationHash: String?,
        val useFakeStampData: Boolean,
        val blazeVersion: String?
    )

    /** Usage statistics.  */
    class Stats(
        val bytesSent: Long,
        val bytesReceived: Long,
        val requestsSent: Long,
        val batches: Long,
        latencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?,
        batchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?,
        matchStatus: TopLevelTargetsMatchStatus?
    ) {
        val latencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
        val batchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
        val matchStatus: TopLevelTargetsMatchStatus?

        init {
            this.latencyMicros = latencyMicros
            this.batchLatencyMicros = batchLatencyMicros
            this.matchStatus = matchStatus
        }
    }

    /** Looks up an entry in the remote analysis cache based on a serialized key.  */
    fun lookup(key: ByteString?): com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>?

    /** Returns the usage statistics.  */
    val stats: Stats?

    /** Looks up the targets in the metadata table  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(
        ExecutionException::class,
        java.util.concurrent.TimeoutException::class,
        java.lang.InterruptedException::class
    )
    fun lookupTopLevelTargets(
        evaluatingVersion: Long,
        configurationHash: String?,
        useFakeStampData: Boolean,
        bazelVersion: String?
    ): LookupTopLevelTargetsResult?

    /**
     * Sets the status of the metadata result to MATCH_STATUS_MISSING_FINGERPRINT. This signals that
     * the build bailed out due to a missing fingerprint during deserialization. This can happen after
     * having started in Skycache mode and having confirmed with metadata that cache hits were
     * possible.
     */
    fun bailOutDueToMissingFingerprint()

    companion object {
        /** Timeout when accessing the future in order to shutdown the client.  */
        const val SHUTDOWN_TIMEOUT_IN_SECONDS: Int = 5


        val EMPTY_STATS: Stats =
            com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient.Stats(
                0,
                0,
                0,
                0,
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>(),
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>(),
                TopLevelTargetsMatchStatus.MATCH_STATUS_UNSPECIFIED
            )
    }
}
