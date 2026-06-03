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
package com.google.devtools.build.buildjar

import com.google.devtools.build.buildjar.JavaLibraryBuildRequest
import com.google.devtools.build.buildjar.instrumentation.JacocoInstrumentationProcessor
import com.google.devtools.build.buildjar.jarhelper.JarCreator
import com.google.devtools.build.buildjar.jarhelper.JarCreator.addDirectory
import com.google.devtools.build.buildjar.jarhelper.JarCreator.execute
import com.google.devtools.build.buildjar.jarhelper.JarCreator.setJarOwner
import com.google.devtools.build.buildjar.jarhelper.JarHelper.setCompression
import com.google.devtools.build.buildjar.javac.BlazeJavacArguments
import com.google.devtools.build.buildjar.javac.BlazeJavacMain
import com.google.devtools.build.buildjar.javac.BlazeJavacResult
import com.google.devtools.build.buildjar.javac.JavacRunner
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.emitDependencyInformation
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.emitManifestProto
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.reducedClasspathLength
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.transitiveClasspathFallback
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.transitiveClasspathLength
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.toBuilder
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import net.starlark.java.syntax.Identifier.getName
import java.io.IOException
import java.nio.file.Path
import java.util.Enumeration
import java.util.jar.JarFile

/** An implementation of the JavaBuilder that uses in-process javac to compile java files.  */
open class SimpleJavaLibraryBuilder : java.io.Closeable {
    @Throws(IOException::class)
    open fun compileSources(build: JavaLibraryBuildRequest, javacRunner: JavacRunner): BlazeJavacResult {
        val result: BlazeJavacResult =
            javacRunner.invokeJavac(build.toBlazeJavacArguments(build.getClassPath()))

        val stats: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder? =
            result.statistics().toBuilder()
                .transitiveClasspathLength(build.getClassPath().size)
                .reducedClasspathLength(build.getClassPath().size)
                .transitiveClasspathFallback(false)
        build.getProcessors().stream()
            .map<String?> { p: String? -> p.substring(p.lastIndexOf('.') + 1) }
            .forEachOrdered { processor: String? -> stats.addProcessor(processor) }
        return result.withStatistics(stats.build())
    }

    @Throws(IOException::class)
    protected fun prepareSourceCompilation(build: JavaLibraryBuildRequest) {
        cleanupDirectory(build.getClassDir())

        setUpSourceJars(build)
        cleanupDirectory(build.getSourceGenDir())
        cleanupDirectory(build.getNativeHeaderDir())
    }

    @Throws(IOException::class)
    fun buildGensrcJar(build: JavaLibraryBuildRequest) {
        val jar: JarCreator = JarCreator(build.getGeneratedSourcesOutputJar())
        try {
            jar.setCompression(build.compressJar())
            jar.addDirectory(build.getSourceGenDir())
        } finally {
            jar.execute()
        }
    }

    /**
     * Prepares a compilation run and sets everything up so that the source files in the build request
     * can be compiled. Invokes compileSources to do the actual compilation.
     * 
     * @param build A JavaLibraryBuildRequest request object describing what to compile
     */
    @Throws(java.lang.Exception::class)
    private fun compileJavaLibrary(build: JavaLibraryBuildRequest): BlazeJavacResult {
        prepareSourceCompilation(build)
        if (build.getSourceFiles().isEmpty()) {
            return BlazeJavacResult.Companion.ok()
        }
        return compileSources(build, JavacRunner { obj: BlazeJavacArguments? -> BlazeJavacMain.compile() })
    }

    /** Perform the build.  */
    @com.google.errorprone.annotations.CheckReturnValue
    @Throws(java.lang.Exception::class)
    fun run(build: JavaLibraryBuildRequest): BlazeJavacResult {
        var result: BlazeJavacResult = BlazeJavacResult.Companion.error("")
        try {
            result = compileJavaLibrary(build)
            if (result.isOk()) {
                buildJar(build)
                nativeHeaderOutput(build)
            }
            if (!build.getProcessors().isEmpty()) {
                if (build.getGeneratedSourcesOutputJar() != null) {
                    buildGensrcJar(build)
                }
            }
        } finally {
            build
                .getDependencyModule()
                .emitDependencyInformation(
                    build.getClassPath(),
                    result.isOk(),  /* requiresFallback= */
                    result.status() == com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.REQUIRES_FALLBACK
                )
            build.getProcessingModule().emitManifestProto()
        }
        return result
    }

    @Throws(IOException::class)
    fun buildJar(build: JavaLibraryBuildRequest) {
        java.nio.file.Files.createDirectories(build.getOutputJar().getParent())
        val jar: JarCreator = JarCreator(build.getOutputJar())
        var processor: JacocoInstrumentationProcessor? = null
        try {
            jar.setCompression(build.compressJar())
            jar.addDirectory(build.getClassDir())
            jar.setJarOwner(build.getTargetLabel(), build.getInjectingRuleKind())
            processor = build.getJacocoInstrumentationProcessor()
            if (processor != null) {
                processor.processRequest(build, jar)
            }
        } finally {
            jar.execute()
            if (processor != null) {
                processor.cleanup()
            }
        }
    }

    @Throws(IOException::class)
    fun nativeHeaderOutput(build: JavaLibraryBuildRequest) {
        if (build.getNativeHeaderOutput() == null) {
            return
        }
        val jar: JarCreator = JarCreator(build.getNativeHeaderOutput())
        try {
            jar.setCompression(build.compressJar())
            jar.addDirectory(build.getNativeHeaderDir())
        } finally {
            jar.execute()
        }
    }

    /**
     * Extracts the all source jars from the build request into the temporary directory specified in
     * the build request. Empties the temporary directory, if it exists.
     */
    @Throws(IOException::class)
    private fun setUpSourceJars(build: JavaLibraryBuildRequest) {
        val sourcesDir: Path = build.getTempDir()

        cleanupDirectory(sourcesDir)

        if (build.getSourceJars().isEmpty()) {
            return
        }

        val protobufMetadataBuffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        for (sourceJar in build.getSourceJars()) {
            try {
                JarFile(sourceJar.toFile()).use { jarFile ->
                    val entries: Enumeration<java.util.jar.JarEntry> = jarFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry: java.util.jar.JarEntry = entries.nextElement()
                        var fileName: String = entry.getName()
                        if (fileName.endsWith(".java")) {
                            if (fileName.get(0) == '/') {
                                fileName = com.google.common.base.CharMatcher.`is`('/').trimLeadingFrom(fileName)
                            }
                            var to: Path = sourcesDir.resolve(fileName)
                            var root = 1
                            if (java.nio.file.Files.exists(to)) {
                                // Make paths unique e.g. if extracting two srcjar entries that differ only in case
                                // to a case-insenitive target filesystem (e.g. on Macs).
                                do {
                                    to = sourcesDir.resolve((root++).toString()).resolve(fileName)
                                } while (java.nio.file.Files.exists(to))
                            }
                            java.nio.file.Files.createDirectories(to.getParent())
                            java.nio.file.Files.copy(jarFile.getInputStream(entry), to)
                            build.getSourceFiles().add(to)
                        } else if (fileName == PROTOBUF_META_NAME) {
                            com.google.common.io.ByteStreams.copy(jarFile.getInputStream(entry), protobufMetadataBuffer)
                        }
                    }
                }
            } catch (e: IOException) {
                throw IOException("unable to open " + sourceJar + " as a jar file", e)
            }
        }
        val output: Path = build.getClassDir().resolve(PROTOBUF_META_NAME)
        if (protobufMetadataBuffer.size() > 0) {
            java.nio.file.Files.newOutputStream(output).use { outputStream ->
                protobufMetadataBuffer.writeTo(outputStream)
            }
        } else if (java.nio.file.Files.exists(output)) {
            // Delete stalled meta file.
            java.nio.file.Files.delete(output)
        }
    }

    @Throws(IOException::class)
    override fun close() {
    }

    companion object {
        /** The name of the protobuf meta file.  */
        private const val PROTOBUF_META_NAME = "protobuf.meta"

        // Necessary for local builds in order to discard previous outputs
        @Throws(IOException::class)
        private fun cleanupDirectory(directory: Path?) {
            if (directory == null) {
                return
            }

            if (java.nio.file.Files.exists(directory)) {
                try {
                    com.google.common.io.MoreFiles.deleteRecursively(
                        directory,
                        com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE
                    )
                } catch (e: IOException) {
                    throw IOException("Cannot clean '" + directory + "'", e)
                }
            }

            java.nio.file.Files.createDirectories(directory)
        }
    }
}
