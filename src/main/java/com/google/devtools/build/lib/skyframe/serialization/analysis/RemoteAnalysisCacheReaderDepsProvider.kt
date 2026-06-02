// Copyright 2026 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.skyframe.serialization.FrontierNodeVersion
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions.RemoteAnalysisCacheMode
import com.google.devtools.build.skyframe.SkyKey

/** Functionality needed to retrieve values from the remote cache.  */
interface RemoteAnalysisCacheReaderDepsProvider {
    fun mode(): RemoteAnalysisCacheMode?

    @kotlin.jvm.JvmField
    @get:Throws(java.lang.InterruptedException::class)
    val skyValueVersion: FrontierNodeVersion?

    @kotlin.jvm.JvmField
    @get:Throws(java.lang.InterruptedException::class)
    val objectCodecs: ObjectCodecs?

    @kotlin.jvm.JvmField
    @get:Throws(java.lang.InterruptedException::class)
    val fingerprintValueService: FingerprintValueService?

    @kotlin.jvm.JvmField
    @get:Throws(java.lang.InterruptedException::class)
    val analysisCacheClient: RemoteAnalysisCacheClient?

    fun recordRetrievalResult(retrievalResult: RetrievalResult?, key: SkyKey?)

    fun recordSerializationException(
        e: com.google.devtools.build.lib.skyframe.serialization.SerializationException?,
        key: SkyKey?
    )

    /** Returns true if bailing out on the first missing fingerprint is enabled.  */
    fun shouldBailOutOnMissingFingerprint(): Boolean

    /** Returns true if Skycache is only used for analysis phase.  */
    @kotlin.jvm.JvmField
    val skycacheAnalysisOnly: Boolean
}
