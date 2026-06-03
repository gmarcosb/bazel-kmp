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
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Helper class for creating and parsing lcov tracefiles and the necessary data structured used by
 * `LcovMerger`.
 */
object LcovMergerTestUtils {
    fun generateLcovContents(
        srcPrefix: String?, numSourceFiles: Int, numLinesPerSourceFile: Int
    ): MutableList<String?> {
        val lines: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        for (i in 0..<numSourceFiles) {
            lines.add(java.lang.String.format("SF:%s%s.cc", srcPrefix, i))
            lines.add("FNF:0")
            lines.add("FNH:0")
            run {
                var srcLineNum = 1
                while (srcLineNum <= numLinesPerSourceFile) {
                    lines.add(java.lang.String.format("BA:%s,2", srcLineNum))
                    srcLineNum += 4
                }
            }
            lines.add("BRF:" + numLinesPerSourceFile / 4)
            lines.add("BRH:" + numLinesPerSourceFile / 4)
            for (srcLineNum in 1..numLinesPerSourceFile) {
                lines.add(java.lang.String.format("DA:%s,%s", srcLineNum, srcLineNum % 2))
            }
            lines.add("LH:" + numLinesPerSourceFile / 2)
            lines.add("LF:" + numLinesPerSourceFile)
            lines.add("end_of_record")
        }
        return lines
    }

    @Throws(IOException::class)
    fun generateLcovFiles(
        srcPrefix: String?, numLcovFiles: Int, numSrcFiles: Int, numLinesPerSrcFile: Int, coverageDir: Path
    ): MutableList<Path?> {
        val lcovFile: Path = java.nio.file.Files.createFile(Paths.get(coverageDir.toString(), "coverage0.dat"))
        val lcovFiles: MutableList<Path?> = java.util.ArrayList<Path?>()
        java.nio.file.Files.write(lcovFile, generateLcovContents(srcPrefix, numSrcFiles, numLinesPerSrcFile))
        lcovFiles.add(lcovFile)
        for (i in 1..<numLcovFiles) {
            lcovFiles.add(
                java.nio.file.Files.createSymbolicLink(
                    Paths.get(coverageDir.toString(), java.lang.String.format("coverage%s.dat", i)), lcovFile
                )
            )
        }
        return lcovFiles
    }
}
