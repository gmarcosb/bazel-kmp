// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * An interface providing the functionalities used for analysis caching serialization and
 * deserialization.
 */
interface RemoteAnalysisCachingDependenciesProvider {
    fun mode(): RemoteAnalysisCacheMode?

    @Throws(java.lang.InterruptedException::class)
    fun queryMetadataAndMaybeBailout()

    /**
     * Returns the set of SkyKeys to be invalidated.
     * 
     * 
     * May call the remote analysis cache to get the set of keys to invalidate.
     */
    @Throws(java.lang.InterruptedException::class)
    fun lookupKeysToInvalidate(
        keysToLookupSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableSet<SkyKey?>?>?,
        remoteAnalysisCachingState: RemoteAnalysisCachingServerState?
    ): MutableSet<SkyKey?>?

    fun bailedOut(): Boolean {
        return false
    }

    fun computeSelectionAndMinimizeMemory(graph: InMemoryGraph?)

    fun shouldMinimizeMemory(): Boolean

    /** Various bits of data and functionality serialization needs.  */
    interface SerializationDependenciesProvider {
        fun mode(): RemoteAnalysisCacheMode?

        @get:Throws(java.lang.InterruptedException::class)
        val skyValueVersion: FrontierNodeVersion?

        @get:Throws(java.lang.InterruptedException::class)
        val objectCodecs: ObjectCodecs?

        @get:Throws(java.lang.InterruptedException::class)
        val fingerprintValueService: FingerprintValueService?

        val serializedFrontierProfile: String?

        val activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>?

        @get:Throws(java.lang.InterruptedException::class)
        val fileInvalidationWriter: KeyValueWriter?

        @get:Throws(java.lang.InterruptedException::class)
        val metadataWriter: RemoteAnalysisMetadataWriter?

        fun shouldMinimizeMemory(): Boolean

        val skycacheAnalysisOnly: Boolean
    }
}
