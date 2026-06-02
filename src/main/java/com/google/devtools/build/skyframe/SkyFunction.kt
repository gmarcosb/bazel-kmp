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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.QuiescingExecutor

/**
 * Machinery to evaluate a single value.
 * 
 * 
 * The SkyFunction [.compute] implementation is supposed to access only direct dependencies
 * of the value. However, the direct dependencies need not be known in advance. The implementation
 * can request arbitrary values using [Environment.getValue]. If the values are not ready, the
 * call will return `null`; in that case the implementation should just return `null`,
 * in which case the missing dependencies will be computed and the [.compute] method will be
 * started again.
 */
interface SkyFunction {
    /**
     * When a value is requested, this method is called with the name of the value and a
     * dependency-tracking environment.
     * 
     * 
     * This method should return a non-`null` value, or `null` if any dependencies were
     * missing ([Environment.valuesMissing] was true before returning). In that case the missing
     * dependencies will be computed and the `compute` method called again. A subsequent
     * invocation of this method after missing dependencies are done is commonly referred to as a
     * *Skyframe restart* (not to be confused with [Reset]).
     * 
     * 
     * This method should throw if it fails, or if one of its dependencies fails with an exception
     * and this method cannot recover. If one of its dependencies fails and this method can enrich the
     * exception with additional context, then this method should catch that exception and throw
     * another containing that additional context. If it has no such additional context, then it
     * should allow its dependency's exception to be thrown through it.
     * 
     * 
     * Be aware that during error bubbling Skyframe will interpret a thrown [ ] to mean that this method has no additional context to contribute to a
     * dependency's exception. Also note that Skyframe interrupts the evaluating thread when, during
     * error bubbling, this method requests a dependency which failed with an exception. Prefer (if
     * possible) exception enrichment logic simple enough to be insensitive to the evaluating thread's
     * interrupt state.
     * 
     * 
     * This method may return [Reset] in rare circumstances. See its docs. Do not return
     * values of this type unless you know exactly what you are doing.
     * 
     * 
     * If version information is discovered for the given `skyKey`, [ ][Environment.injectVersionForNonHermeticFunction] may be called on `env`.
     * 
     * @throws SkyFunctionException on failure
     * @throws InterruptedException if interrupted
     */
    @ThreadSafe
    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    fun compute(skyKey: SkyKey?, env: Environment?): SkyValue?

    /**
     * Extracts a tag (target label) from a SkyKey if it has one. Otherwise return `null`.
     * 
     * 
     * The tag is used for filtering out non-error event messages that do not match --output_filter
     * flag. If a SkyFunction returns `null` in this method it means that all the info/warning
     * messages associated with this value will be shown, no matter what --output_filter says.
     */
    fun extractTag(skyKey: SkyKey?): String? {
        return null
    }

    /**
     * Returns the max transitive source version that would be injected via [ ][SkyFunctionEnvironment.injectVersionForNonHermeticFunction] if [.compute] were invoked for the given [SkyKey]/[SkyValue] pair, or null if no
     * call for version injection would be made.
     */
    @Throws(IOException::class)
    fun getMaxTransitiveSourceVersionToInjectForNonHermeticFunction(
        skyKey: SkyKey, skyValue: SkyValue?
    ): com.google.devtools.build.skyframe.Version? {
        com.google.common.base.Preconditions.checkState(
            skyKey.functionName().getHermeticity() == FunctionHermeticity.HERMETIC
        )
        return null
    }

    /**
     * Sentinel [SkyValue] type for [.compute] to return, indicating that the evaluation
     * should be started over (including calling [NodeEntry.resetEvaluationFromScratch]).
     * 
     * 
     * Returning a [Reset] from [.compute] differs from returning `null`. A
     * `null` return is expected under normal circumstances when a dependency is requested but
     * is not yet done, causing Skyframe to restart the function when all requested dependencies are
     * done. A [Reset] signals a more complex issue that requires clearing the associated node's
     * temporary direct deps and [rewinding][NodeEntry.DirtyType.REWIND] nodes associated
     * with other keys in [.rewindGraph] (whose directed edges should correspond to the nodes'
     * direct dependencies).
     * 
     * 
     * An intended cause for returning this is external data loss; e.g., if a dependency's
     * "done-ness" is intended to mean that certain data is available in an external system, but
     * during evaluation of a node that depends on that external data, that data has gone missing, and
     * reevaluation of the dependency is expected to repair the discrepancy.
     * 
     * 
     * Values of this type will *never* be returned by [Environment]'s getValue
     * methods or from [NodeEntry.getValue].
     * 
     * 
     * All [ListenableFuture]s used in calls to [Environment.dependOnFuture] which were
     * not already complete will be cancelled.
     */
    class Reset private constructor(rewindGraph: com.google.common.graph.ImmutableGraph<SkyKey?>?) : SkyValue {
        private val rewindGraph: com.google.common.graph.ImmutableGraph<SkyKey?>?

        init {
            this.rewindGraph = rewindGraph
        }

        fun rewindGraph(): com.google.common.graph.ImmutableGraph<SkyKey?>? {
            return rewindGraph
        }

        companion object {
            /**
             * Convenience method that creates a [MutableGraph] that fulfills the basic requirements
             * of a [Reset].
             * 
             * 
             * Additional edges may be added to the graph before passing to [.of].
             */
            fun newRewindGraphFor(keyToReset: SkyKey?): com.google.common.graph.MutableGraph<SkyKey?> {
                val rewindGraph: com.google.common.graph.MutableGraph<SkyKey?> =
                    com.google.common.graph.GraphBuilder.directed().allowsSelfLoops(false).build<SkyKey?>()
                rewindGraph.addNode(keyToReset)
                return rewindGraph
            }

            fun of(rewindGraph: com.google.common.graph.Graph<SkyKey?>): Reset {
                com.google.common.base.Preconditions.checkArgument(
                    rewindGraph.isDirected(),
                    "Undirected: %s",
                    rewindGraph
                )
                com.google.common.base.Preconditions.checkArgument(
                    !rewindGraph.allowsSelfLoops(),
                    "Allows self loops: %s",
                    rewindGraph
                )
                com.google.common.base.Preconditions.checkArgument(
                    !rewindGraph.nodes().isEmpty(),
                    "Rewind graph must include key to reset"
                )
                return Reset(com.google.common.graph.ImmutableGraph.copyOf<SkyKey?>(rewindGraph))
            }

            /**
             * Creates a [Reset] for a single key with no rewinding of dependencies.
             * 
             * 
             * This can be used to clear out a node's temporary direct deps without any rewinding.
             */
            fun selfOnly(key: SkyKey?): Reset {
                return of(newRewindGraphFor(key))
            }
        }
    }

    /**
     * Value lookup subset of services provided to [SkyFunction] implementations.
     * 
     * 
     * See [Environment] for the full set of services.
     */
    interface LookupEnvironment {
        /**
         * Returns a direct dependency. If the specified value is not in the set of already evaluated
         * direct dependencies, returns `null`. Also returns `null` if the specified value
         * has already been evaluated and found to be in error.
         * 
         * 
         * On a subsequent evaluation, if any of this value's dependencies have changed they will be
         * re-evaluated in the same order as originally requested by the `SkyFunction` using this
         * `getValue` call (see [.getValuesAndExceptions] for when preserving the order is
         * not important).
         * 
         * 
         * This method and the ones below may throw [InterruptedException]. Such exceptions
         * must not be caught by the [SkyFunction.compute] implementation. Instead, they should be
         * propagated up to the caller of [SkyFunction.compute].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        fun getValue(valueName: SkyKey?): SkyValue?

        /**
         * Returns a direct dependency. If the specified value is not in the set of already evaluated
         * direct dependencies, returns `null`. If the specified value has already been evaluated
         * and found to be in error, throws the exception coming from the error, so long as the
         * exception is of one of the specified types. SkyFunction implementations may use this method
         * to continue evaluation even if one of their dependencies is in error by catching the thrown
         * exception and proceeding. The caller must specify the exception type(s) that might be thrown
         * using the `exceptionClass` argument(s). If the dependency's exception is not an
         * instance of `exceptionClass`, `null` is returned.
         * 
         * 
         * The exception class given cannot be a supertype or a subtype of [RuntimeException],
         * or a subtype of [InterruptedException]. See [ ][SkyFunctionException.validateExceptionType] for details.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(E::class, java.lang.InterruptedException::class)
        fun <E : java.lang.Exception?> getValueOrThrow(depKey: SkyKey?, exceptionClass: java.lang.Class<E?>?): SkyValue?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> getValueOrThrow(
            depKey: SkyKey?, exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
        ): SkyValue?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getValueOrThrow(
            depKey: SkyKey?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            exceptionClass3: java.lang.Class<E3?>?
        ): SkyValue?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(E1::class, E2::class, E3::class, E4::class, java.lang.InterruptedException::class)
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?, E4 : java.lang.Exception?>
                getValueOrThrow(
            depKey: SkyKey?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            exceptionClass3: java.lang.Class<E3?>?,
            exceptionClass4: java.lang.Class<E4?>?
        ): SkyValue?

        /**
         * Requests `depKeys` "in parallel", independent of each others' results. These keys may
         * be thought of as a "dependency group" -- they are requested together by this value.
         * 
         * 
         * In general, if the result of one getValue call can affect the argument of a later getValue
         * call, the two calls cannot be merged into a single getValuesAndExceptions call, since the
         * result of the first call might change on a later evaluation. Inversely, if the result of one
         * getValue call cannot affect the parameters of the next getValue call, the two keys can form a
         * dependency group and the two getValue calls should be merged into one getValuesAndExceptions
         * call. In the latter case, if we fail to combine the _multiple_ getValue (or
         * getValuesAndExceptions) calls into one _single_ getValuesAndExceptions call, it would result
         * in multiple dependency groups with an implicit ordering between them. This would
         * unnecessarily cause sequential evaluations of these groups and could impact overall
         * performance.
         * 
         * 
         * On subsequent evaluations, when checking to see if dependencies require re-evaluation, all
         * the values within one group may be simultaneously checked. A SkyFunction should request a
         * dependency group if checking the deps serially on a subsequent evaluation would take too
         * long, and if the [.compute] method would request all deps anyway as long as no earlier
         * deps had changed. SkyFunction.Environment implementations may also choose to request these
         * deps in parallel on the first evaluation, potentially speeding it up.
         * 
         * 
         * While re-evaluating every value in the group may take longer than re-evaluating just the
         * first one and finding that it has changed, no extra work is done: the contract of the
         * dependency group means that the [.compute] method, when called to re-evaluate this
         * value, will request all values in the group again anyway, so they would have to have been
         * built in any case.
         * 
         * 
         * Example of when to use getValuesAndExceptions: A ListProcessor value is built with key
         * inputListRef. The [.compute] method first calls getValue(InputList.key(inputListRef)),
         * and retrieves inputList. It then iterates through inputList, calling getValue on each input.
         * Finally, it processes the whole list and returns. Say inputList is (a, b, c). Since the
         * [.compute] method will unconditionally call getValue(a), getValue(b), and getValue(c),
         * the [.compute] method can instead just call getValuesAndExceptions({a, b, c}). If the
         * value is later dirtied the evaluator will evaluate a, b, and c in parallel (assuming the
         * inputList value was unchanged), and re-evaluate the ListProcessor value only if at least one
         * of them was changed. On the other hand, if the InputList changes to be (a, b, d), then the
         * evaluator will see that the first dep has changed, and call the [.compute] method to
         * re-evaluate from scratch, without considering the dep group of {a, b, c}.
         * 
         * 
         * Example of when not to use getValuesAndExceptions: A BestMatch value is built with key
         * &lt;potentialMatchesRef, matchCriterion&gt;. The [.compute] method first calls
         * getValue(PotentialMatches.key(potentialMatchesRef) and retrieves potentialMatches. It then
         * iterates through potentialMatches, calling getValue on each potential match until it finds
         * one that satisfies matchCriterion. In this case, if potentialMatches is (a, b, c), it would
         * be *incorrect* to call getValuesAndExceptions({a, b, c}), because it is not known yet
         * whether requesting b or c will be necessary -- if a matches, then we will never call b or c.
         * 
         * 
         * Returns a [SkyframeLookupResult], which allows the calling `SkyFunction` to
         * get a value or throw an exception per SkyKey.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        fun getValuesAndExceptions(depKeys: Iterable<out SkyKey?>?): SkyframeLookupResult?

        /**
         * Returns a lookup result containing previously requested dependencies.
         * 
         * 
         * NB: this may contain fewer dependencies than expected if the node is restarted before all
         * its dependencies have signaled. The two known cases are error bubbling and partial
         * re-evaluation. In error bubbling, an error should be present.
         */
        fun getLookupHandleForPreviouslyRequestedDeps(): SkyframeLookupResult?
    }

    /**
     * The services provided to the [SkyFunction.compute] implementation by the Skyframe
     * evaluation framework.
     */
    interface Environment : LookupEnvironment {
        /**
         * Returns whether there was a previous getValue[s][OrThrow] that indicated a missing
         * dependency. Formally, returns true iff at least one of the following occurred:
         * 
         * 
         *  * getValue[OrThrow](k[, c]) returned `null` for some k
         *  * A call to `result#get[OrThrow](k[, c])` returned `null` where result =
         * getValuesAndExceptions(ks) for some ks
         *  * A call to `result#queryDep(k, cb)` returned `false` where result =
         * getValuesAndExceptions(ks) for some ks
         * 
         * 
         * 
         * If this returns true, the [SkyFunction] must return `null` or throw a [ ]. It should do the latter only if it encountered an error (on its own or
         * from a dependency) and wants to convey that error.
         */
        fun valuesMissing(): Boolean

        /**
         * Returns the [ExtendedEventHandler] that a [SkyFunction] should use to print any
         * errors, warnings, or progress messages during execution of [SkyFunction.compute].
         * 
         * 
         * [Reportable.storeForReplay] is used to determine when to actually [ ][Reportable.reportTo] events passed to the listener. A return of `false`
         * indicates that the event's relevance is tied to the time at which it is created, so it is
         * reported immediately. All other events are temporarily stored in the environment and only
         * reported after the function completes. If the function returns `null` due to a missing
         * dependency, these events are discarded. It is the responsibility of the function to emit the
         * events again after it is restarted. Note that if using [.getState] to prune work, the
         * function may need to store events in the [SkyKeyComputeState] so that they can be
         * replayed on a subsequent invocation.
         * 
         * 
         * The event handler returned by this method:
         * 
         * 
         *  * is safe for concurrent use by multiple threads if all submitted events return `
         * false` for [Event.storeForReplay] or only a single thread is used to
         * submit events that return `true`
         *  * must not be used after [SkyFunction.compute] returns
         * 
         */
        fun getListener(): ExtendedEventHandler?

        /**
         * A live view of deps known to have already been requested either through an earlier call to
         * [SkyFunction.compute] or inferred during change pruning. Should return `null` if
         * unknown. Only for special use cases: do not use in general unless you know exactly what
         * you're doing!
         */
        fun getTemporaryDirectDeps(): GroupedDeps? {
            return null
        }

        /**
         * Injects non-hermetic [Version] information for the currently evaluating [SkyKey].
         * 
         * 
         * This may be called during the course of [SkyFunction.compute] if the function
         * determines that the currently evaluating key's source dependencies have not changed since the
         * given `version`.
         * 
         * 
         * Environments that either do not need or wish to ignore non-hermetic version information
         * may keep the default no-op implementation.
         */
        fun injectVersionForNonHermeticFunction(version: com.google.devtools.build.skyframe.Version?) {}

        /**
         * Register dependencies on keys without necessarily requiring their values.
         * 
         * 
         * WARNING: Dependencies here MUST be done! Only use this function if you know what you're
         * doing.
         * 
         * 
         * If [max transitive source versions][NodeEntry.getMaxTransitiveSourceVersion] are
         * being tracked, then this method must not be called.
         */
        fun registerDependencies(keys: Iterable<SkyKey?>?)

        /**
         * Returns whether we are currently in error bubbling, which only happens in `--nokeep_going` mode when a dependency is in error.
         * 
         * 
         * This method should not be needed by a typical [SkyFunction]. Examples where it may
         * be needed:
         * 
         * 
         *  * A [SkyFunction] that can fully recover from a dependency's error in `--keep_going mode`, returning a value instead of transforming the exception. [       ] is the classic example of
         * such a function, since it can encounter errors while processing target patterns like
         * `//foo/...` but still return the list of all found targets. Such a [       ] cannot unconditionally return a value, since in `--nokeep_going`
         * mode it may be called upon to transform a lower-level exception. This method can tell
         * it whether to transform a dependency's exception or ignore it and return a value as
         * usual.
         *  * A [SkyFunction] that needs to perform important side effects such as posting
         * events unless interrupted by the user. This method can be used to attempt to
         * distinguish user-initiated interrupts from Skyframe-initiated interrupts, which may
         * occur during error bubbling.
         * 
         */
        fun inErrorBubbling(): Boolean

        /**
         * Adds a dependency on a Skyframe-external event. If the given future is already complete, this
         * method silently returns without doing anything (to avoid unnecessary function restarts).
         * Otherwise, Skyframe adds a listener to the passed-in future, and only re-enqueues the current
         * node after the future completes and all requested deps are done. The added listener will
         * perform the minimum amount of work on the thread completing the future necessary for Skyframe
         * bookkeeping.
         * 
         * 
         * Callers of this method must check [.valuesMissing] before returning `null`
         * from a [SkyFunction].
         * 
         * 
         * This API is intended for performing async computations (e.g., remote execution) in another
         * thread pool without blocking the current Skyframe thread.
         */
        fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?)

        /**
         * Returns a [QuiescingExecutor] object so that [SkyFunction.compute] can dispatch
         * some work to other parallel threads.
         * 
         * 
         * If some [SkyFunction] intends to take advantage of this executor, user should first
         * judge between using the existing "skyframe-evaluator" thread pool and introducing a new type
         * of parallelism.
         * 
         * 
         * Using the existing "skyframe-evaluator" one carries significant risks. It is possible that
         * the extra computation added to the existing executor will slow down or even block existing
         * computation, causing performance regression. In order to mitigate this risk, users should
         * also schedule work on the [SkyFunction.compute] thread along with the external ones.
         * For example,
         * 
         * <pre>`class MyFunction implements SkyFunction {   public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {     CountDownLatch countDownLatch = new CountDownLatch(expectRunnableCount);     BlockingQueue<Runnable> runnablesQueue = new LinkedBlockingQueue<>();     for (int i = 0; i < expectRunnableCount; ++i) {       runnablesQueue.put(() -> {         try {           // ...         } finally {           countDownLatch.countDown();         }       });     }     Runnable drainQueue = () -> {           Runnable next;           while ((next = runnablesQueue.poll()) != null) {             next.run();           }         };     // Dispatch the work to external threads     QuiescingExecutor executor = env.getParallelEvaluationExecutor();     for (int i = 0; i < PARALLELISM; ++i) {       executor.execute(drainQueue);     }     // Current thread should also help to execute some Runnables in the queue.     drainQueue.run();     // Wait for all runnables in the queue to complete before returning.     countDownLatch.await();     return new MySkyValue();   } } `</pre>
         * 
         * 
         * On the other hand, abusively creating new parallelism is also strongly discouraged unless
         * the benefits can be reasonably justified. [ ][SkyFunctionEnvironment.getParallelEvaluationExecutor] discusses an approach to introduce
         * new parallelism.
         * 
         * 
         * In summary, it is generally discouraged to use this method to introduce either existing or
         * new parallelism to SkyFunction computation, unless comprehensive research has been conducted.
         */
        fun getParallelEvaluationExecutor(): QuiescingExecutor? {
            return null
        }

        /**
         * Container for data stored in between calls to [.compute] for the same [SkyKey].
         * 
         * 
         * See the javadoc of [.getState] for motivation and an example.
         */
        interface SkyKeyComputeState : java.lang.AutoCloseable {
            /**
             * {@inheritDoc}
             * 
             * 
             * Can be overridden to make sure [SkyKeyComputeState] objects are cleaned up. Note
             * that, while this ostensibly opens up the possibility for [SkyKeyComputeState] to hold
             * on to any kind of external resource, doing so might still be dangerous as we only actively
             * drop [SkyKeyComputeState] objects on high memory pressure. If the external resource
             * being held on to is approaching starvation, we currently don't do anything to alleviate
             * that pressure. So think *hard* before you start doing that!
             * 
             * 
             * Implementations **MUST** be idempotent.
             * 
             * 
             * Note also that this method could be invoked from arbitrary threads, so avoid heavy
             * operations if possible.
             */
            override fun close() {}
        }

        /**
         * Canonical type-safe heterogeneous container for use with [.getState] in SkyFunction
         * implementations that employ complex or abstract compositional strategies.
         */
        // Must be threadsafe: used by PartialReevaluationMailbox#from on multiple threads, to save
        // signals from deps.
        @ThreadSafe
        class ClassToInstanceMapSkyKeyComputeState : SkyKeyComputeState {
            private val map: ConcurrentHashMap<java.lang.Class<out SkyKeyComputeState?>?, SkyKeyComputeState?> =
                ConcurrentHashMap<java.lang.Class<out SkyKeyComputeState?>?, SkyKeyComputeState?>()

            fun <T : SkyKeyComputeState?> getInstance(
                type: java.lang.Class<T?>, stateSupplier: java.util.function.Supplier<T?>
            ): T? {
                return type.cast(
                    map.computeIfAbsent(
                        type,
                        java.util.function.Function { ignored: java.lang.Class<out SkyKeyComputeState?>? -> stateSupplier.get() })
                )
            }
        }

        /**
         * Returns (or creates and returns) a "state" object to assist with temporary computations for
         * the [SkyKey] associated with this [Environment].
         * 
         * 
         * The [SkyKeyComputeState] will either be freshly created via the given [ ], or will be the same exact instance used on the previous call to this method for
         * the same [SkyKey]. This allows [SkyFunction] implementations to avoid redoing the
         * same intermediate work over-and-over again on each [.compute] call for the same [ ], due to missing Skyframe dependencies. For example,
         * 
         * <pre>
         * class MyFunction implements SkyFunction {
         * public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
         * int x = (Integer) skyKey.argument();
         * SkyKey myDependencyKey = getSkyKeyForValue(someExpensiveComputation(x));
         * SkyValue myDependencyValue = env.getValue(myDependencyKey);
         * if (env.valuesMissing()) {
         * return null;
         * }
         * return createMyValue(myDependencyValue);
         * }
         * }
        </pre> * 
         * 
         * 
         * If the dependency was missing, then we'll end up evaluating `someExpensiveComputation(x)` twice, once on the initial call to [.compute] and then
         * again on the subsequent call after the dependency was computed.
         * 
         * 
         * To fix this, we can use a mutable [SkyKeyComputeState] implementation and store the
         * result of `someExpensiveComputation(x)` in there:
         * 
         * <pre>
         * class MyFunction implements SkyFunction {
         * private static class State implements SkyKeyComputeState {
         * private Integer result;
         * }
         * 
         * public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
         * int x = (Integer) skyKey.argument();
         * State state = env.getState(State::new);
         * if (state.result == null) {
         * state.result = someExpensiveComputation(x);
         * }
         * SkyKey myDependencyKey = getSkyKeyForValue(state.result);
         * SkyValue myDependencyValue = env.getValue(myDependencyKey);
         * if (env.valuesMissing()) {
         * return null;
         * }
         * return createMyValue(myDependencyValue);
         * }
         * }
        </pre> * 
         * 
         * 
         * Now `someExpensiveComputation(x)` gets called exactly once for each `x`!
         * 
         * 
         * Important: There's no guarantee the [SkyKeyComputeState] instance will be the same
         * exact instance used on the previous call to this method for the same [SkyKey]. The
         * above example was just illustrating the best-case outcome. Therefore, [SkyFunction]
         * implementations should make use of this feature only as a performance optimization.
         * 
         * 
         * Note that [SkyKeyComputeState.close] allows us to hold on to other kinds of
         * external resources and clean them up when necessary, but see the Javadoc there for caveats.
         * 
         * 
         * A notable example of the above note is that if [.compute] returns a [Reset]
         * then a call to [.getState] on the subsequent call to [.compute] will definitely
         * use the `stateSupplier`. It's important that Skyframe do this because [Reset]
         * indicates that work should be redone, and so it'd be wrong to reuse work from the previous
         * [.compute] call.
         */
        fun <T : SkyKeyComputeState?> getState(stateSupplier: java.util.function.Supplier<T?>?): T?

        /**
         * Returns the max transitive source version of a [NodeEntry].
         * 
         * 
         * This value might not consider all deps' source versions if called before all deps have
         * been requested or if [.valuesMissing] returns `true`.
         * 
         * 
         * Rules for calculation of the max transitive source version:
         * 
         * 
         *  * Returns `null` during cycle detection and error bubbling, or for transient
         * errors.
         *  * If the node is [FunctionHermeticity.NONHERMETIC], returns the version passed to
         * [.injectVersionForNonHermeticFunction] if it was called, or else `null`.
         *  * For all other nodes, queries [NodeEntry.getMaxTransitiveSourceVersion] of direct
         * dependency nodes and chooses the maximal version seen (according to [       ][Version.atMost]). If there are no direct dependencies, returns [       ][ParallelEvaluatorContext.getMinimalVersion]. If any direct dependency node has a `null` MTSV, returns `null`.
         * 
         */
        fun getMaxTransitiveSourceVersionSoFar(): com.google.devtools.build.skyframe.Version?
    }
}
