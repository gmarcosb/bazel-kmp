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

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException

@com.beust.jcommander.Parameters(separators = "= ")
internal class LcovMergerFlags {
    @com.beust.jcommander.Parameter(names = ["--coverage_dir"])
    private val coverageDir: String? = null

    @com.beust.jcommander.Parameter(names = ["--reports_file", "--lcovfile_path"])
    private val reportsFile: String? = null

    @com.beust.jcommander.Parameter(names = ["--output_file"])
    private val outputFile: String? = null

    @com.beust.jcommander.Parameter(names = ["--filter_sources"])
    private val filterSources: MutableList<String?>? = null

    /**
     * The path to a source file manifest. This file contains multiple lines that represent file names
     * of the sources that the final coverage report must include. Additionally this file can also
     * contain coverage metadata files (e.g. gcno, .em), which can be ignored.
     */
    @com.beust.jcommander.Parameter(names = ["--source_file_manifest"])
    private val sourceFileManifest: String? = null

    @com.beust.jcommander.Parameter(names = ["--sources_to_replace_file"])
    private val sourcesToReplaceFile: String? = null

    @com.beust.jcommander.Parameter(names = ["--parse_parallelism"])
    private val parseParallelism: Int? = null

    @com.beust.jcommander.Parameter(names = ["--legacy_branches"])
    private val legacyBranches = false

    fun coverageDir(): String? {
        return coverageDir
    }

    fun outputFile(): String {
        return outputFile!!
    }

    fun filterSources(): MutableList<String?> {
        return if (filterSources == null) com.google.common.collect.ImmutableList.of<String?>() else filterSources
    }

    fun reportsFile(): String? {
        return reportsFile
    }

    fun sourceFileManifest(): String? {
        return sourceFileManifest
    }

    fun sourcesToReplaceFile(): String? {
        return sourcesToReplaceFile
    }

    fun hasSourceFileManifest(): Boolean {
        return sourceFileManifest != null
    }

    fun parseParallelism(): Int {
        return if (parseParallelism == null) DEFAULT_PARSE_FILE_PARALLELISM else parseParallelism
    }

    fun legacyBranches(): Boolean {
        return legacyBranches
    }

    companion object {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger(LcovMergerFlags::class.java.getName())
        private const val DEFAULT_PARSE_FILE_PARALLELISM = 4

        fun parseFlags(args: Array<String?>): LcovMergerFlags {
            val flags = LcovMergerFlags()
            val jCommander: JCommander = JCommander(flags)
            jCommander.setAllowParameterOverwriting(true)
            jCommander.setAcceptUnknownOptions(true)
            try {
                jCommander.parse(*args)
            } catch (e: ParameterException) {
                throw java.lang.IllegalArgumentException("Error parsing args", e)
            }
            require(!(flags.coverageDir == null && flags.reportsFile == null)) { "At least one of coverage_dir or reports_file should be specified." }
            if (flags.coverageDir != null && flags.reportsFile != null) {
                logger.warning("Overriding --coverage_dir value in favor of --reports_file")
            }
            requireNotNull(flags.outputFile) { "output_file was not specified." }
            return flags
        }
    }
}
