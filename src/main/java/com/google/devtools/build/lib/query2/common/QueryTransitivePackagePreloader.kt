// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.cmdline.Label

/**
 * Preloads transitive packages for query: prepopulates Skyframe with [TransitiveTargetValue]
 * objects for the transitive closure of requested targets. To be used when doing a large traversal
 * that benefits from loading parallelism.
 */
class QueryTransitivePackagePreloader(
    memoizingEvaluatorSupplier: java.util.function.Supplier<MemoizingEvaluator?>,
    evaluationContextBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.skyframe.EvaluationContext.Builder?>,
    bugReporter: BugReporter
) {
    private val memoizingEvaluatorSupplier: java.util.function.Supplier<MemoizingEvaluator?>
    private val evaluationContextBuilderSupplier: java.util.function.Supplier<com.google.devtools.build.skyframe.EvaluationContext.Builder?>
    private val bugReporter: BugReporter

    init {
        this.memoizingEvaluatorSupplier = memoizingEvaluatorSupplier
        this.evaluationContextBuilderSupplier = evaluationContextBuilderSupplier
        this.bugReporter = bugReporter
    }

    /** Loads the specified [TransitiveTargetValue]s.  */
    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    fun preloadTransitiveTargets(
        eventHandler: ExtendedEventHandler?,
        labelsToVisit: Iterable<Label?>,
        keepGoing: Boolean,
        parallelThreads: Int,
        callerForError: QueryExpression?
    ) {
        val valueNames: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (label in labelsToVisit) {
            valueNames.add(TransitiveTargetKey.of(label))
        }
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            evaluationContextBuilderSupplier
                .get()
                .setKeepGoing(keepGoing)
                .setParallelism(parallelThreads)
                .setEventHandler(eventHandler) // We're evaluating the set of TransitiveTarget nodes merely to ensure that all Package
                // nodes for all transitive dependencies of the given labels are in the graph, since its
                // these Package nodes that will be consumed by the query engine. We don't
                // do anything with cycles between TransitiveTarget nodes (even stronger: we don't even
                // consume TransitiveTarget nodes after this evaluation here, and no other node type in
                // the graph depends on them), so we disable cycle detection to save time in the
                // situation where there are a lot of large cycles involving TransitiveTarget nodes.
                .setDetectCycles(false)
                .build()
        val result: EvaluationResult<SkyValue?> =
            memoizingEvaluatorSupplier.get().evaluate<SkyValue?>(valueNames, evaluationContext)
        if (!result.hasError()) {
            return
        }
        if (callerForError != null) {
            maybeThrowQueryExceptionForResultWithError(
                result, labelsToVisit, callerForError, "preloading transitive closure", bugReporter
            )
            return
        }
        if (keepGoing && result.getCatastrophe() == null) {
            // keep-going must have completed every in-flight node if there was no catastrophe.
            return
        }

        // At the beginning of every Skyframe evaluation, the evaluator first deletes nodes that were
        // incomplete in the previous evaluation. The query may do later Skyframe evaluations (possibly
        // because this pre-evaluation failed!), so we prevent the first such evaluation from doing
        // unexpected deletions, which can lead to subtle threadpool issues.
        //
        // This is unnecessary in case there is a cycle, but not worth optimizing for.
        memoizingEvaluatorSupplier.get()
            .evaluate<SkyValue?>(com.google.common.collect.ImmutableList.of<SkyKey?>(), evaluationContext)
    }

    companion object {
        /**
         * Unless every top-level key in error depends on a cycle, throws a [QueryException]
         * (derived from an error in `result`).
         */
        @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
        fun maybeThrowQueryExceptionForResultWithError(
            result: EvaluationResult<SkyValue?>,
            roots: Iterable<out SkyKey?>?,
            caller: QueryExpression?,
            operation: String?
        ) {
            maybeThrowQueryExceptionForResultWithError(
                result, roots, caller, operation, BugReporter.defaultInstance()
            )
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
        fun maybeThrowQueryExceptionForResultWithError(
            result: EvaluationResult<SkyValue?>,
            roots: Iterable<out SkyKey?>?,
            caller: QueryExpression?,
            operation: String?,
            bugReporter: BugReporter
        ) {
            var exception: java.lang.Exception? = result.getCatastrophe()
            if (exception != null) {
                throw throwException(exception, caller, operation, result, bugReporter)
            }

            // Catastrophe not present: look at top-level keys now.
            var foundCycle = false
            for (errorInfo in result.errorMap().values) {
                if (!errorInfo.getCycleInfo().isEmpty()) {
                    foundCycle = true
                } else {
                    exception = errorInfo.getException()
                    if (exception is DetailedException) {
                        break
                    }
                }
            }

            if (exception != null) {
                throw throwException(exception, caller, operation, result, bugReporter)
            }
            com.google.common.base.Preconditions.checkState(
                foundCycle, "No cycle or exception found in result with error: %s %s", result, roots
            )
        }

        @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
        private fun throwException(
            exception: java.lang.Exception,
            caller: QueryExpression?,
            operation: String?,
            resultForDebugging: EvaluationResult<SkyValue?>?,
            bugReporter: BugReporter
        ): com.google.devtools.build.lib.query2.engine.QueryException? {
            val failureDetail: FailureDetails.FailureDetail?
            if (exception !is DetailedException) {
                bugReporter.sendNonFatalBugReport(
                    java.lang.IllegalStateException(
                        "Non-detailed exception found for " + operation + ": " + resultForDebugging,
                        exception
                    )
                )
                failureDetail =
                    FailureDetails.FailureDetail.newBuilder()
                        .setQuery(
                            FailureDetails.Query.newBuilder()
                                .setCode(FailureDetails.Query.Code.NON_DETAILED_ERROR)
                        )
                        .build()
            } else {
                failureDetail = (exception as DetailedException).getDetailedExitCode().getFailureDetail()
            }
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                caller, operation + " failed: " + exception.message, exception, failureDetail
            )
        }
    }
}
