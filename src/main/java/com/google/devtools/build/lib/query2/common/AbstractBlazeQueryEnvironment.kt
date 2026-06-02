// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.BazelModuleContext.LoadGraphVisitor

/**
 * [QueryEnvironment] that can evaluate queries to produce a result, and implements as much of
 * QueryEnvironment as possible while remaining mostly agnostic as to the objects being stored.
 */
abstract class AbstractBlazeQueryEnvironment<T>
protected constructor(
    keepGoing: Boolean,
    strictScope: Boolean,
    labelFilter: java.util.function.Predicate<Label?>,
    eventHandler: ExtendedEventHandler?,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>,
    extraFunctions: Iterable<QueryFunction?>,
    labelPrinter: LabelPrinter?
) : QueryEnvironment<T?>, java.lang.AutoCloseable {
    protected var eventHandler: ErrorSensingEventHandler<DetailedExitCode?>
    protected val keepGoing: Boolean
    protected val strictScope: Boolean

    protected val dependencyFilter: DependencyFilter
    protected val labelFilter: java.util.function.Predicate<Label?>

    protected val settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>
    protected val extraFunctions: MutableList<QueryFunction?>
    protected val labelPrinter: LabelPrinter?

    init {
        this.eventHandler = ErrorSensingEventHandler(eventHandler, DetailedExitCode::class.java)
        this.keepGoing = keepGoing
        this.strictScope = strictScope
        this.dependencyFilter = constructDependencyFilter(settings)
        this.labelFilter = labelFilter
        this.settings =
            com.google.common.collect.Sets.immutableEnumSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(
                settings
            )
        this.extraFunctions = com.google.common.collect.ImmutableList.copyOf<QueryFunction?>(extraFunctions)
        this.labelPrinter = labelPrinter
    }

    abstract override fun close()

    override fun getLabelPrinter(): LabelPrinter? {
        return labelPrinter
    }

    /**
     * Used by [.evaluateQuery] to evaluate the given `expr`. The caller, ([ ][.evaluateQuery]), is responsible for managing `callback`.
     */
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected open fun evalTopLevelInternal(expr: QueryExpression, callback: OutputFormatterCallback<T?>) {
        (eval(expr, createEmptyContext(), callback) as QueryTaskFutureImpl<java.lang.Void?>).checked
    }

    protected fun createEmptyContext(): QueryExpressionContext<T?> {
        return QueryExpressionContext.Companion.empty<T?>()
    }

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        IOException::class,
        java.lang.InterruptedException::class
    )
    abstract fun evaluateQuery(
        expr: QueryExpression?, callback: ThreadSafeOutputFormatterCallback<T?>?
    ): QueryEvalResult?

    /**
     * Evaluate the specified query expression in this environment, streaming results to the given
     * `callback`. `callback.start()` will be called before query evaluation and `callback.close()` will be unconditionally called at the end of query evaluation (i.e.
     * regardless of whether it was successful).
     * 
     * @return a [QueryEvalResult] object that contains the resulting set of targets and a bit
     * to indicate whether errors occurred during evaluation; note that the success status can
     * only be false if `--keep_going` was in effect
     * @throws QueryException if the evaluation failed and `--nokeep_going` was in effect
     * @throws IOException for output formatter failures from `callback`
     */
    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class,
        IOException::class
    )
    protected fun evaluateQueryInternal(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<T?>
    ): QueryEvalResult {
        val emptySensingCallback = EmptinessSensingCallback<T?>(callback)
        val startTime: Long = java.lang.System.currentTimeMillis()
        // In the --nokeep_going case, errors are reported in the order in which the patterns are
        // specified; using a linked hash set here makes sure that the left-most error is reported.
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        Profiler.instance().profile("collectTargetPatterns").use { closeable ->
            expr.collectTargetPatterns(targetPatternSet)
        }
        try {
            Profiler.instance().profile("preloadOrThrow").use { closeable ->
                preloadOrThrow(expr, targetPatternSet)
            }
        } catch (e: TargetParsingException) {
            // Unfortunately, by evaluating the patterns in parallel, we lose some location information.
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                expr,
                e.getMessage(),
                e.getDetailedExitCode().getFailureDetail()
            )
        }
        var ioExn: IOException? = null
        var failFast = true
        try {
            emptySensingCallback.start()
            evalTopLevelInternal(expr, emptySensingCallback)
            failFast = false
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(e, expr)
        } finally {
            try {
                emptySensingCallback.close(failFast)
            } catch (e: IOException) {
                // Only throw this IOException if we weren't about to throw a different exception.
                ioExn = e
            }
        }
        if (ioExn != null) {
            throw ioExn
        }
        val elapsedTime: Long = java.lang.System.currentTimeMillis() - startTime
        if (elapsedTime > 1) {
            logger.atInfo().log("Spent %d milliseconds evaluating query", elapsedTime)
        }

        if (eventHandler.hasErrors()) {
            val detailedExitCode: DetailedExitCode? = eventHandler.getErrorProperty()
            if (!keepGoing) {
                if (detailedExitCode != null) {
                    throw com.google.devtools.build.lib.query2.engine.QueryException(
                        "Evaluation of query \"" + expr.toTrunctatedString() + "\" failed",
                        detailedExitCode.getFailureDetail()
                    )
                }
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    ("Evaluation of query \""
                            + expr.toTrunctatedString()
                            + "\" failed due to BUILD file errors"),
                    Query.Code.BUILD_FILE_ERROR
                )
            }
            eventHandler.handle(
                Event.warn("--keep_going specified, ignoring errors. Results may be inaccurate")
            )
            if (detailedExitCode != null) {
                return QueryEvalResult.Companion.failure(emptySensingCallback.isEmpty, detailedExitCode)
            } else {
                return QueryEvalResult.Companion.failure(
                    emptySensingCallback.isEmpty,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                ("Evaluation of query \""
                                        + expr.toTrunctatedString()
                                        + "\" failed due to BUILD file errors")
                            )
                            .setQuery(Query.newBuilder().setCode(Code.BUILD_FILE_ERROR))
                            .build()
                    )
                )
            }
        }
        return QueryEvalResult.Companion.success(emptySensingCallback.isEmpty)
    }

    override fun <R> immediateSuccessfulFuture(value: R?): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl<R?>(com.google.common.util.concurrent.Futures.immediateFuture<R?>(value))
    }

    override fun <R> immediateFailedFuture(e: com.google.devtools.build.lib.query2.engine.QueryException): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl<R?>(com.google.common.util.concurrent.Futures.immediateFailedFuture<R?>(e))
    }

    override fun <R> immediateCancelledFuture(): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl<R?>(com.google.common.util.concurrent.Futures.immediateCancelledFuture<R?>())
    }

    override fun eval(
        expr: QueryExpression,
        context: QueryExpressionContext<T?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<T?>
    ): QueryTaskFuture<java.lang.Void?> {
        // Not all QueryEnvironment implementations embrace the async+streaming evaluation framework. In
        // particular, the streaming callbacks employed by functions like 'deps' use
        // QueryEnvironment#buildTransitiveClosure. So if the implementation of that method does some
        // heavyweight blocking work, then it's best to do this blocking work in a single batch.
        // Importantly, the callback we pass in needs to maintain order.
        val aggregateAllCallback: AggregateAllCallback<T?, out MutableSet<T?>?> =
            QueryUtil.newOrderedAggregateAllOutputFormatterCallback<T?>(this)
        val evalAllFuture: QueryTaskFuture<java.lang.Void?> = expr.eval<T?>(this, context, aggregateAllCallback)
        return whenSucceedsCall<java.lang.Void?>(
            evalAllFuture,
            QueryTaskCallable {
                callback.process(aggregateAllCallback.getResult())
                null
            })
    }

    /**
     * Wrapper for evaluating query expression in a non-streaming blaze query environment.
     * 
     * 
     * In [AbstractBlazeQueryEvaluateExpressionImpl], `futureTask` is created only
     * after [.eval] provides the callback implementation. So creating an [ ] instance and calling [.eval] method
     * should have the same behavior as directly calling `AbstractBlazeQueryEnvironment#eval(QueryExpression, QueryExpressionContext, Callback)` above.
     */
    protected inner class AbstractBlazeQueryEvaluateExpressionImpl private constructor(
        expr: QueryExpression,
        context: QueryExpressionContext<T?>?
    ) : EvaluateExpression<T?> {
        private val expression: QueryExpression
        private val context: QueryExpressionContext<T?>?
        private var queryTaskFuture: QueryTaskFutureImpl<java.lang.Void?>? = null

        init {
            this.expression = expr
            this.context = context
        }

        override fun eval(callback: com.google.devtools.build.lib.query2.engine.Callback<T?>): QueryTaskFuture<java.lang.Void?>? {
            queryTaskFuture =
                this@AbstractBlazeQueryEnvironment.eval(
                    expression,
                    context,
                    callback
                ) as QueryTaskFutureImpl<java.lang.Void?>?
            return queryTaskFuture
        }

        override fun gracefullyCancel(): Boolean {
            // For non-SkyQueryEnvironment-descended environments, there is no need to cancel the future
            // task, so this should be a no-op implementation.
            return false
        }

        val isUngracefullyCancelled: Boolean
            get() {
                if (queryTaskFuture == null) {
                    return false
                }

                // Since `#gracefullyCancel` is a no-op for `AbstractBlazeQueryEvaluateExpressionImpl`
                // instance, any situation causing the `queryTaskFuture` to be cancelled should be regarded as
                // an ungraceful behavior.
                return queryTaskFuture!!.isCancelled
            }
    }

    override fun createEvaluateExpression(
        expr: QueryExpression, context: QueryExpressionContext<T?>?
    ): EvaluateExpression<T?>? {
        return AbstractBlazeQueryEvaluateExpressionImpl(expr, context)
    }

    override fun <R> execute(callable: QueryTaskCallable<R?>): QueryTaskFuture<R?> {
        try {
            return immediateSuccessfulFuture<R?>(callable.call())
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            return immediateFailedFuture<R?>(e)
        } catch (e: java.lang.InterruptedException) {
            return immediateCancelledFuture<R?>()
        }
    }

    override fun <R> executeAsync(callable: QueryTaskAsyncCallable<R?>): QueryTaskFuture<R?>? {
        return callable.call()
    }

    override fun <R> whenSucceedsCall(
        future: QueryTaskFuture<*>, callable: QueryTaskCallable<R?>
    ): QueryTaskFuture<R?> {
        return whenAllSucceedCall<R?>(com.google.common.collect.ImmutableList.of(future), callable)
    }

    override fun whenAllSucceed(futures: Iterable<out QueryTaskFuture<*>?>): QueryTaskFuture<java.lang.Void?> {
        return whenAllSucceedCall<java.lang.Void?>(
            futures,
            com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment.Dummy.Companion.INSTANCE
        )
    }

    override fun <R> whenAllSucceedCall(
        futures: Iterable<out QueryTaskFuture<*>?>, callable: QueryTaskCallable<R?>
    ): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(
            com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(cast(futures))
                .call<R?>(callable, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        )
    }

    override fun <R> whenSucceedsOrIsCancelledCall(
        future: QueryTaskFuture<*>?, callable: QueryTaskCallable<R?>
    ): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.whenSucceedsOrIsCancelledCall<R?>(
            (future as QueryTaskFutureImpl<*>?)!!,
            callable,
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    override fun <T1, T2> transformAsync(
        future: QueryTaskFuture<T1?>?, function: com.google.common.base.Function<T1?, QueryTaskFuture<T2?>?>
    ): QueryTaskFuture<T2?>? {
        val futureImpl = future as QueryTaskFutureImpl<T1?>
        if (futureImpl.isDone) {
            // Due to how our subclasses use single-threaded query engines, in practice
            // futureImpl will always already be done. Therefore this is a fast-path to make it harder to
            // stack overflow on deeply nested expressions whose evaluation involves #transformAsync.
            //
            // TODO(b/283225081): Do something more effective and more pervasive.
            val t1: T1?
            try {
                t1 = futureImpl.checked
            } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
                return immediateFailedFuture<T2?>(e)
            } catch (e: java.lang.InterruptedException) {
                return immediateCancelledFuture<T2?>()
            }
            return function.apply(t1)
        }
        return QueryTaskFutureImpl.Companion.ofDelegate<T2?>(
            com.google.common.util.concurrent.Futures.transformAsync<T1?, T2?>(
                futureImpl,
                com.google.common.util.concurrent.AsyncFunction { input: T1? -> function.apply(input) as QueryTaskFutureImpl<T2?>? },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    private class EmptinessSensingCallback<T>(callback: OutputFormatterCallback<T?>) : OutputFormatterCallback<T?>() {
        private val callback: OutputFormatterCallback<T?>
        private val numTargets: AtomicInteger = AtomicInteger(0)

        init {
            this.callback = callback
        }

        @Throws(IOException::class)
        override fun start() {
            callback.start()
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun processOutput(partialResult: Iterable<T?>) {
            numTargets.addAndGet(com.google.common.collect.Iterables.size(partialResult))
            callback.processOutput(partialResult)
        }

        @Throws(java.lang.InterruptedException::class, IOException::class)
        override fun close(failFast: Boolean) {
            logger.atInfo().log("Saw %d targets in the output", numTargets.get())
            callback.close(failFast)
        }

        val isEmpty: Boolean
            get() = numTargets.get() == 0
    }

    open fun transformParsedQuery(queryExpression: QueryExpression?): QueryExpression? {
        return queryExpression
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun handleError(
        expression: QueryExpression, message: String?, detailedExitCode: DetailedExitCode?
    ) {
        if (!keepGoing) {
            if (detailedExitCode != null) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    expression,
                    message,
                    detailedExitCode.getFailureDetail()
                )
            } else {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException("Undetailed failure: " + message + " for " + expression)
                )
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    expression,
                    message,
                    Code.NON_DETAILED_ERROR
                )
            }
        }
        eventHandler.handle(createErrorEvent(expression, message, detailedExitCode))
    }

    @Throws(
        TargetNotFoundException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class
    )
    abstract fun getTarget(label: Label?): Target?

    /** Batch version of [.getTarget]. Missing targets are absent in the returned map.  */ // TODO(http://b/128626678): Implement and use this in more places.
    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    open fun getTargets(labels: Iterable<Label?>): MutableMap<Label?, Target?>? {
        val resultBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, Target?> =
            com.google.common.collect.ImmutableMap.builder<Label?, Target?>()
        for (label in labels) {
            val target: Target?
            try {
                target = getTarget(label)
            } catch (e: TargetNotFoundException) {
                logger.atInfo().withCause(e).atMostEvery(1, TimeUnit.SECONDS).log("Failure to load %s", label)
                continue
            }
            resultBuilder.put(label, target)
        }
        return resultBuilder.buildOrThrow()
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected fun validateScopeOfTargets(targets: MutableSet<Target>) {
        // Sets.filter would be more convenient here, but can't deal with exceptions.
        if (labelFilter !== com.google.common.base.Predicates.alwaysTrue<Label?>()) {
            // The labelFilter is always true for bazel query; it's only used for genquery rules.
            val targetIterator = targets.iterator()
            while (targetIterator.hasNext()) {
                val target = targetIterator.next()
                if (!validateScope(target.getLabel(), strictScope)) {
                    targetIterator.remove()
                }
            }
        }
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected fun validateScope(label: Label?, strict: Boolean): Boolean {
        if (!labelFilter.test(label)) {
            val error: String? = String.format("target '%s' is not within the scope of the query", label)
            if (strict) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    error,
                    Query.Code.TARGET_NOT_IN_UNIVERSE_SCOPE
                )
            } else {
                eventHandler.handle(Event.warn(error + ". Skipping"))
                return false
            }
        }
        return true
    }

    /** Abstract base class for [<].  */
    protected abstract class TransitiveLoadFilesHelperForTargets

        : TransitiveLoadFilesHelper<Target?> {
        override fun getPkgId(target: Target): PackageIdentifier {
            return target.getLabel().getPackageIdentifier()
        }

        @Throws(
            com.google.devtools.build.lib.query2.engine.QueryException::class,
            java.lang.InterruptedException::class
        )
        override fun visitLoads(
            originalTarget: Target,
            visitor: LoadGraphVisitor<com.google.devtools.build.lib.query2.engine.QueryException?, java.lang.InterruptedException?>?
        ) {
            originalTarget.getPackageDeclarations().visitLoadGraph(visitor)
        }
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun transitiveLoadFiles(
        targets: Iterable<T?>,
        alsoAddBuildFiles: Boolean,
        seenPackages: MutableSet<PackageIdentifier?>,
        seenBzlLabels: MutableSet<Label?>,
        uniquifier: Uniquifier<T?>,
        helper: TransitiveLoadFilesHelper<T?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<T?>
    ) {
        val result: java.util.ArrayList<T?> = java.util.ArrayList<T?>()
        for (target in targets) {
            val pkgId: PackageIdentifier? = helper.getPkgId(target)
            if (!seenPackages.add(pkgId)) {
                continue
            }

            if (alsoAddBuildFiles) {
                val buildFileTarget: T? = helper.getBuildFileTarget(target)
                if (uniquifier.unique(buildFileTarget)) {
                    result.add(buildFileTarget)
                }
            }

            helper.visitLoads(
                target,
                LoadGraphVisitor { bzlLabel ->
                    if (!seenBzlLabels.add(bzlLabel)) {
                        return@visitLoads false
                    }
                    val loadFileTarget: T? = helper.getLoadFileTarget(target, bzlLabel)
                    if (uniquifier.unique(loadFileTarget)) {
                        result.add(loadFileTarget)
                    }
                    if (alsoAddBuildFiles) {
                        val buildFileTargetForLoadFileTarget: T? =
                            helper.maybeGetBuildFileTargetForLoadFileTarget(target, bzlLabel)
                        // Can be null in genquery: see http://b/123795023#comment6.
                        if (buildFileTargetForLoadFileTarget != null) {
                            if (uniquifier.unique(buildFileTargetForLoadFileTarget)) {
                                result.add(buildFileTargetForLoadFileTarget)
                            }
                        }
                    }
                    true
                })
        }
        callback.process(result)
    }

    /**
     * Perform any work that should be done ahead of time to resolve the target patterns in the query.
     * Implementations may choose to cache the results of resolving the patterns, cache intermediate
     * work, or not cache and resolve patterns on the fly.
     */
    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        TargetParsingException::class,
        java.lang.InterruptedException::class
    )
    protected abstract fun preloadOrThrow(caller: QueryExpression?, patterns: MutableCollection<String?>?)

    override fun isSettingEnabled(setting: com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?): Boolean {
        return settings.contains(
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>(
                setting
            )
        )
    }

    val functions: Iterable<QueryFunction>?
        get() {
            val builder: com.google.common.collect.ImmutableList.Builder<QueryFunction?> =
                com.google.common.collect.ImmutableList.builder<QueryFunction?>()
            builder.addAll(QueryEnvironment.Companion.DEFAULT_QUERY_FUNCTIONS)
            builder.addAll(extraFunctions)
            return builder.build()
        }

    /** A [KeyExtractor] that extracts `Label`s out of [Target]s.  */
    protected class TargetKeyExtractor private constructor() : KeyExtractor<Target?, Label?> {
        override fun extractKey(element: Target): Label {
            return element.getLabel()
        }

        companion object {
            val INSTANCE: TargetKeyExtractor = TargetKeyExtractor()
        }
    }

    /** Concrete implementation of [QueryTaskFuture].  */
    class QueryTaskFutureImpl<T> private constructor(delegate: com.google.common.util.concurrent.ListenableFuture<T?>) :
        QueryTaskFutureImplBase<T?>(), com.google.common.util.concurrent.ListenableFuture<T?> {
        private val delegate: com.google.common.util.concurrent.ListenableFuture<T?>

        init {
            this.delegate = delegate
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return delegate.cancel(mayInterruptIfRunning)
        }

        val isCancelled: Boolean
            get() = delegate.isCancelled()

        val isDone: Boolean
            get() = delegate.isDone()

        @Throws(java.lang.InterruptedException::class, ExecutionException::class)
        override fun get(): T? {
            return delegate.get()
        }

        @Throws(
            java.lang.InterruptedException::class,
            ExecutionException::class,
            java.util.concurrent.TimeoutException::class
        )
        override fun get(timeout: Long, unit: TimeUnit?): T? {
            return delegate.get(timeout, unit)
        }

        override fun addListener(listener: java.lang.Runnable, executor: java.util.concurrent.Executor) {
            delegate.addListener(listener, executor)
        }

        val ifSuccessful: T?
            get() {
                try {
                    return com.google.common.util.concurrent.Futures.getDone<T?>(delegate)
                } catch (e: CancellationException) {
                    throw java.lang.IllegalStateException(e)
                } catch (e: ExecutionException) {
                    throw java.lang.IllegalStateException(e)
                }
            }

        @get:Throws(
            java.lang.InterruptedException::class,
            com.google.devtools.build.lib.query2.engine.QueryException::class
        )
        private val checked: T?
            get() {
                try {
                    return get()
                } catch (unused: CancellationException) {
                    throw java.lang.InterruptedException()
                } catch (e: ExecutionException) {
                    val cause: Throwable = e.cause
                    com.google.common.base.Throwables.throwIfInstanceOf<com.google.devtools.build.lib.query2.engine.QueryException?>(
                        cause,
                        com.google.devtools.build.lib.query2.engine.QueryException::class.java
                    )
                    com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                        cause,
                        java.lang.InterruptedException::class.java
                    )
                    com.google.common.base.Throwables.throwIfUnchecked(cause)
                    throw java.lang.IllegalStateException(e)
                }
            }

        companion object {
            fun <R> ofDelegate(delegate: com.google.common.util.concurrent.ListenableFuture<R?>): QueryTaskFutureImpl<R?> {
                return if (delegate is QueryTaskFutureImpl<*>)
                    delegate as QueryTaskFutureImpl<R?>
                else
                    QueryTaskFutureImpl<R?>(delegate)
            }

            fun <R> whenSucceedsOrIsCancelledCall(
                future: QueryTaskFutureImpl<*>, callable: QueryTaskCallable<R?>, executor: java.util.concurrent.Executor
            ): QueryTaskFutureImpl<R?> {
                return ofDelegate<R?>(
                    com.google.common.util.concurrent.Futures.whenAllComplete<Any?>(
                        cast(
                            com.google.common.collect.ImmutableList.of(
                                future
                            )
                        )
                    )
                        .call<R?>(
                            java.util.concurrent.Callable {
                                try {
                                    val unused: Any? = future.get()
                                } catch (unused: CancellationException) {
                                    // If the input future is cancelled, we are supposed to swallow the
                                    // `CancellationException` and proceed normally.
                                }
                                callable.call()
                            },
                            executor
                        )
                )
            }
        }
    }

    private class Dummy : QueryTaskCallable<java.lang.Void?> {
        override fun call(): java.lang.Void? {
            return null
        }

        companion object {
            val INSTANCE: Dummy = com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment.Dummy()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private fun constructDependencyFilter(settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>): DependencyFilter {
            var specifiedFilter: DependencyFilter =
                if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.ONLY_TARGET_DEPS))
                    DependencyFilter.ONLY_TARGET_DEPS
                else
                    DependencyFilter.ALL_DEPS
            if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)) {
                specifiedFilter = specifiedFilter.and(DependencyFilter.NO_IMPLICIT_DEPS)
            }
            if (settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_NODEP_DEPS)) {
                specifiedFilter = specifiedFilter.and(DependencyFilter.NO_NODEP_ATTRIBUTES)
            }
            return specifiedFilter
        }

        private fun createErrorEvent(
            expr: QueryExpression, message: String?, detailedExitCode: DetailedExitCode?
        ): Event {
            val eventMessage: String? =
                String.format("Evaluation of query \"%s\" failed: %s", expr.toTrunctatedString(), message)
            var event: Event = Event.error(eventMessage)
            if (detailedExitCode != null) {
                event =
                    event.withProperty(
                        DetailedExitCode::class.java,
                        DetailedExitCode.of(
                            detailedExitCode.getExitCode(),
                            detailedExitCode.getFailureDetail().toBuilder()
                                .setMessage(eventMessage)
                                .build()
                        )
                    )
            } else {
                logger.atWarning().atMostEvery(1, TimeUnit.MINUTES).log(
                    "Null detailed exit code for %s %s", message, expr
                )
            }
            return event
        }

        protected fun cast(
            futures: Iterable<out QueryTaskFuture<*>?>
        ): Iterable<QueryTaskFutureImpl<*>?> {
            return com.google.common.collect.Iterables.transform(
                futures,
                { obj: Any? -> QueryTaskFutureImpl::class.java.cast(obj) })
        }
    }
}
