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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * Class that allows clients to be notified on each access of the graph. Clients can simply track
 * accesses, or they can block to achieve desired synchronization. Clients should call [ ][TrackingAwaiter.INSTANCE] at the end of tests in case exceptions were swallowed in
 * async threads.
 */
open class NotifyingHelper internal constructor(graphListener: Listener?) {
    val graphListener: ErrorRecordingDelegatingListener

    init {
        this.graphListener = ErrorRecordingDelegatingListener(graphListener)
    }

    /** Subclasses should override if they wish to subclass [NotifyingNodeEntry].  */
    @com.google.errorprone.annotations.ForOverride
    open fun wrapEntry(key: SkyKey?, entry: NodeEntry?): NotifyingNodeEntry? {
        return if (entry == null) null else NotifyingNodeEntry(key, entry)
    }

    internal open class NotifyingProcessableGraph(delegate: ProcessableGraph, notifyingHelper: NotifyingHelper) :
        ProcessableGraph {
        val delegate: ProcessableGraph
        val notifyingHelper: NotifyingHelper

        constructor(delegate: ProcessableGraph, graphListener: Listener?) : this(
            delegate,
            NotifyingHelper(graphListener)
        )

        init {
            this.delegate = delegate
            this.notifyingHelper = notifyingHelper
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun get(requestor: SkyKey?, reason: Reason?, key: SkyKey?): NodeEntry? {
            val node: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                delegate.get(requestor, reason, key)
            // Maintains behavior for tests written when all DEP_REQUESTED calls were made as batch
            // requests. Now there are optimizations in SkyFunctionEnvironment for looking up deps
            // individually, but older tests may be written to listen for a GET_BATCH event.
            if (reason === Reason.DEP_REQUESTED) {
                notifyingHelper.graphListener.accept(
                    key,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_BATCH,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    reason
                )
            } else if (reason === Reason.EVALUATION) {
                notifyingHelper.graphListener.accept(
                    key,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.EVALUATE,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    node
                )
            }
            return notifyingHelper.wrapEntry(key, node)
        }

        public override fun getLookupHint(key: SkyKey?): LookupHint {
            return delegate.getLookupHint(key)
        }

        public override fun remove(key: SkyKey?) {
            delegate.remove(key)
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun getBatch(
            requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
        ): NodeBatch? {
            for (key in keys) {
                notifyingHelper.graphListener.accept(
                    key,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_BATCH,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    reason
                )
            }
            val batch: NodeBatch = delegate.getBatch(requestor, reason, keys)
            val map: HashMap<SkyKey?, NodeEntry?> = HashMap<SkyKey?, NodeEntry?>()
            for (key in keys) {
                if (map.containsKey(key)) {
                    continue
                }
                val entry: NodeEntry? = batch.get(key)
                if (entry != null) {
                    map.put(key, notifyingHelper.wrapEntry(key, entry))
                }
            }
            return map::get
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun createIfAbsentBatch(
            requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
        ): NodeBatch? {
            for (key in keys) {
                notifyingHelper.graphListener.accept(
                    key,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.CREATE_IF_ABSENT,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    null
                )
            }
            val batch: NodeBatch = delegate.createIfAbsentBatch(requestor, reason, keys)
            return NodeBatch { key -> notifyingHelper.wrapEntry(key, batch.get(key)) }
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun getBatchMap(
            requestor: SkyKey?, reason: Reason?, keys: Iterable<out SkyKey?>
        ): MutableMap<SkyKey?, out NodeEntry?>? {
            for (key in keys) {
                notifyingHelper.graphListener.accept(
                    key,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_BATCH,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    reason
                )
            }
            return com.google.common.collect.Maps.transformEntries(
                delegate.getBatchMap(requestor, reason, keys),
                { key: SkyKey?, entry: NodeEntry? -> notifyingHelper.wrapEntry(key, entry) })
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun prefetchDeps(
            requestor: SkyKey?, oldDeps: MutableSet<SkyKey?>?, previouslyRequestedDeps: GroupedDeps?
        ): com.google.common.collect.ImmutableSet<SkyKey?>? {
            return delegate.prefetchDeps(requestor, oldDeps, previouslyRequestedDeps)
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun analyzeDepsDoneness(parent: SkyKey?, deps: MutableList<SkyKey?>?): DepsReport {
            return delegate.analyzeDepsDoneness(parent, deps)
        }
    }

    /**
     * Graph/value entry events that the receiver can be informed of. When writing tests, feel free to
     * add additional events here if needed.
     */
    enum class EventType {
        CREATE_IF_ABSENT,
        EVALUATE,
        ADD_REVERSE_DEP,
        ADD_EXTERNAL_DEP,
        REMOVE_REVERSE_DEP,
        GET_BATCH,
        GET_VALUES,
        GET_TEMPORARY_DIRECT_DEPS,
        SIGNAL,
        SET_VALUE,
        MARK_DIRTY,
        MARK_CLEAN,
        IS_CHANGED,
        GET_LIFECYCLE_STATE,
        GET_VALUE_WITH_METADATA,
        IS_DIRTY,
        IS_READY,
        CHECK_IF_DONE,
        ADD_TEMPORARY_DIRECT_DEPS,
        GET_ALL_DIRECT_DEPS_FOR_INCOMPLETE_NODE,
        RESET_FOR_RESTART_FROM_SCRATCH,
        REMOVE,
    }

    /**
     * Whether the given event is about to happen or has just happened. For some events, both will be
     * published, for others, only one. When writing tests, if you need an additional one to be
     * published, feel free to add it.
     */
    enum class Order {
        BEFORE,
        AFTER
    }

    /** Receiver to be informed when an event for a given key occurs.  */
    interface Listener {
        /**
         * Informs this listener of an event.
         * 
         * 
         * [InterruptedException] may be thrown but is translated to an [ ] by the test framework. Listeners may use blocking synchronization to
         * exercise a certain scenario and are encouraged to propagate unexpected interrupts instead of
         * using [com.google.common.util.concurrent.Uninterruptibles] - this way an unexpected
         * build failure that interrupts and awaits quiescence of skyframe threads leads to a timely
         * test failure without deadlocking.
         */
        @ThreadSafe
        @Throws(java.lang.InterruptedException::class)
        fun accept(key: SkyKey?, type: EventType?, order: Order?, context: Any?)

        companion object {
            val NULL_LISTENER: Listener =
                com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: EventType?, order: Order?, context: Any? -> }
        }
    }

    @kotlin.jvm.JvmRecord
    internal data class ErrorRecordingDelegatingListener(val delegate: Listener?) : Listener {
        override fun accept(key: SkyKey?, type: EventType?, order: Order, context: Any?) {
            try {
                delegate!!.accept(key, type, order, context)
            } catch (e: java.lang.Exception) {
                TrackingAwaiter.Companion.INSTANCE.injectExceptionAndMessage(
                    e,
                    "In NotifyingGraph: "
                            + com.google.common.base.Joiner.on(", ")
                        .join(key, type, order, if (context == null) "null" else context)
                )
                throw java.lang.IllegalStateException(e)
            }
        }
    }

    /** [NodeEntry] that informs a [Listener] of various method calls.  */
    open inner class NotifyingNodeEntry internal constructor(key: SkyKey?, delegate: NodeEntry) :
        DelegatingNodeEntry() {
        private val myKey: SkyKey?
        private val delegate: NodeEntry

        init {
            myKey = key
            this.delegate = delegate
        }

        public override fun getDelegate(): NodeEntry {
            return delegate
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState? {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                reverseDep
            )
            val result: DependencyState? = super.addReverseDepAndCheckIfDone(reverseDep)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_REVERSE_DEP,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                reverseDep
            )
            return result
        }

        public override fun addExternalDep() {
            super.addExternalDep()
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_EXTERNAL_DEP,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                null
            )
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun removeReverseDep(reverseDep: SkyKey?) {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.REMOVE_REVERSE_DEP,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                reverseDep
            )
            super.removeReverseDep(reverseDep)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.REMOVE_REVERSE_DEP,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                reverseDep
            )
        }

        val temporaryDirectDeps: GroupedDeps
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_TEMPORARY_DIRECT_DEPS,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    null
                )
                return super.getTemporaryDirectDeps()
            }

        public override fun signalDep(childVersion: Version?, childForDebugging: SkyKey?): Boolean {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                childForDebugging
            )
            val result: Boolean = super.signalDep(childVersion, childForDebugging)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.SIGNAL,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                childForDebugging
            )
            return result
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun setValue(
            value: SkyValue?, graphVersion: Version?, maxTransitiveSourceVersion: Version?
        ): MutableSet<SkyKey?>? {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                value
            )
            val result: MutableSet<SkyKey?>? = super.setValue(value, graphVersion, maxTransitiveSourceVersion)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.SET_VALUE,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                value
            )
            return result
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult? {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                dirtyType
            )
            val result: MarkedDirtyResult? = super.markDirty(dirtyType)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_DIRTY,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                MarkDirtyAfterContext.Companion.create(dirtyType, result != null)
            )
            return result
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun markClean(): NodeValueAndRdepsToSignal? {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_CLEAN,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                this
            )
            val result: NodeValueAndRdepsToSignal? = super.markClean()
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.MARK_CLEAN,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                this
            )
            return result
        }

        val isChanged: Boolean
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_CHANGED,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                return super.isChanged()
            }

        val isDirty: Boolean
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_DIRTY,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                return super.isDirty()
            }

        val isReadyToEvaluate: Boolean
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.IS_READY,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                return super.isReadyToEvaluate()
            }

        val lifecycleState: LifecycleState?
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_LIFECYCLE_STATE,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                val lifecycleState: LifecycleState? = super.getLifecycleState()
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_LIFECYCLE_STATE,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                    lifecycleState
                )
                return lifecycleState
            }

        @get:Throws(java.lang.InterruptedException::class)
        val valueMaybeWithMetadata: SkyValue
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_VALUE_WITH_METADATA,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                return super.getValueMaybeWithMetadata()
            }

        @Throws(java.lang.InterruptedException::class)
        public override fun checkIfDoneForDirtyReverseDep(reverseDep: SkyKey?): DependencyState? {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.CHECK_IF_DONE,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                reverseDep
            )
            val dependencyState: DependencyState? = super.checkIfDoneForDirtyReverseDep(reverseDep)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.CHECK_IF_DONE,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                reverseDep
            )
            return dependencyState
        }

        public override fun addSingletonTemporaryDirectDep(dep: SkyKey?) {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                dep
            )
            super.addSingletonTemporaryDirectDep(dep)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                dep
            )
        }

        public override fun addTemporaryDirectDepGroup(group: MutableList<SkyKey?>?) {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                group
            )
            super.addTemporaryDirectDepGroup(group)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                group
            )
        }

        public override fun addTemporaryDirectDepsInGroups(deps: MutableSet<SkyKey?>?, groupSizes: MutableList<Int?>?) {
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                deps
            )
            super.addTemporaryDirectDepsInGroups(deps, groupSizes)
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.ADD_TEMPORARY_DIRECT_DEPS,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                deps
            )
        }

        @get:Throws(java.lang.InterruptedException::class)
        val allDirectDepsForIncompleteNode: com.google.common.collect.ImmutableSet<SkyKey?>
            get() {
                graphListener.accept(
                    myKey,
                    com.google.devtools.build.skyframe.NotifyingHelper.EventType.GET_ALL_DIRECT_DEPS_FOR_INCOMPLETE_NODE,
                    com.google.devtools.build.skyframe.NotifyingHelper.Order.BEFORE,
                    this
                )
                return super.getAllDirectDepsForIncompleteNode()
            }

        public override fun resetEvaluationFromScratch() {
            delegate.resetEvaluationFromScratch()
            graphListener.accept(
                myKey,
                com.google.devtools.build.skyframe.NotifyingHelper.EventType.RESET_FOR_RESTART_FROM_SCRATCH,
                com.google.devtools.build.skyframe.NotifyingHelper.Order.AFTER,
                this
            )
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("delegate", delegate).toString()
        }
    }

    /**
     * A pair of [DirtyType] and a bit saying whether the dirtying was successful, emitted to
     * the graph listener as the context [Order.AFTER] a call to [EventType.MARK_DIRTY] a
     * node.
     */
    class MarkDirtyAfterContext(dirtyType: DirtyType?, val actuallyDirtied: Boolean) {
        val dirtyType: DirtyType?

        init {
            this.dirtyType = dirtyType
            java.util.Objects.requireNonNull<Any?>(dirtyType, "dirtyType")
        }

        companion object {
            fun create(dirtyType: DirtyType?, actuallyDirtied: Boolean): MarkDirtyAfterContext {
                return MarkDirtyAfterContext(dirtyType, actuallyDirtied)
            }
        }
    }

    companion object {
        fun makeNotifyingTransformer(
            listener: Listener?
        ): MemoizingEvaluator.GraphTransformerForTesting {
            return object : GraphTransformerForTesting() {
                public override fun transform(graph: InMemoryGraph?): InMemoryGraph? {
                    return NotifyingInMemoryGraph(graph, listener)
                }

                public override fun transform(graph: ProcessableGraph): ProcessableGraph? {
                    return NotifyingProcessableGraph(graph, listener)
                }
            }
        }
    }
}
