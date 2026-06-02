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

import com.google.devtools.build.lib.actions.CommandLineExpansionException

/** Performs `aquery` processing.  */
class AqueryProcessor(
    queryExpression: QueryExpression?,
    mainRepoTargetParser: com.google.devtools.build.lib.cmdline.TargetPattern.Parser?
) : PostAnalysisQueryProcessor<ConfiguredTargetValue?>(queryExpression, mainRepoTargetParser) {
    private val actionFilters: AqueryActionFilter?

    init {
        actionFilters = buildActionFilters(queryExpression)
    }

    override fun getQueryOptions(env: CommandEnvironment): AqueryOptions? {
        return env.getOptions().getOptions<AqueryOptions?>(AqueryOptions::class.java)
    }

    /** Outputs the current action graph from Skyframe.  */
    fun dumpActionGraphFromSkyframe(env: CommandEnvironment): BlazeCommandResult {
        val aqueryOptions: AqueryOptions? = getQueryOptions(env)
        try {
            env.getRuntime().getQueryRuntimeHelperFactory().create(env, aqueryOptions).use { queryRuntimeHelper ->
                val printStream: PrintStream? =
                    if (queryRuntimeHelper.getOutputStreamForQueryOutput() == null)
                        null
                    else
                        PrintStream(queryRuntimeHelper.getOutputStreamForQueryOutput())
                try {
                    ActionGraphProtoOutputFormatterCallback.constructAqueryOutputHandler(
                        AqueryOutputHandler.OutputType.fromString(aqueryOptions.getOutputFormat()),
                        queryRuntimeHelper.getOutputStreamForQueryOutput(),
                        printStream
                    ).use { aqueryOutputHandler ->
                        val actionGraphDump: ActionGraphDump =
                            ActionGraphDump(
                                aqueryOptions.getIncludeCommandline(),
                                aqueryOptions.getIncludeArtifacts(),
                                aqueryOptions.getIncludePrunedInputs(),
                                actionFilters,
                                aqueryOptions.getIncludeParamFiles(),
                                aqueryOptions.getIncludeFileWriteContents(),
                                aqueryOutputHandler,
                                env.getReporter()
                            )
                        dumpActionGraph(env, aqueryOutputHandler, actionGraphDump)
                    }
                } catch (e: InvalidAqueryOutputFormatException) {
                    val message =
                        ("--skyframe_state must be used with --output=proto|textproto|jsonproto. "
                                + e.getMessage())
                    env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                    return getFailureResult(message, Code.SKYFRAME_STATE_PREREQ_UNMET)
                }
                return BlazeCommandResult.success()
            }
        } catch (e: CommandLineExpansionException) {
            val message = "Error while parsing command: " + e.getMessage()
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return getFailureResult(message, Code.COMMAND_LINE_EXPANSION_FAILURE)
        } catch (e: TemplateExpansionException) {
            val message = "Error while expanding template: " + e.getMessage()
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return getFailureResult(message, Code.TEMPLATE_EXPANSION_FAILURE)
        } catch (e: IOException) {
            val message =
                ("Error while emitting output: "
                        + (if (e.getMessage() != null) e.getMessage() else e.getClass().getName()))
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return getFailureResult(message, Code.OUTPUT_FAILURE)
        } catch (e: QueryRuntimeHelperException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }
    }

    override fun getQueryEnvironment(
        request: BuildRequest,
        env: CommandEnvironment,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        walkableGraph: WalkableGraph?
    ): PostAnalysisQueryEnvironment<ConfiguredTargetValue?> {
        val extraFunctions: com.google.common.collect.ImmutableList<QueryFunction?> =
            com.google.common.collect.ImmutableList.Builder<QueryFunction?>()
                .addAll(ActionGraphQueryEnvironment.AQUERY_FUNCTIONS)
                .addAll(env.getRuntime().getQueryFunctions())
                .build()
        val aqueryOptions: AqueryOptions? = request.getOptions<AqueryOptions?>(AqueryOptions::class.java)

        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            env.getSkyframeExecutor()
                .getEffectiveStarlarkSemantics(
                    env.getOptions().getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                )
        val queryEnvironment: ActionGraphQueryEnvironment =
            ActionGraphQueryEnvironment(
                request.getKeepGoing(),
                env.getReporter(),
                extraFunctions,
                topLevelConfigurations,
                transitiveConfigurations,
                mainRepoTargetParser,
                env.getPackageManager().getPackagePath(),
                java.util.function.Supplier { walkableGraph },
                aqueryOptions,
                request
                    .getOptions<AqueryOptions?>(AqueryOptions::class.java)
                    .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping())
            )
        queryEnvironment.setActionFilters(actionFilters)

        return queryEnvironment
    }

    /**
     * Return the action filters in the form { inputs: <pattern>, outputs: <pattern>, ... }
     * 
     * @param queryExpression The query expression from aquery command
     * @return the action filters
     * @throws AqueryActionFilterException if an aquery filter function is preceded by any other
     * function types
    </pattern></pattern> */
    @Throws(AqueryActionFilterException::class)
    private fun buildActionFilters(queryExpression: QueryExpression?): AqueryActionFilter? {
        val actionFiltersBuilder: com.google.devtools.build.lib.query2.aquery.AqueryActionFilter.Builder =
            AqueryActionFilter.builder()

        if (queryExpression !is FunctionExpression) {
            return actionFiltersBuilder.build()
        }

        var functionExpressionOptional: java.util.Optional<FunctionExpression> =
            java.util.Optional.of<FunctionExpression?>(queryExpression as FunctionExpression)

        var nonAqueryFilterFunctionExpression: FunctionExpression? = null

        // Unwrap the function layers
        // Validate that aquery filter functions (inputs, outputs, mnemonics) are not preceded
        // by any other function types
        while (functionExpressionOptional.isPresent()) {
            val functionExpression: FunctionExpression = functionExpressionOptional.get()

            if (functionExpression.getFunction() is ActionFilterFunction) {
                if (nonAqueryFilterFunctionExpression != null) {
                    throw AqueryActionFilterException(
                        ("aquery filter functions (inputs, outputs, mnemonic) produce actions, and therefore "
                                + "can't be the input of other function types: "
                                + nonAqueryFilterFunctionExpression.getFunction().getName())
                    )
                }

                val patternString: String? = functionExpression.getArgs().get(0).getWord()
                try {
                    actionFiltersBuilder.put(
                        actionFilterFunction.getName(),
                        java.util.regex.Pattern.compile(patternString)
                    )
                } catch (e: PatternSyntaxException) {
                    throw AqueryActionFilterException("Wrong query syntax: " + e.getMessage())
                }
            } else {
                nonAqueryFilterFunctionExpression = functionExpression
            }

            functionExpressionOptional = getNextFunctionExpression(functionExpression)
        }

        return actionFiltersBuilder.build()
    }

    /**
     * Unwrap input `functionExpression` to get the next FunctionExpression in the query
     * 
     * @param functionExpression the current function expression
     * @return the Optional of the next FunctionExpression in the query
     */
    private fun getNextFunctionExpression(
        functionExpression: FunctionExpression
    ): java.util.Optional<FunctionExpression> {
        for (arg in functionExpression.getArgs()) {
            if (arg.getType() == QueryEnvironment.ArgumentType.EXPRESSION
                && arg.getExpression() is FunctionExpression
            ) {
                return java.util.Optional.of<FunctionExpression?>(arg.getExpression() as FunctionExpression?)
            }
        }
        return java.util.Optional.empty<FunctionExpression?>()
    }

    /** Custom exception class for aquery filtering  */
    class AqueryActionFilterException internal constructor(message: String?) : java.lang.Exception(message)

    companion object {
        @Throws(CommandLineExpansionException::class, TemplateExpansionException::class, IOException::class)
        fun dumpActionGraph(
            env: CommandEnvironment,
            aqueryOutputHandler: AqueryOutputHandler?,
            actionGraphDump: ActionGraphDump?
        ) {
            if (aqueryOutputHandler is AqueryConsumingOutputHandler) {
                (env.getSkyframeExecutor() as SequencedSkyframeExecutor)
                    .dumpSkyframeStateInParallel(
                        actionGraphDump, aqueryOutputHandler as AqueryConsumingOutputHandler
                    )
            } else {
                (env.getSkyframeExecutor() as SequencedSkyframeExecutor).dumpSkyframeState(actionGraphDump)
            }
        }

        private fun getFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setActionQuery(ActionQuery.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
