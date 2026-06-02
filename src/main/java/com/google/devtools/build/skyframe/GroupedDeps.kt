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

import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet

/**
 * Encapsulates Skyframe dependencies, preserving the groups in which they were requested.
 * 
 * 
 * This class itself does no duplicate checking, although it is expected that a `GroupedDeps` instance contains no duplicates - Skyframe is responsible for only adding keys which
 * are not already present.
 * 
 * 
 * [.equals] is sensitive the order of groups, but is insensitive to the order of elements
 * within a group.
 */
open class GroupedDeps private constructor(
    size: Int,
    elements: java.util.ArrayList<Any?>,
    groupIndices: java.util.ArrayList<Int?> = newSmallArrayList<Int?>()
) : Iterable<MutableList<SkyKey?>> {
    /**
     * Indicates that the annotated element is a compressed [GroupedDeps], so that it can be
     * safely passed to [.decompress] and friends.
     */
    @org.checkerframework.framework.qual.SubtypeOf(DefaultObject::class)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @org.checkerframework.framework.qual.QualifierForLiterals(org.checkerframework.framework.qual.LiteralKind.NULL)
    annotation class Compressed

    /** Default annotation for type-safety checks of [Compressed].  */
    @org.checkerframework.framework.qual.DefaultQualifierInHierarchy
    @org.checkerframework.framework.qual.SubtypeOf
    @Target(
        AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER
    )
    private annotation class DefaultObject

    /** The total number of deps.  */
    private var size = 0

    /**
     * The deps and group delimiters. Each element is either a [SkyKey] or [Integer].
     * Integers represent the start of a new group and indicate how many elements are in the group.
     * Singleton groups have no preceding integer.
     * 
     * 
     * The group sizes are redundant with the indices stored in [.groupIndices], but they are
     * stored here nonetheless so that [.compress] can simply convert this list to an array.
     */
    private val elements: java.util.ArrayList<Any?>

    /**
     * Indices into [.elements] for each group, maintained to provide constant time access to
     * groups in [.getDepGroup]. The first group has no entry in this list since it always
     * starts at index 0. Otherwise, the starting index for group `i` in [.elements] is
     * stored in this list at index `i - 1`. For multi-element groups, the starting index refers
     * to the position of the [Integer] representing the size of the group.
     */
    private val groupIndices: java.util.ArrayList<Int?>

    private val collectionView: CollectionView = com.google.devtools.build.skyframe.GroupedDeps.CollectionView()

    constructor() : this(0, newSmallArrayList<Any?>())

    /**
     * Adds a new group with a single element.
     * 
     * 
     * The caller must ensure that the given element is not already present.
     */
    open fun appendSingleton(key: SkyKey?) {
        markNextGroup()
        elements.add(key)
        size++
    }

    /**
     * Adds a new group.
     * 
     * 
     * The caller must ensure that the new group is duplicate-free and does not contain any
     * elements which are already present.
     */
    open fun appendGroup(group: MutableList<SkyKey?>) {
        appendNextGroup(group.iterator(), group.size())
    }

    /**
     * Adds possibly many new groups.
     * 
     * 
     * The iteration order of the given deps along with the `groupSizes` parameter dictate
     * how deps are grouped. For example, if `deps = {a,b,c}` and `groupSizes = [2, 1]`,
     * then there will be two groups: `[a,b]` and `[c]`. The sum of `groupSizes`
     * must equal the size of `deps`. Note that it only makes sense to call this method with a
     * set implementation that has a stable iteration order.
     * 
     * 
     * The caller must ensure that the given set of deps does not contain any elements which are
     * already present.
     */
    open fun appendGroups(deps: MutableSet<SkyKey?>, groupSizes: MutableList<Int>) {
        elements.ensureCapacity(elements.size() + deps.size())
        if (isEmpty()) {
            groupIndices.ensureCapacity(groupSizes.size() - 1)
        } else {
            groupIndices.ensureCapacity(groupIndices.size() + groupSizes.size())
        }
        val it: MutableIterator<SkyKey?> = deps.iterator()
        for (size in groupSizes) {
            appendNextGroup(it, size)
        }
        com.google.common.base.Preconditions.checkArgument(
            !it.hasNext(), "size(deps) != sum(groupSizes) (deps=%s, groupSizes=%s)", deps, groupSizes
        )
    }

    private fun appendNextGroup(it: MutableIterator<SkyKey?>, groupSize: Int) {
        if (groupSize == 0) {
            return
        }
        if (groupSize == 1) {
            appendSingleton(it.next())
            return
        }
        markNextGroup()
        elements.ensureCapacity(elements.size() + groupSize + 1)
        elements.add(groupSize)
        for (i in 0..<groupSize) {
            elements.add(it.next())
        }
        size += groupSize
    }

    private fun markNextGroup() {
        if (!isEmpty()) {
            groupIndices.add(elements.size())
        }
    }

    /**
     * Removes the elements in `toRemove` from this `GroupedDeps`. Takes time proportional
     * to the number of deps, so should not be called often.
     * 
     * 
     * Should not be called during iteration.
     */
    open fun remove(toRemove: MutableSet<SkyKey?>) {
        if (toRemove.isEmpty()) {
            return
        }
        val newDeps = GroupedDeps()
        for (group in this) {
            val newGroup: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>(group.size())
            for (key in group!!) {
                if (!toRemove.contains(key)) {
                    newGroup.add(key)
                }
            }
            newDeps.appendGroup(newGroup)
        }

        com.google.common.base.Preconditions.checkArgument(
            newDeps.size == size - toRemove.size(),
            "Requested removal of absent element(s) (toRemove=%s, elements=%s)",
            toRemove,
            elements
        )

        size = newDeps.size
        elements.clear()
        elements.addAll(newDeps.elements)
        groupIndices.clear()
        groupIndices.addAll(newDeps.groupIndices)
    }

    /**
     * Returns the group at position `i` as an unmodifiable list.
     * 
     * 
     * The returned list is a live view of the backing list, so should not be used after a
     * subsequent call to [.remove].
     */
    // Cast of sublist containing only SkyKeys to List<SkyKey>.
    fun getDepGroup(i: Int): MutableList<SkyKey?> {
        val index = if (i == 0) 0 else groupIndices.get(i - 1)
        val obj: Any? = elements.get(index)
        if (obj is SkyKey) {
            return com.google.common.collect.ImmutableList.of<SkyKey?>(obj)
        }
        val groupSize = obj as Int
        val slice: MutableList<*> = elements.subList(index + 1, index + 1 + groupSize)
        return Collections.unmodifiableList(slice) as MutableList<SkyKey?>
    }

    /** Returns the number of dependency groups.  */
    fun numGroups(): Int {
        return if (isEmpty()) 0 else groupIndices.size() + 1
    }

    /**
     * Returns the number of individual dependencies, as opposed to the number of groups -- equivalent
     * to adding up the sizes of each dependency group.
     */
    fun numElements(): Int {
        return size
    }

    private enum class CompressionCase {
        EMPTY,
        SINGLETON,
        MULTIPLE
    }

    /** Returns true if this list contains no elements.  */
    fun isEmpty(): Boolean {
        return elements.isEmpty()
    }

    /**
     * Returns true if this list contains the given key. May take time proportional to list size. Call
     * [.toSet] instead and use the result if doing multiple contains checks and this is not a
     * [WithHashSet].
     */
    open fun contains(key: SkyKey?): Boolean {
        return elements.contains(key)
    }

    init {
        this.size = size
        this.elements = elements
        this.groupIndices = groupIndices
    }

    /**
     * Returns a memory-efficient representation of dependency groups.
     * 
     * 
     * The compressed representation does not support mutation or random access to dep groups. If
     * this functionality is needed, use [.decompress].
     */
    fun compress(): @Compressed Any? {
        when (numElements()) {
            0 -> return EMPTY_COMPRESSED
            1 -> return elements.get(0)
            else -> return elements.toArray()
        }
    }

    open fun toSet(): com.google.common.collect.ImmutableSet<SkyKey?> {
        val builder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builderWithExpectedSize<SkyKey?>(size)
        for (obj in elements) {
            if (obj is SkyKey) {
                builder.add(obj)
            }
        }
        return builder.build()
    }

    override fun hashCode(): Int {
        // Hashing requires getting an order-independent hash for each element of this.elements. That
        // is too expensive for a hash code.
        throw java.lang.UnsupportedOperationException("Should not need to get hash for " + this)
    }

    /**
     * A grouping-unaware view which does not support modifications.
     * 
     * 
     * This is implemented as a `Collection` so that calling [Iterables.size] on the
     * return value of [.getAllElementsAsIterable] will take constant time.
     */
    private inner class CollectionView : AbstractCollection<SkyKey?>() {
        override fun iterator(): MutableIterator<SkyKey?> {
            return UngroupedIterator(elements)
        }

        override fun size(): Int {
            return size
        }
    }

    /** An iterator that loops through every element in each group.  */
    private class UngroupedIterator(private val elements: MutableList<Any?>) : MutableIterator<SkyKey?> {
        private var i = 0

        init {
            advanceIfSizeMarker()
        }

        override fun hasNext(): Boolean {
            return i < elements.size()
        }

        override fun next(): SkyKey? {
            val next: SkyKey? = elements.get(i++) as SkyKey?
            advanceIfSizeMarker()
            return next
        }

        fun advanceIfSizeMarker() {
            if (i < elements.size() && elements.get(i) is Int) {
                i++
            }
        }
    }

    @ThreadHostile
    fun getAllElementsAsIterable(): MutableCollection<SkyKey?> {
        return collectionView
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is GroupedDeps) {
            return false
        }
        // Fast paths for inequality.
        if (this.size != other.size || this.elements.size() != other.elements.size() || this.numGroups() != other.numGroups()) {
            return false
        }
        // We must check the deps, ignoring the ordering of deps in the same group.
        val thisIt: MutableIterator<MutableList<SkyKey?>?> = this.iterator()
        val thatIt: MutableIterator<MutableList<SkyKey?>?> = other.iterator()
        while (thisIt.hasNext()) {
            if (!Companion.checkUnorderedEqualityOfGroups(thisIt.next(), thatIt.next())) {
                return false
            }
        }
        return true
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("size", size).add("elements", elements)
            .toString()
    }

    /**
     * Iterator that returns the next group in elements for each call to [.next]. A custom
     * iterator is needed here because, to optimize memory, we store single-element lists as elements
     * internally, and so they must be wrapped before they're returned.
     */
    private inner class GroupedIterator : MutableIterator<MutableList<SkyKey?>?> {
        private var i = 0

        override fun hasNext(): Boolean {
            return i < numGroups()
        }

        override fun next(): MutableList<SkyKey?> {
            return getDepGroup(i++)
        }
    }

    override fun iterator(): MutableIterator<MutableList<SkyKey?>?> {
        return GroupedIterator()
    }

    /**
     * A [GroupedDeps] which keeps a [HashSet] of its elements up to date, resulting in a
     * higher memory cost and faster [.contains] operations.
     */
    class WithHashSet : GroupedDeps() {
        private val set: HashSet<SkyKey?> = HashSet<SkyKey?>()

        override fun appendSingleton(key: SkyKey?) {
            super.appendSingleton(key)
            set.add(key)
        }

        override fun appendGroup(group: MutableList<SkyKey?>) {
            super.appendGroup(group)
            set.addAll(group)
        }

        override fun appendGroups(deps: MutableSet<SkyKey?>, groupSizes: MutableList<Int>) {
            super.appendGroups(deps, groupSizes)
            set.addAll(deps)
        }

        override fun remove(toRemove: MutableSet<SkyKey?>) {
            super.remove(toRemove)
            set.removeAll(toRemove)
        }

        override fun contains(needle: SkyKey?): Boolean {
            return set.contains(needle)
        }

        override fun toSet(): com.google.common.collect.ImmutableSet<SkyKey?> {
            return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(set)
        }
    }

    companion object {
        /**
         * Creates a new [ArrayList] with single-element capacity.
         * 
         * 
         * Many Skyframe nodes have only 0 or 1 dep. Pre-sizing small reduces garbage.
         */
        private fun <T> newSmallArrayList(): java.util.ArrayList<T?> {
            return java.util.ArrayList<T?>(1)
        }

        @kotlin.jvm.JvmStatic
        fun numElements(compressed: @Compressed Any): Int {
            when (compressionCase(compressed)) {
                CompressionCase.EMPTY -> return 0
                CompressionCase.SINGLETON -> return 1
                CompressionCase.MULTIPLE -> {
                    val arr = compressed as Array<Any?>
                    val size = 0
                    val i = 0
                    while (i < arr.size) {
                        val obj = arr[i++]
                        if (obj is SkyKey) {
                            size++
                        } else {
                            val groupSize = obj as Int
                            size += groupSize
                            i += groupSize
                        }
                    }
                    return size
                }
            }
            throw java.lang.AssertionError(compressed)
        }

        private fun compressionCase(compressed: @Compressed Any): CompressionCase {
            if (compressed === EMPTY_COMPRESSED) {
                return CompressionCase.EMPTY
            }
            if (compressed is SkyKey) {
                return CompressionCase.SINGLETON
            }
            com.google.common.base.Preconditions.checkArgument(compressed.getClass().isArray(), compressed)
            return CompressionCase.MULTIPLE
        }

        /**
         * Converts a compressed `GroupedDeps` into an [Iterable]. Equivalent to calling
         * [.decompress] and then [.getAllElementsAsIterable], but more efficient.
         */
        @kotlin.jvm.JvmStatic
        fun compressedToIterable(compressed: @Compressed Any): Iterable<SkyKey?> {
            when (compressionCase(compressed)) {
                CompressionCase.EMPTY -> return com.google.common.collect.ImmutableList.of<SkyKey?>()
                CompressionCase.SINGLETON -> return com.google.common.collect.ImmutableList.of<SkyKey?>(compressed as SkyKey?)
                CompressionCase.MULTIPLE -> {
                    val elements: MutableList<Any?> = java.util.Arrays.asList<Any?>(*compressed as Array<Any?>?)
                    return Iterable { UngroupedIterator(elements) }
                }
            }
            throw java.lang.AssertionError(compressed)
        }

        /**
         * Casts an `Object` which is known to be [Compressed].
         * 
         * 
         * This method should only be used when it is not possible to enforce the type via annotations.
         */
        fun castAsCompressed(obj: Any): @Compressed Any? {
            com.google.common.base.Preconditions.checkArgument(
                obj === EMPTY_COMPRESSED || obj is SkyKey || obj.getClass().isArray()
            )
            return obj as @Compressed Any?
        }

        /** Determines whether the given compressed `GroupedDeps` is empty.  */
        @kotlin.jvm.JvmStatic
        fun isEmpty(compressed: @Compressed Any?): Boolean {
            return compressed === EMPTY_COMPRESSED
        }

        @SerializationConstant
        val EMPTY_COMPRESSED: @Compressed Any = Any()

        /** Reconstitutes a compressed representation returned by [.compress].  */
        @kotlin.jvm.JvmStatic
        fun decompress(compressed: @Compressed Any): GroupedDeps {
            when (compressionCase(compressed)) {
                CompressionCase.EMPTY -> return GroupedDeps()
                CompressionCase.SINGLETON -> return GroupedDeps(
                    1,
                    com.google.common.collect.Lists.newArrayList<Any?>(compressed)
                )

                CompressionCase.MULTIPLE -> {
                    // Count the size and reconstruct groupIndices.
                    val arr = compressed as Array<Any?>
                    val size = 0
                    val groupIndices: java.util.ArrayList<Int?> = newSmallArrayList<Int?>()
                    val i = 0
                    while (i < arr.size) {
                        if (i > 0) {
                            groupIndices.add(i)
                        }
                        val obj = arr[i++]
                        if (obj is SkyKey) {
                            size++
                        } else {
                            val groupSize = obj as Int
                            size += groupSize
                            i += groupSize
                        }
                    }
                    return GroupedDeps(size, com.google.common.collect.Lists.newArrayList<Any?>(*arr), groupIndices)
                }
            }
            throw java.lang.AssertionError(compressed)
        }

        /**
         * Checks that two dep groups (neither of which may contain duplicates) have the same elements,
         * regardless of order.
         */
        private fun checkUnorderedEqualityOfGroups(
            group1: MutableList<SkyKey?>,
            group2: MutableList<SkyKey?>
        ): Boolean {
            if (group1.size() != group2.size()) {
                return false
            }
            // The order-sensitive comparison usually returns true. When it does, the CompactHashSet doesn't
            // need to be constructed.
            return group1 == group2 || CompactHashSet.create(group1).containsAll(group2)
        }
    }
}
