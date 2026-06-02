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

/** Handles the 'aquery' command on the Blaze command line.  */
@Command(
    name = "aquery",
    buildPhase = ANALYZES,
    inheritsOptionsFrom = [BuildCommand::class],
    options = [AqueryOptions::class],
    usesConfigurationOptions = true,
    shortDescription = "Analyzes the given targets and queries the action graph.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label",
    help = "resource:aquery.txt"
)
class AqueryCommand : BlazeCommand {
    public override fun editOptions(optionsParser: com.google.devtools.common.options.OptionsParser) {
        try {
            optionsParser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.COMPUTED_DEFAULT,
                "Option required by aquery",
                com.google.common.collect.ImmutableList.of<String?>("--nobuild")
            )
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw java.lang.IllegalStateException("Aquery's known options failed to parse", e)
        }
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        // TODO(twerth): Reduce overlap with CqueryCommand.
        val aqueryOptions: AqueryOptions? = options.getOptions<O?>(AqueryOptions::class.java)
        QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(env)
        val queryCurrentSkyframeState: Boolean = aqueryOptions.getQueryCurrentSkyframeState()

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
            query = QueryOptionHelper.readQuery(aqueryOptions, options, env, queryCurrentSkyframeState)
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }

        val functions: com.google.common.collect.ImmutableMap<String?, QueryFunction?> = getFunctionsMap(env)

        // Query expression might be null in the case of --skyframe_state.
        val expr: QueryExpression?
        try {
            expr = if (query.isEmpty()) null else com.google.devtools.build.lib.query2.engine.QueryParser.parse(
                query,
                functions
            )
        } catch (e: com.google.devtools.build.lib.query2.engine.QuerySyntaxException) {
            val message: String? =
                java.lang.String.format(
                    "Error while parsing '%s': %s", QueryExpression.truncate(query), e.message
                )
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, Code.EXPRESSION_PARSE_FAILURE)
        }

        val topLevelTargets: com.google.common.collect.ImmutableList<String?>?
        try {
            topLevelTargets =
                QueryCommandUtils.getTopLevelTargets(
                    aqueryOptions.getUniverseScope(), expr, queryCurrentSkyframeState
                )
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return createFailureResult(
                com.google.common.base.Strings.nullToEmpty(e.message), Code.SKYFRAME_STATE_WITH_COMMAND_LINE_EXPRESSION
            )
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
                .build()

        val aqueryBuildTool: AqueryProcessor?

        try {
            aqueryBuildTool = AqueryProcessor(expr, mainRepoTargetParser)
        } catch (e: AqueryActionFilterException) {
            val message = e.getMessage() + "\n" + expr
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, Code.INVALID_AQUERY_EXPRESSION)
        }

        if (queryCurrentSkyframeState) {
            return aqueryBuildTool.dumpActionGraphFromSkyframe(env)
        }
        try {
            return BlazeCommandResult.detailedExitCode(
                BuildTool(env, aqueryBuildTool)
                    .processRequest(request, null, options)
                    .getDetailedExitCode()
            )
        } catch (e: java.lang.StackOverflowError) {
            val message = "Aquery output was too large to handle: " + query
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, Code.AQUERY_OUTPUT_TOO_BIG)
        }
    }

    private fun getFunctionsMap(env: CommandEnvironment): com.google.common.collect.ImmutableMap<String?, QueryFunction?> {
        val functionsBuilder: com.google.common.collect.ImmutableMap.Builder<String?, QueryFunction?> =
            com.google.common.collect.ImmutableMap.builder<String?, QueryFunction?>()

        for (queryFunction in ActionGraphQueryEnvironment.FUNCTIONS) {
            functionsBuilder.put(queryFunction.name, queryFunction)
        }

        for (queryFunction in ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
            functionsBuilder.put(queryFunction.name, queryFunction)
        }

        for (queryFunction in env.getRuntime().getQueryFunctions()) {
            functionsBuilder.put(queryFunction.name, queryFunction)
        }
        return functionsBuilder.buildOrThrow()
    }

    companion object {
        private fun createFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setActionQuery(ActionQuery.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
