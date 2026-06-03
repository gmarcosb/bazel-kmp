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
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import java.util.TreeMap

internal class Coverage {
    private val sourceFiles: TreeMap<String?, SourceFileCoverage>

    init {
        sourceFiles = TreeMap<String?, SourceFileCoverage>()
    }

    fun add(input: SourceFileCoverage) {
        val sourceFilename: String? = input.sourceFileName()
        if (sourceFiles.containsKey(sourceFilename)) {
            val old: SourceFileCoverage = sourceFiles.get(sourceFilename)
            sourceFiles.put(sourceFilename, SourceFileCoverage.Companion.merge(old, input))
        } else {
            sourceFiles.put(sourceFilename, input)
        }
    }

    /**
     * Replaces the source file names in the current coverage with their mapping in the given map, if
     * it exists.
     */
    fun maybeReplaceSourceFileNames(reportedToOriginalSources: com.google.common.collect.ImmutableMap<String?, String?>?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, String?>?>(
            reportedToOriginalSources
        )
        if (reportedToOriginalSources.isEmpty()) {
            // nothing to replace
            return
        }
        for (source in this.getAllSourceFiles()) {
            if (reportedToOriginalSources.containsKey(source.sourceFileName())) {
                source.changeSourcefileName(reportedToOriginalSources.get(source.sourceFileName()))
            }
        }
    }

    fun isEmpty(): Boolean {
        return sourceFiles.isEmpty()
    }

    fun getAllSourceFiles(): MutableCollection<SourceFileCoverage> {
        return sourceFiles.values()
    }

    companion object {
        fun merge(vararg coverages: Coverage?): Coverage {
            return Companion.merge(java.util.Arrays.asList<Coverage?>(*coverages))
        }

        fun merge(coverages: MutableList<Coverage>): Coverage {
            val merged = Coverage()
            for (c in coverages) {
                for (sourceFile in c.getAllSourceFiles()) {
                    merged.add(sourceFile)
                }
            }
            return merged
        }

        fun create(vararg sourceFilesCoverage: SourceFileCoverage?): Coverage {
            return Companion.create(java.util.Arrays.asList<SourceFileCoverage?>(*sourceFilesCoverage))
        }

        fun create(sourceFilesCoverage: MutableList<SourceFileCoverage>): Coverage {
            val coverage = Coverage()
            for (sourceFileCoverage in sourceFilesCoverage) {
                coverage.add(sourceFileCoverage)
            }
            return coverage
        }

        /**
         * Returns [Coverage] only for the given CC source filenames, filtering out every other CC
         * sources of the given coverage. Other types of source files (e.g. Java) will not be filtered
         * out.
         * 
         * @param coverage The initial coverage.
         * @param sourcesToKeep The filenames of the sources to keep from the initial coverage.
         */
        fun getOnlyTheseSources(coverage: Coverage, sourcesToKeep: MutableSet<String?>): Coverage {
            require(!(coverage == null || sourcesToKeep == null)) { "Coverage and sourcesToKeep should not be null." }
            if (coverage.isEmpty()) {
                return coverage
            }
            if (sourcesToKeep.isEmpty()) {
                return Coverage()
            }
            val finalCoverage = Coverage()
            for (source in coverage.getAllSourceFiles()) {
                if (sourcesToKeep.contains(source.sourceFileName())) {
                    finalCoverage.add(source)
                }
            }
            return finalCoverage
        }

        @Throws(java.lang.IllegalArgumentException::class)
        fun filterOutMatchingSources(coverage: Coverage, regexes: MutableList<String?>): Coverage {
            require(!(coverage == null || regexes == null)) { "Coverage and regex should not be null." }
            if (regexes.isEmpty()) {
                return coverage
            }
            // Pre-compile patterns once instead of recompiling for every source file
            val compiledPatterns: com.google.common.collect.ImmutableList<java.util.regex.Pattern> =
                regexes.stream().map<java.util.regex.Pattern?>(java.util.function.Function { regex: String? ->
                    java.util.regex.Pattern.compile(regex)
                }).collect(com.google.common.collect.ImmutableList.toImmutableList<java.util.regex.Pattern?>())
            val filteredCoverage = Coverage()
            for (source in coverage.getAllSourceFiles()) {
                if (!matchesAnyPattern(source.sourceFileName(), compiledPatterns)) {
                    filteredCoverage.add(source)
                }
            }
            return filteredCoverage
        }

        private fun matchesAnyPattern(input: String?, patterns: MutableList<java.util.regex.Pattern>): Boolean {
            for (pattern in patterns) {
                if (pattern.matcher(input).matches()) {
                    return true
                }
            }
            return false
        }
    }
}
