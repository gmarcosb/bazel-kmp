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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * A NestedSet is an immutable ordered set of element values of type `E`. Elements must not be
 * arrays.
 * 
 * 
 * Conceptually, NestedSet values form a directed acyclic graph (DAG). Each leaf node represents
 * a set containing a single element; there is also a distinguished leaf node representing the empty
 * set. Each non-leaf node represents the union of the sets represented by its successors.
 * 
 * 
 * A NestedSet value represents a node in this graph. The elements of a NestedSet may be
 * enumerated by traversing the complete DAG, eliminating duplicates using an ephemeral hash table.
 * The [.toList] and [.toSet] methods provide the result of this traversal as a list or
 * a set, respectively. These operations, which are relatively expensive, are known as "flattening".
 * Computing the size of the set requires flattening.
 * 
 * 
 * By contrast, construction of a new set as a union of existing sets is relatively cheap. The
 * constructor accepts a list of "direct" elements and list of "transitive" nodes. The resulting
 * NestedSet refers to a new graph node representing their union. The relative order of direct and
 * transitive successors is governed by the Order parameter. Duplicates among the "direct" elements
 * are eliminated at construction, again with an ephemeral hash table. If after duplicate
 * elimination the new node would have exactly one successor, whether "direct" or "transitive", the
 * resulting NestedSet reuses the existing node for the sole successor.
 * 
 * 
 * The implementation has been highly optimized as it is crucial to Blaze's performance.
 * 
 * @see NestedSetBuilder
 */
@AutoCodec
class NestedSet<E> {
    /**
     * The set's order and approximate depth, packed to save space.
     * 
     * 
     * The low 2 bits contain the Order.ordinal value.
     * 
     * 
     * The high 30 bits, of which only about 12 are really necessary, contain the depth of the set;
     * see getApproxDepth. Because the union constructor discards the depths of all but the deepest
     * nonleaf child, the sets returned by getNonLeaves have inaccurate depths that may
     * overapproximate the true depth.
     */
    @get:com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    val depthAndOrder: Int

    /**
     * Contains the "direct" elements and "transitive" nested sets.
     * 
     * 
     * Direct elements are never arrays. Transitive elements may be arrays, but singletons are
     * replaced by their sole element (thus transitive arrays always contain at least two logical
     * elements).
     * 
     * 
     * The relative order of direct and transitive is determined by the Order.
     * 
     * 
     * All empty sets have the value [.EMPTY_CHILDREN], not null.
     * 
     * 
     * Please be careful to use the terms of the conceptual model in the API documentation, and the
     * terms of the physical representation in internal comments. They are not the same. In graphical
     * terms, the "direct" elements are the graph successors that are leaves, and the "transitive"
     * elements are the graph successors that are non-leaves, and non-leaf nodes have an out-degree of
     * at least 2.
     */
    val children: Any

    /**
     * Cached representation of [.toList].
     * 
     * 
     * For instances with no transitive members, this is always `null` - caching is not
     * worthwhile, since no traversal is needed. For instances with transitive members, this is
     * initialized to [.EMPTY_WEAK_REF] and replaced with a populated [WeakReference] when
     * a traversal is performed.
     * 
     * 
     * As an exception to the above, deserializing instances created by [.withFuture] are
     * assigned [.EMPTY_WEAK_REF].
     * 
     * 
     * Using weak references is preferable to soft references because [ ] may throw a manual OOM before all
     * soft references are collected. See b/322474776.
     * 
     * 
     * This field is `volatile` to support double-checked locking in [ ][.expandWithCaching].
     */
    @kotlin.concurrent.Volatile
    @Transient
    private var cached: java.lang.ref.WeakReference<com.google.common.collect.ImmutableList<E?>?>?

    /** Constructs an empty NestedSet. Should only be called by Order's class initializer.  */
    @kotlin.jvm.JvmOverloads
    internal constructor(
        order: com.google.devtools.build.lib.collect.nestedset.Order,
        depth: Int = 0,
        children: Any = EMPTY_CHILDREN
    ) {
        this.depthAndOrder = (depth shl 2) or order.ordinal()
        this.children = children
        // expandWithCaching() assumes that cached == null means there are no transitive members. We
        // could use depth, but that's an approximation in some cases, so avoid relying on it.
        this.cached =
            when (children) {
                -> EMPTY_WEAK_REF
                -> EMPTY_WEAK_REF
                else -> null
            }
    }

    internal constructor(
        order: com.google.devtools.build.lib.collect.nestedset.Order,
        direct: MutableSet<E?>,
        transitive: MutableCollection<NestedSet<E?>>,
        interruptStrategy: InterruptStrategy
    ) {
        // The iteration order of these collections is the order in which we add the items.
        var directOrder: MutableCollection<E?>? = direct
        // True if we visit the direct members before the transitive members.
        val preorder: Boolean

        when (order) {
            com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER -> {
                directOrder = com.google.common.collect.ImmutableList.copyOf<E?>(direct).reverse()
                preorder = false
            }

            com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER, com.google.devtools.build.lib.collect.nestedset.Order.COMPILE_ORDER -> preorder =
                false

            com.google.devtools.build.lib.collect.nestedset.Order.NAIVE_LINK_ORDER -> preorder = true
            else -> throw java.lang.AssertionError(order)
        }

        // Remember children we extracted from one-element subsets. Otherwise we can end up with two of
        // the same child, which is a problem for the fast path in toList().
        var alreadyInserted: MutableSet<E?> = com.google.common.collect.ImmutableSet.of<E?>()
        // The candidate array of children.
        var children: Array<Any> = arrayOfNulls<Any>(direct.size() + transitive.size())
        var approxDepth = 0
        var n = 0 // current position in children array
        var shallow = true // whether true depth < 3

        for (pass in 0..1) {
            if ((pass == 0) == preorder && !direct.isEmpty()) {
                for (member in directOrder!!) {
                    require(!member is Array<Any>) { "cannot store Object[] in NestedSet" }
                    require(!member is ByteString) { "cannot store ByteString in NestedSet" }
                    if (!alreadyInserted.contains(member)) {
                        children[n++] = member!!
                        approxDepth = java.lang.Math.max(approxDepth, 2)
                    }
                }
                alreadyInserted = direct
            } else if ((pass == 1) == preorder && !transitive.isEmpty()) {
                var hoisted: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?>? = null
                for (subset in transitive) {
                    approxDepth = java.lang.Math.max(approxDepth, 1 + subset.approxDepth)
                    // If this is a deserialization future, this call blocks.
                    val c = subset.getChildrenInternal(interruptStrategy)
                    if (c is Array<Any>) {
                        if (c.length < 2) {
                            throw java.lang.AssertionError(c.length)
                        }
                        children[n++] = c
                        shallow = false
                    } else {
                        if (!alreadyInserted.contains(c)) {
                            if (hoisted == null) {
                                hoisted =
                                    com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.create<E?>()
                            }
                            if (hoisted.add(c as E?)) {
                                children[n++] = c!!
                            }
                        }
                    }
                }
                alreadyInserted = if (hoisted == null) com.google.common.collect.ImmutableSet.of<E?>() else hoisted
            }
        }

        // n == |successors|
        if (n == 0) {
            approxDepth = 0
            this.children = EMPTY_CHILDREN
        } else if (n == 1) {
            // If we ended up wrapping exactly one item or one other set, dereference it.
            approxDepth--
            this.children = children[0]
        } else {
            if (n < children.length) {
                children = java.util.Arrays.copyOf<Any?>(children, n) // shrink to save space
            }
            this.children = children
        }
        this.depthAndOrder = (approxDepth shl 2) or order.ordinal()

        this.cached = if (shallow) null else EMPTY_WEAK_REF
    }

    /**
     * Clears the cached list representation to free up memory when no other traversal of the
     * NestedSet is expected.
     * 
     * 
     * Although the cached representation is stored as a [WeakReference], eagerly clearing it
     * helps the garbage collector, plus it also frees the [WeakReference] itself.
     */
    fun clearCachedListRepresentation() {
        if (cached != null) {
            cached = EMPTY_WEAK_REF
        }
    }

    val order: com.google.devtools.build.lib.collect.nestedset.Order?
        /** Returns the ordering of this nested set.  */
        get() = com.google.devtools.build.lib.collect.nestedset.Order.Companion.getOrder(depthAndOrder and 3)

    /**
     * Returns the internal item or array. If the internal item is a deserialization future, blocks on
     * completion. For use only by NestedSetVisitor.
     */
    fun getChildren(): Any? {
        return this.childrenUninterruptibly
    }

    @get:Throws(java.lang.InterruptedException::class)
    val childrenInterruptibly: Any?
        /** Same as [.getChildren], except propagates [InterruptedException].  */
        get() = if (children is com.google.common.util.concurrent.ListenableFuture<*>)
            com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGet<Array<Any?>?>(
                children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>,  /* cancelOnInterrupt= */
                false
            )
        else
            children

    /**
     * What to do when an interruption occurs while getting the result of a deserialization future.
     */
    internal enum class InterruptStrategy {
        /** Crash with [ExitCode.INTERRUPTED].  */
        CRASH,

        /** Throw [InterruptedException].  */
        PROPAGATE
    }

    private val childrenUninterruptibly: Any?
        /**
         * Implementation of [.getChildren] that crashes with the appropriate failure detail if it
         * encounters [InterruptedException].
         */
        get() {
            if (children !is com.google.common.util.concurrent.ListenableFuture<*>) {
                return children
            }
            try {
                return com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGet<Array<Any?>?>(
                    children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>,  /* cancelOnInterrupt= */
                    false
                )
            } catch (e: java.lang.InterruptedException) {
                val failureDetail: FailureDetail? =
                    FailureDetail.newBuilder()
                        .setMessage("Interrupted during NestedSet deserialization")
                        .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                        .build()
                BugReport.handleCrash(
                    Crash.Companion.from(e, DetailedExitCode.of(failureDetail)),
                    CrashContext.Companion.halt()
                )
                throw java.lang.IllegalStateException("Should have halted", e)
            }
        }

    /**
     * Private implementation of getChildren that will propagate an InterruptedException from a future
     * in the nested set based on the value of `interruptStrategy`.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getChildrenInternal(interruptStrategy: InterruptStrategy): Any? {
        return when (interruptStrategy) {
            InterruptStrategy.CRASH -> this.childrenUninterruptibly
            InterruptStrategy.PROPAGATE -> this.childrenInterruptibly
        }
    }

    val isEmpty: Boolean
        /** Returns true if the set is empty. Runs in O(1) time (i.e. does not flatten the set).  */
        get() =// We don't check for future members here, since empty sets are special-cased in serialization
            // and do not make requests against storage.
            children === EMPTY_CHILDREN

    val isSingleton: Boolean
        /** Returns true if the set has exactly one element.  */
        get() = isSingleton(children)

    val approxDepth: Int
        /**
         * Returns the approximate depth of the nested set graph. The empty set has depth zero. A leaf
         * node with a single element has depth 1. A non-leaf node has a depth one greater than its
         * deepest successor.
         * 
         * 
         * This function may return an overapproximation of the true depth if the NestedSet was derived
         * from the result of calling [.getNonLeaves] or [.splitIfExceedsMaximumSize].
         */
        get() = this.depthAndOrder ushr 2

    val isFromStorage: Boolean
        /** Returns true if this set depends on data from storage.  */
        get() = children is com.google.common.util.concurrent.ListenableFuture<*>

    val isReady: Boolean
        /**
         * Returns true if the contents of this set are currently available in memory.
         * 
         * 
         * Only returns false if this set [.isFromStorage] and the contents are not fully
         * deserialized (either because the deserialization future is not complete or because it failed).
         */
        get() {
            if (!this.isFromStorage) {
                return true
            }
            val future: com.google.common.util.concurrent.ListenableFuture<*> =
                children as com.google.common.util.concurrent.ListenableFuture<*>
            if (!future.isDone() || future.isCancelled()) {
                return false
            }
            try {
                com.google.common.util.concurrent.Futures.getDone(future)
                return true
            } catch (e: java.lang.Exception) {
                return false
            }
        }

    /** Returns the single element; only call this if [.isSingleton] returns true.  */
    fun getSingleton(): E? {
        com.google.common.base.Preconditions.checkState(this.isSingleton)
        return children as E?
    }

    /**
     * Returns an immutable list of all unique elements of this set, similar to [.toList], but
     * will propagate an `InterruptedException` or [MissingFingerprintValueException] if
     * one is thrown.
     */
    @Throws(java.lang.InterruptedException::class, MissingFingerprintValueException::class)
    fun toListInterruptibly(): com.google.common.collect.ImmutableList<E?> {
        val actualChildren: Any?
        if (children is com.google.common.util.concurrent.ListenableFuture<*>) {
            actualChildren =
                com.google.devtools.build.lib.concurrent.MoreFutures.waitForFutureAndGetWithCheckedException<Array<Any?>?, MissingFingerprintValueException?>(
                    children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>,  /* cancelOnInterrupt= */
                    false,
                    MissingFingerprintValueException::class.java
                )
        } else {
            actualChildren = children
        }
        return actualChildrenToList(actualChildren)
    }

    /**
     * Returns an immutable list of all unique elements of this set, similar to [.toList], but
     * will propagate an `InterruptedException` if one is thrown and will throw [ ] if this set is deserializing and does not become ready within the given
     * timeout.
     * 
     * 
     * Additionally, throws [MissingFingerprintValueException] if this nested set [ ][.isFromStorage] and could not be retrieved.
     * 
     * 
     * Note that the timeout only applies to blocking for the deserialization future to become
     * available. The actual list transformation is untimed.
     */
    @Throws(
        java.lang.InterruptedException::class,
        java.util.concurrent.TimeoutException::class,
        MissingFingerprintValueException::class
    )
    fun toListWithTimeout(timeout: java.time.Duration): com.google.common.collect.ImmutableList<E?> {
        val actualChildren: Any?
        if (children is com.google.common.util.concurrent.ListenableFuture<*>) {
            try {
                actualChildren =
                    (children as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>).get(
                        timeout.toNanos(),
                        TimeUnit.NANOSECONDS
                    )
            } catch (e: ExecutionException) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfInstanceOf<MissingFingerprintValueException?>(
                    e.getCause(),
                    MissingFingerprintValueException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
                throw java.lang.IllegalStateException(e)
            }
        } else {
            actualChildren = children
        }
        return actualChildrenToList(actualChildren)
    }

    /**
     * Returns an immutable list of all unique elements of this set (including subsets) in an
     * implementation-specified order.
     * 
     * 
     * Prefer calling this method over [ImmutableList.copyOf] on this set for better
     * efficiency, as it saves an iteration.
     */
    fun toList(): com.google.common.collect.ImmutableList<E?> {
        return actualChildrenToList(this.childrenUninterruptibly)
    }

    /**
     * Private implementation of toList which takes the actual children (the deserialized `Object[]` if [.children] is a [ListenableFuture]).
     */
    private fun actualChildrenToList(actualChildren: Any?): com.google.common.collect.ImmutableList<E?> {
        if (actualChildren === EMPTY_CHILDREN) {
            return com.google.common.collect.ImmutableList.of<E?>()
        }
        if (actualChildren !is Array<Any>) {
            return com.google.common.collect.ImmutableList.of<E?>(actualChildren as E?)
        }
        val list: com.google.common.collect.ImmutableList<E?> = expandWithCaching(actualChildren)
        return if (this.order == com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER) list.reverse() else list
    }

    /**
     * Returns an immutable set of all unique elements of this set (including subsets) in an
     * implementation-specified order.
     */
    fun toSet(): com.google.common.collect.ImmutableSet<E?> {
        return com.google.common.collect.ImmutableSet.copyOf<E?>(toList())
    }

    /**
     * Important: This does a full traversal of the nested set if it's not been previously traversed.
     * 
     * @return the size of the nested set.
     */
    fun memoizedFlattenAndGetSize(): Int {
        if (cached == null) {
            val children = this.childrenUninterruptibly
            return if (children is Array<Any>) children.length else 1
        }
        return toList().size()
    }

    /**
     * Returns true if this set is equal to `other` based on the top-level elements and object
     * identity (==) of direct subsets. As such, this function can fail to equate `this` with
     * another `NestedSet` that holds the same elements. It will never fail to detect that two
     * `NestedSet`s are different, however.
     * 
     * 
     * If one of the sets is in the process of deserialization, returns true iff both sets depend
     * on the same future.
     * 
     * @param other the `NestedSet` to compare against.
     */
    fun shallowEquals(other: NestedSet<out E?>?): Boolean {
        if (this == other) {
            return true
        }

        return other != null && this.order == other.order && (children == other.children
                || (!this.isSingleton && !other.isSingleton && children is Array<Any>
                && other.children is Array<Any>
                && java.util.Arrays.equals(children as Array<Any?>, other.children as Array<Any?>)))
    }

    /**
     * Returns a hash code that produces a notion of identity that is consistent with [ ][.shallowEquals]. In other words, if two `NestedSet`s are equal according to `#shallowEquals`, then they return the same `shallowHashCode`.
     * 
     * 
     * The main reason for having these separate functions instead of reusing the standard
     * equals/hashCode is to minimize accidental use, since they are different from both standard Java
     * objects and collection-like objects.
     */
    fun shallowHashCode(): Int {
        return if (this.isSingleton || children is com.google.common.util.concurrent.ListenableFuture<*>)
            java.util.Objects.hash(this.order, children)
        else
            java.util.Objects.hash(this.order, java.util.Arrays.hashCode(children as Array<Any?>?))
    }

    override fun toString(): String {
        if (isSingleton(children)) {
            return "[" + children + "]"
        }
        if (children is java.util.concurrent.Future<*> && !children.isDone()) {
            return "Deserializing NestedSet with future: " + children
        }
        val elems: com.google.common.collect.ImmutableList<*> = toList()
        if (elems.size() <= MAX_ELEMENTS_TO_STRING) {
            return elems.toString()
        }
        return (elems.subList(0, MAX_ELEMENTS_TO_STRING)
            .toString() + " (truncated, full size "
                + elems.size()
                + ")")
    }

    private fun expandWithCaching(children: Array<Any?>): com.google.common.collect.ImmutableList<E?> {
        val localCached: java.lang.ref.WeakReference<com.google.common.collect.ImmutableList<E?>?>? = this.cached
        if (localCached == null) {
            return com.google.common.collect.ImmutableList.copyOf<E?>(ArraySharingCollection<E?>(children))
        }

        var result: com.google.common.collect.ImmutableList<E?>? = localCached.get()
        if (result != null) {
            return result
        }

        synchronized(this) {
            // Read the field again under a lock.
            result = this.cached.get()
            if (result != null) {
                return result
            }
            result = expand(children)
            this.cached = java.lang.ref.WeakReference<com.google.common.collect.ImmutableList<E?>?>(result)
        }
        return result
    }

    /** Implementation of [.toList] for sets with > 1 element.  */
    private fun expand(children: Array<Any?>): com.google.common.collect.ImmutableList<E?> {
        val members: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<E?>(128)
        val arrays = VisitedArraySet()
        arrays.add(children)
        walk(arrays, members, children)
        return com.google.common.collect.ImmutableList.copyOf<E?>(members)
    }

    /**
     * Performs a depth-first traversal of `children`, tracking visited arrays in `arrays`
     * and visited leaves in `members`.
     */
    private fun walk(
        arrays: VisitedArraySet,
        members: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?>,
        children: Array<Any?>
    ) {
        for (child in children) {
            if (child is Array<Any>) {
                if (arrays.add(child)) {
                    walk(arrays, members, child)
                }
            } else {
                members.add(child as E?)
            }
        }
    }

    /**
     * Efficient data structure for tracking the set of visited arrays during [NestedSet.walk].
     * Much more CPU-efficient than general-purpose set implementations like CompactHashSet and
     * Sets.newIdentityHashSet.
     * 
     * 
     * Implements the set data structure via a hash table with open addressing and linear probing,
     * using the identity of the arrays for equals/hashCode.
     */
    internal class VisitedArraySet {
        private var data: Array<Array<Any?>?>
        private var size = 0

        init {
            this.data = arrayOfNulls<Array<Any?>>(256)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(array: Array<Any?>?): Boolean {
            val hashCode: Int = java.lang.System.identityHashCode(array)
            var probe = hash(hashCode, data.length)
            while (data[probe] != null) {
                if (data[probe] == array) {
                    return false
                }
                if (++probe == data.length) {
                    probe = 0
                }
            }
            data[probe] = array
            if (++size * 2 >= data.length) {
                resize()
            }
            return true
        }

        private fun resize() {
            val oldData = data
            data = arrayOfNulls<Array<Any?>>(oldData.length * 2)
            for (array in oldData) {
                if (array == null) {
                    continue
                }
                var probe = hash(java.lang.System.identityHashCode(array), data.length)
                while (data[probe] != null) {
                    if (++probe == data.length) {
                        probe = 0
                    }
                }
                data[probe] = array
            }
        }

        companion object {
            private fun hash(hashCode: Int, length: Int): Int {
                return ((hashCode shl 1) - (hashCode shl 8)) and (length - 1)
            }
        }
    }

    // Hack to share our internal array with ImmutableList/ImmutableSet, or avoid
    // a copy in cases where we can preallocate an array of the correct size.
    private class ArraySharingCollection<E>(private val array: Array<Any?>) : AbstractCollection<E?>() {
        override fun toArray(): Array<Any?> {
            return array
        }

        override fun size(): Int {
            return array.length
        }

        override fun iterator(): MutableIterator<E?>? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    /**
     * Returns a new NestedSet containing the same elements, but represented using a graph node whose
     * out-degree does not exceed `maxDegree`, which must be at least 2. The operation is
     * shallow, not deeply recursive. The resulting set's iteration order is undefined.
     */
    // TODO(adonovan): move this hack into BuildEventStreamer. And rename 'size' to 'degree'.
    fun splitIfExceedsMaximumSize(maxDegree: Int): NestedSet<E?>? {
        com.google.common.base.Preconditions.checkArgument(maxDegree >= 2, "maxDegree must be at least 2")
        val children = getChildren() // may wait for a future
        if (children !is Array<Any>) {
            return this
        }
        val nsuccs: Int = children.length
        if (nsuccs <= maxDegree) {
            return this
        }
        val pieces = arrayOfNulls<Array<Any?>>(ceildiv(nsuccs, maxDegree))
        for (i in pieces.indices) {
            val max: Int = java.lang.Math.min((i + 1) * maxDegree, children.length)
            pieces[i] = java.util.Arrays.copyOfRange<Any?>(children, i * maxDegree, max)
        }
        val depth = this.approxDepth + 1 // may be an overapproximation

        // TODO(adonovan): (preexisting): if the last piece is a singleton, it must be inlined.

        // Each piece is now smaller than maxDegree, but there may be many pieces.
        // Recursively split pieces. (The recursion affects only the root; it
        // does not traverse into successors.) In practice, maxDegree is large
        // enough that the recursion rarely does any work.
        return NestedSet<E?>(this.order, depth, pieces).splitIfExceedsMaximumSize(maxDegree)
    }

    val nonLeaves: com.google.common.collect.ImmutableList<NestedSet<E?>?>
        /** Returns the list of this node's successors that are themselves non-leaf nodes.  */
        get() {
            val children = getChildren() // may wait for a future
            if (children !is Array<Any>) {
                return com.google.common.collect.ImmutableList.of<NestedSet<E?>?>()
            }
            val res: com.google.common.collect.ImmutableList.Builder<NestedSet<E?>?> =
                com.google.common.collect.ImmutableList.builder<NestedSet<E?>?>()
            for (c in children) {
                if (c is Array<Any>) {
                    val depth = this.approxDepth - 1 // possible overapproximation
                    res.add(NestedSet<E?>(this.order, depth, c))
                }
            }
            return res.build()
        }

    val leaves: com.google.common.collect.ImmutableList<E?>
        /**
         * Returns the list of elements (leaf nodes) of this set that are reached by following at most one
         * graph edge.
         */
        get() {
            val children = getChildren() // may wait for a future
            if (children !is Array<Any>) {
                return com.google.common.collect.ImmutableList.of<E?>(children as E?)
            }
            val res: com.google.common.collect.ImmutableList.Builder<E?> =
                com.google.common.collect.ImmutableList.builder<E?>()
            for (c in children as Array<Any?>) {
                if (c !is Array<Any>) {
                    res.add(c as E?)
                }
            }
            return res.build()
        }

    /**
     * Returns a Node, an opaque reference to the logical node of the DAG that this NestedSet
     * represents.
     */
    fun toNode(): Node {
        return com.google.devtools.build.lib.collect.nestedset.NestedSet.Node(children)
    }

    /**
     * A Node is an opaque reference to a logical node of the NestedSet DAG.
     * 
     * 
     * The only operation it supports is [Object.equals]. Branch nodes are equal if and only
     * if they refer to the same logical graph node. Leaf nodes are equal if they refer to equal
     * elements. Two distinct NestedSets may have equal elements.
     * 
     * 
     * Node is provided so that clients can implement their own traversals and detect when they
     * have encountered a subgraph already visited.
     */
    class Node private constructor(private val children: Any) {
        override fun hashCode(): Int {
            return children.hashCode()
        }

        override fun equals(that: Any?): Boolean {
            return that is Node && this.children == that.children
        }

        override fun toString(): String {
            return "NestedSet.Node@" + hashCode() // intentionally opaque
        }
    }

    companion object {
        /** Initial value of [.cached] indicating that a traversal is necessary.  */
        // Safe to use as WeakReference<ImmutableList<E>> since it's null.
        private val EMPTY_WEAK_REF: java.lang.ref.WeakReference<*> = java.lang.ref.WeakReference<Any?>(null)

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        @SerializationConstant
        val EMPTY_CHILDREN: Array<Any?> = arrayOf<Any?>()

        /** Returns a new builder.  */
        fun <E> builder(order: com.google.devtools.build.lib.collect.nestedset.Order): NestedSetBuilder<E?> {
            return NestedSetBuilder.Companion.newBuilder<E?>(order)
        }

        /**
         * Constructs a NestedSet that is currently being deserialized. The provided future, when
         * complete, gives the contents of the NestedSet.
         */
        fun <E> withFuture(
            order: com.google.devtools.build.lib.collect.nestedset.Order,
            depth: Int,
            deserializationFuture: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
        ): NestedSet<E?> {
            return NestedSet<E?>(order, depth, deserializationFuture)
        }

        @AutoCodec.Instantiator
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        fun <E> forDeserialization(
            order: com.google.devtools.build.lib.collect.nestedset.Order,
            approxDepth: Int,
            children: Any
        ): NestedSet<E?> {
            com.google.common.base.Preconditions.checkState(
                children !is com.google.common.util.concurrent.ListenableFuture<*>,
                children
            )
            return NestedSet<E?>(order, approxDepth, children)
        }

        private fun hasTransitiveMember(children: Array<Any?>): Boolean {
            for (child in children) {
                if (child is Array<Any>) {
                    return true
                }
            }
            return false
        }

        private fun isSingleton(children: Any?): Boolean {
            // Singleton sets are special cased in serialization, and make no calls to storage.  Therefore,
            // we know that any NestedSet with a ListenableFuture member is not a singleton.
            return !(children is Array<Any> || children is com.google.common.util.concurrent.ListenableFuture<*>)
        }

        @com.google.common.annotations.VisibleForTesting
        const val MAX_ELEMENTS_TO_STRING: Int = 1000000

        // ceildiv(x/y) returns ⌈x/y⌉.
        private fun ceildiv(x: Int, y: Int): Int {
            return (x + y - 1) / y
        }
    }
}
