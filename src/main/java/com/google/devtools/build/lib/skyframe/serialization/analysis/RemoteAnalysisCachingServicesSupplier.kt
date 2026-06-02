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

import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisMetadataWriter
import com.google.devtools.build.lib.util.AbruptExitException

/**
 * Supplies external services used by remote analysis caching.
 * 
 * 
 * This interface exists so its implementation can be injected, at the workspace level.
 * 
 * 
 * The services themselves depend only on command options. Clients must call [.configure]
 * prior to calling either of the service getters.
 * 
 * 
 * Updating parameters is not thread safe. This class assumes that such updates are performed
 * synchronously. Subsequent service get calls are thread safe.
 */
interface RemoteAnalysisCachingServicesSupplier {
    /**
     * Service definitions and parameters depend on `options`, which are allowed to vary
     * per-command.
     * 
     * 
     * This method updates the services and parameters when the relevant flags change.
     */
    @Throws(AbruptExitException::class)
    fun configure(
        cachingOptions: RemoteAnalysisCachingOptions?, clientId: ClientId?, buildId: String?
    ) {
        // Does nothing by default.
    }

    /**
     * Gets or creates the [FingerprintValueService],
     * 
     * 
     * This may entail I/O so it is wrapped in a future.
     */
    val fingerprintValueService: com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?>?

    val analysisCacheClient: com.google.common.util.concurrent.ListenableFuture<out RemoteAnalysisCacheClient?>?
        /**
         * Gets or creates the analysis cache service interface.
         * 
         * 
         * This may entail I/O so it is wrapped in a future.
         */
        get() = null

    val metadataWriter: com.google.common.util.concurrent.ListenableFuture<out RemoteAnalysisMetadataWriter?>?
        get() = null

    val skycacheMetadataParams: SkycacheMetadataParams?
        get() = null

    /** Relinquishes any underlying resource that is scoped to the current command.  */
    fun resetCommandState()

    /** Relinquishes any global, server-lifetime resources (like cached channels).  */
    fun blazeShutdown() {}
}
