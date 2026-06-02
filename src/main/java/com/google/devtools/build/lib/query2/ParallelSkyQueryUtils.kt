// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.packages.Target

/**
 * Parallel implementations of various functionality in [SkyQueryEnvironment].
 * 
 * 
 * Special attention is given to memory usage. Naive parallel implementations of query
 * functionality would lead to memory blowup. Instead of dealing with [Target]s, we try to
 * deal with [SkyKey]s as much as possible to reduce the number of [Package]s forcibly
 * in memory at any given time.
 */
// TODO(bazel-team): Be more deliberate about bounding memory usage here.
object ParallelSkyQueryUtils {
    /** The maximum number of keys to visit at once.  */
    @com.google.common.annotations.VisibleForTesting
    const val VISIT_BATCH_SIZE: Int = 10000

    fun getAllRdepsUnboundedParallel(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return env.eval(
            expression,
            context,
            ParallelVisitorUtils.createParallelVisitorCallback<OutputResultT?, CallbackT?>(
                com.google.devtools.build.lib.query2.RdepsUnboundedVisitor.Factory(
                    env,  /* unfilteredUniverse= */
                    com.google.common.base.Predicates.alwaysTrue<SkyKey?>(),
                    context.extraGlobalDeps(),
                    callback
                )
            )
        )
    }

    fun getAllRdepsBoundedParallel(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        depth: Int,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return env.eval(
            expression,
            context,
            ParallelVisitorUtils.createParallelVisitorCallback<OutputResultT?, CallbackT?>(
                com.google.devtools.build.lib.query2.RdepsBoundedVisitor.Factory(
                    env,
                    depth,  /* universe= */
                    com.google.common.base.Predicates.alwaysTrue<SkyKey?>(),
                    context.extraGlobalDeps(),
                    callback
                )
            )
        )
    }

    fun getRdepsInUniverseUnboundedParallel(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        unfilteredUniverse: com.google.common.base.Predicate<SkyKey?>?,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return env.eval(
            expression,
            context,
            ParallelVisitorUtils.createParallelVisitorCallback<OutputResultT?, CallbackT?>(
                com.google.devtools.build.lib.query2.RdepsUnboundedVisitor.Factory(
                    env, unfilteredUniverse, context.extraGlobalDeps(), callback
                )
            )
        )
    }

    fun getDTCSkyKeyPredicateFuture(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        context: QueryExpressionContext<Target?>,
        processResultsBatchSize: Int,
        concurrencyLevel: Int
    ): QueryTaskFuture<com.google.common.base.Predicate<SkyKey?>?>? {
        val universeValueFuture: QueryTaskFuture<ThreadSafeMutableSet<Target?>?>? =
            QueryUtil.evalAll<Target?>(env, context, expression)

        val getTransitiveClosureAsyncFunction: com.google.common.base.Function<ThreadSafeMutableSet<Target?>?, QueryTaskFuture<com.google.common.base.Predicate<SkyKey?>?>?> =
            com.google.common.base.Function { universeValue: ThreadSafeMutableSet<Target?>? ->
                val aggregateAllCallback =
                    ThreadSafeAggregateAllSkyKeysCallback(concurrencyLevel)
                env.execute<com.google.common.base.Predicate<SkyKey?>?>(
                    QueryTaskCallable {
                        val visitor: UnfilteredSkyKeyLabelDTCVisitor =
                            com.google.devtools.build.lib.query2.UnfilteredSkyKeyLabelDTCVisitor.Factory(
                                env,
                                env.createSkyKeyUniquifier(),
                                processResultsBatchSize,
                                context.extraGlobalDeps(),
                                aggregateAllCallback
                            )
                                .create()
                        visitor.visitAndWaitForCompletion(
                            SkyQueryEnvironment.Companion.makeLabelsStrict<Target?>(universeValue)
                        )
                        com.google.common.base.Predicates.`in`<SkyKey?>(aggregateAllCallback.result)
                    })
            }

        return env.transformAsync<ThreadSafeMutableSet<Target?>?, com.google.common.base.Predicate<SkyKey?>?>(
            universeValueFuture,
            getTransitiveClosureAsyncFunction
        )
    }

    fun getRdepsInUniverseBoundedParallel(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        depth: Int,
        universe: com.google.common.base.Predicate<SkyKey?>?,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return env.eval(
            expression,
            context,
            ParallelVisitorUtils.createParallelVisitorCallback<OutputResultT?, CallbackT?>(
                com.google.devtools.build.lib.query2.RdepsBoundedVisitor.Factory(
                    env, depth, universe, context.extraGlobalDeps(), callback
                )
            )
        )
    }

    /** Specialized parallel variant of [SkyQueryEnvironment.getRBuildFiles].  */
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    fun getRBuildFilesParallel(
        env: SkyQueryEnvironment,
        fileIdentifiers: MutableCollection<PathFragment?>?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ) {
        val visitor: RBuildFilesVisitor =
            RBuildFilesVisitor(
                env,  /*visitUniquifier=*/
                env.createSkyKeyUniquifier(),  /*resultUniquifier=*/
                env.createSkyKeyUniquifier(),
                context,
                callback
            )
        visitor.visitFileIdentifiersAndWaitForCompletion(env.graph, fileIdentifiers)
    }

    fun getDepsUnboundedParallel(
        env: SkyQueryEnvironment,
        expression: QueryExpression?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?,
        depsNeedFiltering: Boolean,
        caller: QueryExpression?
    ): QueryTaskFuture<java.lang.Void?>? {
        return env.eval(
            expression,
            context,
            ParallelVisitorUtils.createParallelVisitorCallback<OutputResultT?, CallbackT?>(
                com.google.devtools.build.lib.query2.DepsUnboundedVisitor.Factory(
                    env,
                    callback,
                    depsNeedFiltering,
                    context,
                    caller
                )
            )
        )
    }

    internal class DepAndRdep(dep: SkyKey?, rdep: SkyKey) {
        val dep: SkyKey?
        val rdep: SkyKey

        init {
            this.dep = dep
            this.rdep = rdep
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is DepAndRdep) {
                return false
            }
            return dep == obj.dep && rdep == obj.rdep
        }

        override fun hashCode(): Int {
            // N.B. - We deliberately use a garbage-free hashCode implementation (rather than e.g.
            // Objects#hash). Depending on the structure of the graph being traversed, this method can
            // be very hot.
            return 31 * java.util.Objects.hashCode(dep) + rdep.hashCode()
        }
    }

    internal class DepAndRdepAtDepth(val depAndRdep: DepAndRdep?, val rdepDepth: Int)

    /** Thread-safe [AggregateAllCallback] backed by a concurrent [Set].  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    private class ThreadSafeAggregateAllSkyKeysCallback
        (concurrencyLevel: Int) : AggregateAllCallback<SkyKey?, com.google.common.collect.ImmutableSet<SkyKey?>?> {
        private val results: MutableSet<SkyKey?>

        init {
            this.results =
                Collections.newSetFromMap<SkyKey?>(
                    ConcurrentHashMap<SkyKey?, Boolean?>( /*initialCapacity=*/
                        concurrencyLevel,  /*loadFactor=*/0.75f
                    )
                )
        }

        @Throws(
            com.google.devtools.build.lib.query2.engine.QueryException::class,
            java.lang.InterruptedException::class
        )
        override fun process(partialResult: Iterable<SkyKey?>) {
            com.google.common.collect.Iterables.addAll<SkyKey?>(results, partialResult)
        }

        val result: com.google.common.collect.ImmutableSet<SkyKey?>
            get() = com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(results)
    }
}
