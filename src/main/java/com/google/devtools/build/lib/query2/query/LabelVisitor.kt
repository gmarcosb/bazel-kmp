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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * Visit the transitive closure of a label. Primarily used to "fault in" packages to the
 * packageProvider and ensure the necessary targets exists, in advance of the configuration step,
 * which is intolerant of missing packages/targets.
 * 
 * 
 * LabelVisitor loads packages concurrently where possible, to increase I/O parallelism. However,
 * the public interface is not thread-safe: calls to public methods should not be made concurrently.
 * 
 * 
 * LabelVisitor is stateful: It remembers the previous visitation and can check its validity on
 * subsequent calls to sync() instead of doing the normal visitation.
 * 
 * 
 * TODO(bazel-team): (2009) a small further optimization could be achieved if we create tasks at
 * the package (not individual label) level, since package loading is the expensive step. This would
 * require additional bookkeeping to maintain the list of labels that we need to visit once a
 * package becomes available. Profiling suggests that there is still a potential benefit to be
 * gained: when the set of packages is known a-priori, loading a set of packages that took 20
 * seconds can be done under 5 in the sequential case or 7 in the current (parallel) case.
 * 
 * <h4>Concurrency</h4>
 * 
 * 
 * The sync() methods of this class is thread-compatible. The accessor ([.hasVisited] and
 * similar must not be called until the concurrent phase is over, i.e. all external calls to visit()
 * methods have completed.
 */
internal class LabelVisitor(targetProvider: TargetProvider, edgeFilter: DependencyFilter?) {
    /** Attributes of a visitation which determine whether it is up-to-date or not.  */
    private class VisitationAttributes(private val targetsToVisit: MutableSet<Target>, maxDepth: OptionalInt) {
        private val maxDepth: OptionalInt
        private var success = false

        init {
            this.maxDepth = maxDepth
        }

        /** Returns true if and only if this visitation attribute is still up-to-date.  */
        fun current(lastVisitation: VisitationAttributes): Boolean {
            return targetsToVisit == lastVisitation.targetsToVisit
                    && (lastVisitation.maxDepth.isEmpty()
                    || !QueryEnvironment.Companion.shouldVisit(maxDepth, lastVisitation.maxDepth.getAsInt()))
        }
    }

    /*
   * Interrupts during the loading phase ===================================
   *
   * Bazel can be interrupted in the middle of the loading phase. The mechanics
   * of this are far from trivial, so there is an explanation of how they are
   * supposed to work. For a description how the same thing works in the
   * execution phase, see ParallelBuilder.java .
   *
   * The sequence of events that happen when the user presses Ctrl-C is the
   * following:
   *
   * 1. A SIGINT gets delivered to the Bazel client process.
   *
   * 2. The client process delivers the SIGINT to the server process.
   *
   * 3. The interruption state of the main thread is set to true.
   *
   * 4. Sooner or later, this results in an InterruptedException being thrown.
   * Usually this takes place because the main thread is interrupted during
   * AbstractQueueVisitor.awaitTermination(). The only exception to this is when
   * the interruption occurs during the loading of a package of a label
   * specified on the command line; in this case, the InterruptedException is
   * thrown during the loading of an individual package (see below where this
   * can occur)
   *
   * 5. The main thread calls ThreadPoolExecutor.shutdown(), which in turn
   * interrupts every worker thread. Then the main thread waits for their
   * termination.
   *
   * 6. An InterruptedException is thrown during the loading of an individual
   * package in the worker threads.
   *
   * 7. All worker threads terminate.
   *
   * 8. An InterruptedException is thrown from
   * AbstractQueueVisitor.awaitTermination()
   *
   * 9. This exception causes the execution of the currently running command to
   * terminate prematurely.
   *
   * The interruption of the loading of an individual package happens as follow:
   *
   * 1. We periodically check the interruption state of the thread in
   * UnixGlob.reallyGlob(). If it is interrupted, an InterruptedException is
   * thrown.
   *
   * 2. The stack is unwound until we are out of the part of the call stack
   * responsible for package loading. This either means that the worker thread
   * terminates or that the label parsing terminates if the package that is
   * being loaded was specified on the command line.
   */
    private val targetProvider: TargetProvider
    private val edgeFilter: DependencyFilter?
    private val visitedTargets: ConcurrentMap<Label?, Int?> = ConcurrentHashMap<Label?, Int?>()

    private var lastVisitation: VisitationAttributes

    /**
     * Construct a LabelVisitor.
     * 
     * @param targetProvider how to resolve labels to targets
     * @param edgeFilter which edges may be traversed
     */
    init {
        this.targetProvider = targetProvider
        this.lastVisitation = NONE
        this.edgeFilter = edgeFilter
    }

    @Throws(java.lang.InterruptedException::class)
    fun syncWithVisitor(
        eventHandler: ExtendedEventHandler?,
        targetsToVisit: MutableSet<Target>,
        keepGoing: Boolean,
        parallelThreads: Int,
        maxDepth: OptionalInt,
        observer: TargetEdgeObserver
    ) {
        val nextVisitation = VisitationAttributes(targetsToVisit, maxDepth)
        if (!lastVisitation.success || !nextVisitation.current(lastVisitation)) {
            lastVisitation = nextVisitation
            lastVisitation.success =
                redoVisitation(
                    eventHandler, targetsToVisit, keepGoing, parallelThreads, maxDepth, observer
                )
        }
    }

    /**
     * Performs a bounded transitive closure visitation, similar to syncWithVisitor, but does not
     * cache the result.
     */
    @Throws(java.lang.InterruptedException::class)
    fun syncUncached(
        eventHandler: ExtendedEventHandler?,
        targetsToVisit: Iterable<Target>,
        keepGoing: Boolean,
        parallelThreads: Int,
        maxDepth: OptionalInt,
        observer: TargetEdgeObserver
    ) {
        lastVisitation = NONE
        redoVisitation(eventHandler, targetsToVisit, keepGoing, parallelThreads, maxDepth, observer)
    }

    // Does a bounded transitive visitation starting at the given top-level targets.
    @Throws(java.lang.InterruptedException::class)
    private fun redoVisitation(
        eventHandler: ExtendedEventHandler?,
        targetsToVisit: Iterable<Target>,
        keepGoing: Boolean,
        parallelThreads: Int,
        maxDepth: OptionalInt,
        observer: TargetEdgeObserver
    ): Boolean {
        visitedTargets.clear()

        val visitor: Visitor = com.google.devtools.build.lib.query2.query.LabelVisitor.Visitor(
            eventHandler,
            keepGoing,
            parallelThreads,
            maxDepth,
            observer
        )

        var uncaught: Throwable? = null
        val result: Boolean
        try {
            visitor.visitTargets(targetsToVisit)
        } catch (t: Throwable) {
            visitor.stopNewActions()
            uncaught = t
        } finally {
            // Run finish() in finally block to ensure we don't leak threads on exceptions.
            result = visitor.finish()
        }
        com.google.common.base.Throwables.propagateIfPossible(uncaught)
        return result
    }

    fun hasVisited(target: Label?): Boolean {
        return visitedTargets.containsKey(target)
    }

    private inner class Visitor(
        eventHandler: ExtendedEventHandler?,
        keepGoing: Boolean,
        parallelThreads: Int,
        maxDepth: OptionalInt,
        observer: TargetEdgeObserver
    ) {
        private val executorService: ExecutorService
        private val executor: QuiescingExecutor
        private val eventHandler: ExtendedEventHandler?
        private val maxDepth: OptionalInt

        // Observers are stored individually instead of in a list to reduce iteration cost.
        private val observer: TargetEdgeObserver
        private val errorObserver: TargetEdgeErrorObserver = TargetEdgeErrorObserver()

        init {
            if (parallelThreads > 1) {
                this.executorService = NamedForkJoinPool.newNamedPool(
                    com.google.devtools.build.lib.query2.query.LabelVisitor.Visitor.Companion.THREAD_NAME,
                    parallelThreads
                )
            } else {
                // ForkJoinPool has a bug where it deadlocks with parallelism=1, so use a
                // SingleThreadExecutor instead.
                this.executorService =
                    Executors.newSingleThreadExecutor(
                        com.google.common.util.concurrent.ThreadFactoryBuilder()
                            .setNameFormat(com.google.devtools.build.lib.query2.query.LabelVisitor.Visitor.Companion.THREAD_NAME + " %d")
                            .build()
                    )
            }
            this.executor =
                AbstractQueueVisitor.createWithExecutorService(
                    executorService,
                    if (keepGoing) ExceptionHandlingMode.KEEP_GOING else ExceptionHandlingMode.FAIL_FAST,
                    ErrorClassifier.DEFAULT
                )
            this.eventHandler = eventHandler
            this.maxDepth = maxDepth
            this.observer = observer
        }

        /**
         * Visit the specified labels and follow the transitive closure of their outbound dependencies.
         * 
         * @param targets the targets to visit
         */
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        @Throws(java.lang.InterruptedException::class)
        fun visitTargets(targets: Iterable<Target>) {
            for (target in targets) {
                visit(null, null, target, 0, 0)
            }
        }

        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        @Throws(java.lang.InterruptedException::class)
        fun finish(): Boolean {
            executor.awaitQuiescence( /*interruptWorkers=*/true)
            return !errorObserver.hasErrors()
        }

        fun stopNewActions() {
            executorService.shutdownNow()
        }

        fun enqueueTarget(from: Target?, attr: Attribute?, label: Label, depth: Int, count: Int) {
            // Don't perform the targetProvider lookup if at the maximum depth already.
            if (maxDepth.isPresent() && depth >= maxDepth.getAsInt()) {
                return
            }

            // Avoid thread-related overhead when not crossing packages.
            // Can start a new thread when count reaches 100, to prevent infinite recursion.
            if (from != null && from.getLabel().getPackageFragment().equals(label.getPackageFragment())
                && count < RECURSION_LIMIT
            ) {
                newVisitRunnable(from, attr, label, depth, count + 1).run()
            } else {
                executor.execute(newVisitRunnable(from, attr, label, depth, 0))
            }
        }

        fun newVisitRunnable(
            from: Target?,
            attr: Attribute?,
            label: Label?,
            depth: Int,
            count: Int
        ): java.lang.Runnable {
            return java.lang.Runnable {
                try {
                    visit(from, attr, targetProvider.getTarget(eventHandler, label), depth + 1, count)
                } catch (e: NoSuchThingException) {
                    observeError(from, label, e)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

        /**
         * Visits the target and its package.
         * 
         * 
         * Potentially blocking invocations into the package cache are enqueued in the worker pool if
         * CONCURRENT.
         */
        @Throws(java.lang.InterruptedException::class)
        fun visit(from: Target?, attribute: Attribute?, target: Target, depth: Int, count: Int) {
            if (target == null) {
                throw java.lang.NullPointerException(
                    java.lang.String.format(
                        "'%s' attribute '%s'",
                        if (from == null) "(null)" else from.getLabel().toString(),
                        if (attribute == null) "(null)" else attribute.name
                    )
                )
            }
            if (!QueryEnvironment.Companion.shouldVisit(maxDepth, depth)) {
                return
            }

            if (from != null) {
                observeEdge(from, attribute, target)
                visitAspectsIfRequired(from, attribute, target, depth, count)
            }
            visitTargetNode(target, depth, count)
        }

        fun visitAspectsIfRequired(
            from: Target?, attribute: Attribute, to: Target?, depth: Int, count: Int
        ) {
            // TODO(bazel-team): The getAspects call below is duplicate work for each direct dep entailed
            // by an attribute's value. Additionally, we might end up enqueueing the same exact visitation
            // multiple times: consider the case where the same direct dependency is entailed by aspects
            // of *different* attributes. These visitations get culled later, but we still have to pay the
            // overhead for all that.

            if (from !is Rule || to !is Rule) {
                return
            }
            for (aspect in attribute.getAspects(from)) {
                if (AspectDefinition.satisfies(
                        aspect, to.getRuleClassObject().getAdvertisedProviders()
                    )
                ) {
                    AspectDefinition.forEachLabelDepFromAllAttributesOfAspect(
                        aspect,
                        edgeFilter,
                        { aspectAttribute, aspectLabel ->
                            enqueueTarget(
                                from,
                                aspectAttribute,
                                aspectLabel,
                                depth,
                                count
                            )
                        })
                }
            }
        }

        /**
         * Visit the specified target. Called in a worker thread if CONCURRENT.
         * 
         * @param target the target to visit
         */
        @Throws(java.lang.InterruptedException::class)
        fun visitTargetNode(target: Target, depth: Int, count: Int) {
            val minTargetDepth: Int? = visitedTargets.putIfAbsent(target.getLabel(), depth)
            if (minTargetDepth != null) {
                // The target was already visited at a greater depth.
                // The closure we are about to build is therefore a subset of what
                // has already been built, and we can skip it.
                // Also special case no depth bound, where we never want to revisit targets.
                // (This avoids loading phase overhead outside of queries).
                if (maxDepth.isEmpty() || minTargetDepth <= depth) {
                    return
                }
                // Check again in case it was overwritten by another thread.
                synchronized(visitedTargets) {
                    if (visitedTargets.get(target.getLabel()) <= depth) {
                        return
                    }
                    visitedTargets.put(target.getLabel(), depth)
                }
            }

            observeNode(target)

            // LabelVisitor has some legacy special handling of OutputFiles.
            if (target is OutputFile) {
                val rule: Rule = target.getGeneratingRule()
                observeEdge(target, null, rule)
                visit(null, null, rule, depth + 1, count + 1)
            }

            LabelVisitationUtils.visitTarget(
                target,
                edgeFilter,
                { fromTarget, attribute, toLabel -> enqueueTarget(target, attribute, toLabel, depth, count) })
        }

        fun observeEdge(from: Target?, attribute: Attribute?, to: Target?) {
            observer.edge(from, attribute, to)
            errorObserver.edge(from, attribute, to)
        }

        fun observeNode(target: Target) {
            observer.node(target)
            errorObserver.node(target)
        }

        fun observeError(from: Target?, label: Label?, e: NoSuchThingException) {
            observer.missingEdge(from, label, e)
            errorObserver.missingEdge(from, label, e)
        }

        companion object {
            private const val THREAD_NAME = "LabelVisitor"
        }
    }

    companion object {
        private val NONE = VisitationAttributes(com.google.common.collect.ImmutableSet.of<Target?>(), OptionalInt.of(0))

        /** Constant for limiting the permitted depth of recursion.  */
        private const val RECURSION_LIMIT = 100
    }
}
