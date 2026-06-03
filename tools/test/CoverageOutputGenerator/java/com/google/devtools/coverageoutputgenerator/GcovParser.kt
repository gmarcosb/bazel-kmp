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
 * A [Parser] for gcov intermediate format. See the flag `--intermediate-format` in [gcov documentation](https://gcc.gnu.org/onlinedocs/gcc/Invoking-gcov.html).
 */
class GcovParser private constructor(inputStream: java.io.InputStream) {
    private var allSourceFiles: MutableList<SourceFileCoverage?>? = null
    private val inputStream: java.io.InputStream
    private var currentSourceFileCoverage: SourceFileCoverage? = null
    private var branchValues: com.google.common.collect.ListMultimap<Int?, String?>? = null

    init {
        this.inputStream = inputStream
    }

    @Throws(IOException::class)
    private fun parse(): MutableList<SourceFileCoverage?> {
        allSourceFiles = java.util.ArrayList<SourceFileCoverage?>()
        var malformedInput = false
        BufferedReader(
            java.io.InputStreamReader(
                inputStream,
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).use { bufferedReader ->
            var line: String?
            // TODO(bazel-team): This is susceptible to OOM if the input file is too large and doesn't
            // contain any newlines.
            while ((bufferedReader.readLine().also { line = it }) != null) {
                if (!parseLine(line!!)) {
                    malformedInput = true
                }
            }
            bufferedReader.close()
        }
        endSourceFile()
        if (malformedInput) {
            logger.log(
                java.util.logging.Level.WARNING,
                "gcov intermediate input was malformed, some lines might not have been parsed. "
                        + "Check the previous log entries for more information."
            )
        }
        return allSourceFiles
    }

    /**
     * Merges `currentSourceFileCoverage` into `allSourceFilesCoverageData` and resets
     * `currentSourceFileCoverage` to null.
     */
    private fun endSourceFile() {
        if (currentSourceFileCoverage == null) {
            return
        }
        recordBranchInformation(branchValues)
        allSourceFiles!!.add(currentSourceFileCoverage)
        currentSourceFileCoverage = null
    }

    private fun parseLine(line: String): Boolean {
        if (line.isEmpty()) {
            return true
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_FILE_MARKER)) {
            endSourceFile()
            return parseSource(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_FUNCTION_MARKER)) {
            return parseFunction(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_LINE_MARKER)) {
            return parseLCount(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_MARKER)) {
            return parseBranch(line)
        }
        if (line.startsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_VERSION_MARKER) || line.startsWith(
                com.google.devtools.coverageoutputgenerator.Constants.GCOV_CWD_MARKER
            )
        ) {
            // Ignore these fields for now as they are not necessary.
            return true
        }
        logger.log(
            java.util.logging.Level.WARNING,
            "Line <" + line + "> does not respect the gcov intermediate format and was ignored."
        )
        return false
    }

    private fun parseSource(line: String): Boolean {
        val sourcefile: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.GCOV_FILE_MARKER.length())
        if (sourcefile.isEmpty()) {
            logger.log(java.util.logging.Level.WARNING, "gcov info doesn't contain source file name on line: " + line)
            return false
        }
        currentSourceFileCoverage = SourceFileCoverage(sourcefile)
        branchValues = com.google.common.collect.MultimapBuilder.treeKeys().arrayListValues().build<Int?, String?>()
        return true
    }

    /**
     * Valid lines: function:start_line_number,end_line_number,execution_count,function_name
     * function:start_line_number,execution_count,function_name
     */
    private fun parseFunction(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.GCOV_FUNCTION_MARKER.length())
        val items: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (items.size != 4 && items.size != 3) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        try {
            // Ignore end_line_number since it's redundant information.
            val startLine: Int = java.lang.Integer.parseInt(items[0])
            val execCount: Long =
                if (items.size == 4) java.lang.Long.parseLong(items[2]) else java.lang.Long.parseLong(items[1])
            val functionName: String? = if (items.size == 4) items[3] else items[2]
            currentSourceFileCoverage.addFunctionLineNumber(functionName, startLine)
            currentSourceFileCoverage.addFunctionExecution(functionName, execCount)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        return true
    }

    /**
     * Valid lines: lcount:line number,execution_count,has_unexecuted_block lcount:line
     * number,execution_count
     */
    private fun parseLCount(line: String): Boolean {
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.GCOV_LINE_MARKER.length())
        val items: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (items.size != 3 && items.size != 2) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        try {
            // Ignore has_unexecuted_block since it's not used.
            val lineNr: Int = java.lang.Integer.parseInt(items[0])
            val execCount: Long = java.lang.Long.parseLong(items[1])
            currentSourceFileCoverage.addLine(lineNr, execCount)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        return true
    }

    /** Valid lines: branch:line number,taken string  */
    private fun parseBranch(line: String): Boolean {
        // We can't add this to the source file object because we need to construct branch numbers,
        // which can only be done once we have all the branches for a given line number.
        val lineContent: String =
            line.substring(com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_MARKER.length())
        val items: Array<String> =
            lineContent.split(com.google.devtools.coverageoutputgenerator.Constants.DELIMITER, -1)
        if (items.size != 2) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        // Ignore has_unexecuted_block since it's not used.
        try {
            val lineNumber: Int = java.lang.Integer.parseInt(items[0])
            val type = items[1]
            if (!(type == com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_NOTEXEC
                        || type == com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_NOTTAKEN
                        || type == com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_TAKEN)
            ) {
                logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
                return false
            }
            branchValues.put(lineNumber, type)
        } catch (e: java.lang.NumberFormatException) {
            logger.log(java.util.logging.Level.WARNING, "gcov info contains invalid line " + line)
            return false
        }
        return true
    }

    private fun recordBranchInformation(branchMap: com.google.common.collect.ListMultimap<Int?, String?>) {
        for (lineEntry in branchMap.asMap().entrySet()) {
            var branchNumber = 0
            val branches: MutableCollection<String> = lineEntry.getValue()
            for (value in branches) {
                var execCount = 0
                var evaluated = false
                when (value) {
                    com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_NOTEXEC -> {}
                    com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_NOTTAKEN -> evaluated = true
                    com.google.devtools.coverageoutputgenerator.Constants.GCOV_BRANCH_TAKEN -> {
                        evaluated = true
                        execCount =
                            1 // we don't have the number of executions recorded, so simply say "1" if the
                    }

                    else -> throw java.lang.AssertionError("Invalid branch value '" + value + "'")
                }
                currentSourceFileCoverage.addBranch(
                    lineEntry.getKey(), "0", java.lang.Integer.toString(branchNumber), evaluated, execCount.toLong()
                )
                branchNumber++
            }
        }
    }

    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(GcovParser::class.java.getName())

        @Throws(IOException::class)
        fun parse(inputStream: java.io.InputStream): MutableList<SourceFileCoverage?> {
            return GcovParser(inputStream).parse()
        }
    }
}
