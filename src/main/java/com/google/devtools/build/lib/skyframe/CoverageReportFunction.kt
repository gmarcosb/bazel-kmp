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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A Skyframe function to calculate the coverage report Action and Artifacts.
 */
class CoverageReportFunction internal constructor(actionKeyContext: ActionKeyContext?) : SkyFunction {
    private val actionKeyContext: ActionKeyContext?

    init {
        this.actionKeyContext = actionKeyContext
    }

    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment?): SkyValue? {
        com.google.common.base.Preconditions.checkState(
            CoverageReportValue.COVERAGE_REPORT_KEY.equals(skyKey),
            "Expected %s for SkyKey but got %s instead",
            CoverageReportValue.COVERAGE_REPORT_KEY,
            skyKey
        )

        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? = COVERAGE_REPORT_KEY.get(env)
        if (actions == null) {
            return null
        }

        try {
            Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                actionKeyContext, actions, CoverageReportValue.COVERAGE_REPORT_KEY
            )
        } catch (e: ActionConflictException) {
            throw java.lang.IllegalStateException("Issues not expected in coverage: " + skyKey, e)
        } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
            throw java.lang.IllegalStateException("Issues not expected in coverage: " + skyKey, e)
        }
        return CoverageReportValue(actions)
    }

    companion object {
        val COVERAGE_REPORT_KEY: Precomputed<com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?> =
            Precomputed("coverage_report_actions")
    }
}
