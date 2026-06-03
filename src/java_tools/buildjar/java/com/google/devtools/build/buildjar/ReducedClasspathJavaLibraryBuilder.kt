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
import com.google.devtools.build.buildjar.OptionsParser.ReduceClasspathMode
import com.google.devtools.build.buildjar.SimpleJavaLibraryBuilder
import com.google.devtools.build.buildjar.javac.BlazeJavacResult
import com.google.devtools.build.buildjar.javac.FormattedDiagnostic
import com.google.devtools.build.buildjar.javac.JavacRunner
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.computeStrictClasspath
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.getImplicitDependenciesMap
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.minClasspathLength
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.reducedClasspathLength
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.transitiveClasspathFallback
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.transitiveClasspathLength
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.toBuilder
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.io.IOException
import java.nio.file.Path

/**
 * A variant of SimpleJavaLibraryBuilder that attempts to reduce the compile-time classpath right
 * before invoking the compiler, based on extra information from provided .jdeps files. This mode is
 * enabled via the --reduce_classpath flag, only when Blaze runs with --experimental_java_classpath.
 * 
 * 
 * A fall-back mechanism detects whether javac fails because the classpath is incorrectly
 * discarding required entries, and re-attempts to compile with the full classpath.
 */
class ReducedClasspathJavaLibraryBuilder : SimpleJavaLibraryBuilder() {
    /**
     * Attempts to minimize the compile-time classpath before invoking javac, falling back to a
     * regular compile.
     * 
     * @param build A JavaLibraryBuildRequest request object describing what to compile
     * @throws IOException clean-up up the output directory fails
     */
    @Throws(IOException::class)
    override fun compileSources(build: JavaLibraryBuildRequest, javacRunner: JavacRunner): BlazeJavacResult? {
        // Minimize classpath, but only if we're actually compiling some sources (some invocations of
        // JavaBuilder are only building resource jars).
        var compressedClasspath: com.google.common.collect.ImmutableList<Path?>? = build.getClassPath()
        if (!build.getSourceFiles().isEmpty()
            && build.reduceClasspathMode() == ReduceClasspathMode.JAVABUILDER_REDUCED
        ) {
            compressedClasspath =
                build.getDependencyModule().computeStrictClasspath(build.getClassPath())
        }

        // Compile!
        var result: BlazeJavacResult =
            javacRunner.invokeJavac(build.toBlazeJavacArguments(compressedClasspath))

        // If javac errored out and there's any chance that the cause was missing classpath entries,
        // then give it another try with the full classpath.
        val fallback = shouldFallBack(result)
        if (fallback) {
            if (build.reduceClasspathMode() == ReduceClasspathMode.BAZEL_REDUCED) {
                return BlazeJavacResult.Companion.fallback()
            }
            if (build.reduceClasspathMode() == ReduceClasspathMode.JAVABUILDER_REDUCED) {
                result = fallback(build, javacRunner)
            }
        }

        val stats: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder? =
            result.statistics().toBuilder()
                .minClasspathLength(build.getDependencyModule().getImplicitDependenciesMap().size)
        build.getProcessors().stream()
            .map<String?> { p: String? -> p.substring(p.lastIndexOf('.') + 1) }
            .forEachOrdered { processor: String? -> stats.addProcessor(processor) }

        when (build.reduceClasspathMode()) {
            ReduceClasspathMode.BAZEL_REDUCED, ReduceClasspathMode.BAZEL_FALLBACK -> {
                stats.transitiveClasspathLength(build.fullClasspathLength())
                stats.reducedClasspathLength(build.reducedClasspathLength())
                stats.transitiveClasspathFallback(
                    build.reduceClasspathMode() == ReduceClasspathMode.BAZEL_FALLBACK
                )
            }

            ReduceClasspathMode.JAVABUILDER_REDUCED -> {
                stats.transitiveClasspathLength(build.getClassPath().size)
                stats.reducedClasspathLength(compressedClasspath.size)
                stats.transitiveClasspathFallback(fallback)
            }

            else -> throw java.lang.AssertionError(build.reduceClasspathMode())
        }
        return result.withStatistics(stats.build())
    }

    @Throws(IOException::class)
    private fun fallback(build: JavaLibraryBuildRequest, javacRunner: JavacRunner): BlazeJavacResult {
        // TODO(cushon): warn for transitive classpath fallback

        // Reset output directories

        prepareSourceCompilation(build)

        // Fall back to the regular compile, but add extra checks to catch transitive uses
        return javacRunner.invokeJavac(build.toBlazeJavacArguments(build.getClassPath()))
    }

    companion object {
        private fun shouldFallBack(result: BlazeJavacResult): Boolean {
            if (result.isOk()) {
                return false
            }
            if (result.status() == com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.CRASH) {
                return true
            }
            if (result.diagnostics().stream().allMatch { d: FormattedDiagnostic? -> d.isJSpecifyDiagnostic() }) {
                return false
            }
            return true
        }
    }
}
