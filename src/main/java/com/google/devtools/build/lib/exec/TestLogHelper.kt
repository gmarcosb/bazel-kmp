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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.exec.ExecutionOptions.TestOutputFormat
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.PrintStream

/**
 * A helper class for test log handling. It determines whether the test log should be output and
 * formats the test log for console display.
 */
object TestLogHelper {
    @com.google.common.annotations.VisibleForTesting
    const val HEADER_DELIMITER: String = "-----------------------------------------------------------------------------"

    /**
     * Determines whether the test log should be output from the current outputMode and whether the
     * test has passed or not.
     */
    fun shouldOutputTestLog(outputMode: TestOutputFormat?, hasPassed: Boolean): Boolean {
        return (outputMode == TestOutputFormat.ALL)
                || (!hasPassed && (outputMode == TestOutputFormat.ERRORS))
    }

    /**
     * Streams the contents of testOutput file to the provided output, adding a new header and footer.
     * The internal test header is elided. The test output is not emitted if its size is greater than
     * the provided threshold.
     * 
     * @param maxTestOutputBytes Maximum test log size, including header, to output. Negative implies
     * no limit.
     */
    @Throws(IOException::class)
    fun writeTestLog(
        testOutput: com.google.devtools.build.lib.vfs.Path,
        testName: String?,
        out: java.io.OutputStream?,
        maxTestOutputBytes: Int
    ) {
        val printOut: PrintStream = PrintStream(BufferedOutputStream(out))
        try {
            printOut.print("==================== Test output for " + testName + ":\n")
            printOut.flush()

            if (maxTestOutputBytes < 0) {
                // No limit, print it all.
                streamTestLog(testOutput, printOut)
            } else {
                val testOutputBytes: Long = testOutput.getFileSize()
                if (testOutputBytes <= maxTestOutputBytes) {
                    streamTestLog(testOutput, printOut)
                } else {
                    printOut.printf(
                        "Test log too large (%s > %s), skipping...\n", testOutputBytes, maxTestOutputBytes
                    )
                }
            }

            printOut.print(
                "================================================================================\n"
            )
        } finally {
            printOut.flush()
        }
    }

    /**
     * Returns an output stream that doesn't write to original until it sees HEADER_DELIMITER by
     * itself on a line.
     */
    fun getHeaderFilteringOutputStream(original: java.io.OutputStream?): FilterTestHeaderOutputStream {
        return FilterTestHeaderOutputStream(original)
    }

    @Throws(IOException::class)
    private fun streamTestLog(fromPath: com.google.devtools.build.lib.vfs.Path, out: PrintStream) {
        val filteringOutputStream = getHeaderFilteringOutputStream(out)
        fromPath.getInputStream().use { input ->
            com.google.common.io.ByteStreams.copy(input, filteringOutputStream)
        }
        if (!filteringOutputStream.foundHeader()) {
            fromPath.getInputStream().use { inputAgain ->
                com.google.common.io.ByteStreams.copy(inputAgain, out)
            }
        }
    }

    /** Use this class to filter the streaming output of a test until we see the header delimiter.  */
    class FilterTestHeaderOutputStream(out: java.io.OutputStream?) : FilterOutputStream(out) {
        private var seenDelimiter = false
        private var lineBuilder: java.lang.StringBuilder = java.lang.StringBuilder()

        @Throws(IOException::class)
        override fun write(b: Int) {
            if (seenDelimiter) {
                out.write(b)
            } else if (b == NEWLINE) {
                val line = lineBuilder.toString()
                lineBuilder = java.lang.StringBuilder()
                if (line == HEADER_DELIMITER) {
                    seenDelimiter = true
                }
            } else if (lineBuilder.length() <= HEADER_DELIMITER.length()) {
                lineBuilder.append(b.toChar())
            }
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            if (seenDelimiter) {
                out.write(b, off, len)
            } else {
                super.write(b, off, len)
            }
        }

        fun foundHeader(): Boolean {
            return seenDelimiter
        }

        companion object {
            private val NEWLINE: Int = '\n'.code
        }
    }
}
