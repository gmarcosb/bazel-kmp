// Copyright 2020 The Bazel Authors. All rights reserved.
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
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * A [Parser] for gcov intermediate json format introduced in GCC 9.1. See the flag `--intermediate-format` in [gcov documentation](https://gcc.gnu.org/onlinedocs/gcc-9.3.0/gcc/Invoking-Gcov.html).
 */
class GcovJsonParser private constructor(inputStream: java.io.InputStream) {
    private val inputStream: java.io.InputStream

    init {
        this.inputStream = inputStream
    }

    @Throws(IOException::class)
    private fun parse(): MutableList<SourceFileCoverage?> {
        val allSourceFiles: java.util.ArrayList<SourceFileCoverage?> = java.util.ArrayList<SourceFileCoverage?>()
        GZIPInputStream(inputStream).use { gzipStream ->
            val contents: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while ((gzipStream.read(buffer).also { length = it }) != -1) {
                contents.write(buffer, 0, length)
            }
            val gson: Gson = Gson()
            val document: GcovJsonFormat =
                gson.fromJson<GcovJsonFormat>(contents.toString(), GcovJsonFormat::class.java)
            if (document.format_version != "1") {
                logger.log(
                    java.util.logging.Level.WARNING,
                    "Expect GCov JSON format version 1, got format version " + document.format_version
                )
            }
            for (file in document.files) {
                val currentFileCoverage: SourceFileCoverage = SourceFileCoverage(file.file)
                for (function in file.functions) {
                    currentFileCoverage.addFunctionLineNumber(function.name, function.start_line)
                    currentFileCoverage.addFunctionExecution(function.name, function.execution_count)
                }
                for (line in file.lines) {
                    currentFileCoverage.addLine(line.line_number, line.count)
                    var branchNumber = 0
                    val taken: Boolean = java.util.Arrays.stream<GcovJsonBranch?>(line.branches)
                        .anyMatch(java.util.function.Predicate { b: GcovJsonBranch? -> b!!.count > 0 })
                    for (branch in line.branches) {
                        currentFileCoverage.addBranch(
                            line.line_number, "0", java.lang.Integer.toString(branchNumber), taken, branch.count
                        )
                        branchNumber += 1
                    }
                }
                allSourceFiles.add(currentFileCoverage)
            }
        }
        return allSourceFiles
    }

    // Classes for the Gson data mapper representing the structure of the GCov JSON format
    // These do not follow the Java naming styleguide as they need to match the JSON field names
    // Documentation can be found in GCov's manpage, of which the source is available at
    // https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=gcc/doc/gcov.texi;h=dcdd7831ff063483d43e5347af0b67083c85ecc4;hb=4212a6a3e44f870412d9025eeb323fd4f50a61da#l184
    internal class GcovJsonFormat {
        var gcc_version: String? = null
        var files: Array<GcovJsonFile>
        var format_version: String? = null
        var current_working_directory: String? = null
        var data_file: String? = null
    }

    internal class GcovJsonFile {
        var file: String? = null
        var functions: Array<GcovJsonFunction>
        var lines: Array<GcovJsonLine>
    }

    internal class GcovJsonFunction {
        var blocks: Int = 0
        var end_column: Int = 0
        var start_line: Int = 0
        var name: String? = null
        var blocks_executed: Int = 0
        var execution_count: Long = 0
        var demangled_name: String? = null
        var start_column: Int = 0
        var end_line: Int = 0
    }

    internal class GcovJsonLine {
        var branches: Array<GcovJsonBranch>
        var count: Long = 0
        var line_number: Int = 0
        var unexecuted_block: Boolean = false
        var function_name: String? = null
    }

    internal class GcovJsonBranch {
        var fallthrough: Boolean = false
        var count: Long = 0

        @SerializedName("throw")
        var _throw: Boolean = false
    }

    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(GcovJsonParser::class.java.getName())

        @Throws(IOException::class)
        fun parse(inputStream: java.io.InputStream): MutableList<SourceFileCoverage?> {
            return GcovJsonParser(inputStream).parse()
        }
    }
}
