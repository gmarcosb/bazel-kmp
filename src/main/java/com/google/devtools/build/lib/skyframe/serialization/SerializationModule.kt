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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** A [BlazeModule] to store Skyframe serialization lifecycle hooks.  */
open class SerializationModule : BlazeModule() {
    private var remoteAnalysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier? = null

    public override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories, builder: WorkspaceBuilder
    ) {
        if (!directories.inWorkspace()) {
            // Serialization only works when the Bazel server is invoked from a workspace.
            // Counter-example: invoking the Bazel server outside of a workspace to generate/dump
            // documentation HTML.
            return
        }
        // This is injected as a callback instead of evaluated eagerly to avoid forcing the somewhat
        // expensive AutoRegistry.get call on clients that don't require it.
        builder.setAnalysisCodecRegistrySupplier(
            getAnalysisCodecRegistrySupplier(runtime, directories)
        )

        remoteAnalysisCachingServicesSupplier = getAnalysisCachingServicesSupplier()
        builder.setRemoteAnalysisCachingServicesSupplier(remoteAnalysisCachingServicesSupplier)
    }

    public override fun commandComplete() {
        if (remoteAnalysisCachingServicesSupplier != null) {
            remoteAnalysisCachingServicesSupplier.resetCommandState()
        }
    }

    public override fun blazeShutdown() {
        if (remoteAnalysisCachingServicesSupplier != null) {
            remoteAnalysisCachingServicesSupplier.blazeShutdown()
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun getAnalysisCodecRegistrySupplier(
        runtime: BlazeRuntime, directories: BlazeDirectories
    ): java.util.function.Supplier<ObjectCodecRegistry?> {
        return java.util.function.Supplier {
            SerializationRegistrySetupHelpers.initializeAnalysisCodecRegistryBuilder(
                runtime.getRuleClassProvider(),
                SerializationRegistrySetupHelpers.makeReferenceConstants(
                    directories,
                    runtime.getRuleClassProvider(),
                    directories.getWorkspace().getBaseName()
                )
            )
                .build()
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun getAnalysisCachingServicesSupplier(): RemoteAnalysisCachingServicesSupplier {
        return InMemoryRemoteAnalysisCachingServicesSupplier.Companion.INSTANCE
    }

    /** A supplier that uses an in-memory fingerprint value service.  */
    private class InMemoryRemoteAnalysisCachingServicesSupplier

        : RemoteAnalysisCachingServicesSupplier {
        override fun getFingerprintValueService(): com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?> {
            return WRAPPED_SERVICE_INSTANCE
        }

        override fun resetCommandState() {}

        companion object {
            private val INSTANCE = InMemoryRemoteAnalysisCachingServicesSupplier()

            private val SERVICE_INSTANCE: FingerprintValueService = FingerprintValueService(
                ForkJoinPool.commonPool(),  // TODO: b/358347099 - use a persistent store
                FingerprintValueStore.Companion.inMemoryStore(),
                FingerprintValueCache(com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.NOT_LINKED),
                FingerprintValueService.Companion.NONPROD_FINGERPRINTER
            )

            private val WRAPPED_SERVICE_INSTANCE: com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?> =
                com.google.common.util.concurrent.Futures.immediateFuture<FingerprintValueService?>(
                    SERVICE_INSTANCE
                )
        }
    }
}
