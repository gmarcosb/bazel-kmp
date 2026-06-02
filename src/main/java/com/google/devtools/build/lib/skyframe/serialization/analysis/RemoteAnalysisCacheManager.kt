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

import com.google.devtools.build.lib.cmdline.Label

/** A collection of dependencies and minor bits of functionality for remote analysis caching.  */ // Non-final for mockability
class RemoteAnalysisCacheManager : RemoteAnalysisCachingDependenciesProvider {
    private val mode: RemoteAnalysisCacheMode?

    private val analysisCacheClient: java.util.concurrent.Future<out RemoteAnalysisCacheClient?>?
    private val analysisCacheInvalidator: java.util.concurrent.Future<out AnalysisCacheInvalidator?>?

    private val topLevelTargets: MutableCollection<Label?>?
    private val activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>?

    private val eventHandler: ExtendedEventHandler?

    private val areMetadataQueriesEnabled: Boolean
    private val skycacheMetadataParams: SkycacheMetadataParams?

    private var bailedOut = false

    private val minimizeMemory: Boolean

    /**
     * A collection of various parts of this class that various parts of Bazel (cache reading, cache
     * writing, in-memory bookkeeping) need.
     */
    class AnalysisDeps(
        deps: RemoteAnalysisCachingDependenciesProvider?,
        readerDeps: RemoteAnalysisCacheReaderDepsProvider?,
        serializationDeps: SerializationDependenciesProvider?
    ) {
        val deps: RemoteAnalysisCachingDependenciesProvider?
        val readerDeps: RemoteAnalysisCacheReaderDepsProvider?
        val serializationDeps: SerializationDependenciesProvider?

        init {
            this.deps = deps
            this.readerDeps = readerDeps
            this.serializationDeps = serializationDeps
        }
    }

    private constructor() {
        this.mode = RemoteAnalysisCacheMode.OFF
        this.analysisCacheClient = null
        this.analysisCacheInvalidator = null
        this.topLevelTargets = com.google.common.collect.ImmutableList.of<Label?>()
        this.activeDirectoriesMatcher = java.util.Optional.empty<java.util.function.Predicate<PackageIdentifier?>?>()
        this.minimizeMemory = false
        this.eventHandler = null
        this.skycacheMetadataParams = null
        this.areMetadataQueriesEnabled = false
    }

    internal constructor(
        mode: RemoteAnalysisCacheMode?,
        areMetadataQueriesEnabled: Boolean,
        eventHandler: ExtendedEventHandler?,
        skycacheMetadataParams: SkycacheMetadataParams?,
        analysisCacheClient: java.util.concurrent.Future<out RemoteAnalysisCacheClient?>?,
        analysisCacheInvalidator: java.util.concurrent.Future<out AnalysisCacheInvalidator?>?,
        topLevelTargets: MutableCollection<Label?>?,
        activeDirectoriesMatcher: java.util.Optional<java.util.function.Predicate<PackageIdentifier?>?>?,
        minimizeMemory: Boolean
    ) {
        this.mode = mode
        this.analysisCacheClient = analysisCacheClient
        this.analysisCacheInvalidator = analysisCacheInvalidator
        this.topLevelTargets = topLevelTargets
        this.activeDirectoriesMatcher = activeDirectoriesMatcher
        this.minimizeMemory = minimizeMemory
        this.eventHandler = eventHandler
        this.skycacheMetadataParams = skycacheMetadataParams
        this.areMetadataQueriesEnabled = areMetadataQueriesEnabled
    }

    public override fun mode(): RemoteAnalysisCacheMode? {
        return mode
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun queryMetadataAndMaybeBailout() {
        com.google.common.base.Preconditions.checkState(mode == RemoteAnalysisCacheMode.DOWNLOAD)
        if (!areMetadataQueriesEnabled) {
            return
        }
        if (skycacheMetadataParams.getTargets().isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn("Skycache: Not querying Skycache metadata because invocation has no targets")
            )
        } else {
            try {
                val result: LookupTopLevelTargetsResult =
                    RemoteAnalysisCacheDeps.Companion.resolveWithTimeout(analysisCacheClient, "analysis cache client")
                        .lookupTopLevelTargets(
                            skycacheMetadataParams.getEvaluatingVersion(),
                            skycacheMetadataParams.getConfigurationHash(),
                            skycacheMetadataParams.getUseFakeStampData(),
                            skycacheMetadataParams.getBazelVersion()
                        )

                val event: com.google.devtools.build.lib.events.Event? =
                    when (result.status) {
                        MATCH_STATUS_MATCH -> com.google.devtools.build.lib.events.Event.info("Skycache: " + result.statusMessage)
                        else -> {
                            bailedOut = true
                            com.google.devtools.build.lib.events.Event.warn("Skycache: " + result.statusMessage)
                        }
                    }
                eventHandler.handle(event)
            } catch (e: ExecutionException) {
                eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("Skycache: Error with metadata store: " + e.message))
            } catch (e: java.util.concurrent.TimeoutException) {
                eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("Skycache: Error with metadata store: " + e.message))
            }
        }
    }

    private fun checkEnabled() {
        com.google.common.base.Preconditions.checkState(
            mode != RemoteAnalysisCacheMode.OFF, "Remote analysis cache is disabled"
        )
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun lookupKeysToInvalidate(
        keysToLookupSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableSet<SkyKey?>?>,
        remoteAnalysisCachingState: RemoteAnalysisCachingServerState?
    ): MutableSet<SkyKey?>? {
        checkEnabled()
        val invalidator: AnalysisCacheInvalidator? =
            RemoteAnalysisCacheDeps.Companion.resolveWithTimeout(
                analysisCacheInvalidator, "analysis cache invalidator"
            )
        if (invalidator == null) {
            // We need to know which keys to invalidate but we don't have an invalidator, presumably
            // because the backend services couldn't be contacted. Play if safe and invalidate every
            // value retrieved from the remote cache.
            return keysToLookupSupplier.get()
        }
        return invalidator.lookupKeysToInvalidate(keysToLookupSupplier, remoteAnalysisCachingState)
    }

    public override fun bailedOut(): Boolean {
        checkEnabled()
        return bailedOut
    }

    public override fun computeSelectionAndMinimizeMemory(graph: InMemoryGraph?) {
        checkEnabled()
        FrontierSerializer.computeSelectionAndMinimizeMemory(
            graph, topLevelTargets, activeDirectoriesMatcher
        )
    }

    public override fun shouldMinimizeMemory(): Boolean {
        checkEnabled()
        return minimizeMemory
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun createDisabled(): RemoteAnalysisCacheManager {
            return RemoteAnalysisCacheManager()
        }
    }
}
