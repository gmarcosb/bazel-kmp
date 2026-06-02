// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.test.AnalysisFailure
import com.google.devtools.build.lib.collect.nestedset.Depset
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisFailureInfoApi
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisFailureInfoApi.AnalysisFailureInfoProviderApi

/**
 * Implementation of [AnalysisFailureInfoApi].
 * 
 * 
 * Encapsulates information about analysis-phase errors which would have occurred during a build.
 */
class AnalysisFailureInfo private constructor(causes: NestedSet<AnalysisFailure?>) :
    com.google.devtools.build.lib.packages.Info, AnalysisFailureInfoApi<AnalysisFailure?> {
    private val causes: NestedSet<AnalysisFailure?>

    init {
        this.causes = causes
    }

    override fun  /*<AnalysisFailure>*/getCauses(): Depset? {
        return Depset.of<AnalysisFailure?>(AnalysisFailure::class.java, causes)
    }

    val causesNestedSet: NestedSet<AnalysisFailure?>
        get() = causes

    /**
     * Provider implementation for [AnalysisFailureInfo].
     */
    class AnalysisFailureInfoProvider

        : BuiltinProvider<AnalysisFailureInfo?>("AnalysisFailureInfo", AnalysisFailureInfo::class.java),
        AnalysisFailureInfoProviderApi

    companion object {
        /** Singleton provider instance for [AnalysisFailureInfo].  */
        val provider: AnalysisFailureInfoProvider = AnalysisFailureInfoProvider()
            get() = Companion.field

        /**
         * Constructs and returns an [AnalysisFailureInfo] object representing the given failures.
         */
        fun forAnalysisFailures(failures: Iterable<AnalysisFailure?>?): AnalysisFailureInfo {
            return AnalysisFailureInfo(
                NestedSetBuilder.stableOrder<AnalysisFailure?>().addAll(failures).build()
            )
        }

        /**
         * Constructs and returns an [AnalysisFailureInfo] object representing the given sets of
         * failures. When combining nested sets of analysis failures, use this method instead of [ ][.forAnalysisFailures] so that the sets do not need to be flattened.
         */
        fun forAnalysisFailureSets(
            failures: Iterable<NestedSet<AnalysisFailure?>?>
        ): AnalysisFailureInfo {
            val fullSetBuilder: NestedSetBuilder<AnalysisFailure?> =
                NestedSetBuilder.stableOrder<AnalysisFailure?>()
            for (failure in failures) {
                fullSetBuilder.addTransitive(failure)
            }
            return AnalysisFailureInfo(fullSetBuilder.build())
        }
    }
}
