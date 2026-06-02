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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Implementation of remote analysis cache functionality that is needed for reading and writing.
 * 
 * 
 * The parts that are needed for maintenance of Skyframe, etc. are in [ ].
 */
class RemoteAnalysisCacheDeps

    : SerializationDependenciesProvider, RemoteAnalysisCacheReaderDepsProvider {
    private val mode: RemoteAnalysisCacheMode?
    private val bailOutOnMissingFingerprint: Boolean
    private val minimizeMemory: Boolean
    private val serializedFrontierProfile: String?
    private val activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>?
    private val listener: RemoteAnalysisCachingEventListener?
    private val frontierNodeVersion: FrontierNodeVersion?
    private val skycacheAnalysisOnly: Boolean

    private val objectCodecs: com.google.common.util.concurrent.ListenableFuture<ObjectCodecs?>?
    private val fingerprintValueServiceFuture: com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?>?
    private val analysisCacheClient: com.google.common.util.concurrent.ListenableFuture<out RemoteAnalysisCacheClient?>?
    private val metadataWriter: com.google.common.util.concurrent.ListenableFuture<out RemoteAnalysisMetadataWriter?>?

    private val bailedOut: AtomicBoolean = AtomicBoolean()
    private val eventHandler: ExtendedEventHandler?

    internal constructor(
        eventHandler: ExtendedEventHandler?,
        mode: RemoteAnalysisCacheMode?,
        bailOutOnMissingFingerprint: Boolean,
        minimizeMemory: Boolean,
        servicesSupplier: RemoteAnalysisCachingServicesSupplier,
        listener: RemoteAnalysisCachingEventListener?,
        objectCodecs: com.google.common.util.concurrent.ListenableFuture<ObjectCodecs?>?,
        frontierNodeVersion: FrontierNodeVersion?,
        activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>?,
        serializedFrontierProfile: String?,
        skycacheAnalysisOnly: Boolean
    ) {
        this.mode = mode
        this.bailOutOnMissingFingerprint = bailOutOnMissingFingerprint
        this.skycacheAnalysisOnly = skycacheAnalysisOnly
        this.minimizeMemory = minimizeMemory
        this.serializedFrontierProfile = serializedFrontierProfile
        this.activeDirectoriesMatcher = activeDirectoriesMatcher
        this.eventHandler = eventHandler

        this.objectCodecs = objectCodecs
        this.listener = listener

        this.frontierNodeVersion = frontierNodeVersion

        this.fingerprintValueServiceFuture = servicesSupplier.getFingerprintValueService()
        this.metadataWriter = servicesSupplier.getMetadataWriter()
        this.analysisCacheClient = servicesSupplier.getAnalysisCacheClient()
    }

    private constructor() {
        this.mode = RemoteAnalysisCacheMode.OFF
        this.bailOutOnMissingFingerprint = false
        this.minimizeMemory = false
        this.skycacheAnalysisOnly = false
        this.serializedFrontierProfile = ""
        this.activeDirectoriesMatcher = java.util.Optional.empty<java.util.function.Predicate<PackageIdentifier?>?>()
        this.eventHandler = null
        this.objectCodecs = null
        this.listener = null
        this.frontierNodeVersion = null
        this.fingerprintValueServiceFuture = null
        this.metadataWriter = null
        this.analysisCacheClient = null
    }

    private fun checkEnabled() {
        com.google.common.base.Preconditions.checkState(
            mode != RemoteAnalysisCacheMode.OFF, "Remote analysis cache is disabled"
        )
    }

    public override fun mode(): RemoteAnalysisCacheMode? {
        return mode
    }

    public override fun shouldMinimizeMemory(): Boolean {
        checkEnabled()
        return minimizeMemory
    }

    public override fun getSerializedFrontierProfile(): String? {
        checkEnabled()
        return serializedFrontierProfile
    }

    public override fun getActiveDirectoriesMatcher(): java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>? {
        checkEnabled()
        return activeDirectoriesMatcher
    }

    val skyValueVersion: FrontierNodeVersion?
        get() {
            checkEnabled()
            return frontierNodeVersion
        }

    @Throws(java.lang.InterruptedException::class)
    public override fun getObjectCodecs(): ObjectCodecs? {
        checkEnabled()
        try {
            return objectCodecs.get()
        } catch (e: ExecutionException) {
            throw java.lang.IllegalStateException("Failed to initialize ObjectCodecs", e)
        }
    }

    @get:Throws(java.lang.InterruptedException::class)
    val fingerprintValueService: FingerprintValueService?
        get() {
            checkEnabled()
            return Companion.resolveWithTimeout<FingerprintValueService?>(
                fingerprintValueServiceFuture,
                "fingerprint value service"
            )
        }

    @get:Throws(java.lang.InterruptedException::class)
    val fileInvalidationWriter: KeyValueWriter?
        get() {
            checkEnabled()
            return this.fingerprintValueService
        }

    @Throws(java.lang.InterruptedException::class)
    public override fun getAnalysisCacheClient(): RemoteAnalysisCacheClient? {
        checkEnabled()
        return resolveWithTimeout(analysisCacheClient, "analysis cache client")
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun getMetadataWriter(): RemoteAnalysisMetadataWriter? {
        checkEnabled()
        return resolveWithTimeout(metadataWriter, "metadata writer")
    }

    public override fun recordRetrievalResult(retrievalResult: RetrievalResult?, key: SkyKey?) {
        checkEnabled()
        listener.recordRetrievalResult(retrievalResult, key)
    }

    public override fun recordSerializationException(
        e: com.google.devtools.build.lib.skyframe.serialization.SerializationException?,
        key: SkyKey?
    ) {
        checkEnabled()
        listener.recordSerializationException(e, key)
    }

    public override fun shouldBailOutOnMissingFingerprint(): Boolean {
        checkEnabled()
        if (!bailOutOnMissingFingerprint) {
            return false
        }
        if (bailedOut.get()) {
            return true
        }

        try {
            val service: FingerprintValueService? = this.fingerprintValueService
            val retVal = service != null && service.getStats().entriesNotFound > 0
            if (retVal) {
                bailedOut.set(true)
                eventHandler.handle(
                    Event.warn(
                        "Skycache: falling back to local evaluation due to unexpected missing cache"
                                + " entries"
                    )
                )
                analysisCacheClient.get().bailOutDueToMissingFingerprint()
            }
            return retVal
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            return false
        } catch (e: ExecutionException) {
            throw java.lang.IllegalStateException(
                "At this point the Skycache client should have been initialized", e
            )
        }
    }

    public override fun getSkycacheAnalysisOnly(): Boolean {
        checkEnabled()
        return skycacheAnalysisOnly
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val CLIENT_LOOKUP_TIMEOUT_SEC = 20L

        @kotlin.jvm.JvmStatic
        fun createDisabled(): RemoteAnalysisCacheDeps {
            return RemoteAnalysisCacheDeps()
        }

        @Throws(java.lang.InterruptedException::class)
        fun <T> resolveWithTimeout(future: java.util.concurrent.Future<out T?>?, what: String?): T? {
            if (future == null) {
                return null
            }
            try {
                Profiler.instance().profile("resolveWithTimeout: " + what).use { unused ->
                    return future.get(
                        CLIENT_LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS
                    )
                }
            } catch (e: ExecutionException) {
                logger.atWarning().withCause(e).log("Unable to initialize %s", what)
                return null
            } catch (e: java.util.concurrent.TimeoutException) {
                logger.atWarning().withCause(e).log("Unable to initialize %s", what)
                return null
            }
        }
    }
}
