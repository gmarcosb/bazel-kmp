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

import com.google.devtools.coverageoutputgenerator.Coverage
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter

/**
 * Prints coverage data stored in a collection of [SourceFileCoverage] in a [lcov tracefile format](http://ltp.sourceforge.net/coverage/lcov/geninfo.1.php)
 */
internal class LcovPrinter private constructor(bufferedWriter: BufferedWriter, outputLegacyBranches: Boolean) {
    private val bufferedWriter: BufferedWriter

    private val outputLegacyBranches: Boolean

    init {
        this.bufferedWriter = bufferedWriter
        this.outputLegacyBranches = outputLegacyBranches
    }

    @Throws(IOException::class)
    private fun print(coverage: Coverage) {
        for (sourceFile in coverage.getAllSourceFiles()) {
            print(sourceFile)
        }
    }

    /**
     * Prints the given source data in an lcov tracefile format.
     * 
     * 
     * Assumes the file is opened and closed outside of this method.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun print(sourceFile: SourceFileCoverage) {
        printSFLine(sourceFile)
        printFNLines(sourceFile)
        printFNDALines(sourceFile)
        printFNFLine(sourceFile)
        printFNHLine(sourceFile)
        if (outputLegacyBranches) {
            printBALines(sourceFile)
        } else {
            printBRDALines(sourceFile)
        }
        printBRFLine(sourceFile)
        printBRHLine(sourceFile)
        printDALines(sourceFile)
        printLHLine(sourceFile)
        printLFLine(sourceFile)
        printEndOfRecordLine()
    }

    // SF:<absolute path to the source file>
    @Throws(IOException::class)
    private fun printSFLine(sourceFile: SourceFileCoverage) {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.SF_MARKER)
        bufferedWriter.write(sourceFile.sourceFileName())
        bufferedWriter.newLine()
    }

    // FN:<line number of function start>,<function name>
    @Throws(IOException::class)
    private fun printFNLines(sourceFile: SourceFileCoverage) {
        for (entry in sourceFile.getAllFunctionLineNumbers()) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.FN_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(entry.getValue())) // line number of function start
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            bufferedWriter.write(entry.getKey()) // function name
            bufferedWriter.newLine()
        }
    }

    // FNDA:<execution count>,<function name>
    @Throws(IOException::class)
    private fun printFNDALines(sourceFile: SourceFileCoverage) {
        for (entry in sourceFile.getAllExecutionCount()) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.FNDA_MARKER)
            bufferedWriter.write(java.lang.Long.toString(entry.getValue())) // execution count
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            bufferedWriter.write(entry.getKey()) // function name
            bufferedWriter.newLine()
        }
    }

    // FNF:<number of functions found>
    @Throws(IOException::class)
    private fun printFNFLine(sourceFile: SourceFileCoverage) {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.FNF_MARKER)
        bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrFunctionsFound()))
        bufferedWriter.newLine()
    }

    // FNH:<number of functions hit>
    @Throws(IOException::class)
    private fun printFNHLine(sourceFile: SourceFileCoverage) {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.FNH_MARKER)
        bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrFunctionsHit()))
        bufferedWriter.newLine()
    }

    // BRDA:<line number>,<block number>,<branch number>,<taken>
    @Throws(IOException::class)
    private fun printBRDALines(sourceFile: SourceFileCoverage) {
        for (branch in sourceFile.getAllBranches()) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.BRDA_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(branch.lineNumber()))
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            bufferedWriter.write(branch.blockNumber())
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            bufferedWriter.write(branch.branchNumber())
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            if (branch.evaluated()) {
                bufferedWriter.write(java.lang.Long.toString(branch.nrOfExecutions()))
            } else {
                bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.NEVER_EVALUATED)
            }
            bufferedWriter.newLine()
        }
    }

    // BA:<line number>,<taken>
    @Throws(IOException::class)
    private fun printBALines(sourceFile: SourceFileCoverage) {
        for (branch in sourceFile.getAllBranches()) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.BA_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(branch.lineNumber()))
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            if (branch.evaluated()) {
                val value = if (branch.nrOfExecutions() > 0) "2" else "1"
                bufferedWriter.write(value)
            } else {
                bufferedWriter.write("0")
            }
            bufferedWriter.newLine()
        }
    }

    // BRF:<number of branches found>
    @Throws(IOException::class)
    private fun printBRFLine(sourceFile: SourceFileCoverage) {
        if (sourceFile.nrBranchesFound() > 0) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.BRF_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrBranchesFound()))
            bufferedWriter.newLine()
        }
    }

    // BRH:<number of branches hit>
    @Throws(IOException::class)
    private fun printBRHLine(sourceFile: SourceFileCoverage) {
        // Only print if there were any branches found.
        if (sourceFile.nrBranchesFound() > 0) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.BRH_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrBranchesHit()))
            bufferedWriter.newLine()
        }
    }

    // DA:<line number>,<execution count>[,<checksum>]
    @Throws(IOException::class)
    private fun printDALines(sourceFile: SourceFileCoverage) {
        for (entry in sourceFile.getAllLines()) {
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DA_MARKER)
            bufferedWriter.write(java.lang.Integer.toString(entry.getKey()))
            bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER)
            bufferedWriter.write(java.lang.Long.toString(entry.getValue()))
            bufferedWriter.newLine()
        }
    }

    // LH:<number of lines with a non-zero execution count>
    @Throws(IOException::class)
    private fun printLHLine(sourceFile: SourceFileCoverage) {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.LH_MARKER)
        bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrOfLinesWithNonZeroExecution()))
        bufferedWriter.newLine()
    }

    // LF:<number of instrumented lines>
    @Throws(IOException::class)
    private fun printLFLine(sourceFile: SourceFileCoverage) {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.LF_MARKER)
        bufferedWriter.write(java.lang.Integer.toString(sourceFile.nrOfInstrumentedLines()))
        bufferedWriter.newLine()
    }

    // end_of_record
    @Throws(IOException::class)
    private fun printEndOfRecordLine() {
        bufferedWriter.write(com.google.devtools.coverageoutputgenerator.Constants.END_OF_RECORD_MARKER)
        bufferedWriter.newLine()
    }

    companion object {
        @kotlin.jvm.JvmOverloads
        @Throws(IOException::class)
        fun print(outputStream: java.io.OutputStream, coverage: Coverage, outputLegacyBranches: Boolean = false) {
            // Emit consistent line endings across all platforms.
            OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8).use { fileWriter ->
                object : BufferedWriter(fileWriter) {
                    @Throws(IOException::class)
                    override fun newLine() {
                        write('\n'.code)
                    }
                }.use { bufferedWriter ->
                    val lcovPrinter = LcovPrinter(bufferedWriter, outputLegacyBranches)
                    lcovPrinter.print(coverage)
                }
            }
        }
    }
}
