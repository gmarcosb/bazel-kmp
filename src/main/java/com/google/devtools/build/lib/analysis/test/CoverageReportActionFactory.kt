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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** A factory class to create coverage report actions.  */
interface CoverageReportActionFactory {
    /**
     * Wraps the necessary actions to get a coverage report as well as the final output artifacts. The
     * lcovWriteAction creates a file containing a set of lcov files. This file is used as an input
     * artifact for coverageReportAction. We are only interested about the output artifacts from
     * coverageReportAction.
     */
    class CoverageReportActionsWrapper(
        baselineReportAction: ActionAnalysisMetadata,
        coverageReportAction: ActionAnalysisMetadata,
        intermediateActions: MutableList<ActionAnalysisMetadata?>,
        actionKeyContext: ActionKeyContext?
    ) {
        private val baselineReportAction: ActionAnalysisMetadata
        private val coverageReportAction: ActionAnalysisMetadata
        private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>

        init {
            this.baselineReportAction = baselineReportAction
            this.coverageReportAction = coverageReportAction
            this.actions =
                com.google.common.collect.ImmutableList.builder<ActionAnalysisMetadata?>()
                    .add(baselineReportAction)
                    .add(coverageReportAction)
                    .addAll(intermediateActions)
                    .build()
            try {
                Actions.assignOwnersAndThrowIfConflict(
                    actionKeyContext, actions, CoverageReportValue.COVERAGE_REPORT_KEY
                )
            } catch (e: ActionConflictException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> {
            return actions
        }

        val coverageOutputs: Iterable<Artifact>
            get() = com.google.common.collect.Iterables.concat(
                baselineReportAction.getOutputs(),
                coverageReportAction.getOutputs()
            )

        val baselineReportArtifact: Artifact
            get() = baselineReportAction.getPrimaryOutput()

        val coverageReportArtifact: Artifact
            get() = coverageReportAction.getPrimaryOutput()
    }

    /**
     * Returns a CoverageReportActionsWrapper. May return null if it's not necessary to create such
     * Actions based on the input parameters and some other data available to the factory
     * implementation, such as command line options.
     */
    @Throws(java.lang.InterruptedException::class)
    fun createCoverageReportActionsWrapper(
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        eventBus: com.google.common.eventbus.EventBus?,
        directories: BlazeDirectories?,
        configuredTargets: MutableCollection<ConfiguredTarget?>?,
        targetsToTest: MutableCollection<ConfiguredTarget?>?,
        artifactFactory: ArtifactFactory?,
        actionKeyContext: ActionKeyContext?,
        actionLookupKey: ActionLookupKey?,
        workspaceName: String?
    ): CoverageReportActionsWrapper?
}
