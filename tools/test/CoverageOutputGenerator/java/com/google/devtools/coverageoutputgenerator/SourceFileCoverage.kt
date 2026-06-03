// Copyright 2018 The Bazel Authors. All rights reserved.
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
import com.google.devtools.coverageoutputgenerator.BranchCoverage
import com.google.devtools.coverageoutputgenerator.BranchCoverageItem
import com.google.devtools.coverageoutputgenerator.BranchCoverageKey
import com.google.devtools.coverageoutputgenerator.LineCoverage
import java.util.Collections
import java.util.SortedMap
import java.util.TreeMap
import java.util.function.BinaryOperator
import java.util.stream.Collectors

/** Stores coverage information for a specific source file.  */
internal class SourceFileCoverage {
    private var sourceFileName: String
    private val functionLineNumbers: SortedMap<String?, Int?> // function name to line numbers
    private val functionsExecution: SortedMap<String?, Long?> // function name to execution count
    private val lineCoverage: LineCoverage
    private val branchCoverage: BranchCoverage

    constructor(sourcefile: String) {
        this.sourceFileName = sourcefile
        this.functionsExecution = TreeMap<String?, Long?>()
        this.functionLineNumbers = TreeMap<String?, Int?>()
        this.lineCoverage = LineCoverage.Companion.create()
        this.branchCoverage = BranchCoverage.Companion.create()
    }

    constructor(other: SourceFileCoverage) {
        this.sourceFileName = other.sourceFileName

        this.functionsExecution = TreeMap<String?, Long?>()
        this.functionLineNumbers = TreeMap<String?, Int?>()

        this.functionLineNumbers.putAll(other.functionLineNumbers)
        this.functionsExecution.putAll(other.functionsExecution)
        this.lineCoverage = LineCoverage.Companion.copy(other.lineCoverage)
        this.branchCoverage = BranchCoverage.Companion.copy(other.branchCoverage)
    }

    fun changeSourcefileName(newSourcefileName: String) {
        this.sourceFileName = newSourcefileName
    }

    fun sourceFileName(): String {
        return sourceFileName
    }

    fun nrFunctionsFound(): Int {
        return functionsExecution.size()
    }

    fun nrFunctionsHit(): Int {
        return functionsExecution.entrySet().stream()
            .filter(java.util.function.Predicate { function: MutableMap.MutableEntry<String?, Long?>? -> function.getValue() > 0 })
            .count().toInt()
    }

    fun nrBranchesFound(): Int {
        return branchCoverage.size()
    }

    fun nrBranchesHit(): Int {
        return branchCoverage.executedBranchesCount()
    }

    fun nrOfLinesWithNonZeroExecution(): Int {
        return lineCoverage.numberOfExecutedLines()
    }

    fun nrOfInstrumentedLines(): Int {
        return lineCoverage.numberOfInstrumentedLines()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getFunctionLineNumbers(): SortedMap<String?, Int?> {
        return functionLineNumbers
    }

    fun getAllFunctionLineNumbers(): MutableSet<MutableMap.MutableEntry<String?, Int?>> {
        return functionLineNumbers.entrySet()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getFunctionsExecution(): SortedMap<String?, Long?> {
        return functionsExecution
    }

    fun getAllExecutionCount(): MutableSet<MutableMap.MutableEntry<String?, Long?>> {
        return functionsExecution.entrySet()
    }

    fun getAllBranches(): com.google.common.collect.ImmutableList<BranchCoverageItem?> {
        // this is not efficient, but should only ever be called when printing out the final lcov data
        val builder: com.google.common.collect.ImmutableList.Builder<BranchCoverageItem?> =
            com.google.common.collect.ImmutableList.builder<BranchCoverageItem?>()
        val sortedKeys: java.util.ArrayList<BranchCoverageKey> =
            java.util.ArrayList<BranchCoverageKey>(branchCoverage.getKeys())
        Collections.sort<BranchCoverageKey?>(sortedKeys)
        for (branch in sortedKeys) {
            builder.add(branchCoverage.get(branch))
        }
        return builder.build()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getLines(): MutableMap<Int?, Long?> {
        val result: TreeMap<Int?, Long?> = TreeMap<Int?, Long?>()
        for (entry in lineCoverage) {
            result.put(entry.getKey(), entry.getValue())
        }
        return result
    }

    fun getAllLines(): Iterable<MutableMap.MutableEntry<Int?, Long?>?> {
        return lineCoverage
    }

    fun addFunctionLineNumber(functionName: String?, lineNumber: Int?) {
        this.functionLineNumbers.put(functionName, lineNumber)
    }

    fun addAllFunctionLineNumbers(lineNumber: SortedMap<String?, Int?>?) {
        this.functionLineNumbers.putAll(lineNumber)
    }

    fun addFunctionExecution(functionName: String?, executionCount: Long) {
        val value: Long = functionsExecution.getOrDefault(functionName, 0L) + executionCount
        this.functionsExecution.put(functionName, value)
    }

    private fun addAllFunctionsExecution(functionsExecution: SortedMap<String?, Long?>) {
        for (entry in functionsExecution.entrySet()) {
            addFunctionExecution(entry.getKey(), entry.getValue())
        }
    }

    /**
     * Adds a new branch to the source file. If the branch already exists, the execution count and
     * evaluated status are combined with the existing one.
     * 
     * @param lineNumber The line number the branch is on
     * @param blockNumber ID for the block containing the branch
     * @param branchNumber ID for the specific branch at this line
     * @param evaluated Whether branches for this line were ever evaluated
     * @param executionCount How many times this particular branch was taken
     */
    fun addBranch(
        lineNumber: Int,
        blockNumber: String?,
        branchNumber: String?,
        evaluated: Boolean,
        executionCount: Long
    ) {
        branchCoverage.addBranch(lineNumber, blockNumber, branchNumber, evaluated, executionCount)
    }

    fun addLine(lineNumber: Int, executionCount: Long) {
        lineCoverage.addLine(lineNumber, executionCount)
    }

    companion object {
        /** Returns the merged functions found in the two given `SourceFileCoverage`s.  */
        @com.google.common.annotations.VisibleForTesting
        fun mergeFunctionLineNumbers(
            s1: SourceFileCoverage, s2: SourceFileCoverage
        ): SortedMap<String?, Int?> {
            val merged: SortedMap<String?, Int?> = TreeMap<String?, Int?>()
            merged.putAll(s1.functionLineNumbers)
            merged.putAll(s2.functionLineNumbers)
            return merged
        }

        /** Returns the merged execution count found in the two given `SourceFileCoverage`s.  */
        @com.google.common.annotations.VisibleForTesting
        fun mergeFunctionsExecution(
            s1: SourceFileCoverage, s2: SourceFileCoverage
        ): SortedMap<String?, Long?> {
            return java.util.stream.Stream.of<SortedMap<String?, Long?>?>(s1.functionsExecution, s2.functionsExecution)
                .map<MutableSet<MutableMap.MutableEntry<String?, Long?>?>?>(java.util.function.Function { obj: SortedMap<kotlin.String?, Long?>? -> obj.entrySet() })
                .flatMap<MutableMap.MutableEntry<String?, Long?>?>(java.util.function.Function { obj: MutableSet<MutableMap.MutableEntry<String?, Long?>?>? -> obj.stream() })
                .collect(
                    Collectors.toMap(
                        java.util.function.Function { java.util.Map.Entry.getKey() },
                        java.util.function.Function { java.util.Map.Entry.getValue() },
                        BinaryOperator { a: Long, b: Long -> java.lang.Long.sum(a, b) },
                        java.util.function.Supplier { TreeMap() })
                )
        }

        /**
         * Merges all the fields of `other` with the current [SourceFileCoverage] into a new
         * [SourceFileCoverage]
         * 
         * 
         * Assumes both the current and the given [SourceFileCoverage] have the same `sourceFileName`.
         * 
         * @return a new [SourceFileCoverage] that contains the merged coverage.
         */
        fun merge(source1: SourceFileCoverage, source2: SourceFileCoverage): SourceFileCoverage {
            assert(source1.sourceFileName == source2.sourceFileName)
            val merged = SourceFileCoverage(source1)

            merged.addAllFunctionLineNumbers(source2.functionLineNumbers)
            merged.addAllFunctionsExecution(source2.functionsExecution)
            merged.branchCoverage.add(source2.branchCoverage)
            merged.lineCoverage.add(source2.lineCoverage)
            return merged
        }
    }
}
