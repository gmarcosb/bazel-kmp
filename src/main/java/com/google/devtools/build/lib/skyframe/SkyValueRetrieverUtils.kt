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
package com.google.devtools.build.lib.skyframe

/**
 * A wrapper around [SkyValueRetriever] to handle Bazel-on-Skyframe specific logic, metrics
 * gathering, and error handling.
 */
object SkyValueRetrieverUtils {
    @Throws(java.lang.InterruptedException::class)
    fun retrieveRemoteSkyValue(
        key: SkyKey?,
        env: SkyFunction.Environment,
        analysisCachingDeps: RemoteAnalysisCacheReaderDepsProvider,
        stateSupplier: java.util.function.Supplier<out SerializableSkyKeyComputeState?>?
    ): RetrievalResult? {
        if (env.inErrorBubbling()) {
            // Remote retrieval during error bubbling causes incorrect error propagation. See b/449016469.
            return NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED)
        }

        if (analysisCachingDeps.shouldBailOutOnMissingFingerprint()) {
            return NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED)
        }

        val label: Label? =
            when (key) {
                -> alk.getLabel()
                -> ald.getLabel()
                -> artifact.getOwnerLabel()
                else -> throw java.lang.IllegalStateException("unexpected key: " + key.getCanonicalName())
            }

        if (label == null) {
            // If there's no label, there's no cached data.
            return NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED)
        }

        var retrievalResult: RetrievalResult? = null
        val state: RetrievalContext = env.getState<T?>(stateSupplier).getRetrievalContext()
        try {
            retrievalResult =
                SkyValueRetriever.tryRetrieve(
                    env,
                    DefaultDependOnFutureShim(env),
                    analysisCachingDeps.objectCodecs,
                    analysisCachingDeps.fingerprintValueService,
                    java.util.Objects.requireNonNull<T?>(analysisCachingDeps.analysisCacheClient),
                    key,
                    state,  /* frontierNodeVersion= */
                    analysisCachingDeps.skyValueVersion
                )
            analysisCachingDeps.recordRetrievalResult(retrievalResult, key)
        } catch (e: SerializationException) {
            // TODO: b/445242928 - also log this in BEP
            //
            // Don't crash the build if deserialization failed. Gracefully fallback to local evaluation.
            analysisCachingDeps.recordSerializationException(e, key)
            retrievalResult = NoCachedData(e.getReason())
        } catch (e: java.lang.RuntimeException) {
            throw e
        } catch (e: java.lang.InterruptedException) {
            throw e
        } finally {
            if (retrievalResult === Restart.RESTART) {
                state.addRestart()
            }
        }

        check(
            !(retrievalResult is
                    && v !is DeserializedSkyValue
        )) {
            ("deserialized SkyValue of type "
                    + v.getClass().getCanonicalName()
                    + " does not implement DeserializedSkyValue. Try using"
                    + " @AutoCodec(deserializedInterface = DeserializedSkyValue.class)")
        }

        return retrievalResult
    }
}
