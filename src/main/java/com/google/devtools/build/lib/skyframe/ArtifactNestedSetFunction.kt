// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionExecutionException

/**
 * A builder of values for [ArtifactNestedSetKey].
 * 
 * 
 * When an Action is executed with ActionExecutionFunction, the actions's input `NestedSet<Artifact>` could be evaluated as an [ArtifactNestedSetKey][1].
 * 
 * 
 * [ArtifactNestedSetFunction] then evaluates the [ArtifactNestedSetKey] by:
 * 
 * 
 * - Evaluating the directs elements as Artifacts.
 * 
 * 
 * - Evaluating the transitive elements as [ArtifactNestedSetKey]s.
 * 
 * 
 * [1] Heuristic: If the size of the NestedSet exceeds a certain threshold, we evaluate it as an
 * ArtifactNestedSetKey.
 */
internal class ArtifactNestedSetFunction(consumedArtifactsTrackerSupplier: java.util.function.Supplier<ConsumedArtifactsTracker?>) :
    SkyFunction {
    private val consumedArtifactsTrackerSupplier: java.util.function.Supplier<ConsumedArtifactsTracker?>

    init {
        this.consumedArtifactsTrackerSupplier = consumedArtifactsTrackerSupplier
    }

    @Throws(java.lang.InterruptedException::class, ArtifactNestedSetFunctionException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val artifactNestedSetKey: ArtifactNestedSetKey = skyKey as ArtifactNestedSetKey
        if (consumedArtifactsTrackerSupplier.get() != null) {
            artifactNestedSetKey.applyToDirectArtifacts(
                { x -> consumedArtifactsTrackerSupplier.get().registerConsumedArtifact(x) })
        }
        val depKeys: com.google.common.collect.ImmutableList<SkyKey?> = artifactNestedSetKey.getDirectDepKeys()
        val depsEvalResult: SkyframeLookupResult = env.getValuesAndExceptions(depKeys)

        val transitiveExceptionsBuilder: NestedSetBuilder<com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?>?> =
            NestedSetBuilder.stableOrder()
        var catastrophic = false
        var result: ArtifactNestedSetValue = ArtifactNestedSetValue.ALL_PRESENT

        // Throw a SkyFunctionException when a dep evaluation results in an exception.
        for (key in depKeys) {
            try {
                // Trigger the exception, if any.
                val value: SkyValue? =
                    depsEvalResult.getOrThrow<E1?, E2?, E3?>(
                        key,
                        SourceArtifactException::class.java,
                        ActionExecutionException::class.java,
                        ArtifactNestedSetEvalException::class.java
                    )
                if (value == null) {
                    continue
                }

                if (key is ArtifactNestedSetKey) {
                    if (value === ArtifactNestedSetValue.SOME_MISSING) {
                        result = ArtifactNestedSetValue.SOME_MISSING
                    }
                    continue
                }

                if (value is MissingArtifactValue) {
                    result = ArtifactNestedSetValue.SOME_MISSING
                }
            } catch (e: SourceArtifactException) {
                // SourceArtifactException is never catastrophic.
                transitiveExceptionsBuilder.add(com.google.devtools.build.lib.util.Pair.of<A?, B?>(key, e))
            } catch (e: ActionExecutionException) {
                transitiveExceptionsBuilder.add(com.google.devtools.build.lib.util.Pair.of<A?, B?>(key, e))
                catastrophic = catastrophic or e.isCatastrophe()
            } catch (e: ArtifactNestedSetEvalException) {
                catastrophic = catastrophic or e.isCatastrophic
                transitiveExceptionsBuilder.addTransitive(e.getNestedExceptions())
            }
        }

        if (!transitiveExceptionsBuilder.isEmpty()) {
            val transitiveExceptions: NestedSet<com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?>?> =
                transitiveExceptionsBuilder.build()
            // The NestedSet of exceptions is usually small, hence flattening won't be too costly.
            val firstSkyKeyAndException: com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?> =
                transitiveExceptions.toList().get(0)
            throw ArtifactNestedSetFunctionException(
                ArtifactNestedSetEvalException(
                    ("Error evaluating artifact nested set. First exception: "
                            + firstSkyKeyAndException.getSecond()
                            + ", SkyKey: "
                            + firstSkyKeyAndException.getFirst()),
                    transitiveExceptions,
                    catastrophic
                )
            )
        }

        // This should only happen when all error handling is done.
        if (env.valuesMissing()) {
            return null
        }
        return result
    }

    /** Mainly used for error bubbling when evaluating direct/transitive children.  */
    private class ArtifactNestedSetFunctionException(e: ArtifactNestedSetEvalException) :
        SkyFunctionException(e, Transience.PERSISTENT) {
        val isCatastrophic: Boolean

        init {
            this.isCatastrophic = e.isCatastrophic
        }
    }

    /** Bundles the exceptions from the evaluation of the children keys together.  */
    internal class ArtifactNestedSetEvalException(
        message: String?,
        nestedExceptions: NestedSet<com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?>?>?,
        catastrophic: Boolean
    ) : java.lang.Exception(message) {
        private val nestedExceptions: NestedSet<com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?>?>?

        // Should be true if at least one child exception is catastrophic.
        val isCatastrophic: Boolean

        init {
            this.nestedExceptions = nestedExceptions
            this.isCatastrophic = catastrophic
        }

        fun getNestedExceptions(): NestedSet<com.google.devtools.build.lib.util.Pair<SkyKey?, java.lang.Exception?>?>? {
            return nestedExceptions
        }
    }
}
