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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.ConfiguredAspect

/** Performs `cquery` processing.  */
class CqueryProcessor(
    queryExpression: QueryExpression?,
    mainRepoTargetParser: com.google.devtools.build.lib.cmdline.TargetPattern.Parser?
) : PostAnalysisQueryProcessor<CqueryNode?>(queryExpression, mainRepoTargetParser) {
    override fun getQueryOptions(env: CommandEnvironment): CommonQueryOptions? {
        return env.getOptions().getOptions<CqueryOptions?>(CqueryOptions::class.java)
    }

    override fun getQueryEnvironment(
        request: BuildRequest,
        env: CommandEnvironment,
        configurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        walkableGraph: WalkableGraph?
    ): ConfiguredTargetQueryEnvironment {
        val extraFunctions: com.google.common.collect.ImmutableList<QueryFunction?> =
            com.google.common.collect.ImmutableList.Builder<QueryFunction?>()
                .addAll(ConfiguredTargetQueryEnvironment.CQUERY_FUNCTIONS)
                .addAll(env.getRuntime().getQueryFunctions())
                .build()
        val cqueryOptions: CqueryOptions? = request.getOptions<CqueryOptions?>(CqueryOptions::class.java)
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            env.getSkyframeExecutor()
                .getEffectiveStarlarkSemantics(
                    env.getOptions().getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                )
        return ConfiguredTargetQueryEnvironment(
            request.getKeepGoing(),
            env.getReporter(),
            extraFunctions,
            configurations,
            transitiveConfigurations,
            topLevelAspects,
            mainRepoTargetParser,
            env.getPackageManager().getPackagePath(),
            java.util.function.Supplier { walkableGraph },
            cqueryOptions,
            request.getTopLevelArtifactContext(),
            request
                .getOptions<CqueryOptions?>(CqueryOptions::class.java)
                .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping())
        )
    }
}
