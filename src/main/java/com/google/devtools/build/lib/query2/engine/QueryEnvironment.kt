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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.BazelModuleContext.LoadGraphVisitor
import java.util.concurrent.Callable
import javax.annotation.Nonnull

/**
 * The environment of a Blaze query. Implementations do not need to be thread-safe. The generic type
 * T represents a node of the graph on which the query runs; as such, there is no restriction on T.
 * However, query assumes a certain graph model, and the [TargetAccessor] class is used to
 * access properties of these nodes. Also, the query engine doesn't assume T's [ ][Object.hashCode] and [Object.equals] are meaningful and instead uses [ ][QueryEnvironment.createUniquifier] and [QueryEnvironment.createThreadSafeMutableSet] when
 * appropriate.
 * 
 * @param <T> the node type of the dependency graph
</T> */
interface QueryEnvironment<T> {
    /** Type of an argument of a user-defined query function.  */
    enum class ArgumentType {
        EXPRESSION,
        WORD,
        INTEGER
    }

    /** Value of an argument of a user-defined query function.  */
    class Argument private constructor(
        val type: ArgumentType?,
        private val expression: QueryExpression,
        val word: String?,
        val integer: Int
    ) {
        fun getExpression(): QueryExpression {
            return Preconditions.checkNotNull<QueryExpression>(expression, "Expected expression argument")
        }

        override fun toString(): String {
            return when (type) {
                ArgumentType.WORD -> "'" + word + "'"
                ArgumentType.EXPRESSION -> expression.toString()
                ArgumentType.INTEGER -> integer.toString()
            }
        }

        companion object {
            fun of(expression: QueryExpression): Argument {
                return Argument(ArgumentType.EXPRESSION, expression, null, 0)
            }

            fun of(word: String?): Argument {
                return QueryEnvironment.Argument(ArgumentType.WORD, null, word, 0)
            }

            fun of(integer: Int): Argument {
                return QueryEnvironment.Argument(ArgumentType.INTEGER, null, null, integer)
            }
        }
    }

    /** A user-defined query function.  */
    interface QueryFunction {
        /** Name of the function as it appears in the query language.  */
        @kotlin.jvm.JvmField
        val name: String?

        /**
         * The number of arguments that are required. The rest is optional.
         * 
         * 
         * This should be greater than or equal to zero and at smaller than or equal to the length of
         * the list returned by [.getArgumentTypes].
         */
        val mandatoryArguments: Int

        /** The types of the arguments of the function.  */
        val argumentTypes: Iterable<ArgumentType?>?

        /**
         * Returns a [QueryTaskFuture] representing the asynchronous application of this [ ] to the given `args`, feeding the results to the given `callback`.
         * 
         * @param env the query environment this function is evaluated in.
         * @param expression the expression being evaluated.
         * @param context the context relevant to the expression being evaluated. Contains the variable
         * bindings from [LetExpression]s.
         * @param args the input arguments. These are type-checked against the specification returned by
         * [.getArgumentTypes] and [.getMandatoryArguments]
         */
        fun <T> eval(
            env: QueryEnvironment<T?>?,
            context: QueryExpressionContext<T?>?,
            expression: QueryExpression?,
            args: MutableList<Argument?>?,
            callback: Callback<T?>?
        ): QueryTaskFuture<Void?>?

        /**
         * A filtering function is one whose outputs are a subset of a single input argument. Returns
         * the function as a filtering function if it is one and `null` otherwise.
         */
        fun asFilteringFunction(): FilteringQueryFunction? {
            return null
        }

        /** Returns true if this function requires traversing edges in the graph.  */
        fun requiresEdges(): Boolean {
            return false
        }
    }

    /** A [QueryFunction] whose output is a subset of some input argument expression.  */
    class FilteringQueryFunction : QueryFunction {
        override fun asFilteringFunction(): FilteringQueryFunction? {
            return this
        }

        /** Returns a function representing the filter but inverted.  */
        abstract fun invert(): FilteringQueryFunction?

        /** Returns the argument index of the expression that is used as the input to be filtered.  */
        abstract val expressionToFilterIndex: Int
    }

    /** Functional interface for classes that need to look up a Target from a Label.  */
    fun interface TargetLookup {
        @Throws(TargetNotFoundException::class, InterruptedException::class)
        fun getTarget(label: Label?): Target?
    }

    /**
     * Exception type for the case where a target cannot be found. It's basically a wrapper for
     * whatever exception is internally thrown.
     */
    class TargetNotFoundException(cause: Throwable, detailedExitCode: DetailedExitCode?) :
        Exception(cause.message, cause) {
        private val detailedExitCode: DetailedExitCode

        init {
            this.detailedExitCode = Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
        }

        fun getDetailedExitCode(): DetailedExitCode {
            return detailedExitCode
        }
    }

    /**
     * QueryEnvironment implementations can optionally also implement this interface to provide custom
     * implementations of various operators.
     */
    interface CustomFunctionQueryEnvironment<T> : QueryEnvironment<T?> {
        /**
         * Returns a [QueryTaskFuture] representing the asynchronous evaluation of the given
         * `expr` and passing of the results to the given `callback`.
         * 
         * 
         * This provides callers the option to decide whether or not to batch futures resulting from
         * the given `expr`.
         * 
         * @param expr The expression to evaluate
         * @param callback The caller's callback to notify when results are available
         * @param batch Whether or not to invoke the callback with a single batch of the result set
         */
        fun eval(
            expr: QueryExpression?,
            context: QueryExpressionContext<T?>?,
            callback: Callback<T?>?,
            batch: Boolean
        ): QueryTaskFuture<Void?>?

        /**
         * Computes the transitive closure of dependencies at most maxDepth away from the given targets
         * (if set), and calls the given callback with the results.
         */
        @Throws(InterruptedException::class, QueryException::class)
        fun deps(from: Iterable<T?>?, maxDepth: OptionalInt?, caller: QueryExpression?, callback: Callback<T?>?)

        /** Computes some path from a node in 'from' to a node in 'to'.  */
        @Throws(InterruptedException::class, QueryException::class)
        fun somePath(from: Iterable<T?>?, to: Iterable<T?>?, caller: QueryExpression?, callback: Callback<T?>?)

        /** Computes all paths from a node in 'from' to a node in 'to'.  */
        @Throws(InterruptedException::class, QueryException::class)
        fun allPaths(from: Iterable<T?>?, to: Iterable<T?>?, caller: QueryExpression?, callback: Callback<T?>?)

        /**
         * Computes all reverse dependencies of a node in 'from' with at most distance maxDepth within
         * the transitive closure of 'universe'.
         */
        @Throws(InterruptedException::class, QueryException::class)
        fun rdeps(
            from: Iterable<T?>?,
            universe: Iterable<T?>?,
            maxDepth: OptionalInt?,
            caller: QueryExpression?,
            callback: Callback<T?>?
        )

        /** Computes direct reverse deps of all nodes in 'from' within the same package.  */
        @Throws(InterruptedException::class, QueryException::class)
        fun samePkgDirectRdeps(from: Iterable<T?>?, caller: QueryExpression?, callback: Callback<T?>?)
    }

    /** Returns all of the targets in `target`'s package, in some stable order.  */
    @Throws(QueryException::class, InterruptedException::class)
    fun getSiblingTargetsInPackage(target: T?): MutableCollection<T?>?

    /**
     * Invokes `callback` with the set of target nodes in the graph for the specified target
     * pattern, in 'blaze build' syntax.
     */
    fun getTargetsMatchingPattern(
        owner: QueryExpression?, pattern: String?, callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    /** Ensures the specified target exists.  */ // NOTE(bazel-team): this method is left here as scaffolding from a previous refactoring. It may
    // be possible to remove it.
    fun getOrCreate(target: T?): T?

    /** Returns the direct forward dependencies of the specified targets.  */
    @Throws(InterruptedException::class)
    fun getFwdDeps(targets: Iterable<T?>?, context: QueryExpressionContext<T?>?): Iterable<T?>?

    /** Returns the direct reverse dependencies of the specified targets.  */
    @Throws(InterruptedException::class)
    fun getReverseDeps(targets: Iterable<T?>?, context: QueryExpressionContext<T?>?): Iterable<T?>?

    /**
     * Returns the forward transitive closure of all of the targets in "targets". Callers must ensure
     * that [.buildTransitiveClosure] has been called for the relevant subgraph.
     */
    @Throws(InterruptedException::class)
    fun getTransitiveClosure(
        targets: ThreadSafeMutableSet<T?>?, context: QueryExpressionContext<T?>?
    ): ThreadSafeMutableSet<T?>?

    /**
     * Construct the dependency graph for a depth-bounded forward transitive closure of all nodes in
     * "targetNodes". The identity of the calling expression is required to produce error messages.
     * 
     * 
     * The closure may not be loaded, depending on the value of `maxDepth` and the
     * implementation of this `QueryEnvironment`.
     * 
     * 
     * Passing [OptionalInt.empty] for `maxDepth` means that the full closure should be
     * preloaded.
     */
    @Throws(QueryException::class, InterruptedException::class)
    fun buildTransitiveClosure(
        caller: QueryExpression?, targetNodes: ThreadSafeMutableSet<T?>?, maxDepth: OptionalInt?
    )

    /** Returns the ordered sequence of nodes on some path from "from" to "to".  */
    @Throws(InterruptedException::class)
    fun getNodesOnPath(from: T?, to: T?, context: QueryExpressionContext<T?>?): Iterable<T?>?

    /**
     * Returns a [QueryTaskFuture] representing the asynchronous evaluation of the given `expr` and passing of the results to the given `callback`.
     * 
     * 
     * Note that this method should guarantee that the callback does not see repeated elements.
     * 
     * @param expr The expression to evaluate
     * @param callback The caller callback to notify when results are available
     */
    fun eval(
        expr: QueryExpression?, context: QueryExpressionContext<T?>?, callback: Callback<T?>?
    ): QueryTaskFuture<Void?>?

    /**
     * A wrapper for evaluating query expression. User does not need to provide callback at object
     * instantiation, and could manipulate the future in the callback implementation in some derived
     * classes.
     * 
     * 
     * It replaces directly calling [.eval] method in [SomeFunction.eval].
     * 
     * 
     * For SkyQueryEnvironment-descended environments which feature streaming support, in `SomeFunction#eval(...)` method, we want to cancel the [QueryTaskFuture] immediately after
     * targeted number of results are reached, which would significantly improve the performance of
     * [SomeFunction] evaluation. So client will call [.gracefullyCancel] to cancel the
     * underlying `QueryTaskFuture`.
     * 
     * 
     * Users should use [.createEvaluateExpression]
     * to create the `EvaluateExpression` instance.
     */
    interface EvaluateExpression<T> {
        /**
         * Returns a [QueryTaskFuture] representing the asynchronous evaluation of the expression
         * provided by the constructor of the inherited classes. See `AbstractBlazeQueryEvaluateExpressionImpl` and `SkyQueryEvaluateExpressionImpl`;
         * 
         * 
         * Requires the user to provide a `callback` to be associated with the [ ]. Results of the asynchronous evaluation of the expression are passed to the
         * given `callback`.
         */
        fun eval(callback: Callback<T?>?): QueryTaskFuture<Void?>?

        /**
         * Attempts to cancel execution of expression evaluation task.
         * 
         * 
         * Please note that [.gracefullyCancel] is a no-op implementation for
         * non-SkyQueryEnvironment-descended environments.
         */
        fun gracefullyCancel(): Boolean

        /**
         * Returns `true` if the underlying future is cancelled but not via [ ][.gracefullyCancel]. Clients are advised to propagate such cancellations instead of recovering
         * from them, because they probably indicate that query evaluation was interrupted.
         */
        val isUngracefullyCancelled: Boolean
    }

    /**
     * Creates an [EvaluateExpression] instance based on [QueryEnvironment] type.
     * 
     * @param expr the expression to evaluate
     * @param context the context relevant to the expression being evaluated.
     */
    fun createEvaluateExpression(
        expr: QueryExpression?, context: QueryExpressionContext<T?>?
    ): EvaluateExpression<T?>?

    /**
     * An asynchronous computation of part of a query evaluation.
     * 
     * 
     * A [QueryTaskFuture] can only be produced from scratch via [.eval], [ ][.execute], [.immediateSuccessfulFuture], [.immediateFailedFuture], and [ ][.immediateCancelledFuture].
     * 
     * 
     * Combined with the helper methods like [.whenSucceedsCall] below, this is very similar
     * to Guava's [ListenableFuture].
     * 
     * 
     * This class is deliberately opaque; the only ways to compose/use [.QueryTaskFuture]
     * instances are the helper methods like [.whenSucceedsCall] below. A crucial consequence of
     * this is there is no way for a [QueryExpression] or [QueryFunction] implementation
     * to block on the result of a [.QueryTaskFuture]. This eliminates a large class of
     * deadlocks by design!
     */
    @ThreadSafe
    class QueryTaskFuture<T>  // We use a public abstract class with a private constructor so that this type is visible to all
    // the query codebase, but yet the only possible implementation is under our control in this
    // file.
    private constructor() {
        /**
         * If this [QueryTaskFuture]'s encapsulated computation is currently complete and
         * successful, returns the result. This method is intended to be used in combination with [ ][.whenSucceedsCall].
         * 
         * 
         * See the javadoc for the various helper methods that produce [QueryTaskFuture] for
         * the precise definition of "successful".
         */
        @kotlin.jvm.JvmField
        abstract val ifSuccessful: T?
    }

    /**
     * Returns a [QueryTaskFuture] representing the successful computation of `value`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful].
     */
    fun <R> immediateSuccessfulFuture(value: R?): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing a computation that was unsuccessful because of
     * `e`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "unsuccessful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful].
     */
    fun <R> immediateFailedFuture(e: QueryException?): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing a cancelled computation.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "unsuccessful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful].
     */
    fun <R> immediateCancelledFuture(): QueryTaskFuture<R?>?

    /** A [ThreadSafe] [Callable] for computations during query evaluation.  */
    @ThreadSafe
    interface QueryTaskCallable<T> : Callable<T?> {
        /**
         * Returns the computed value or throws a [QueryException] on failure or a [ ] on interruption.
         */
        @Throws(QueryException::class, InterruptedException::class)
        override fun call(): T?
    }

    /** Like Guava's AsyncCallable, but for [QueryTaskFuture].  */
    @ThreadSafe
    interface QueryTaskAsyncCallable<T> {
        /**
         * Returns a [QueryTaskFuture] whose completion encapsulates the result of the
         * computation.
         */
        fun call(): QueryTaskFuture<T?>?
    }

    /**
     * Returns a [QueryTaskFuture] representing the given computation `callable` being
     * performed asynchronously.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful] iff
     * `callable#call` does not throw an exception.
     */
    fun <R> execute(callable: QueryTaskCallable<R?>?): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing both the given `callable` being performed
     * asynchronously and also the returned [QueryTaskFuture] returned therein being completed.
     */
    fun <R> executeAsync(callable: QueryTaskAsyncCallable<R?>?): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing the given computation `callable` being
     * performed after the successful completion of the computation encapsulated by the given `future` has completed successfully.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful] iff
     * `future` is successful and `callable#call` does not throw an exception.
     */
    fun <R> whenSucceedsCall(future: QueryTaskFuture<*>?, callable: QueryTaskCallable<R?>?): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing the successful completion of all the
     * computations encapsulated by the given `futures`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful] iff
     * all of the given computations are "successful".
     */
    fun whenAllSucceed(futures: Iterable<out QueryTaskFuture<*>?>?): QueryTaskFuture<Void?>?

    /**
     * Returns a [QueryTaskFuture] representing the given computation `callable` being
     * performed after the successful completion of all the computations encapsulated by the given
     * `futures`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful] iff
     * all of the given computations are "successful" and `callable#call` does not throw an
     * exception.
     */
    fun <R> whenAllSucceedCall(
        futures: Iterable<out QueryTaskFuture<*>?>?, callable: QueryTaskCallable<R?>?
    ): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing the given computation `callable` being
     * performed after the successful completion or cancellation of the computations encapsulated by
     * the given `future`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" iff `future` is
     * "successful" or only throws a [java.util.concurrent.CancellationException] and `callable#call` does not throw an exception.
     */
    fun <R> whenSucceedsOrIsCancelledCall(
        future: QueryTaskFuture<*>?, callable: QueryTaskCallable<R?>?
    ): QueryTaskFuture<R?>?

    /**
     * Returns a [QueryTaskFuture] representing the asynchronous application of the given `function` to the value produced by the computation encapsulated by the given `future`.
     * 
     * 
     * The returned [QueryTaskFuture] is considered "successful" for purposes of [ ][.whenSucceedsCall], [.whenAllSucceed], and [QueryTaskFuture.getIfSuccessful] iff
     * `future` is "successful".
     */
    fun <T1, T2> transformAsync(
        future: QueryTaskFuture<T1?>?, function: Function<T1?, QueryTaskFuture<T2?>?>?
    ): QueryTaskFuture<T2?>?

    /**
     * The sole package-protected subclass of [QueryTaskFuture].
     * 
     * 
     * Do not subclass this class; it's an implementation detail. [QueryExpression] and
     * [QueryFunction] implementations should use [.eval] and [.execute] to get
     * access to [QueryTaskFuture] instances and the then use the helper methods like [ ][.whenSucceedsCall] to transform them.
     */
    class QueryTaskFutureImplBase<T> protected constructor() : QueryTaskFuture<T?>()

    /**
     * A mutable [ThreadSafe] [Set] that uses proper equality semantics for `T`.
     * [QueryExpression]/[QueryFunction] implementations should use `ThreadSafeMutableSet<T>` they need a set-like data structure for `T`.
     */
    @ThreadSafe
    interface ThreadSafeMutableSet<T> : MutableSet<T?>

    /** Returns a fresh [ThreadSafeMutableSet] instance for the type `T`.  */
    fun createThreadSafeMutableSet(): ThreadSafeMutableSet<T?>?

    /**
     * Creates a Uniquifier for use in a `QueryExpression`. Note that the usage of this
     * uniquifier should not be used for returning unique results to the parent callback. It should
     * only be used to avoid processing the same elements multiple times within this QueryExpression.
     */
    fun createUniquifier(): Uniquifier<T?>?

    /**
     * Creates a [MinDepthUniquifier] for use in a `QueryExpression`. Note that the usage
     * of this uniquifier should not be used for returning unique results to the parent callback. It
     * should only be used to try to avoid processing the same elements multiple times at the same
     * depth bound within this QueryExpression.
     */
    fun createMinDepthUniquifier(): MinDepthUniquifier<T?>?

    /**
     * Handle an error during evaluation of `expression` by either throwing [ ] or emitting an event, depending on whether the evaluation is running in a "keep
     * going" mode.
     */
    @Throws(QueryException::class)
    fun handleError(
        expression: QueryExpression?, message: String?, detailedExitCode: DetailedExitCode?
    )

    /**
     * Helper for [.transitiveLoadFiles]. Encapsulates the differences between the different
     * [QueryEnvironment] implementations.
     */
    interface TransitiveLoadFilesHelper<T> {
        fun getPkgId(target: T?): PackageIdentifier?

        @Throws(QueryException::class, InterruptedException::class)
        fun visitLoads(
            originalTarget: T?, visitor: LoadGraphVisitor<QueryException?, InterruptedException?>?
        )

        @Throws(InterruptedException::class)
        fun getBuildFileTarget(originalTarget: T?): T?

        @Throws(InterruptedException::class)
        fun getLoadFileTarget(originalTarget: T?, bzlLabel: Label?): T?

        @Throws(QueryException::class, InterruptedException::class)
        fun maybeGetBuildFileTargetForLoadFileTarget(originalTarget: T?, bzlLabel: Label?): T?
    }

    @get:Throws(QueryException::class)
    val transitiveLoadFilesHelper: TransitiveLoadFilesHelper<T?>?

    /**
     * Feeds to the given `callback` the transitive bzl files loaded (and BUILD files too, if
     * `alsoAddBuildFiles` says to), represented as make-believe targets corresponding to their
     * load labels, across all unique packages in `targets`, using `seenPackages` and
     * `seenBzlLabels` to avoid duplicate work and using `uniquifier` to avoid feeding
     * duplicate results.
     */
    @Throws(QueryException::class, InterruptedException::class)
    fun transitiveLoadFiles(
        targets: Iterable<T?>?,
        alsoAddBuildFiles: Boolean,
        seenPackages: MutableSet<PackageIdentifier?>?,
        seenBzlLabels: MutableSet<Label?>?,
        uniquifier: Uniquifier<T?>?,
        helper: TransitiveLoadFilesHelper<T?>?,
        callback: Callback<T?>?
    )

    /**
     * Returns an object that can be used to query information about targets. Implementations should
     * create a single instance and return that for all calls. A class can implement both `QueryEnvironment` and `TargetAccessor` at the same time, in which case this method simply
     * returns `this`.
     */
    @kotlin.jvm.JvmField
    val accessor: TargetAccessor<T?>?

    val labelPrinter: LabelPrinter?

    /**
     * Whether the given setting is enabled. The code should default to return `false` for all
     * unknown settings. The enum is used rather than a method for each setting so that adding more
     * settings is backwards-compatible.
     * 
     * @throws NullPointerException if setting is null
     */
    fun isSettingEnabled(@Nonnull setting: Setting): Boolean

    /** Returns the set of query functions implemented by this query environment.  */
    @kotlin.jvm.JvmField
    val functions: Iterable<QueryFunction?>?

    /** Settings for the query engine. See [QueryEnvironment.isSettingEnabled].  */
    enum class Setting {
        /**
         * Whether to evaluate tests() expressions in strict mode. If [.isSettingEnabled] returns
         * true for this setting, then the tests() expression will give an error when expanding tests
         * suites, if the test suite contains any non-test targets.
         */
        TESTS_EXPRESSION_STRICT,

        /**
         * Do not consider implicit deps (any label that was not explicitly specified in the BUILD file)
         * when traversing dependency edges.
         */
        NO_IMPLICIT_DEPS,

        /** Do not consider non-target dependencies when traversing dependency edges.  */
        ONLY_TARGET_DEPS,

        /** Do not consider nodep attributes when traversing dependency edges.  */
        NO_NODEP_DEPS,

        /** Include aspect-generated output. No-op for query, which always follows aspects.  */
        INCLUDE_ASPECTS,

        /** Include configured aspect targets in cquery output.  */
        EXPLICIT_ASPECTS
    }

    /**
     * An adapter interface giving access to properties of T. There are four types of targets: rules,
     * package groups, source files, and generated files. Of these, only rules can have attributes.
     */
    interface TargetAccessor<T> {
        /**
         * Returns the target type represented as a string of the form `&lt;type&gt; rule` or
         * `package group` or `source file` or `generated file`. This is widely used
         * for target filtering, so implementations must use the Blaze rule class naming scheme.
         */
        fun getTargetKind(target: T?): String?

        /** Returns the full label of the target as a string, e.g. `//some:target`.  */
        fun getLabel(target: T?): String?

        /** Returns the label of the target's package as a string, e.g. `//some/package`  */
        fun getPackage(target: T?): String?

        /** Returns whether the given target is a rule.  */
        fun isRule(target: T?): Boolean

        /** Returns whether the given rule is executable with 'bazel run'.  */
        fun isExecutableNonTestRule(target: T?): Boolean

        /**
         * Returns whether the given target is a test target. If this returns true, then [.isRule]
         * must also return true for the target.
         */
        fun isTestRule(target: T?): Boolean

        /**
         * Returns whether the given target is a test suite target. If this returns true, then [ ][.isRule] must also return true for the target, but [.isTestRule] must return false;
         * test suites are not test rules, and vice versa.
         */
        fun isTestSuite(target: T?): Boolean

        /**
         * If the attribute of the given name on the given target is a label or label list, then this
         * method returns the list of corresponding target instances. Otherwise returns an empty list.
         * If an error occurs during resolution, it throws a [QueryException] using the caller and
         * error message prefix.
         * 
         * @throws IllegalArgumentException if target is not a rule (according to [.isRule])
         */
        @Throws(QueryException::class, InterruptedException::class)
        fun getPrerequisites(
            caller: QueryExpression?, target: T?, attrName: String?, errorMsgPrefix: String?
        ): Iterable<T?>?

        /**
         * If the attribute of the given name on the given target is a string list, then this method
         * returns it.
         * 
         * @throws IllegalArgumentException if target is not a rule (according to [.isRule]), or
         * if the target does not have an attribute of type string list with the given name
         */
        fun getStringListAttr(target: T?, attrName: String?): MutableList<String?>?

        /**
         * If the attribute of the given name on the given target is a string, then this method returns
         * it.
         * 
         * @throws IllegalArgumentException if target is not a rule (according to [.isRule]), or
         * if the target does not have an attribute of type string with the given name
         */
        fun getStringAttr(target: T?, attrName: String?): String?

        /**
         * Returns the given attribute represented as a list of strings. For "normal" attributes, this
         * should just be a list of size one containing the attribute's value. For configurable
         * attributes, there should be one entry for each possible value the attribute may take.
         * 
         * 
         * Note that for backwards compatibility, tristate and boolean attributes are returned as int
         * using the values `0, 1` and `-1`. If there is no such attribute, this method
         * returns an empty list.
         * 
         * @throws IllegalArgumentException if target is not a rule (according to [.isRule])
         */
        fun getAttrAsString(target: T?, attrName: String?): Iterable<String?>?

        /**
         * Returns the set of package specifications the given target is visible from, represented as
         * [QueryVisibility]s.
         */
        @Throws(QueryException::class, InterruptedException::class)
        fun getVisibility(caller: QueryExpression?, from: T?): ImmutableSet<QueryVisibility<T?>?>?
    }

    companion object {
        fun shouldVisit(maxDepth: OptionalInt, currentDepth: Int): Boolean {
            return !maxDepth.isPresent() || maxDepth.getAsInt() >= currentDepth
        }

        /** List of the default query functions.  */
        @kotlin.jvm.JvmField
        val DEFAULT_QUERY_FUNCTIONS: ImmutableList<QueryFunction?> = ImmutableList.of<QueryFunction?>(
            AllPathsFunction(),
            AttrFunction(),
            BuildFilesFunction(),
            DepsFunction(),
            ExecutablesFunction(),
            FilterFunction(),
            KindFunction(),
            LabelsFunction(),
            LoadFilesFunction(),
            RdepsFunction(),
            SamePkgDirectRdepsFunction(),
            SiblingsFunction(),
            SomeFunction(),
            SomePathFunction(),
            TestsFunction(),
            VisibleFunction()
        )
    }
}
