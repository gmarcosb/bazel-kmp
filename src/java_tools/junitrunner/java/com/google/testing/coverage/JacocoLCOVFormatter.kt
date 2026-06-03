// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.testing.coverage.BranchCoverageDetail
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.syntax.Identifier.getName
import org.jacoco.core.analysis.IBundleCoverage
import org.jacoco.core.analysis.IClassCoverage
import org.jacoco.core.analysis.IMethodCoverage
import org.jacoco.core.analysis.ISourceFileCoverage
import org.jacoco.report.IReportGroupVisitor
import org.jacoco.report.IReportVisitor
import org.jacoco.report.ISourceFileLocator
import java.io.IOException
import java.io.PrintWriter
import java.util.TreeMap


/**
 * Simple lcov formatter to be used with lcov_merger.par.
 * 
 * 
 * The lcov format is documented here: http://ltp.sourceforge.net/coverage/lcov/geninfo.1.php
 */
class JacocoLCOVFormatter {
    // Exec paths of the uninstrumented files that are being analyzed. This is helpful for files in
    // jars passed through java_import or some custom rule where blaze doesn't have enough context to
    // compute the right paths, but relies on these pre-computed exec paths.
    // Exec paths can be provided in two formats, either as a plain string or as a delimited
    // string mapping source file paths to class paths. Coverage entries whose class-paths are not the
    // suffix of any file in this list are discarded.  If not provided (as is
    // the case when class is initialized with the zero-argument constructor), the entries are
    // returned unchanged (but note this may result in LCOV output which do not reference actual
    // file-paths).
    private val execPathsOfUninstrumentedFiles: java.util.Optional<com.google.common.collect.ImmutableSet<String>?>

    constructor(execPathsOfUninstrumentedFiles: com.google.common.collect.ImmutableSet<String?>) {
        this.execPathsOfUninstrumentedFiles =
            java.util.Optional.of<com.google.common.collect.ImmutableSet<String?>?>(execPathsOfUninstrumentedFiles)
    }

    constructor() {
        this.execPathsOfUninstrumentedFiles =
            java.util.Optional.empty<com.google.common.collect.ImmutableSet<String?>?>()
    }

    fun createVisitor(
        output: PrintWriter, branchCoverageDetail: MutableMap<String?, BranchCoverageDetail?>
    ): IReportVisitor {
        return object : IReportVisitor() {
            private val sourceToClassCoverage: MutableMap<String?, MutableMap<String?, IClassCoverage>?> =
                TreeMap<String?, MutableMap<String?, IClassCoverage>?>()
            private val sourceToFileCoverage: MutableMap<String?, ISourceFileCoverage?> =
                TreeMap<String?, ISourceFileCoverage?>()

            fun getExecPathForEntryName(classPath: String): String? {
                if (!execPathsOfUninstrumentedFiles.isPresent()) {
                    return classPath
                }

                val matchingFileName = if (classPath.startsWith("/")) classPath else "/" + classPath
                for (execPath in execPathsOfUninstrumentedFiles.get()) {
                    if (execPath.contains(EXEC_PATH_DELIMITER)) {
                        val parts: Array<String?> =
                            execPath.split(EXEC_PATH_DELIMITER.toRegex(), limit = 2).toTypedArray()
                        if (parts.size != 2) {
                            continue
                        }
                        if (parts[1] == matchingFileName) {
                            return parts[0]
                        }
                    } else if (execPath.endsWith(matchingFileName) || execPath == classPath) {
                        return execPath
                    }
                }
                return null
            }

            @Throws(IOException::class)
            override fun visitInfo(
                sessionInfos: MutableList<org.jacoco.core.data.SessionInfo?>?,
                executionData: MutableCollection<org.jacoco.core.data.ExecutionData?>?
            ) {
            }

            @Throws(IOException::class)
            override fun visitEnd() {
                for (sourceFile in sourceToClassCoverage.keys) {
                    processSourceFile(output, sourceFile)
                }
            }

            @Throws(IOException::class)
            override fun visitBundle(bundle: IBundleCoverage, locator: ISourceFileLocator?) {
                // Jacoco's API is geared towards HTML/XML reports which have a hierarchical nature. The
                // following loop would call the report generators for packages, classes, methods, and
                // finally link the source view (which would be generated by walking the actual source file
                // and annotating the coverage data). For lcov, we don't really need the source file, but
                // we need to output FN/FNDA pairs with method coverage, which means we need to index this
                // information and process everything at the end.
                for (pkgCoverage in bundle.getPackages()) {
                    for (clsCoverage in pkgCoverage.getClasses()) {
                        val fileName =
                            getExecPathForEntryName(
                                clsCoverage.getPackageName() + "/" + clsCoverage.getSourceFileName()
                            )
                        if (fileName == null) {
                            continue
                        }
                        if (!sourceToClassCoverage.containsKey(fileName)) {
                            sourceToClassCoverage.put(fileName, TreeMap<String?, IClassCoverage?>())
                        }
                        sourceToClassCoverage.get(fileName)!!.put(clsCoverage.getName(), clsCoverage)
                    }
                    for (srcCoverage in pkgCoverage.getSourceFiles()) {
                        val sourceName =
                            getExecPathForEntryName(srcCoverage.getPackageName() + "/" + srcCoverage.getName())
                        if (sourceName != null) {
                            sourceToFileCoverage.put(sourceName, srcCoverage)
                        }
                    }
                }
            }

            @Throws(IOException::class)
            override fun visitGroup(name: String?): IReportGroupVisitor? {
                return null
            }

            fun processSourceFile(writer: PrintWriter, sourceFile: String?) {
                writer.printf("SF:%s\n", sourceFile)

                val srcCoverage: ISourceFileCoverage? = sourceToFileCoverage.get(sourceFile)
                if (srcCoverage != null) {
                    // List methods, including methods from nested classes, in FN/FNDA pairs
                    for (clsCoverage in sourceToClassCoverage.get(sourceFile)!!.values) {
                        for (mthCoverage in clsCoverage.getMethods()) {
                            val name = constructFunctionName(mthCoverage, clsCoverage.getName())
                            writer.printf("FN:%d,%s\n", mthCoverage.getFirstLine(), name)
                            writer.printf("FNDA:%d,%s\n", mthCoverage.getMethodCounter().getCoveredCount(), name)
                        }
                    }

                    // List branches
                    for (clsCoverage in sourceToClassCoverage.get(sourceFile)!!.values) {
                        val detail: BranchCoverageDetail? = branchCoverageDetail.get(clsCoverage.getName())
                        if (detail != null) {
                            for (line in detail.linesWithBranches()) {
                                val numBranches: Int = detail.getBranches(line)
                                val executed: Boolean = detail.getExecutedBit(line)
                                if (executed) {
                                    for (branchIdx in 0..<numBranches) {
                                        // We haven't got execution counts for branches; just record if they were hit or
                                        // not.
                                        if (detail.getTakenBit(line, branchIdx)) {
                                            writer.printf(
                                                "BRDA:%d,%d,%d,%d\n",
                                                line,
                                                0,
                                                branchIdx,
                                                1
                                            ) // executed, taken
                                        } else {
                                            writer.printf(
                                                "BRDA:%d,%d,%d,%d\n", line, 0, branchIdx, 0
                                            ) // executed, not taken
                                        }
                                    }
                                } else {
                                    for (branchIdx in 0..<numBranches) {
                                        writer.printf("BRDA:%d,%d,%d,%s\n", line, 0, branchIdx, "-") // not executed
                                    }
                                }
                            }
                        }
                    }

                    // List of DA entries matching source lines
                    val firstLine: Int = srcCoverage.getFirstLine()
                    val lastLine: Int = srcCoverage.getLastLine()
                    for (line in firstLine..lastLine) {
                        val instructionCounter: org.jacoco.core.analysis.ICounter =
                            srcCoverage.getLine(line).getInstructionCounter()
                        if (instructionCounter.getTotalCount() != 0) {
                            // All we can do is say if a line was hit, we do not have execution counts.
                            val execCount = if (instructionCounter.getCoveredCount() > 0) 1 else 0
                            writer.printf("DA:%d,%d\n", line, execCount)
                        }
                    }
                }
                writer.println("end_of_record")
            }

            fun constructFunctionName(mthCoverage: IMethodCoverage, clsName: String?): String {
                // The lcov spec doesn't of course cover Java formats, so we output the method signature.
                // lcov_merger doesn't seem to care about these entries.
                return clsName + "::" + mthCoverage.getName() + " " + mthCoverage.getDesc()
            }
        }
    }

    companion object {
        private const val EXEC_PATH_DELIMITER = "///"
    }
}
