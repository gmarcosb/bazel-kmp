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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.LOADS

/** Command line wrapper for executing a query with blaze.  */
@Command(
    name = "query",
    buildPhase = LOADS,
    options = [CoreOptions::class // for --action_env, which affects the repo env
        , PackageOptions::class, QueryOptions::class, KeepGoingOption::class, LoadingPhaseThreadsOption::class
    ],
    help = "resource:query.txt",
    shortDescription = "Executes a dependency graph query.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label"
)
class QueryCommand : QueryEnvironmentBasedCommand() {
    public override fun doQuery(
        query: String?,
        env: CommandEnvironment,
        queryOptions: QueryOptions,
        streamResults: Boolean,
        formatter: com.google.devtools.build.lib.query2.query.output.OutputFormatter,
        queryEnv: AbstractBlazeQueryEnvironment<Target?>,
        queryRuntimeHelper: QueryRuntimeHelper
    ): Either<BlazeCommandResult?, QueryEvalResult?> {
        var expr: QueryExpression
        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("QueryExpression.parse")
                .use { closeable ->
                    expr = QueryExpression.parse(query, queryEnv)
                }
        } catch (e: com.google.devtools.build.lib.query2.engine.QuerySyntaxException) {
            val message: String? =
                java.lang.String.format(
                    "Error while parsing '%s': %s", QueryExpression.truncate(query), e.message
                )
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(null, message))
            return Either.ofLeft<A?, B?>(
                BlazeCommandResult.detailedExitCode(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(e.message)
                            .setQuery(
                                FailureDetails.Query.newBuilder()
                                    .setCode(FailureDetails.Query.Code.SYNTAX_ERROR)
                            )
                            .build()
                    )
                )
            )
        }

        try {
            formatter.verifyCompatible(queryEnv, expr)
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
            return Either.ofLeft<A?, B?>(BlazeCommandResult.failureDetail(e.getFailureDetail()))
        }

        expr = queryEnv.transformParsedQuery(expr)

        // This only applies to --order_output=auto. Instead of being written directly to the stream
        // by the callback, this option aggregates the results in the lexicographically sorted
        // aggregator first before using the StreamedFormatter to write it to stream later.
        // An exception to this is when somepath is used at the top level of the query expression.
        val lexicographicallySortOutput =
            QueryOutputUtils.lexicographicallySortOutput(queryOptions, formatter)
                    && !expr.isTopLevelSomePathFunction

        val out: java.io.OutputStream
        if (formatter.canBeBuffered()) {
            // There is no particular reason for the 16384 constant here, except its a multiple of the
            // gRPC buffer size. We mainly don't want to send each label individually because the output
            // stream is connected to gRPC, and every write gets converted to one gRPC call.
            out = BufferedOutputStream(queryRuntimeHelper.outputStreamForQueryOutput, 16384)
        } else {
            out = queryRuntimeHelper.outputStreamForQueryOutput
        }

        val callback: ThreadSafeOutputFormatterCallback<Target?>?
        val hashFunction: com.google.common.hash.HashFunction? =
            env.getRuntime().getFileSystem().getDigestFunction().getHashFunction()
        if (streamResults) {
            disableAnsiCharactersFiltering(env)
            val streamedFormatter: StreamedFormatter = (formatter as StreamedFormatter)
            streamedFormatter.setOptions(
                queryOptions,
                queryOptions.getAspectDeps().createResolver(env.getPackageManager(), env.getReporter()),
                hashFunction
            )
            streamedFormatter.setEventHandler(env.getReporter())
            if (lexicographicallySortOutput) {
                callback = QueryUtil.newLexicographicallySortedTargetAggregator()
            } else {
                callback = streamedFormatter.createStreamCallback(out, queryOptions, queryEnv)
            }
        } else {
            callback = QueryUtil.newOrderedAggregateAllOutputFormatterCallback<Target?>(queryEnv)
        }

        var result: QueryEvalResult
        var catastrophe = true
        try {
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance().profile("queryEnv.evaluateQuery")
                    .use { closeable ->
                        result = queryEnv.evaluateQuery(expr, callback)
                        catastrophe = false
                    }
            } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
                catastrophe = false
                // Keep consistent with reportBuildFileError()
                env.getReporter() // TODO(bazel-team): this is a kludge to fix a bug observed in the wild. We should make
                    // sure no null error messages ever get in.
                    .handle(com.google.devtools.build.lib.events.Event.error(if (e.message == null) e.toString() else e.message))
                return Either.ofLeft<BlazeCommandResult?, QueryEvalResult?>(
                    finalizeBlazeCommandResult(
                        ExitCode.ANALYSIS_FAILURE,
                        e
                    )
                )
            } catch (e: java.lang.InterruptedException) {
                catastrophe = false
                val ioException: IOException = callback.getIoException()
                if (ioException == null || ioException is ClosedByInterruptException) {
                    return reportAndCreateInterruptedResult(env)
                }
                return reportAndCreateIOExceptionResult(env, e.message)
            } catch (e: IOException) {
                catastrophe = false
                return reportAndCreateIOExceptionResult(env, e.message)
            } finally {
                if (!catastrophe) {
                    out.flush()
                }
            }
            if (!streamResults || lexicographicallySortOutput) {
                disableAnsiCharactersFiltering(env)
                try {
                    com.google.devtools.build.lib.profiler.Profiler.instance().profile("QueryOutputUtils.output")
                        .use { closeable ->
                            val targets: MutableSet<Target?>? =
                                (callback as AggregateAllOutputFormatterCallback<Target?, *>).result
                            QueryOutputUtils.output(
                                queryOptions,
                                result,
                                targets,
                                formatter,
                                out,
                                queryOptions
                                    .getAspectDeps()
                                    .createResolver(env.getPackageManager(), env.getReporter()),
                                env.getReporter(),
                                hashFunction,
                                queryEnv.getLabelPrinter()
                            )
                        }
                } catch (e: ClosedByInterruptException) {
                    return reportAndCreateInterruptedResult(env)
                } catch (e: java.lang.InterruptedException) {
                    return reportAndCreateInterruptedResult(env)
                } catch (e: IOException) {
                    return reportAndCreateIOExceptionResult(env, e.message)
                } finally {
                    out.flush()
                }
            }
        } catch (e: IOException) {
            return reportAndCreateFlushFailureResult(env, e)
        }

        return Either.ofRight<BlazeCommandResult?, QueryEvalResult?>(result)
    }

    companion object {
        /**
         * When Blaze is used with --color=no or not in a tty a ansi characters filter is set so that
         * we don't print fancy colors in non-supporting terminal outputs. But query output, specifically
         * the binary formatters, can print actual data that contain ansi bytes/chars. Because of that
         * we need to remove the filtering before printing any query result.
         */
        private fun disableAnsiCharactersFiltering(env: CommandEnvironment) {
            env.getReporter().switchToAnsiAllowingHandler()
        }

        private fun reportAndCreateFlushFailureResult(
            env: CommandEnvironment, e: IOException
        ): Either<BlazeCommandResult?, QueryEvalResult?> {
            val message = "Failed to flush query results: " + e.message
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return Either.ofLeft<A?, B?>(
                BlazeCommandResult.failureDetail(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setQuery(Query.newBuilder().setCode(Code.QUERY_RESULTS_FLUSH_FAILURE))
                        .build()
                )
            )
        }

        private fun reportAndCreateInterruptedResult(
            env: CommandEnvironment
        ): Either<BlazeCommandResult?, QueryEvalResult?> {
            val message = "query interrupted"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return Either.ofLeft<A?, B?>(
                BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message))
            )
        }

        private fun reportAndCreateIOExceptionResult(
            env: CommandEnvironment, message: String
        ): Either<BlazeCommandResult?, QueryEvalResult?> {
            val prefixedMessage = "I/O error: " + message
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(prefixedMessage))
            return Either.ofLeft<A?, B?>(
                BlazeCommandResult.failureDetail(
                    FailureDetail.newBuilder()
                        .setMessage(prefixedMessage)
                        .setQuery(Query.newBuilder().setCode(Code.OUTPUT_FORMATTER_IO_EXCEPTION))
                        .build()
                )
            )
        }

        private fun finalizeBlazeCommandResult(
            exitCode: ExitCode?, e: com.google.devtools.build.lib.query2.engine.QueryException
        ): BlazeCommandResult {
            return BlazeCommandResult.detailedExitCode(DetailedExitCode.of(exitCode, e.getFailureDetail()))
        }
    }
}
