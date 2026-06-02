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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile

/**
 * A graph, defined by a set of functions that can construct values from value keys.
 * 
 * 
 * The value constructor functions ([SkyFunction]s) can declare dependencies on
 * prerequisite [SkyValue]s. The [MemoizingEvaluator] implementation makes sure that
 * those are created beforehand.
 * 
 * 
 * The graph caches previously computed values. Arbitrary values can be invalidated between calls
 * to [.evaluate]; they will be recreated the next time they are requested.
 */
interface MemoizingEvaluator {
    /**
     * Computes the transitive closure of a given set of values. See [ ][EagerInvalidator.invalidate].
     * 
     * 
     * The returned EvaluationResult is guaranteed to contain a result for at least one root if
     * keepGoing is false. It will contain a result for every root if keepGoing is true, *unless*
     * the evaluation failed with a "catastrophic" error. In that case, some or all results may be
     * missing.
     */
    @Throws(java.lang.InterruptedException::class)
    fun <T : SkyValue?> evaluate(
        roots: Iterable<out SkyKey?>?, evaluationContext: com.google.devtools.build.skyframe.EvaluationContext?
    ): EvaluationResult<T?>?

    /** Same as [.delete], but takes a predicate that only uses the key.  */
    fun delete(pred: java.util.function.Predicate<SkyKey?>) {
        delete(java.util.function.BiPredicate { k: SkyKey?, v: SkyValue? -> pred.test(k) })
    }

    /**
     * Ensures that after the next completed [.evaluate] call the current values of any value
     * matching this predicate (and all values that transitively depend on them) will be removed from
     * the value cache. All values that were already marked dirty in the graph will also be deleted,
     * regardless of whether or not they match the predicate.
     * 
     * 
     * If a later call to [.evaluate] requests some of the deleted values, those values will
     * be recomputed and the new values stored in the cache again.
     * 
     * 
     * To delete all dirty values, you can specify a predicate that's always false.
     */
    fun delete(pred: java.util.function.BiPredicate<SkyKey?, SkyValue?>?)

    /**
     * Marks dirty values for deletion if they have been dirty for at least as many graph versions
     * as the specified limit.
     * 
     * 
     * This ensures that after the next completed [.evaluate] call, all such values, along
     * with all values that transitively depend on them, will be removed from the value cache. Values
     * that were marked dirty after the threshold version will not be affected by this call.
     * 
     * 
     * If a later call to [.evaluate] requests some of the deleted values, those values will
     * be recomputed and the new values stored in the cache again.
     * 
     * 
     * To delete all dirty values, you can specify 0 for the limit.
     */
    fun deleteDirty(versionAgeLimit: Long)

    /**
     * Returns the values in the graph.
     * 
     * 
     * The returned map may be a live view of the graph.
     */
    // TODO(bazel-team): Replace all usages of getValues, getDoneValues, getExistingValue,
    // and getExistingErrorForTesting with usages of WalkableGraph. Changing the getValues usages
    // require some care because getValues gives access to the previous value for changed/dirty nodes.
    val values: MutableMap<SkyKey, SkyValue>?
        /**
         * Returns the values in the graph.
         * 
         * 
         * The returned map may be a live view of the graph.
         */
        get

    /**
     * Returns an [InMemoryGraph] containing all of the nodes backing this evaluator.
     * 
     * 
     * Throws [UnsupportedOperationException] if this evaluator does not store its entire
     * graph in memory.
     */
    fun getInMemoryGraph(): InMemoryGraph?

    /**
     * Informs the evaluator that a sequence of evaluations at the same version has finished.
     * Evaluators may make optimizations under the assumption that successive evaluations are all at
     * the same version. A call of this method tells the evaluator that the next evaluation is not
     * guaranteed to be at the same version.
     */
    @Throws(java.lang.InterruptedException::class)
    fun noteEvaluationsAtSameVersionMayBeFinished(eventHandler: ExtendedEventHandler?) {
        postLoggingStats(eventHandler)
    }

    /**
     * Tells the evaluator to post any logging statistics that it may have accumulated over the last
     * sequence of evaluations. Normally called internally by [ ][.noteEvaluationsAtSameVersionMayBeFinished].
     */
    fun postLoggingStats(eventHandler: ExtendedEventHandler?) {}

    /**
     * Returns node types that can be safely removed from the graph to save memory prior to
     * [ frontier serialization][com.google.devtools.build.lib.skyframe.serialization.analysis.FrontierSerializer] when the graph will not be used incrementally.
     */
    fun getNodesToRemoveBeforeFrontierSerialization(): com.google.common.collect.ImmutableSet<SkyFunctionName?> {
        return com.google.common.collect.ImmutableSet.of<SkyFunctionName?>()
    }

    /**
     * Returns the done (without error) values in the graph.
     * 
     * 
     * The returned map may be a live view of the graph.
     */
    fun getDoneValues(): MutableMap<SkyKey?, SkyValue?>?

    /**
     * Returns a value if and only if an earlier call to [.evaluate] created it; null otherwise.
     * 
     * 
     * This method should mainly be used by tests that need to verify the presence of a value in
     * the graph after an [.evaluate] call.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getExistingValue(key: SkyKey?): SkyValue?

    @Throws(java.lang.InterruptedException::class)
    fun getExistingEntryAtCurrentlyEvaluatingVersion(key: SkyKey?): NodeEntry?

    /**
     * Returns an error if and only if an earlier call to [.evaluate] created it; null
     * otherwise.
     * 
     * 
     * This method should only be used by tests that need to verify the presence of an error in the
     * graph after an [.evaluate] call.
     */
    // Only exists for testing.
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun getExistingErrorForTesting(key: SkyKey?): com.google.devtools.build.skyframe.ErrorInfo?

    /**
     * Injects a [GraphTransformerForTesting] to allow tests to have finer-grained control over
     * the graph.
     * 
     * 
     * May be called multiple times, in which case the effective graph is the result of
     * sequentially applying all transformers in the order in which they were passed to this method.
     * 
     * 
     * Must only be called in tests.
     */
    fun injectGraphTransformerForTesting(transformer: GraphTransformerForTesting?)

    /** Transforms a graph, possibly injecting other functionality.  */
    interface GraphTransformerForTesting {
        fun transform(graph: InMemoryGraph?): InMemoryGraph?

        fun transform(graph: ProcessableGraph?): ProcessableGraph?

        companion object {
            /**
             * Returns a composite transformer that applies both of the given transformers in the given
             * order.
             */
            fun compose(
                before: GraphTransformerForTesting, after: GraphTransformerForTesting
            ): GraphTransformerForTesting {
                com.google.common.base.Preconditions.checkNotNull<GraphTransformerForTesting?>(before)
                com.google.common.base.Preconditions.checkNotNull<GraphTransformerForTesting?>(after)
                if (before === NO_OP) {
                    return after
                }
                if (after === NO_OP) {
                    return before
                }
                return object : GraphTransformerForTesting {
                    override fun transform(graph: InMemoryGraph?): InMemoryGraph? {
                        return after.transform(before.transform(graph))
                    }

                    override fun transform(graph: ProcessableGraph?): ProcessableGraph? {
                        return after.transform(before.transform(graph))
                    }
                }
            }

            val NO_OP: GraphTransformerForTesting = object : GraphTransformerForTesting {
                override fun transform(graph: InMemoryGraph?): InMemoryGraph? {
                    return graph
                }

                override fun transform(graph: ProcessableGraph?): ProcessableGraph? {
                    return graph
                }
            }
        }
    }

    /**
     * Writes a brief summary about the graph to the given output stream.
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    fun dumpSummary(out: PrintStream?)

    /**
     * Writes a list of counts of each node type in the graph to the given output stream.
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    fun dumpCount(out: PrintStream?)

    /**
     * Writes a detailed summary of the graph to the given output stream. For each key matching the
     * given filter, prints the key name and value.
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    @Throws(java.lang.InterruptedException::class)
    fun dumpValues(out: PrintStream?, filter: java.util.function.Predicate<String?>?)

    /**
     * Writes a detailed summary of the graph to the given output stream. For each key matching the
     * given filter, prints the key name and deps. The deps are printed in groups according to the
     * dependency order registered in Skyframe.
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    @Throws(java.lang.InterruptedException::class)
    fun dumpDeps(out: PrintStream?, filter: java.util.function.Predicate<String?>?)

    /**
     * Emits the graph representation in the DOT description format of SkyFunction dependencies of the
     * keys matching the given filter to the given output stream.
     * 
     * 
     * Useful for understanding the high level dependency edges established by Skyframe lookups.
     * calls.
     * 
     * 
     * The nodes are [SkyFunctionName]s. They do not include individual SkyKey information
     * since the most basic builds already create way too many nodes to generate a useful graph image.
     * 
     * 
     * Edges are de-duplicated (e.g. all FILE -> FILE_STATE edges show up as a single edge), and
     * the output may show cycles (e.g. ACTION_EXECUTION -> ARTIFACT -> ACTION_EXECUTION -> ...)
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    @Throws(java.lang.InterruptedException::class)
    fun dumpFunctionGraph(out: PrintStream?, filter: java.util.function.Predicate<String?>?)

    /**
     * Writes a detailed summary of the graph to the given output stream. For each key matching the
     * given filter, prints the key name and its reverse deps.
     * 
     * 
     * Not necessarily thread-safe. Use only for debugging purposes.
     */
    @ThreadHostile
    @Throws(java.lang.InterruptedException::class)
    fun dumpRdeps(out: PrintStream?, filter: java.util.function.Predicate<String?>?)

    /**
     * Cleans up [interning][com.google.devtools.build.lib.concurrent.PooledInterner.Pool] by moving objects to weak interners and uninstalling the current pools.
     * 
     * 
     * May destroy this evaluator's [graph][.getInMemoryGraph]. Only call when the graph
     * is about to be thrown away.
     */
    fun cleanupInterningPools()

    fun skyfocusSupported(): Boolean

    /**
     * Enables Skyfocus, a graph optimizer for Skyframe with active directoriess, by remembering the
     * root nodes.
     */
    fun rememberTopLevelEvaluations(remember: Boolean)

    /** Cleans up the set of evaluated root SkyKeys. Used for Skyfocus.  */
    fun cleanupLatestTopLevelEvaluations()
}
