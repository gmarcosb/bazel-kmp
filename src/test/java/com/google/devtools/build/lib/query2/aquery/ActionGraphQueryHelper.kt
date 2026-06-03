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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/** Helper class for aquery test  */
class ActionGraphQueryHelper : PostAnalysisQueryHelper<ConfiguredTargetValue?>() {
    override fun getPostAnalysisQueryEnvironment(
        walkableGraph: WalkableGraph?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKeyCreator.AspectKey?, ConfiguredAspect?>?
    ): PostAnalysisQueryEnvironment<ConfiguredTargetValue?>? {
        val extraFunctions: com.google.common.collect.ImmutableList<QueryFunction?> =
            com.google.common.collect.ImmutableList.copyOf(ActionGraphQueryEnvironment.AQUERY_FUNCTIONS)
        return ActionGraphQueryEnvironment(
            keepGoing,
            getReporter(),
            extraFunctions,
            topLevelConfigurations,
            transitiveConfigurations,
            mainRepoTargetParser,
            analysisHelper.packageManager.getPackagePath(),
            { walkableGraph },
            settings,
            LabelPrinter.legacy()
        )
    }

    override fun getLabel(configuredTargetValue: ConfiguredTargetValue): String {
        return configuredTargetValue.getConfiguredTarget().getOriginalLabel().toString()
    }
}
