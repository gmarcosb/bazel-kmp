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
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.BitSet

/** Stores line coverage data.  */
internal class LineCoverage private constructor(private var lineExecutions: LongArray, instrumentedLines: BitSet) :
    Iterable<MutableMap.MutableEntry<Int?, Long?>?> {
    private val instrumentedLines: BitSet

    init {
        this.instrumentedLines = instrumentedLines
    }

    /** Adds the line data from the given LineCoverage to this one.  */
    fun add(other: LineCoverage) {
        ensureLineCapacity(other.lineExecutions.size - 1)
        instrumentedLines.or(other.instrumentedLines)
        for (i in other.lineExecutions.indices) {
            lineExecutions[i] += other.lineExecutions[i]
        }
    }

    /**
     * Adds data for a single line. If the line already exists, the execution count is added to the
     * existing count.
     * 
     * @param lineNumber the line number to add
     * @param executionCount the number of times the line was executed
     * @throws IllegalArgumentException if the line number is negative
     */
    fun addLine(lineNumber: Int, executionCount: Long) {
        require(lineNumber >= 0) { "Line number must be non-negative." }
        ensureLineCapacity(lineNumber)
        lineExecutions[lineNumber] += executionCount
        instrumentedLines.set(lineNumber)
    }

    fun numberOfInstrumentedLines(): Int {
        return instrumentedLines.cardinality()
    }

    fun numberOfExecutedLines(): Int {
        var count = 0
        for (i in lineExecutions.indices) {
            if (lineExecutions[i] > 0) {
                count++
            }
        }
        return count
    }

    private fun ensureLineCapacity(lineNumber: Int) {
        val n = lineNumber + 1
        if (n > lineExecutions.size) {
            val growthSize = (lineExecutions.size * 3) / 2 + 1
            val newSize: Int = java.lang.Math.max(n, growthSize)
            lineExecutions = java.util.Arrays.copyOf(lineExecutions, newSize)
        }
    }

    override fun iterator(): MutableIterator<MutableMap.MutableEntry<Int?, Long?>?> {
        return LineCoverageIterator()
    }

    private inner class LineCoverageIterator : MutableIterator<MutableMap.MutableEntry<Int?, Long?>?> {
        var idx: Int = -1

        init {
            advanceToNextInstrumentedLine()
        }

        fun advanceToNextInstrumentedLine() {
            do {
                idx++
            } while (idx < lineExecutions.size && !instrumentedLines.get(idx))
        }

        override fun next(): MutableMap.MutableEntry<Int?, Long?> {
            if (!hasNext()) {
                throw java.util.NoSuchElementException()
            }
            val result: MutableMap.MutableEntry<Int?, Long?> =
                SimpleImmutableEntry<Int?, Long?>(idx, lineExecutions[idx])
            advanceToNextInstrumentedLine()
            return result
        }

        override fun hasNext(): Boolean {
            return idx < lineExecutions.size
        }
    }

    companion object {
        /** Creates a new LineCoverage instance.  */
        fun create(): LineCoverage {
            return LineCoverage(LongArray(32), BitSet(32))
        }

        /** Creates a copy of the given LineCoverage.  */
        fun copy(other: LineCoverage): LineCoverage {
            val lineExecutions: LongArray = java.util.Arrays.copyOf(other.lineExecutions, other.lineExecutions.size)
            val instrumentedLines: BitSet = BitSet(other.instrumentedLines.length())
            instrumentedLines.or(other.instrumentedLines)
            return LineCoverage(lineExecutions, instrumentedLines)
        }
    }
}
