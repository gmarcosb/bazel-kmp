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

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue
import com.google.devtools.build.lib.skyframe.serialization.analysis.MatchIndicator
import com.google.devtools.build.lib.skyframe.serialization.analysis.NoMatch
import com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges

/** Container for [DeltaDepotValidator.matches] result types.  */
internal object NestedMatchResultTypes {
    @kotlin.jvm.JvmStatic
    fun createNestedMatchResult(analysisVersion: Int, sourceVersion: Int): NestedMatchResult {
        if (analysisVersion <= sourceVersion) {
            // When checking for an analysis match, the source version is irrelevant. When checking for an
            // execution match, the analysis version is included. If the analysis version is less than the
            // source match, then it dominates. In both cases, the source version can be ignored.
            return if (analysisVersion == VersionedChanges.Companion.NO_MATCH)
                NoMatch.NO_MATCH_RESULT
            else
                AnalysisMatch(analysisVersion)
        }
        return if (analysisVersion == VersionedChanges.Companion.NO_MATCH)
            SourceMatch(sourceVersion)
        else
            AnalysisAndSourceMatch(analysisVersion, sourceVersion)
    }

    /** [DeltaDepotValidator.matches] result type.  */
    internal interface NestedMatchResultOrFuture

    /** An immediate match result.  */
    internal interface NestedMatchResult : NestedMatchResultOrFuture


    /**
     * The delta matched the set of dependencies, meaning a **cache miss**.
     * 
     * @param version the minimum version where the analysis match was observed
     */
    @kotlin.jvm.JvmRecord
    internal data class AnalysisMatch(val version: Int) : NestedMatchResult, MatchIndicator {
        val isMatch: Boolean
            get() = true
    }

    /**
     * The delta didn't match analysis dependencies, but matched source dependencies, indicating a
     * **cache miss**.
     * 
     * @param sourceVersion the minimum version where the match was observed.
     */
    @kotlin.jvm.JvmRecord
    internal data class SourceMatch(val sourceVersion: Int) : NestedMatchResult

    /**
     * The delta matched both (analysis) dependencies and source dependencies.
     * 
     * 
     * `sourceVersion` must be **less than** `analysisVersion` for this to apply. An
     * analysis match would already invalidate the corresponding execution value, so if it matches
     * analysis first, the source match is irrelevant.
     * 
     * @param analysisVersion the minimum version where an analysis match was observed
     * @param sourceVersion the minimum version where the source match was observed.
     */
    @kotlin.jvm.JvmRecord
    internal data class AnalysisAndSourceMatch(val analysisVersion: Int, val sourceVersion: Int) : NestedMatchResult,
        MatchIndicator {
        val isMatch: Boolean
            get() = true

        init {
            com.google.common.base.Preconditions.checkArgument(
                sourceVersion < analysisVersion,
                "sourceVersion=%s must be less than analysisVersion=%s",
                sourceVersion,
                analysisVersion
            )
        }
    }

    /** A future match result.  */
    internal class FutureNestedMatchResult
        (key: NestedDependencies?, consumer: java.util.function.BiConsumer<NestedDependencies?, NestedMatchResult?>?) :
        SettableFutureKeyedValue<FutureNestedMatchResult?, NestedDependencies?, NestedMatchResult?>(key, consumer),
        NestedMatchResultOrFuture
}
