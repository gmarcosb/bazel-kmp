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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSet.InterruptStrategy
import java.util.AbstractCollection

/**
 * A builder for nested sets.
 * 
 * 
 * The builder supports the standard builder interface (that is, `#add`, `#addAll`
 * and `#addTransitive` followed by `build`), in addition to shortcut method `#wrap`. Any duplicate elements will be inserted as-is, and pruned later on during the traversal
 * of the actual NestedSet.
 */
abstract class NestedSetBuilder<E> private constructor(order: com.google.devtools.build.lib.collect.nestedset.Order?) {
    private val order: com.google.devtools.build.lib.collect.nestedset.Order?
    private var items: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<E?>? = null

    /**
     * Returns the order used by this builder.
     * 
     * 
     * This is useful for testing for incompatibilities (via [Order.isCompatible]) without
     * catching an unchecked exception from [.addTransitive].
     */
    fun getOrder(): com.google.devtools.build.lib.collect.nestedset.Order? {
        return order
    }

    open val isEmpty: Boolean
        /** Returns whether the set to be built is empty.  */
        get() = items == null

    /**
     * Adds a direct member to the set to be built.
     * 
     * 
     * The relative left-to-right order of direct members is preserved from the sequence of calls
     * to [.add] and [.addAll]. Since the traversal [Order] controls whether direct
     * members appear before or after transitive ones, the interleaving of [.add]/[ ][.addAll] with [.addTransitive] does not matter.
     * 
     * @param element item to add; must not be null
     * @return the builder
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun add(element: E?): NestedSetBuilder<E?> {
        com.google.common.base.Preconditions.checkNotNull<E?>(element)
        if (items == null) {
            items = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.create<E?>()
        }
        items.add(element)
        return this
    }

    /**
     * Adds a sequence of direct members to the set to be built. Equivalent to invoking [.add]
     * for each item in `elements`, in order.
     * 
     * 
     * The relative left-to-right order of direct members is preserved from the sequence of calls
     * to [.add] and [.addAll]. Since the traversal [Order] controls whether direct
     * members appear before or after transitive ones, the interleaving of [.add]/[ ][.addAll] with [.addTransitive] does not matter.
     * 
     * @param elements the sequence of items to add; must not be null
     * @return the builder
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addAll(elements: Iterable<out E?>?): NestedSetBuilder<E?> {
        com.google.common.base.Preconditions.checkNotNull(elements)
        if (items == null) {
            val n: Int = com.google.common.collect.Iterables.size(elements)
            if (n == 0) {
                return this // avoid allocating an empty set
            }
            items =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<E?>(
                    n
                )
        }
        com.google.common.collect.Iterables.addAll<E?>(items, elements)
        return this
    }

    /**
     * Adds a nested set as a transitive member to the set to be built.
     * 
     * 
     * The relative left-to-right order of transitive members is preserved from the sequence of
     * calls to [.addTransitive]. Since the traversal [Order] controls whether direct
     * members appear before or after transitive ones, the interleaving of [.add]/[ ][.addAll] with [.addTransitive] does not matter.
     * 
     * 
     * The [Order] of the added set must be compatible with the order of this builder (see
     * [Order.isCompatible]). This is true even if the added set is empty. Strictly speaking, it
     * is not technically necessary that two nested sets have compatible orders for them to be
     * combined as part of one larger set. But checking for it helps readability and protects against
     * bugs. Since [Order.STABLE_ORDER] is compatible with everything, it effectively disables
     * the check. This can be used as an escape hatch to mix and match the set arbitrarily, including
     * sharing the set as part of multiple other larger sets that have disagreeing orders.
     * 
     * 
     * The relative order of the elements of an added set are preserved, unless it has duplicates
     * or overlaps with other added sets, or its order is different from that of the builder.
     * 
     * @param subset the set to add as a transitive member; must not be null
     * @return the builder
     * @throws IllegalArgumentException if the order of `subset` is not compatible with the
     * order of this builder
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addTransitive(subset: NestedSet<out E?>?): NestedSetBuilder<E?> {
        com.google.common.base.Preconditions.checkNotNull(subset)
        com.google.common.base.Preconditions.checkArgument(
            getOrder().isCompatible(subset.getOrder()),
            "Order mismatch: %s != %s",
            subset.getOrder().getStarlarkName(),
            getOrder().getStarlarkName()
        )
        if (!subset.isEmpty()) {
            addTransitiveImpl(subset)
        }
        return this
    }

    @com.google.errorprone.annotations.ForOverride
    abstract fun addTransitiveImpl(subset: NestedSet<out E?>?)

    /**
     * Builds the actual nested set.
     * 
     * 
     * This method may be called multiple times with interleaved [.add], [.addAll] and
     * [.addTransitive] calls.
     */
    fun build(): NestedSet<E?>? {
        try {
            return buildInternal(InterruptStrategy.CRASH)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("Cannot throw with InterruptStrategy.CRASH", e)
        }
    }

    /**
     * Similar to [.build] except that if any subset is based on a deserialization future and an
     * interrupt is observed, [InterruptedException] is propagated.
     */
    @Throws(java.lang.InterruptedException::class)
    fun buildInterruptibly(): NestedSet<E?>? {
        return buildInternal(InterruptStrategy.PROPAGATE)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun buildInternal(interruptStrategy: InterruptStrategy?): NestedSet<E?>? {
        if (this.isEmpty) {
            return getOrder().emptySet<E?>()
        }

        val direct: MutableSet<E?> =
            com.google.common.base.MoreObjects.firstNonNull(items, com.google.common.collect.ImmutableSet.of<E?>())
        val transitive: MutableCollection<NestedSet<E?>?> = this.transitive

        // When there is exactly one transitive set, we can reuse it if its order matches and either
        // there are no direct members, or the only direct member equals the transitive set's singleton.
        if (transitive.size() == 1 && direct.size() <= 1) {
            val candidate: NestedSet<E?>? =
                com.google.common.collect.Iterables.getOnlyElement<NestedSet<E?>?>(transitive)
            if (candidate.getOrder() == getOrder()
                && (direct.isEmpty()
                        || com.google.common.collect.Iterables.getOnlyElement<E?>(direct) == candidate.getChildrenInterruptibly())
            ) {
                return candidate
            }
        }

        return NestedSet<E?>(getOrder(), direct, transitive, interruptStrategy)
    }

    @get:com.google.errorprone.annotations.ForOverride
    abstract val transitive: MutableCollection<NestedSet<E?>>

    init {
        this.order = order
    }

    private class DefaultNestedSetBuilder<E>(order: com.google.devtools.build.lib.collect.nestedset.Order?) :
        NestedSetBuilder<E?>(order) {
        private var transitiveSets: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<NestedSet<E?>?>? =
            null

        override fun addTransitiveImpl(subset: NestedSet<out E?>?) {
            if (transitiveSets == null) {
                transitiveSets =
                    com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.create<NestedSet<E?>?>()
            }
            transitiveSets.add(subset as NestedSet<E?>?)
        }

        override fun getTransitive(): MutableCollection<NestedSet<E?>?> {
            return com.google.common.base.MoreObjects.firstNonNull<AbstractCollection<NestedSet<E?>?>>(
                transitiveSets,
                com.google.common.collect.ImmutableList.of<NestedSet<E?>?>()
            )
        }

        override fun isEmpty(): Boolean {
            return super.isEmpty && transitiveSets == null
        }
    }

    /**
     * A specialized [NestedSetBuilder] for [Order.LINK_ORDER] that reverses the order of
     * transitive inputs *before* deduplication.
     * 
     * 
     * [DefaultNestedSetBuilder] deduplicates transitive sets as they are added. This class,
     * however, delays deduplication until [.build] is called. Furthermore, for [ ][Order.LINK_ORDER], the input order of transitive sets is reversed to implement a right-to-left
     * traversal. The order of these operations (reversal and deduplication) affects the final result.
     * 
     * 
     * Consider the transitive inputs `<A, B, A>`, where `A` and `B` are [ ]s with no common elements.
     * 
     * 
     *  * **Deduplicate-then-reverse:** If we deduplicate first and then reverse, the
     * intermediate result is `<B, A>`. When this is passed to [NestedSet.toList],
     * the final output is `<toList(A), toList(B)>` (because `toList` also reverses
     * [Order.LINK_ORDER] sets).
     *  * **Reverse-then-deduplicate:** If we reverse first and then deduplicate, the
     * intermediate result is `<A, B>`. When passed to [NestedSet.toList], the final
     * output is `<toList(B), toList(A)>`.
     * 
     * 
     * 
     * This class implements the **reverse-then-deduplicate** strategy. This choice is important
     * for consistency when deeply equivalent, but distinct, [NestedSet] instances are used as
     * transitive inputs.
     * 
     * 
     * Consider transitive inputs `<A, B, A*>`, where `A*` is deeply equivalent to
     * `A` (i.e., `A.deepEquals(A*) == true`) but is a different instance (i.e., `A != A*`). With both reverse-then-deduplicate and deduplicate-then-reverse, the intermediate
     * result is `<A*, B, A>`. [NestedSet.toList], which deduplicates `A`
     * element-wise, then produces `<toList(B), toList(A*))>`. By deep-equality of `A` and
     * `A*`, this result is equivalent to `<toList(B), toList(A)>` which is only
     * consistent with **reverse-then-deduplicate**.
     * 
     * 
     * In summary, this builder ensures that [NestedSet.toList], when used with a [ ] built by this builder, behaves consistently, regardless of whether transitive inputs
     * are the same instance or are deeply equivalent (but distinct) instances.
     */
    private class LinkOrderNestedSetBuilder<E>(order: com.google.devtools.build.lib.collect.nestedset.Order?) :
        NestedSetBuilder<E?>(order) {
        private var transitiveSets: java.util.ArrayList<NestedSet<E?>?>? = null

        override fun addTransitiveImpl(subset: NestedSet<out E?>?) {
            if (transitiveSets == null) {
                transitiveSets = java.util.ArrayList<NestedSet<E?>?>()
            }
            transitiveSets.add(subset as NestedSet<E?>?)
        }

        override fun getTransitive(): MutableCollection<NestedSet<E?>?> {
            if (transitiveSets == null) {
                return com.google.common.collect.ImmutableList.of<NestedSet<E?>?>()
            }
            val size: Int = transitiveSets.size()
            if (size == 1) {
                return transitiveSets
            }
            val reversedAndDeduped: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<NestedSet<E?>?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.createWithExpectedSize<NestedSet<E?>?>(
                    size
                )
            for (i in size - 1 downTo 0) {
                reversedAndDeduped.add(transitiveSets.get(i))
            }
            return reversedAndDeduped
        }

        override fun isEmpty(): Boolean {
            return super.isEmpty && transitiveSets == null
        }
    }

    companion object {
        fun <E> newBuilder(order: com.google.devtools.build.lib.collect.nestedset.Order): NestedSetBuilder<E?> {
            return when (order) {
                com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER -> LinkOrderNestedSetBuilder<E?>(order)
                else -> DefaultNestedSetBuilder<E?>(order)
            }
        }

        private val stableOrderImmutableListCache: com.github.benmanes.caffeine.cache.LoadingCache<com.google.common.collect.ImmutableList<*>?, NestedSet<*>?> =
            Caffeine.newBuilder()
                .initialCapacity(16)
                .weakKeys()
                .build<com.google.common.collect.ImmutableList<*>?, NestedSet<*>?>(com.github.benmanes.caffeine.cache.CacheLoader { list: com.google.common.collect.ImmutableList<*>? ->
                    newBuilder<Any?>(
                        com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER
                    ).addAll(list).build()
                })

        /** Creates a nested set from a given list of items.  */
        fun <E> wrap(
            order: com.google.devtools.build.lib.collect.nestedset.Order,
            wrappedItems: Iterable<out E?>
        ): NestedSet<E?>? {
            if (com.google.common.collect.Iterables.isEmpty(wrappedItems)) {
                return order.emptySet<E?>()
            } else if (order == com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER && wrappedItems is com.google.common.collect.ImmutableList<*>) {
                val wrappedList: com.google.common.collect.ImmutableList<E?> =
                    wrappedItems as com.google.common.collect.ImmutableList<E?>
                if (wrappedList.size() > 1) {
                    return stableOrderImmutableListCache.get(wrappedList) as NestedSet<E?>?
                }
            }
            return newBuilder<E?>(order).addAll(wrappedItems).build()
        }

        /**
         * Creates a nested set with the given list of items as its elements.
         */
        fun <E> create(order: com.google.devtools.build.lib.collect.nestedset.Order, vararg elems: E?): NestedSet<E?>? {
            return wrap<E?>(order, com.google.common.collect.ImmutableList.copyOf<E?>(elems))
        }

        /**
         * Creates an empty nested set.
         */
        fun <E> emptySet(order: com.google.devtools.build.lib.collect.nestedset.Order): NestedSet<E?>? {
            return order.emptySet<E?>()
        }

        /**
         * Creates a builder for stable order nested sets.
         */
        @kotlin.jvm.JvmStatic
        fun <E> stableOrder(): NestedSetBuilder<E?> {
            return newBuilder<E?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER)
        }

        /**
         * Creates a builder for compile order nested sets.
         */
        @kotlin.jvm.JvmStatic
        fun <E> compileOrder(): NestedSetBuilder<E?> {
            return newBuilder<E?>(com.google.devtools.build.lib.collect.nestedset.Order.COMPILE_ORDER)
        }

        /**
         * Creates a builder for link order nested sets.
         */
        @kotlin.jvm.JvmStatic
        fun <E> linkOrder(): NestedSetBuilder<E?> {
            return newBuilder<E?>(com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER)
        }

        /**
         * Creates a builder for naive link order nested sets.
         */
        @kotlin.jvm.JvmStatic
        fun <E> naiveLinkOrder(): NestedSetBuilder<E?> {
            return newBuilder<E?>(com.google.devtools.build.lib.collect.nestedset.Order.NAIVE_LINK_ORDER)
        }

        fun <E> fromNestedSet(set: NestedSet<out E?>): NestedSetBuilder<E?> {
            return newBuilder<E?>(set.getOrder()).addTransitive(set)
        }

        /**
         * Creates a Builder with the contents of 'sets'.
         * 
         * 
         * If 'sets' is empty, a stable-order empty NestedSet is returned.
         */
        fun <E> fromNestedSets(sets: Iterable<NestedSet<E?>?>): NestedSetBuilder<E?> {
            val firstSet: NestedSet<*>? =
                com.google.common.collect.Iterables.getFirst<NestedSet<E?>?>(sets, null /* defaultValue */)
            if (firstSet == null) {
                return stableOrder<E?>()
            }
            val result = newBuilder<E?>(firstSet.getOrder())
            sets.forEach(java.util.function.Consumer { subset: NestedSet<E?>? -> result.addTransitive(subset) })
            return result
        }
    }
}
