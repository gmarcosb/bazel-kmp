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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES

/** Handles the 'cquery' command on the Blaze command line.  */
@Command(
    name = "cquery",
    buildPhase = ANALYZES,
    inheritsOptionsFrom = [com.google.devtools.build.lib.runtime.commands.TestCommand::class],
    options = [CqueryOptions::class],
    usesConfigurationOptions = true,
    shortDescription = "Loads, analyzes, and queries the specified targets w/ configurations.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label",
    help = "resource:cquery.txt"
)
class CqueryCommand : BlazeCommand {
    public override fun editOptions(optionsParser: com.google.devtools.common.options.OptionsParser) {
        val cqueryOptions: CqueryOptions? = optionsParser.getOptions<O?>(CqueryOptions::class.java)
        try {
            if (!cqueryOptions.getTransitions().equals(CqueryOptions.Transitions.NONE)) {
                optionsParser.parse(
                    com.google.devtools.common.options.OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                    "Option required by setting the --transitions flag",
                    com.google.common.collect.ImmutableList.of<String?>("--output=transitions")
                )
            }
            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                "Options required by cquery",
                com.google.common.collect.ImmutableList.of<String?>("--nobuild")
            )
            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                "cquery should include 'tags = [\"manual\"]' targets by default",
                com.google.common.collect.ImmutableList.of<String?>("--build_manual_tests")
            )
            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.SOFTWARE_REQUIREMENT,  // https://github.com/bazelbuild/bazel/issues/11078
                "cquery should not exclude test_suite rules",
                com.google.common.collect.ImmutableList.of<String?>("--noexpand_test_suites")
            )
            if (cqueryOptions.getShowRequiredConfigFragments() !== IncludeConfigFragmentsEnum.OFF) {
                optionsParser.parse(
                    com.google.devtools.common.options.OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                    "Options required by cquery's --show_config_fragments flag",
                    com.google.common.collect.ImmutableList.of<String?>(
                        "--include_config_fragments_provider="
                                + cqueryOptions.getShowRequiredConfigFragments()
                    )
                )
            }
            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.SOFTWARE_REQUIREMENT,
                "cquery should not exclude tests",
                com.google.common.collect.ImmutableList.of<String?>("--nobuild_tests_only")
            )
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw java.lang.IllegalStateException("Cquery's known options failed to parse", e)
        }
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val mainRepoTargetParser: TargetPattern.Parser?
        try {
            val repoMapping: RepositoryMapping? =
                env.getSkyframeExecutor()
                    .getMainRepoMapping(
                        env.getOptions().getOptions(KeepGoingOption::class.java).getKeepGoing(),
                        env.getOptions().getOptions(LoadingPhaseThreadsOption::class.java).getThreads(),
                        env.getReporter()
                    )
            mainRepoTargetParser =
                Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping)
        } catch (e: RepositoryMappingResolutionException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        } catch (e: java.lang.InterruptedException) {
            val errorMessage = "Fetch interrupted: " + e.message
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(errorMessage))
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode(errorMessage)
            )
        }

        var query: String? = null
        try {
            query =
                QueryOptionHelper.readQuery(
                    options.getOptions<O?>(CqueryOptions::class.java), options, env,  /* allowEmptyQuery= */false
                )
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }

        val functions: HashMap<String?, QueryFunction?> = HashMap<String?, QueryFunction?>()
        for (queryFunction in ConfiguredTargetQueryEnvironment.FUNCTIONS) {
            functions.put(queryFunction.name, queryFunction)
        }
        for (queryFunction in env.getRuntime().getQueryFunctions()) {
            functions.put(queryFunction.name, queryFunction)
        }
        val expr: QueryExpression
        try {
            expr = com.google.devtools.build.lib.query2.engine.QueryParser.parse(query, functions)
        } catch (e: com.google.devtools.build.lib.query2.engine.QuerySyntaxException) {
            val message: String? =
                java.lang.String.format(
                    "Error while parsing '%s': %s", QueryExpression.truncate(query), e.message
                )
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, Code.EXPRESSION_PARSE_FAILURE)
        }

        var topLevelTargets: MutableList<String?> = options.getOptions<O?>(CqueryOptions::class.java).getUniverseScope()
        val targetPatternSet: LinkedHashSet<String?> = LinkedHashSet<String?>()
        var targetsForProjectResolution: com.google.common.collect.ImmutableList<String?>? = null
        if (topLevelTargets.isEmpty()) {
            expr.collectTargetPatterns(targetPatternSet)
            topLevelTargets = java.util.ArrayList<String?>(targetPatternSet)
            if (expr is FunctionExpression
                && (expr.getFunction() is SomePathFunction
                        || expr.getFunction() is AllPathsFunction)
            ) {
                targetsForProjectResolution =
                    com.google.common.collect.ImmutableList.of<String?>(targetPatternSet.getFirst())
            }
        }
        val runtime: BlazeRuntime = env.getRuntime()

        val request: BuildRequest? =
            BuildRequest.builder()
                .setCommandName(javaClass.getAnnotation<A?>(Command::class.java).name())
                .setId(env.getCommandId())
                .setOptions(options)
                .setStartupOptions(runtime.getStartupOptionsProvider())
                .setOutErr(env.getReporter().getOutErr())
                .setTargets(topLevelTargets)
                .setStartTimeMillis(env.commandStartTime)
                .setCheckforActionConflicts(false)
                .setReportIncompatibleTargets(false)
                .build()
        QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(env)
        val detailedExitCode: DetailedExitCode? =
            BuildTool(env, CqueryProcessor(expr, mainRepoTargetParser))
                .processRequest(
                    request,  /* validator= */
                    null,  /* postBuildCallback= */
                    null,
                    options,
                    targetsForProjectResolution
                )
                .getDetailedExitCode()
        return BlazeCommandResult.detailedExitCode(detailedExitCode)
    }

    companion object {
        private fun createFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setConfigurableQuery(ConfigurableQuery.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
