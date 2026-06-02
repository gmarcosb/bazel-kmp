// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** A [TransitiveInfoProvider] that creates extra actions.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ExtraActionArtifactsProvider private constructor(
    extraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>?,
    transitiveExtraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>?
) : com.google.devtools.build.lib.analysis.TransitiveInfoProvider {
    /** The outputs of the extra actions associated with this target.  */
    private val extraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>?

    private val transitiveExtraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>?

    /** Use [.create] instead.  */
    init {
        this.extraActionArtifacts = extraActionArtifacts
        this.transitiveExtraActionArtifacts = transitiveExtraActionArtifacts
    }

    /** The outputs of the extra actions associated with this target.  */
    fun getExtraActionArtifacts(): NestedSet<Artifact.DerivedArtifact?>? {
        return extraActionArtifacts
    }

    /** The outputs of the extra actions in the whole transitive closure.  */
    fun getTransitiveExtraActionArtifacts(): NestedSet<Artifact.DerivedArtifact?>? {
        return transitiveExtraActionArtifacts
    }

    companion object {
        @SerializationConstant
        val EMPTY: ExtraActionArtifactsProvider = ExtraActionArtifactsProvider(
            NestedSetBuilder.emptySet<Artifact.DerivedArtifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            NestedSetBuilder.emptySet<Artifact.DerivedArtifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER)
        )

        fun create(
            extraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>,
            transitiveExtraActionArtifacts: NestedSet<Artifact.DerivedArtifact?>
        ): ExtraActionArtifactsProvider? {
            if (extraActionArtifacts.isEmpty() && transitiveExtraActionArtifacts.isEmpty()) {
                return EMPTY
            }
            return ExtraActionArtifactsProvider(extraActionArtifacts, transitiveExtraActionArtifacts)
        }

        fun merge(
            providers: Iterable<ExtraActionArtifactsProvider>
        ): ExtraActionArtifactsProvider? {
            val artifacts: NestedSetBuilder<Artifact.DerivedArtifact?> =
                NestedSetBuilder.stableOrder<Artifact.DerivedArtifact?>()
            val transitiveExtraActionArtifacts: NestedSetBuilder<Artifact.DerivedArtifact?> =
                NestedSetBuilder.stableOrder<Artifact.DerivedArtifact?>()

            for (provider in providers) {
                artifacts.addTransitive(provider.getExtraActionArtifacts())
                transitiveExtraActionArtifacts.addTransitive(provider.getTransitiveExtraActionArtifacts())
            }
            return create(
                artifacts.build(), transitiveExtraActionArtifacts.build()
            )
        }
    }
}
