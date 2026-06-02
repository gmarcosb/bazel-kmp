// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Rule.ALL_LABELS

/**
 * Common methods and utils to set up Blaze Runtime environments for [BlazeCommand] which
 * requires [QueryEnvironment]
 */
abstract class QueryEnvironmentBasedCommand : BlazeCommand {
    /**
     * Exit codes: 0 on successful evaluation. 1 if query evaluation did not complete. 2 if query
     * parsing failed. 3 if errors were reported but evaluation produced a partial result (only when
     * --keep_going is in effect.)
     */
    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        env.getEventBus()
            .post(
                NoBuildEvent(
                    env.getCommandName(),
                    env.commandStartTime,  /* separateFinishedEvent= */
                    true,  /* showProgress= */
                    true,  /* id= */
                    null
                )
            )
        val result: BlazeCommandResult = execInternal(env, options)
        try {
            MemoryProfiler.instance().markPhase(ProfilePhase.FINISH)
            com.google.devtools.build.lib.profiler.Profiler.instance().markPhase(ProfilePhase.FINISH)
        } catch (e: java.lang.InterruptedException) {
            return reportAndCreateInterruptResult(env, "Profile finish operation interrupted")
        }
        env.getEventBus()
            .post(
                NoBuildRequestFinishedEvent(
                    result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()
                )
            )
        return result
    }

    private fun execInternal(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val runtime: BlazeRuntime = env.getRuntime()
        val queryOptions: QueryOptions? = options.getOptions<O?>(QueryOptions::class.java)

        val threadsOption: LoadingPhaseThreadsOption? =
            options.getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java)
        val keepGoing: Boolean = options.getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing()

        val mainRepoTargetParser: TargetPattern.Parser?
        try {
            env.syncPackageLoading(options)
            val repoMapping: RepositoryMapping? =
                env.getSkyframeExecutor()
                    .getMainRepoMapping(keepGoing, threadsOption.getThreads(), env.getReporter())
            mainRepoTargetParser =
                Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping)
        } catch (e: RepositoryMappingResolutionException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        } catch (e: java.lang.InterruptedException) {
            return reportAndCreateInterruptResult(env, "query interrupted")
        } catch (e: AbruptExitException) {
            env.getReporter()
                .handle(com.google.devtools.build.lib.events.Event.error(null, "Unknown error: " + e.message))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        }

        var query: String? = null
        try {
            query = QueryOptionHelper.readQuery(queryOptions, options, env,  /* allowEmptyQuery =*/false)
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }

        val formatters: Iterable<com.google.devtools.build.lib.query2.query.output.OutputFormatter?> =
            runtime.getQueryOutputFormatters()
        val formatter: com.google.devtools.build.lib.query2.query.output.OutputFormatter? =
            com.google.devtools.build.lib.query2.query.output.OutputFormatters.getFormatter(
                formatters,
                queryOptions.outputFormat
            )
        if (formatter == null) {
            return reportAndCreateFailureResult(
                env,
                String.format(
                    "Invalid output format '%s'. Valid values are: %s",
                    queryOptions.outputFormat,
                    com.google.devtools.build.lib.query2.query.output.OutputFormatters.formatterNames(formatters)
                ),
                Query.Code.OUTPUT_FORMAT_INVALID
            )
        }

        val settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?> =
            queryOptions.toSettings()
        val streamResults: Boolean = QueryOutputUtils.shouldStreamResults(queryOptions, formatter)
        val useGraphlessQuery =
            queryOptions.useGraphlessQuery == com.google.devtools.common.options.TriState.YES
                    || (queryOptions.useGraphlessQuery == com.google.devtools.common.options.TriState.AUTO && streamResults)
        if (useGraphlessQuery && !streamResults) {
            return reportAndCreateFailureResult(
                env,
                String.format(
                    "--experimental_graphless_query requires --order_output=no or --order_output=auto and"
                            + " an --output option that supports streaming; valid values are: %s",
                    com.google.devtools.build.lib.query2.query.output.OutputFormatters.streamingFormatterNames(
                        formatters
                    )
                ),
                Query.Code.GRAPHLESS_PREREQ_UNMET
            )
        }

        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? =
            env.getSkyframeExecutor()
                .getEffectiveStarlarkSemantics(env.getOptions().getOptions(BuildLanguageOptions::class.java))
        val labelPrinter: LabelPrinter? =
            env.getOptions()
                .getOptions(QueryOptions::class.java)
                .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping())

        try {
            env.getRuntime().getQueryRuntimeHelperFactory().create(env, queryOptions).use { queryRuntimeHelper ->
                val result: Either<BlazeCommandResult?, QueryEvalResult?>
                newQueryEnvironment(
                    env,
                    keepGoing,
                    !streamResults,
                    env.getSkyframeExecutor()
                        .maybeGetHardcodedUniverseScope()
                        .orElse(getUniverseScope(queryOptions)),
                    threadsOption.getThreads(),
                    settings,
                    useGraphlessQuery,
                    mainRepoTargetParser,
                    labelPrinter
                ).use { queryEnv ->
                    result =
                        doQuery(
                            query, env, queryOptions, streamResults, formatter, queryEnv, queryRuntimeHelper
                        )
                }
                return result.map<BlazeCommandResult>(
                    java.util.function.Function.identity<Any?>(),
                    java.util.function.Function { queryEvalResult: QueryEvalResult? ->
                        if (queryEvalResult.isEmpty) {
                            env.getReporter().handle(com.google.devtools.build.lib.events.Event.info("Empty results"))
                        }
                        try {
                            queryRuntimeHelper.afterQueryOutputIsWritten()
                        } catch (e: QueryRuntimeHelperException) {
                            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                            return@map BlazeCommandResult.detailedExitCode(DetailedExitCode.of(e.getFailureDetail()))
                        } catch (e: java.lang.InterruptedException) {
                            return@map reportAndCreateInterruptResult(env, "query interrupted")
                        }
                        if (queryEvalResult.success) {
                            return@map BlazeCommandResult.success()
                        }
                        BlazeCommandResult.detailedExitCode(
                            DetailedExitCode.of(
                                ExitCode.PARTIAL_ANALYSIS_FAILURE,
                                queryEvalResult.detailedExitCode.getFailureDetail()
                            )
                        )
                    })
            }
        } catch (e: QueryRuntimeHelperException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
            return BlazeCommandResult.detailedExitCode(DetailedExitCode.of(e.getFailureDetail()))
        }
    }

    protected abstract fun doQuery(
        query: String?,
        env: CommandEnvironment?,
        queryOptions: QueryOptions?,
        streamResults: Boolean,
        formatter: com.google.devtools.build.lib.query2.query.output.OutputFormatter?,
        queryEnv: AbstractBlazeQueryEnvironment<Target?>?,
        queryRuntimeHelper: QueryRuntimeHelper?
    ): Either<BlazeCommandResult?, QueryEvalResult?>

    companion object {
        private fun getUniverseScope(queryOptions: QueryOptions): UniverseScope {
            if (!queryOptions.getUniverseScope().isEmpty()) {
                return UniverseScope.fromUniverseScopeList(
                    com.google.common.collect.ImmutableList.copyOf(queryOptions.getUniverseScope())
                )
            }
            return if (queryOptions.getInferUniverseScope())
                UniverseScope.INFER_FROM_QUERY_EXPRESSION
            else
                UniverseScope.EMPTY
        }

        fun newQueryEnvironment(
            env: CommandEnvironment,
            keepGoing: Boolean,
            orderedResults: Boolean,
            universeScope: UniverseScope?,
            loadingPhaseThreads: Int,
            settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
            useGraphlessQuery: Boolean,
            mainRepoTargetParser: TargetPattern.Parser?,
            labelPrinter: LabelPrinter?
        ): AbstractBlazeQueryEnvironment<Target?> {
            val walkableGraph: WalkableGraph =
                SkyframeExecutorWrappingWalkableGraph.of(env.getSkyframeExecutor())

            val targetProviderForQueryEnvironment: TargetProviderForQueryEnvironment =
                TargetProviderForQueryEnvironment(walkableGraph, env.getPackageManager())

            val progressReceiver: PackageProgressReceiver? =
                env.getSkyframeExecutor().getPackageProgressReceiver()
            if (progressReceiver != null) {
                progressReceiver.reset()
                env.getReporter().post(LoadingPhaseStartedEvent(progressReceiver))
            }

            val trackIncrementalState: Boolean =
                env.getOptions().getOptions(CommonCommandOptions::class.java).getTrackIncrementalState()

            return env.getRuntime()
                .getQueryEnvironmentFactory()
                .create(
                    env.getSkyframeExecutor().getQueryTransitivePackagePreloader(),
                    env.getSkyframeExecutor(),
                    targetProviderForQueryEnvironment,
                    env.getPackageManager(),
                    SkyframeTargetPatternEvaluator(env.getSkyframeExecutor()),
                    mainRepoTargetParser,
                    env.getRelativeWorkingDirectory(),
                    keepGoing,  /* strictScope= */
                    true,
                    orderedResults,
                    universeScope,
                    loadingPhaseThreads,
                    trackIncrementalState,  /* labelFilter= */
                    ALL_LABELS,
                    env.getReporter(),
                    settings,
                    env.getRuntime().getQueryFunctions(),
                    env.getPackageManager().getPackagePath(),
                    useGraphlessQuery,
                    labelPrinter
                )
        }

        private fun reportAndCreateInterruptResult(
            env: CommandEnvironment, message: String?
        ): BlazeCommandResult {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message))
        }

        private fun reportAndCreateFailureResult(
            env: CommandEnvironment, message: String?, detailedCode: Query.Code?
        ): BlazeCommandResult {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, detailedCode)
        }

        private fun createFailureResult(message: String?, detailedCode: Query.Code?): BlazeCommandResult {
            return BlazeCommandResult.detailedExitCode(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setQuery(Query.newBuilder().setCode(detailedCode))
                        .build()
                )
            )
        }
    }
}
