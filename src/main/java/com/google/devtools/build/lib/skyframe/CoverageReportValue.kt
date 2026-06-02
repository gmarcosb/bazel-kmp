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

/** A SkyValue to store the coverage report Action and Artifacts.  */
class CoverageReportValue internal constructor(actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?) :
    BasicActionLookupValue(actions) {
    private class CoverageReportKey : ActionLookupKey {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.COVERAGE_REPORT
        }

        val configurationKey: BuildConfigurationKey?
            get() = null

        val label: Label?
            get() = null

        override fun toString(): String {
            return "CoverageReportKeySingleton"
        }
    }

    companion object {
        // There should only ever be one CoverageReportValue value in the graph.
        @kotlin.jvm.JvmField
        @SerializationConstant
        val COVERAGE_REPORT_KEY: ActionLookupKey = CoverageReportKey()
    }
}
