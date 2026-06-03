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

import com.google.devtools.build.lib.pkgcache.PackageManager.PackageManagerStatistics

/**
 * This event is fired after the analysis phase is complete.
 */
class AnalysisPhaseCompleteEvent(
    topLevelTargets: MutableCollection<out ConfiguredTarget?>,
    targetsConfigured: TotalAndConfiguredTargetOnlyMetric?,
    actionsConstructed: TotalAndConfiguredTargetOnlyMetric?,
    actionsConstructedByMnemonic: com.google.common.collect.ImmutableMap<String?, Int?>?,
    private val timeInMs: Long,
    pkgManagerStats: PackageManagerStatistics?,
    analysisCacheDropped: Boolean
) {
    private val topLevelTargets: MutableCollection<ConfiguredTarget?>
    private val targetsConfigured: TotalAndConfiguredTargetOnlyMetric?
    private val pkgManagerStats: PackageManagerStatistics?
    private val actionsConstructed: TotalAndConfiguredTargetOnlyMetric?
    private val actionsConstructedByMnemonic: com.google.common.collect.ImmutableMap<String?, Int?>? = null
    private val analysisCacheDropped: Boolean

    init {
        this.topLevelTargets = com.google.common.collect.ImmutableList.copyOf<ConfiguredTarget?>(topLevelTargets)
            .also {
                this.targetsConfigured = it
            }<TotalAndConfiguredTargetOnlyMetric> com . google . common . base . Preconditions . checkNotNull < TotalAndConfiguredTargetOnlyMetric ? > (targetsConfigured)
        this.pkgManagerStats = pkgManagerStats
            .also {
                this.actionsConstructed = it
            }<TotalAndConfiguredTargetOnlyMetric> com . google . common . base . Preconditions . checkNotNull < TotalAndConfiguredTargetOnlyMetric ? > (actionsConstructed)
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.actionsConstructedByMnemonic = <ImmutableMap<String, Integer>>checkNotNull(actionsConstructedByMnemonic);
            """.trimMargin()
        )
        this.analysisCacheDropped = analysisCacheDropped
    }

    /**
     * Returns the set of active topLevelTargets remaining, which is a subset of the topLevelTargets
     * we attempted to analyze.
     */
    fun getTopLevelTargets(): MutableCollection<ConfiguredTarget?> {
        return topLevelTargets
    }

    /** Returns the number of targets/aspects configured during analysis.  */
    fun getTargetsConfigured(): TotalAndConfiguredTargetOnlyMetric? {
        return targetsConfigured
    }

    fun getTimeInMs(): Long {
        return timeInMs
    }

    /** Returns the actions constructed during this analysis.  */
    fun getActionsConstructed(): TotalAndConfiguredTargetOnlyMetric? {
        return actionsConstructed
    }

    fun getActionsConstructedByMnemonic(): com.google.common.collect.ImmutableMap<String?, Int?>? {
        return actionsConstructedByMnemonic
    }

    fun wasAnalysisCacheDropped(): Boolean {
        return analysisCacheDropped
    }

    /**
     * Returns package manager statistics.
     */
    fun getPkgManagerStats(): PackageManagerStatistics? {
        return pkgManagerStats
    }
}
