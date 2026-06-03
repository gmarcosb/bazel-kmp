// Copyright 2016 The Bazel Authors. All rights reserved.
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
import com.google.devtools.coverageoutputgenerator.Coverage
import com.google.devtools.coverageoutputgenerator.GcovJsonParser
import com.google.devtools.coverageoutputgenerator.GcovParser
import com.google.devtools.coverageoutputgenerator.LcovMergerFlags
import com.google.devtools.coverageoutputgenerator.LcovParser
import com.google.devtools.coverageoutputgenerator.LcovPrinter
import com.google.devtools.coverageoutputgenerator.SourceFileCoverage
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.HashSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.function.BinaryOperator
import java.util.stream.Collectors

/** Command line utility to convert raw coverage files to lcov (text) format.  */
object Main {
    private val logger: java.util.logging.Logger = java.util.logging.Logger.getLogger(Main::class.java.getName())

    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        try {
            val exitCode: Int = com.google.devtools.coverageoutputgenerator.Main.runWithArgs(*args)
            java.lang.System.exit(exitCode)
        } catch (e: java.lang.Exception) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Unhandled exception on lcov tool: " + e.getMessage(),
                e
            )
            java.lang.System.exit(1)
        }
    }

    @Throws(ExecutionException::class, java.lang.InterruptedException::class)
    fun runWithArgs(vararg args: String?): Int {
        var flags: LcovMergerFlags? = null
        try {
            flags = LcovMergerFlags.Companion.parseFlags(args)
        } catch (e: java.lang.IllegalArgumentException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(java.util.logging.Level.SEVERE, e.getMessage())
            return 1
        }

        val outputFile: java.io.File = java.io.File(flags.outputFile())

        val filesInCoverageDir: MutableList<java.io.File?> =
            if (flags.coverageDir() != null)
                com.google.devtools.coverageoutputgenerator.Main.getCoverageFilesInDir(flags.coverageDir())
            else
                com.google.common.collect.ImmutableList.of<java.io.File?>()
        var coverage: Coverage =
            Coverage.Companion.merge(
                com.google.devtools.coverageoutputgenerator.Main.parseFiles(
                    com.google.devtools.coverageoutputgenerator.Main.getTracefiles(flags, filesInCoverageDir),
                    com.google.devtools.coverageoutputgenerator.Parser { inputStream: java.io.InputStream? ->
                        LcovParser.Companion.parse(
                            inputStream
                        )
                    },
                    flags.parseParallelism()
                ),
                com.google.devtools.coverageoutputgenerator.Main.parseFiles(
                    com.google.devtools.coverageoutputgenerator.Main.getGcovInfoFiles(filesInCoverageDir),
                    com.google.devtools.coverageoutputgenerator.Parser { inputStream: java.io.InputStream? ->
                        GcovParser.Companion.parse(inputStream)
                    },
                    flags.parseParallelism()
                ),
                com.google.devtools.coverageoutputgenerator.Main.parseFiles(
                    com.google.devtools.coverageoutputgenerator.Main.getGcovJsonInfoFiles(filesInCoverageDir),
                    com.google.devtools.coverageoutputgenerator.Parser { inputStream: java.io.InputStream? ->
                        GcovJsonParser.Companion.parse(
                            inputStream
                        )
                    },
                    flags.parseParallelism()
                )
            )

        if (flags.sourcesToReplaceFile() != null) {
            coverage.maybeReplaceSourceFileNames(com.google.devtools.coverageoutputgenerator.Main.getMapFromFile(flags.sourcesToReplaceFile()))
        }

        val profdataFile: java.io.File? =
            com.google.devtools.coverageoutputgenerator.Main.getProfdataFileOrNull(filesInCoverageDir)
        if (coverage.isEmpty()) {
            var exitStatus = 0
            if (profdataFile == null) {
                try {
                    com.google.devtools.coverageoutputgenerator.Main.logger.log(
                        java.util.logging.Level.WARNING,
                        "There was no coverage found."
                    )
                    if (!java.nio.file.Files.exists(outputFile.toPath())) {
                        java.nio.file.Files.createFile(outputFile.toPath()) // Generate empty declared output
                    }
                    exitStatus = 0
                } catch (e: IOException) {
                    com.google.devtools.coverageoutputgenerator.Main.logger.log(
                        java.util.logging.Level.SEVERE,
                        ("Could not create empty output file "
                                + outputFile.getName()
                                + " due to: "
                                + e.getMessage())
                    )
                    exitStatus = 1
                }
            } else {
                // Bazel doesn't support yet converting profdata files to lcov. We still want to output a
                // coverage report so we copy the content of the profdata file to the output file. This is
                // not ideal but it unblocks some Bazel C++
                // coverage users.
                // TODO(#5881): Add support for profdata files.
                try {
                    java.nio.file.Files.copy(
                        profdataFile.toPath(),
                        outputFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: IOException) {
                    com.google.devtools.coverageoutputgenerator.Main.logger.log(
                        java.util.logging.Level.SEVERE,
                        ("Could not copy file "
                                + profdataFile.getName()
                                + " to output file "
                                + outputFile.getName()
                                + " due to: "
                                + e.getMessage())
                    )
                    exitStatus = 1
                }
            }
            return exitStatus
        }

        if (!coverage.isEmpty() && profdataFile != null) {
            // If there is one profdata file then there can't be other types of reports because there is
            // no way to merge them.
            // TODO(#5881): Add support for profdata files.
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.WARNING,
                "Bazel doesn't support LLVM profdata coverage amongst other coverage formats."
            )
            return 0
        }

        if (!flags.filterSources().isEmpty()) {
            coverage = Coverage.Companion.filterOutMatchingSources(coverage, flags.filterSources())
        }

        if (flags.hasSourceFileManifest()) {
            coverage =
                Coverage.Companion.getOnlyTheseSources(
                    coverage,
                    com.google.devtools.coverageoutputgenerator.Main.getSourcesFromSourceFileManifest(flags.sourceFileManifest())
                )
        }

        if (coverage.isEmpty()) {
            try {
                com.google.devtools.coverageoutputgenerator.Main.logger.log(
                    java.util.logging.Level.WARNING,
                    "There was no coverage found."
                )
                if (!java.nio.file.Files.exists(outputFile.toPath())) {
                    java.nio.file.Files.createFile(outputFile.toPath()) // Generate empty declared output
                }
                return 0
            } catch (e: IOException) {
                com.google.devtools.coverageoutputgenerator.Main.logger.log(
                    java.util.logging.Level.SEVERE,
                    ("Could not create empty output file "
                            + outputFile.getName()
                            + " due to: "
                            + e.getMessage())
                )
                return 1
            }
        }

        var exitStatus = 0

        try {
            LcovPrinter.Companion.print(FileOutputStream(outputFile), coverage, flags.legacyBranches())
        } catch (e: IOException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Could not write to output file " + outputFile + " due to " + e.getMessage()
            )
            exitStatus = 1
        }
        return exitStatus
    }

    /**
     * Returns a set of source file names from the given manifest.
     * 
     * 
     * The manifest contains file names line by line. Each file can either be a source file (e.g.
     * .java, .cc) or a coverage metadata file (e.g. .gcno, .em).
     * 
     * 
     * This method only returns the C++ source files, ignoring the other files as they are not
     * necessary when putting together the final coverage report.
     */
    private fun getSourcesFromSourceFileManifest(sourceFileManifest: String): MutableSet<String?> {
        val sourceFiles: MutableSet<String?> = HashSet<String?>()
        try {
            FileInputStream(java.io.File(sourceFileManifest)).use { inputStream ->
                java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8)
                    .use { inputStreamReader ->
                        BufferedReader(inputStreamReader).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                if (!com.google.devtools.coverageoutputgenerator.Main.isMetadataFile(line)) {
                                    sourceFiles.add(line)
                                }
                                line = reader.readLine()
                            }
                        }
                    }
            }
        } catch (e: IOException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Error reading file " + sourceFileManifest + ": " + e.getMessage()
            )
        }
        return sourceFiles
    }

    private fun isMetadataFile(filename: String): Boolean {
        return filename.endsWith(".gcno") || filename.endsWith(".em")
    }

    private fun getGcovInfoFiles(filesInCoverageDir: MutableList<java.io.File?>): MutableList<java.io.File> {
        val gcovFiles: MutableList<java.io.File> =
            com.google.devtools.coverageoutputgenerator.Main.getFilesWithExtension(
                filesInCoverageDir,
                com.google.devtools.coverageoutputgenerator.Constants.GCOV_EXTENSION
            )
        if (gcovFiles.isEmpty()) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "No gcov info file found."
            )
        } else {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "Found " + gcovFiles.size() + " gcov info files."
            )
        }
        return gcovFiles
    }

    private fun getGcovJsonInfoFiles(filesInCoverageDir: MutableList<java.io.File?>): MutableList<java.io.File> {
        val gcovJsonFiles: MutableList<java.io.File> =
            com.google.devtools.coverageoutputgenerator.Main.getFilesWithExtension(
                filesInCoverageDir,
                com.google.devtools.coverageoutputgenerator.Constants.GCOV_JSON_EXTENSION
            )
        if (gcovJsonFiles.isEmpty()) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "No gcov json file found."
            )
        } else {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "Found " + gcovJsonFiles.size() + " gcov json files."
            )
        }
        return gcovJsonFiles
    }

    /**
     * Returns a .profdata file from the given files or null if none or more profdata files were
     * found.
     */
    private fun getProfdataFileOrNull(files: MutableList<java.io.File?>): java.io.File? {
        val profdataFiles: MutableList<java.io.File> =
            com.google.devtools.coverageoutputgenerator.Main.getFilesWithExtension(
                files,
                com.google.devtools.coverageoutputgenerator.Constants.PROFDATA_EXTENSION
            )
        if (profdataFiles.isEmpty()) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "No .profdata file found."
            )
            return null
        }
        if (profdataFiles.size() > 1) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                ("Bazel currently supports only one profdata file per test. "
                        + profdataFiles.size()
                        + " .profadata files were found instead.")
            )
            return null
        }
        com.google.devtools.coverageoutputgenerator.Main.logger.log(
            java.util.logging.Level.FINE,
            "Found one .profdata file."
        )
        return profdataFiles.get(0)
    }

    private fun getTracefiles(
        flags: LcovMergerFlags,
        filesInCoverageDir: MutableList<java.io.File?>
    ): MutableList<java.io.File> {
        var lcovTracefiles: MutableList<java.io.File> = java.util.ArrayList<java.io.File>()
        if (flags.reportsFile() != null) {
            lcovTracefiles = com.google.devtools.coverageoutputgenerator.Main.getTracefilesFromFile(flags.reportsFile())
        } else if (flags.coverageDir() != null) {
            lcovTracefiles = com.google.devtools.coverageoutputgenerator.Main.getFilesWithExtension(
                filesInCoverageDir,
                com.google.devtools.coverageoutputgenerator.Constants.TRACEFILE_EXTENSION
            )
        }
        if (lcovTracefiles.isEmpty()) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "No lcov file found."
            )
        } else {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.FINE,
                "Found " + lcovTracefiles.size() + " tracefiles."
            )
        }
        return lcovTracefiles
    }

    /**
     * Reads the content of the given file and returns a matching map.
     * 
     * 
     * It assumes the file contains lines in the form key:value. For each line it creates an entry
     * in the map with the corresponding key and value.
     */
    private fun getMapFromFile(file: String?): com.google.common.collect.ImmutableMap<String?, String?> {
        val mapBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()

        try {
            FileInputStream(file).use { inputStream ->
                java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8)
                    .use { inputStreamReader ->
                        BufferedReader(inputStreamReader).use { reader ->
                            var keyToValueLine: String? = reader.readLine()
                            while (keyToValueLine != null
                            ) {
                                val keyAndValue: Array<String?> = keyToValueLine.split(":")
                                if (keyAndValue.size == 2) {
                                    mapBuilder.put(keyAndValue[0], keyAndValue[1])
                                }
                                keyToValueLine = reader.readLine()
                            }
                        }
                    }
            }
        } catch (e: IOException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Error reading file " + file + ": " + e.getMessage()
            )
        }
        return mapBuilder.buildOrThrow()
    }

    @Throws(ExecutionException::class, java.lang.InterruptedException::class)
    fun parseFiles(
        files: MutableList<java.io.File>,
        parser: com.google.devtools.coverageoutputgenerator.Parser,
        parallelism: Int
    ): Coverage? {
        if (parallelism == 1) {
            return com.google.devtools.coverageoutputgenerator.Main.parseFilesSequentially(files, parser)
        } else {
            return com.google.devtools.coverageoutputgenerator.Main.parseFilesInParallel(files, parser, parallelism)
        }
    }

    fun parseFilesSequentially(
        files: MutableList<java.io.File>,
        parser: com.google.devtools.coverageoutputgenerator.Parser
    ): Coverage {
        val coverage: Coverage = Coverage()
        for (file in files) {
            try {
                com.google.devtools.coverageoutputgenerator.Main.logger.log(
                    java.util.logging.Level.FINE,
                    "Parsing file " + file
                )
                val sourceFilesCoverage: MutableList<SourceFileCoverage> = parser.parse(FileInputStream(file))
                for (sourceFileCoverage in sourceFilesCoverage) {
                    coverage.add(sourceFileCoverage)
                }
            } catch (e: IOException) {
                com.google.devtools.coverageoutputgenerator.Main.logger.log(
                    java.util.logging.Level.SEVERE,
                    "File " + file.getAbsolutePath() + " could not be parsed due to: " + e.getMessage(),
                    e
                )
                java.lang.System.exit(1)
            }
        }
        return coverage
    }

    @Throws(ExecutionException::class, java.lang.InterruptedException::class)
    fun parseFilesInParallel(
        files: MutableList<java.io.File>,
        parser: com.google.devtools.coverageoutputgenerator.Parser,
        parallelism: Int
    ): Coverage? {
        val pool: ForkJoinPool = ForkJoinPool(parallelism)
        val partitionSize: Int = java.lang.Math.max(1, files.size() / parallelism)
        val partitions: MutableList<MutableList<java.io.File?>?> =
            com.google.common.collect.Lists.partition<java.io.File?>(files, partitionSize)
        return pool.submit<Coverage?>(
            java.util.concurrent.Callable {
                partitions.parallelStream()
                    .map<Coverage?>(java.util.function.Function { p: MutableList<java.io.File>? ->
                        com.google.devtools.coverageoutputgenerator.Main.parseFilesSequentially(
                            p,
                            parser
                        )
                    })
                    .reduce(BinaryOperator { c1: Coverage?, c2: Coverage? -> Coverage.Companion.merge(c1, c2) })
                    .orElse(Coverage.Companion.create())
            })
            .get()
    }

    /**
     * Returns a list of all the files with the given extension found recursively under the given dir.
     */
    @com.google.common.annotations.VisibleForTesting
    fun getCoverageFilesInDir(dir: String?): MutableList<java.io.File?> {
        var files: MutableList<java.io.File?> = java.util.ArrayList<java.io.File?>()
        try {
            java.nio.file.Files.walk(Paths.get(dir)).use { stream ->
                files =
                    stream
                        .filter(
                            java.util.function.Predicate { p: Path? ->
                                p.toString()
                                    .endsWith(com.google.devtools.coverageoutputgenerator.Constants.TRACEFILE_EXTENSION)
                                        || p.toString()
                                    .endsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_EXTENSION)
                                        || p.toString()
                                    .endsWith(com.google.devtools.coverageoutputgenerator.Constants.GCOV_JSON_EXTENSION)
                                        || p.toString()
                                    .endsWith(com.google.devtools.coverageoutputgenerator.Constants.PROFDATA_EXTENSION)
                            })
                        .map<java.io.File?>(java.util.function.Function { path: Path? -> path.toFile() })
                        .collect(Collectors.toList())
            }
        } catch (ex: IOException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Error reading folder " + dir + ": " + ex.getMessage()
            )
        }
        return files
    }

    fun getFilesWithExtension(files: MutableList<java.io.File?>, extension: String?): MutableList<java.io.File> {
        return files.stream()
            .filter(java.util.function.Predicate { file: java.io.File? -> file.toString().endsWith(extension) })
            .collect(Collectors.toList())
    }

    fun getTracefilesFromFile(reportsFile: String?): MutableList<java.io.File> {
        val datFiles: MutableList<java.io.File> = java.util.ArrayList<java.io.File>()
        try {
            FileInputStream(reportsFile).use { inputStream ->
                val inputStreamReader: java.io.InputStreamReader =
                    java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8)
                val reader: BufferedReader = BufferedReader(inputStreamReader)
                var tracefile: String? = reader.readLine()
                while (tracefile != null) {
                    datFiles.add(java.io.File(tracefile))
                    tracefile = reader.readLine()
                }
            }
        } catch (e: IOException) {
            com.google.devtools.coverageoutputgenerator.Main.logger.log(
                java.util.logging.Level.SEVERE,
                "Error reading file " + reportsFile + ": " + e.getMessage()
            )
        }
        return datFiles
    }
}
