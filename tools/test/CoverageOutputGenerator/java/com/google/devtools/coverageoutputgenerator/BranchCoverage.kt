// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.coverageoutputgenerator

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.coverageoutputgenerator.BranchCoverageItem
import com.google.devtools.coverageoutputgenerator.BranchCoverageKey
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.BitSet

/**
 * A store for branch coverage data.
 * 
 * 
 * Implemented as an open-addressed hash map to avoid excessive object creation.
 * 
 * 
 * Potentially many instances of this class will be created and then later merged. To avoid
 * excessive GC and memory pressure we store the data as arrays of primitives for as long as we can,
 * only creating the [BranchCoverageItem] object when it's requested, which should only happen
 * after all branch data has been read and merged. This may make the interface to this class
 * atypical and a little cumbersome, but wrapping the data in an object when adding to this map
 * would be very expensive relative to the necessary work being done.
 */
internal class BranchCoverage private constructor(
    lineNumberKeyData: IntArray,
    branchIdKeydData: Array<String>,
    blockIdKeyData: Array<String>,
    executionCountData: LongArray,
    evaluatedData: BitSet,
    size: Int
) : Iterable<MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?>?> {
    // Key data
    // A slot is filled if the corresponding branchId value is non-null
    private var lineNumberKeyData: IntArray
    private var branchIdKeyData: Array<String>
    private var blockIdKeyData: Array<String>

    // Value data
    private var executionCountData: LongArray

    // This is simply keyed by line number
    private val evaluatedData: BitSet

    private var capacity: Int
    private var size: Int

    init {
        val capacity = lineNumberKeyData.size
        require(!(branchIdKeydData.size != capacity || blockIdKeyData.size != capacity || executionCountData.size != capacity)) {
            java.lang.String.format(
                "Input arrays must have the same length. lineNumberKeyData: %d, branchIdKeydData: %d,"
                        + " blockIdKeyData: %d, executionCountData: %d",
                lineNumberKeyData.size,
                branchIdKeydData.size,
                blockIdKeyData.size,
                executionCountData.size
            )
        }
        this.lineNumberKeyData = lineNumberKeyData
        this.branchIdKeyData = branchIdKeydData
        this.blockIdKeyData = blockIdKeyData
        this.executionCountData = executionCountData
        this.evaluatedData = evaluatedData
        this.capacity = capacity
        this.size = size
    }

    /** Adds all data in the given branch coverage to this one.  */
    fun add(other: BranchCoverage) {
        for (i in 0..<other.capacity) {
            if (!other.indexIsFilled(i)) {
                continue
            }
            addBranch(
                other.lineNumberKeyData[i],
                other.blockIdKeyData[i],
                other.branchIdKeyData[i],
                other.evaluatedData.get(other.lineNumberKeyData[i]),
                other.executionCountData[i]
            )
        }
    }

    /**
     * Adds a branch to this collection.
     * 
     * 
     * If the branch already exists, the execution count and evaluated status are combined.
     * 
     * @param lineNumber The line number of the branch.
     * @param blockId The block ID of the branch.
     * @param branchId The branch ID of the branch.
     * @param evaluated Whether the branch was evaluated.
     * @param executionCount The number of times the branch was executed.
     */
    fun addBranch(
        lineNumber: Int, blockId: String, branchId: String, evaluated: Boolean, executionCount: Long
    ) {
        ensureCapacity()
        if (evaluated) {
            evaluatedData.set(lineNumber)
        }
        val index = findIndex(lineNumber, blockId, branchId)
        if (!indexIsFilled(index)) {
            lineNumberKeyData[index] = lineNumber
            blockIdKeyData[index] = blockId
            branchIdKeyData[index] = branchId
            executionCountData[index] = executionCount
            size++
        } else {
            executionCountData[index] += executionCount
        }
    }

    /**
     * Adds a branch to this collection.
     * 
     * 
     * If the branch already exists, the execution count and evaluated status are combined.
     * 
     * @param branch The branch to add.
     */
    fun addBranch(branch: BranchCoverageItem) {
        addBranch(
            branch.lineNumber(),
            branch.blockNumber(),
            branch.branchNumber(),
            branch.evaluated(),
            branch.nrOfExecutions()
        )
    }

    fun size(): Int {
        return size
    }

    fun getKeys(): com.google.common.collect.ImmutableList<BranchCoverageKey?> {
        val builder: com.google.common.collect.ImmutableList.Builder<BranchCoverageKey?> =
            com.google.common.collect.ImmutableList.builder<BranchCoverageKey?>()
        for (i in lineNumberKeyData.indices) {
            if (indexIsFilled(i)) {
                builder.add(
                    BranchCoverageKey.Companion.create(lineNumberKeyData[i], blockIdKeyData[i], branchIdKeyData[i])
                )
            }
        }
        return builder.build()
    }

    fun get(key: BranchCoverageKey): BranchCoverageItem? {
        return get(key.lineNumber(), key.blockNumber(), key.branchNumber())
    }

    /**
     * Returns the branch coverage data for the given line number, block ID, and branch ID.
     * 
     * 
     * Returns null if no data has been added for the given key.
     * 
     * @param lineNumber The line number of the branch.
     * @param blockId The block ID of the branch.
     * @param branchId The branch ID of the branch.
     * @return The branch coverage data for the given line number, block ID, and branch ID.
     */
    fun get(lineNumber: Int, blockId: String, branchId: String): BranchCoverageItem? {
        val index = findIndex(lineNumber, blockId, branchId)
        if (!indexIsFilled(index)) {
            return null
        }
        return BranchCoverageItem.Companion.create(
            lineNumberKeyData[index],
            blockIdKeyData[index],
            branchIdKeyData[index],
            evaluatedData.get(lineNumber),
            executionCountData[index]
        )
    }

    fun containsKey(key: BranchCoverageKey): Boolean {
        return containsKey(key.lineNumber(), key.blockNumber(), key.branchNumber())
    }

    fun containsKey(lineNumber: Int, blockId: String, branchId: String): Boolean {
        val index = findIndex(lineNumber, blockId, branchId)
        return indexIsFilled(index)
    }

    fun executedBranchesCount(): Int {
        var count = 0
        for (i in executionCountData.indices) {
            if (executionCountData[i] > 0) {
                count++
            }
        }
        return count
    }

    /**
     * Find the index into the arrays that correspond to the given key data. If the key is not
     * currently in the map, this will point to the slot that should be used for it. The index is
     * found simply via linear probing.
     * 
     * @param lineNumber The line number of the branch.
     * @param blockId The block ID of the branch.
     * @param branchId The branch ID of the branch.
     * @return The index into the arrays that correspond to the given key data.
     */
    private fun findIndex(lineNumber: Int, blockId: String, branchId: String): Int {
        requireNotNull(branchId) { "Branch ID must not be null" }
        var hashValue = hash(lineNumber, blockId, branchId)
        hashValue = hashValue and java.lang.Integer.MAX_VALUE
        var index = hashValue % capacity
        val originalIndex = index
        while (true) {
            if (!indexIsFilled(index)) {
                return index
            }
            if (doesIndexContain(index, lineNumber, blockId, branchId)) {
                return index
            }
            index = (index + 1) % capacity
            check(index != originalIndex) { "Branch data store is overloaded." }
        }
    }

    private fun doesIndexContain(index: Int, lineNumber: Int, blockId: String, branchId: String): Boolean {
        return lineNumber == lineNumberKeyData[index] && blockId == blockIdKeyData[index]
                && branchId == branchIdKeyData[index]
    }

    private fun ensureCapacity() {
        // Maximum load should be ~75%
        if (size < 3 * (capacity / 4)) {
            return
        }

        // 0x40000000 is the smallest int that overflows when doubled.
        check(capacity < 0x40000000) { "Branch data store cannot be expanded further." }
        val newCapacity = capacity * 2
        val oldLineNumberKeyData = lineNumberKeyData
        val oldBranchIdKeyData = branchIdKeyData
        val oldBlockIdKeyData = blockIdKeyData
        val oldExecutionCountData = executionCountData

        lineNumberKeyData = IntArray(newCapacity)
        branchIdKeyData = arrayOfNulls<String>(newCapacity)
        blockIdKeyData = arrayOfNulls<String>(newCapacity)
        executionCountData = LongArray(newCapacity)
        capacity = newCapacity
        for (i in oldLineNumberKeyData.indices) {
            if (oldBranchIdKeyData[i] != null) {
                val lineNumber = oldLineNumberKeyData[i]
                val blockId = oldBlockIdKeyData[i]
                val branchId = oldBranchIdKeyData[i]
                val index = findIndex(lineNumber, blockId, branchId)
                lineNumberKeyData[index] = lineNumber
                blockIdKeyData[index] = blockId
                branchIdKeyData[index] = branchId
                executionCountData[index] = oldExecutionCountData[i]
            }
        }
    }

    private fun hash(lineNumber: Int, blockId: String?, branchId: String?): Int {
        // blockId is almost always "0" and branchId is typically a small single digit number
        // clustering seems to be a little less bad putting blockId at the end instead of branchId
        return java.util.Objects.hash(lineNumber, branchId, blockId)
    }

    private fun indexIsFilled(index: Int): Boolean {
        return branchIdKeyData[index] != null
    }

    override fun iterator(): MutableIterator<MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?>?> {
        return BranchCoverageIterator()
    }

    private inner class BranchCoverageIterator

        : MutableIterator<MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?>?> {
        var idx: Int

        init {
            idx = -1
            advanceToNextPopulatedSlot()
        }

        fun advanceToNextPopulatedSlot() {
            do {
                idx++
            } while (idx < capacity && !indexIsFilled(idx))
        }

        override fun next(): MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?> {
            if (!hasNext()) {
                throw java.util.NoSuchElementException()
            }
            val result: MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?> =
                SimpleImmutableEntry<BranchCoverageKey?, BranchCoverageItem?>(
                    BranchCoverageKey.Companion.create(
                        lineNumberKeyData[idx], blockIdKeyData[idx], branchIdKeyData[idx]
                    ),
                    BranchCoverageItem.Companion.create(
                        lineNumberKeyData[idx],
                        blockIdKeyData[idx],
                        branchIdKeyData[idx],
                        evaluatedData.get(lineNumberKeyData[idx]),
                        executionCountData[idx]
                    )
                )
            advanceToNextPopulatedSlot()
            return result
        }

        override fun hasNext(): Boolean {
            return idx < capacity
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 32

        fun copy(other: BranchCoverage): BranchCoverage {
            return BranchCoverage(
                java.util.Arrays.copyOf(other.lineNumberKeyData, other.lineNumberKeyData.size),
                java.util.Arrays.copyOf<String?>(other.branchIdKeyData, other.branchIdKeyData.size),
                java.util.Arrays.copyOf<String?>(other.blockIdKeyData, other.blockIdKeyData.size),
                java.util.Arrays.copyOf(other.executionCountData, other.executionCountData.size),
                other.evaluatedData.clone() as BitSet?,
                other.size
            )
        }

        fun create(): BranchCoverage {
            return BranchCoverage(
                IntArray(INITIAL_CAPACITY),
                arrayOfNulls<String>(INITIAL_CAPACITY),
                arrayOfNulls<String>(INITIAL_CAPACITY),
                LongArray(INITIAL_CAPACITY),
                BitSet(INITIAL_CAPACITY),  /* size= */
                0
            )
        }

        /** Combines the data in the given branch coverage instances into a new one.  */
        fun merge(other1: BranchCoverage, other2: BranchCoverage): BranchCoverage {
            val merged = copy(other1)
            merged.add(other2)
            return merged
        }
    }
}
