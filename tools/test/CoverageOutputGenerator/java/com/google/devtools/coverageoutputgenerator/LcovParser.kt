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

import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import java.io.BufferedReader
import java.io.IOException

/**
 * A parser for the lcov tracefile format used by geninfo. See [lcov documentation](http://ltp.sourceforge.net/coverage/lcov/geninfo.1.php)
 */
internal class LcovParser private constructor(inputStream: java.io.InputStream) {
    private val inputStream: java.io.InputStream
    private var currentSourceFileCoverage: SourceFileCoverage? = null
    private var baBranchesAtLine = 0
    private var lastBaLine = -1

    init {
        this.inputStream = inputStream
    }

    /**
     * Reads the tracefile line by line and creates a SourceFileCoverage object for each section of
     * the file between a SF:<source file></source> line and an end_of_record line.
     * 
     * @return a list of each source file path found in the tracefile
     */
    @Throws(IOException::class)
    private fun parse(): MutableList<SourceFileCoverage?> {
        val allSourceFiles: MutableList<SourceFileCoverage?> = java.util.ArrayList<SourceFileCoverage?>()
        BufferedReader(
            java.io.InputStreamReader(
                inputStream,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).use { bufferedReader ->
            var line: String?
            while ((bufferedReader.readLine().also { line = it }) != null) {
                parseLine(line!!, allSourceFiles)
            }
            bufferedReader.close()
        }
        return allSourceFiles
    }

    /**
     * Merges `currentSourceFileCoverage` into `allSourceFilesCoverageData` and resets
     * `currentSourceFileCoverage` to null.
     */
    private fun reset(allSourceFiles: MutableList<SourceFileCoverage?>) {
        allSourceFiles.add(currentSourceFileCoverage)
        currentSourceFileCoverage = null
    }

    /**
     * Reads the line and redirects the parsing to the corresponding `parseXLine` method. Every
     * `parseXLine` methods fills in data to `currentSourceFileCoverage` accordingly.
     */
    private fun parseLine(line: String, allSourceFiles: MutableList<SourceFileCoverage?>): Boolean {
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.SF_MARKER)) {
            return parseSFLine(line)
        }
        // currentSourceFileCoverage should be null only before calling an SF line, otherwise
        // the object should have been created in parseSFLine. If currentSourceFileCoverage is null
        // here it means the parser arrived in an invalid state.
        if (currentSourceFileCoverage == null) {
            return false
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.FN_MARKER)) {
            return parseFNLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.FNDA_MARKER)) {
            return parseFNDALine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.FNF_MARKER)) {
            return parseFNFLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.FNH_MARKER)) {
            return parseFNHLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.BRDA_MARKER)) {
            return parseBRDALine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.BA_MARKER)) {
            return parseBALine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.BRF_MARKER)) {
            return parseBRFLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.BRH_MARKER)) {
            return parseBRHLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.DA_MARKER)) {
            return parseDALine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.LH_MARKER)) {
            return parseLHLine(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.LF_MARKER)) {
            return parseLFLine(line)
        }
        if (line == com.google.devtools.coverageoutputgenerator.Constants.END_OF_RECORD_MARKER) {
            reset(allSourceFiles)
            return true
        }
        logger.log(java.util.logging.Level.WARNING, "Tracefile includes invalid line: " + line)
        return false
    }

    // SF:<path to source file name>
    private fun parseSFLine(line: String): Boolean {
        lastBaLine = -1
        if (currentSourceFileCoverage != null) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile doesn't have SF:<source file> line before" + line)
            return false
        }
        val sourcefile: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.SF_MARKER.length())
        if (sourcefile.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile doesn't contain source file name on line: " + line)
            return false
        }
        currentSourceFileCoverage = SourceFileCoverage(sourcefile)
        return true
    }

    // FN:<line number of function start>,[<line number of function end>,]<function name>
    private fun parseFNLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.FN_MARKER.length())
        val funcData: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (funcData.size < 2 || funcData.size > 3 || (funcData.size == 3 && funcData[2].isEmpty())
            || funcData[0].isEmpty()
            || funcData[1].isEmpty()
        ) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid FN line " + line)
            return false
        }
        try {
            val lineNrFunctionStart: Int = java.lang.Integer.parseInt(funcData[0])
            // Line number of function end is optional and not used.
            val functionName: String? = funcData[funcData.size - 1]
            currentSourceFileCoverage.addFunctionLineNumber(functionName, lineNrFunctionStart)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid line number on FN line " + line)
            return false
        }
        return true
    }

    // FNDA:<execution count>,<function name>
    private fun parseFNDALine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.FNDA_MARKER.length())
        val funcData: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (funcData.size != 2 || funcData[0].isEmpty() || funcData[1].isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid FNDA line " + line)
            return false
        }
        try {
            val executionCount: Long = java.lang.Long.parseLong(funcData[0])
            val functionName: String? = funcData[1]
            currentSourceFileCoverage.addFunctionExecution(functionName, executionCount)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid execution count on FN line " + line)
            return false
        }
        return true
    }

    // FNF:<number of functions found>
    private fun parseFNFLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.FNF_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid FNF line " + line)
            return false
        }
        try {
            val nrFunctionsFound: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrFunctionsFound() == nrFunctionsFound)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(
                java.util.logging.Level.WARNING, "Tracefile contains invalid number of functions on FNF line " + line
            )
            return false
        }
        return true
    }

    // FNH:<number of function hit>
    private fun parseFNHLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.FNH_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid FNH line " + line)
            return false
        }
        try {
            val nrFunctionsHit: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrFunctionsHit() == nrFunctionsHit)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(
                java.util.logging.Level.WARNING,
                "Tracefile contains invalid number of functions hit on FNH line " + line
            )
            return false
        }
        return true
    }

    // BA:<line number>,<taken>
    private fun parseBALine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.BA_MARKER.length())
        val lineData: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (lineData.size != 2) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BA line " + line)
            return false
        }
        for (data in lineData) {
            if (data.isEmpty()) {
                logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BA line " + line)
                return false
            }
        }
        try {
            val lineNumber: Int = java.lang.Integer.parseInt(lineData[0])
            val execValue: Int = java.lang.Integer.parseInt(lineData[1])
            var evaluated = false
            var execCount: Long = 0
            when (execValue) {
                0 -> {
                    // Branch was never evaluated.
                    evaluated = false
                    execCount = 0
                }

                1 -> {
                    // Branch was evaluated, but not taken.
                    evaluated = true
                    execCount = 0
                }

                2 -> {
                    // Branch was taken. We don't know how often, so simply record "1".
                    evaluated = true
                    execCount = 1
                }

                else -> {
                    logger.log(
                        java.util.logging.Level.WARNING,
                        "Tracefile contains invalid BA " + line + " - value not one of {0, 1, 2}"
                    )
                    return false
                }
            }
            if (lastBaLine == lineNumber) {
                baBranchesAtLine++
            } else {
                baBranchesAtLine = 0
                lastBaLine = lineNumber
            }
            currentSourceFileCoverage.addBranch(
                lineNumber, "0", java.lang.Integer.toString(baBranchesAtLine), evaluated, execCount
            )
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains an invalid number BA line " + line)
            return false
        }
        return true
    }

    // BRDA:<line number>,<block number>,<branch number>,<taken>
    private fun parseBRDALine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.BRDA_MARKER.length())
        val lineData: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (lineData.size != 4) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BRDA line " + line)
            return false
        }
        for (data in lineData) {
            if (data.isEmpty()) {
                logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BRDA line " + line)
                return false
            }
        }
        try {
            val lineNumber: Int = java.lang.Integer.parseInt(lineData[0])
            val blockNumber: String? = lineData[1]
            val branchNumber: String? = lineData[2]
            val taken = lineData[3]

            var executionCount: Long = 0
            var wasEvaluated = false
            if (taken != com.google.devtools.coverageoutputgenerator.Constants.NEVER_EVALUATED) {
                executionCount = java.lang.Long.parseLong(taken)
                wasEvaluated = true
            }
            currentSourceFileCoverage.addBranch(
                lineNumber, blockNumber, branchNumber, wasEvaluated, executionCount
            )
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains an invalid number BRDA line " + line)
            return false
        }
        return true
    }

    // BRF:<number of branches found>
    private fun parseBRFLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.BRF_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BRF line " + line)
            return false
        }
        try {
            val nrBranchesFound: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrBranchesFound() == nrBranchesFound)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(
                java.util.logging.Level.WARNING, "Tracefile contains invalid number of branches in BRDA line " + line
            )
            return false
        }
        return true
    }

    // BRH:<number of branches hit>
    private fun parseBRHLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.BRH_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid BRH line " + line)
            return false
        }
        try {
            val nrBranchesHit: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrBranchesHit() == nrBranchesHit)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(
                java.util.logging.Level.WARNING, "Tracefile contains invalid number of branches hit in BRH line " + line
            )
            return false
        }
        return true
    }

    // DA:<line number>,<execution count>,[,<checksum>]
    private fun parseDALine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.DA_MARKER.length())
        val lineData: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (lineData.size != 2 && lineData.size != 3) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid DA line " + line)
            return false
        }
        for (data in lineData) {
            if (data.isEmpty()) {
                logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid DA line " + line)
                return false
            }
        }
        try {
            val lineNumber: Int = java.lang.Integer.parseInt(lineData[0])
            val executionCount: Long = java.lang.Long.parseLong(lineData[1])
            // Ignore the optional checksum
            currentSourceFileCoverage.addLine(lineNumber, executionCount)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains an invalid number on DA line " + line)
            return false
        }
        return true
    }

    // LH:<nr of lines with non-zero exec count>
    private fun parseLHLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.LH_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid LHL line " + line)
            return false
        }
        try {
            val nrLines: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrOfLinesWithNonZeroExecution() == nrLines)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains an invalid number on LHL line " + line)
            return false
        }
        return true
    }

    // LF:<number of instrumented lines>
    private fun parseLFLine(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.LF_MARKER.length())
        if (lineContent.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains invalid LF line " + line)
            return false
        }
        try {
            val nrLines: Int = java.lang.Integer.parseInt(lineContent)
            assert(currentSourceFileCoverage.nrOfInstrumentedLines() == nrLines)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "Tracefile contains an invalid number on LF line " + line)
            return false
        }
        return true
    }

    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(LcovParser::class.java.getName())

        @Throws(IOException::class)
        fun parse(inputStream: java.io.InputStream): MutableList<SourceFileCoverage?> {
            return LcovParser(inputStream).parse()
        }
    }
}
