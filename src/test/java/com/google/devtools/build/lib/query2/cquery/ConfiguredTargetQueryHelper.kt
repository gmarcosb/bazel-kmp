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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/**
 * [QueryHelper] for [ConfiguredTargetQueryTest]. Big warts: uses an [ ] to do analysis before query, but [AnalysisTestCase] is meant to be
 * inherited from, not composed. In particular, means that @Before and @After annotations of [ ] must be run manually. @BeforeClass and @AfterClass are completely ignored for
 * now.
 */
class ConfiguredTargetQueryHelper : PostAnalysisQueryHelper<CqueryNode?>() {
    override fun getPostAnalysisQueryEnvironment(
        walkableGraph: WalkableGraph?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKeyCreator.AspectKey?, ConfiguredAspect?>?
    ): ConfiguredTargetQueryEnvironment? {
        val extraFunctions: com.google.common.collect.ImmutableList<QueryFunction?> =
            com.google.common.collect.ImmutableList.copyOf(ConfiguredTargetQueryEnvironment.CQUERY_FUNCTIONS)
        return ConfiguredTargetQueryEnvironment(
            keepGoing,
            getReporter(),
            extraFunctions,
            topLevelConfigurations,
            transitiveConfigurations,
            topLevelAspects,
            mainRepoTargetParser,
            analysisHelper.packageManager.getPackagePath(),
            { walkableGraph },
            this.settings,
            null,
            LabelPrinter.legacy()
        )
    }

    override fun getLabel(target: CqueryNode): String {
        return target.getOriginalLabel().toString()
    }
}
