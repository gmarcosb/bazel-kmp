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

import com.google.devtools.build.lib.analysis.AnalysisResult

/**
 * Version of [BuildTool] that handles all work for queries based on results from the analysis
 * phase.
 */
abstract class PostAnalysisQueryProcessor<T> internal constructor(
    queryExpression: QueryExpression?,
    mainRepoTargetParser: com.google.devtools.build.lib.cmdline.TargetPattern.Parser?
) : AnalysisPostProcessor {
    private val queryExpression: QueryExpression?
    protected val mainRepoTargetParser: com.google.devtools.build.lib.cmdline.TargetPattern.Parser?

    init {
        this.queryExpression = queryExpression
        this.mainRepoTargetParser = mainRepoTargetParser
    }

    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class, ExitException::class)
    override fun process(
        request: BuildRequest,
        env: CommandEnvironment,
        runtime: BlazeRuntime,
        analysisResult: AnalysisResult
    ) {
        if (queryExpression == null) {
            return
        }

        // This query will operate over the graph as constructed by analysis, but will also pick up
        // any nodes that are in the graph from prior builds, including dirty nodes. While they can't be
        // reached from the scope of the current build via deps, they can appear in rdeps, which results
        // in the processor doing unnecessary work or even expecting stale keys to be present. Clean
        // them up now, which requires ignoring the value of --version_window_for_dirty_node_gc.
        // TODO: b/71905538 - Keeping state from previous builds around makes the results not
        //  reproducible at the level of a single command. Either tolerate, or wipe the analysis graph
        //  beforehand if this option is specified, or add another option to wipe if desired
        //  (SkyframeExecutor#handleAnalysisInvalidatingChange should be sufficient).
        env.getSkyframeExecutor().deleteOldNodes( /* versionWindowForDirtyGc= */0)
        env.getSkyframeExecutor().applyInvalidation(env.getReporter())
        if (!env.getSkyframeExecutor().tracksStateForIncrementality()) {
            throw ExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(
                            ("Queries based on analysis results are not allowed if incrementality state"
                                    + " is not being kept. Pass --track_incremental_state to enable this"
                                    + " feature.")
                        )
                        .setQuery(Query.newBuilder().setCode(Query.Code.ANALYSIS_QUERY_PREREQ_UNMET))
                        .build()
                )
            )
        }

        try {
            env.getRuntime().getQueryRuntimeHelperFactory().create(env, getQueryOptions(env))
                .use { queryRuntimeHelper ->
                    doPostAnalysisQuery(
                        request,
                        env,
                        runtime,
                        TopLevelConfigurations(analysisResult.getTopLevelTargetsWithConfigs()),
                        analysisResult.getAspectsMap(),
                        env.getSkyframeExecutor().getTransitiveConfigurationKeys(),
                        queryRuntimeHelper,
                        queryExpression
                    )
                }
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            val errorMessage = "Error doing post analysis query"
            if (!request.getKeepGoing()) {
                throw ViewCreationFailedException(errorMessage, e.getFailureDetail(), e)
            }
            env.getReporter().error(null, errorMessage + ": " + e.getFailureDetail().getMessage())
        } catch (e: IOException) {
            val errorMessage = "I/O error doing post analysis query"
            val failureDetail: FailureDetail =
                FailureDetail.newBuilder()
                    .setMessage(errorMessage + ": " + e.getMessage())
                    .setQuery(Query.newBuilder().setCode(Query.Code.OUTPUT_FORMATTER_IO_EXCEPTION))
                    .build()
            if (!request.getKeepGoing()) {
                throw ViewCreationFailedException(errorMessage, failureDetail, e)
            }
            env.getReporter().error(null, failureDetail.getMessage())
        } catch (e: QueryRuntimeHelperException) {
            throw ExitException(DetailedExitCode.of(e.getFailureDetail()))
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            throw ExitException(
                DetailedExitCode.of(
                    ExitCode.COMMAND_LINE_ERROR,
                    FailureDetail.newBuilder()
                        .setMessage(e.getMessage())
                        .setActionQuery(
                            ActionQuery.newBuilder().setCode(ActionQuery.Code.INCORRECT_ARGUMENTS)
                        )
                        .build()
                )
            )
        }
    }

    protected abstract fun getQueryOptions(env: CommandEnvironment?): CommonQueryOptions?

    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getQueryEnvironment(
        request: BuildRequest?,
        env: CommandEnvironment?,
        topLevelConfigurations: TopLevelConfigurations?,
        transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?>?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        walkableGraph: WalkableGraph?
    ): PostAnalysisQueryEnvironment<T?>

    @Throws(
        java.lang.InterruptedException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        IOException::class,
        QueryRuntimeHelperException::class,
        com.google.devtools.common.options.OptionsParsingException::class
    )
    private fun doPostAnalysisQuery(
        request: BuildRequest?,
        env: CommandEnvironment,
        runtime: BlazeRuntime,
        topLevelConfigurations: TopLevelConfigurations?,
        topLevelAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        transitiveConfigurationKeys: MutableCollection<SkyKey?>?,
        queryRuntimeHelper: QueryRuntimeHelper,
        queryExpression: QueryExpression?
    ) {
        val walkableGraph: WalkableGraph =
            SkyframeExecutorWrappingWalkableGraph.of(env.getSkyframeExecutor())
        val transitiveConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?> =
            getTransitiveConfigurations(transitiveConfigurationKeys, walkableGraph)

        val postAnalysisQueryEnvironment: PostAnalysisQueryEnvironment<T?> =
            getQueryEnvironment(
                request,
                env,
                topLevelConfigurations,
                transitiveConfigurations,
                topLevelAspects,
                walkableGraph
            )

        val callbacks: Iterable<NamedThreadSafeOutputFormatterCallback<T?>?> =
            postAnalysisQueryEnvironment.getDefaultOutputFormatters(
                postAnalysisQueryEnvironment.getAccessor(),
                env.getReporter(),
                queryRuntimeHelper.getOutputStreamForQueryOutput(),
                env.getSkyframeExecutor(),
                runtime.getRuleClassProvider(),
                env.getPackageManager(),
                env.getSkyframeExecutor()
                    .getEffectiveStarlarkSemantics(
                        env.getOptions().getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java)
                    )
            )
        val outputFormat: String? = postAnalysisQueryEnvironment.getOutputFormat()
        val callback: NamedThreadSafeOutputFormatterCallback<T?>? =
            NamedThreadSafeOutputFormatterCallback.selectCallback<T?>(outputFormat, callbacks)
        if (callback == null) {
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format(
                    "Invalid output format '%s'. Valid values are: %s",
                    outputFormat, NamedThreadSafeOutputFormatterCallback.callbackNames<T?>(callbacks)
                )
            )
        }

        // A certain subset of output formatters support "streaming" results - the formatter is called
        // multiple times where each call has only a some of the full query results (see
        // StreamedOutputFormatter for details). cquery and aquery don't do this. But the reason is
        // subtle and hard to follow. Post-analysis output formatters inherit from Callback, which
        // declares "void process(Iterable<T> partialResult)". Its javadoc says that the subinterface
        // BatchCallback may stream partial results. But post-analysis callbacks don't inherit
        // BatchCallback!
        //
        // To protect against accidental feature regression (like implementing a callback that
        // accidentally inherits BatchCallback), we explicitly disable streaming here. The aggregating
        // callback collects the entire query's results, even if the query was evaluated in a streaming
        // manner. Note that streaming query evaluation is a distinct concept from streaming output
        // formatting. Once the complete query finishes, we replay the full results back to the original
        // callback. That way callback implementations can safely assume they're only called once and
        // the results for that call are indeed complete.
        val aggregateResultsCallback: AggregateAllOutputFormatterCallback<T?, MutableSet<T?>?> =
            QueryUtil.newOrderedAggregateAllOutputFormatterCallback<T?>(postAnalysisQueryEnvironment)
        val result: QueryEvalResult =
            postAnalysisQueryEnvironment.evaluateQuery(queryExpression, aggregateResultsCallback)
        if (result.isEmpty()) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.info("Empty query results"))
        }
        try {
            callback.start()
            callback.process(aggregateResultsCallback.getResult())
            callback.close( /* failFast= */!result.getSuccess())
        } catch (e: IoExceptionInterruptedException) {
            throw e.getCause() as IOException?
        }

        queryRuntimeHelper.afterQueryOutputIsWritten()
    }

    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun getTransitiveConfigurations(
            transitiveConfigurationKeys: MutableCollection<SkyKey?>?, graph: WalkableGraph
        ): com.google.common.collect.ImmutableMap<String?, BuildConfigurationValue?> {
            // BuildConfigurationKey and BuildConfigurationValue should be 1:1
            // so merge function intentionally omitted
            return graph.getSuccessfulValues(transitiveConfigurationKeys).values().stream()
                .map<BuildConfigurationValue?>(java.util.function.Function { obj: SkyValue? ->
                    BuildConfigurationValue::class.java.cast(
                        obj
                    )
                })
                .sorted(java.util.Comparator.comparing<BuildConfigurationValue?, Any?>(BuildConfigurationValue::checksum))
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        BuildConfigurationValue::checksum,
                        java.util.function.Function.identity<Any?>()
                    )
                )
        }
    }
}
