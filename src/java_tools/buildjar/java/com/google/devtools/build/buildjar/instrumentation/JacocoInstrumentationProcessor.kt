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
package com.google.devtools.build.buildjar.instrumentation

import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.JavaLibraryBuildRequest
import com.google.devtools.build.buildjar.jarhelper.JarCreator
import com.google.devtools.build.buildjar.jarhelper.JarCreator.addDirectory
import com.google.devtools.build.buildjar.jarhelper.JarCreator.addEntry
import com.google.devtools.build.buildjar.jarhelper.JarHelper.setCompression
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Instruments compiled java classes using Jacoco instrumentation library.  */
class JacocoInstrumentationProcessor private constructor(private val coverageInformation: String) {
    private var instrumentedClassesDirectory: Path? = null

    /**
     * Instruments classes using Jacoco and keeps copies of uninstrumented class files in
     * jacocoMetadataDir, to be zipped up in the output file jacocoMetadataOutput.
     */
    @Throws(IOException::class)
    fun processRequest(build: JavaLibraryBuildRequest, jar: JarCreator) {
        // Use a directory for coverage metadata  that is unique to each built jar. Avoids
        // multiple threads performing read/write/delete actions on the instrumented classes directory.
        instrumentedClassesDirectory = getMetadataDirRelativeToJar(build.getOutputJar())
        java.nio.file.Files.createDirectories(instrumentedClassesDirectory)
        jar.setCompression(build.compressJar())
        val instr: org.jacoco.core.instr.Instrumenter =
            org.jacoco.core.instr.Instrumenter(OfflineInstrumentationAccessGenerator())
        instrumentRecursively(instr, build.getClassDir())
        jar.addDirectory(instrumentedClassesDirectory)
        jar.addEntry(coverageInformation, Path.of(coverageInformation))
    }

    @Throws(IOException::class)
    fun cleanup() {
        if (java.nio.file.Files.exists(instrumentedClassesDirectory)) {
            com.google.common.io.MoreFiles.deleteRecursively(
                instrumentedClassesDirectory, com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE
            )
        }
    }

    /**
     * Runs Jacoco instrumentation processor over all .class files recursively, starting with root.
     */
    @Throws(IOException::class)
    private fun instrumentRecursively(instr: org.jacoco.core.instr.Instrumenter, root: Path) {
        java.nio.file.Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path?>() {
                @Throws(IOException::class)
                override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (!file.getFileName().toString().endsWith(".class")) {
                        return FileVisitResult.CONTINUE
                    }

                    // TODO(bazel-team): filter with coverage_instrumentation_filter?
                    // It's not clear whether there is any advantage in not instrumenting *Test classes,
                    // apart from lowering the covered percentage in the aggregate statistics.

                    // We first copy the original .class file to our metadata directory, then instrument it
                    // and rewrite the instrumented version back into the regular classes output directory.

                    // Not moving or unlinking the source .class file is essential to guarantee visiting
                    // it only once during recursive directory traversal while also mutating the directory.
                    val instrumentedCopy: Path = file
                    val absoluteUninstrumentedCopy: Path? = Path.of(file.toString() + ".uninstrumented")
                    val uninstrumentedCopy: Path =
                        instrumentedClassesDirectory.resolve(root.relativize(absoluteUninstrumentedCopy))
                    java.nio.file.Files.createDirectories(uninstrumentedCopy.getParent())
                    java.nio.file.Files.copy(file, uninstrumentedCopy)
                    BufferedInputStream(java.nio.file.Files.newInputStream(uninstrumentedCopy)).use { input ->
                        BufferedOutputStream(
                            java.nio.file.Files.newOutputStream(instrumentedCopy, StandardOpenOption.TRUNCATE_EXISTING)
                        ).use { output ->
                            instr.instrument(input, output, file.toString())
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
    }

    companion object {
        @Throws(InvalidCommandLineException::class)
        fun create(args: MutableList<String?>): JacocoInstrumentationProcessor {
            // Ignore extra arguments for backwards compatibility (they used to contain filters).
            if (args.size < 1) {
                throw InvalidCommandLineException(
                    ("Number of arguments for Jacoco instrumentation should be 1+ (given "
                            + args.size
                            + ": pathsForCoverageFile")
                )
            }

            return JacocoInstrumentationProcessor(args.getFirst())
        }

        // Return the path of the coverage metadata directory relative to the output jar path.
        private fun getMetadataDirRelativeToJar(outputJar: Path): Path {
            return outputJar.resolveSibling(outputJar.toString() + "-coverage-metadata")
        }
    }
}
