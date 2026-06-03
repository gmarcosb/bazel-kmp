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
import com.google.testing.coverage.BranchExp
import com.google.testing.coverage.ClassProbesMapper
import com.google.testing.coverage.CovExp
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.model.TestNode.result
import org.jacoco.core.analysis.IClassCoverage
import org.jacoco.core.analysis.ICoverageVisitor
import java.io.IOException
import java.util.TreeMap

/**
 * Analyzer that process the branch coverage detail information.
 * 
 * 
 * Reuse the Analyzer class from Jacoco to avoid duplicating the content detection logic.
 * Override the main `Analyzer.analyzeClass` method which does the main work.
 */
class BranchDetailAnalyzer(executionData: org.jacoco.core.data.ExecutionDataStore) : org.jacoco.core.analysis.Analyzer(
    executionData,
    object : ICoverageVisitor() {
        override fun visitCoverage(coverage: IClassCoverage?) {}
    }) {
    private val executionData: org.jacoco.core.data.ExecutionDataStore
    private val branchDetails: MutableMap<String?, BranchCoverageDetail?>

    init {
        this.executionData = executionData
        this.branchDetails = TreeMap<String?, BranchCoverageDetail?>()
    }

    // Override all analyzeClass methods.
    @Throws(IOException::class)
    override fun analyzeClass(input: java.io.InputStream, location: String?) {
        val buffer: ByteArray
        try {
            buffer = org.jacoco.core.internal.InputStreams.readFully(input)
        } catch (e: IOException) {
            throw analyzerError(location, e)
        }
        analyzeClass(buffer, location)
    }

    @Throws(IOException::class)
    override fun analyzeClass(buffer: ByteArray, location: String?) {
        try {
            analyzeClass(buffer)
        } catch (cause: java.lang.RuntimeException) {
            throw analyzerError(location, cause)
        }
    }

    fun analyzeClass(reader: org.objectweb.asm.ClassReader) {
        val lineToBranchExp: MutableMap<Int?, BranchExp?> = mapProbes(reader)

        val classid: Long = org.jacoco.core.internal.data.CRC64.classId(reader.b)
        val classData: org.jacoco.core.data.ExecutionData? = executionData.get(classid)

        // It's possible our class was never executed or that we're generating a baseline coverage
        // report but we still need to perform the analysis run.
        var probes: BooleanArray? = null
        if (classData != null) {
            probes = classData.getProbes()
        }

        val detail: BranchCoverageDetail = BranchCoverageDetail()

        for (entry in lineToBranchExp.entries) {
            val line: Int = entry.key!!
            val branchExp: BranchExp = entry.value
            val branches: MutableList<CovExp?> = branchExp.getBranches()

            detail.setBranches(line, branches.size)
            for (branchIdx in branches.indices) {
                if (branches.get(branchIdx).eval(probes)) {
                    detail.setTakenBit(line, branchIdx)
                }
            }
        }
        if (detail.linesWithBranches().size > 0) {
            branchDetails.put(reader.getClassName(), detail)
        }
    }

    private fun analyzeClass(source: ByteArray) {
        val reader: org.objectweb.asm.ClassReader = org.objectweb.asm.ClassReader(source)
        analyzeClass(reader)
    }

    private fun analyzerError(location: String?, cause: java.lang.Exception?): IOException {
        val ex: IOException = IOException(String.format("Error while analyzing %s.", location))
        ex.initCause(cause)
        return ex
    }

    // Generate the line to probeExp map so that we can evaluate the coverage.
    private fun mapProbes(reader: org.objectweb.asm.ClassReader): MutableMap<Int?, BranchExp?> {
        val mapper: ClassProbesMapper = ClassProbesMapper(reader.getClassName())
        val adapter: org.jacoco.core.internal.flow.ClassProbesAdapter =
            org.jacoco.core.internal.flow.ClassProbesAdapter(mapper, false)
        reader.accept(adapter, 0)

        return mapper.result()
    }

    fun getBranchDetails(): MutableMap<String?, BranchCoverageDetail?> {
        return branchDetails
    }
}
