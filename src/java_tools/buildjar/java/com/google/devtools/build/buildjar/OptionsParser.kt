// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.OptionsParser.ReduceClasspathMode
import com.google.devtools.build.buildjar.javac.JavacOptions
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.io.IOException
import java.nio.file.Paths
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Parses options that the [JavaLibraryBuildRequest] needs to construct a build request from
 * command-line flags and options files and provides them via getters.
 */
class OptionsParser @kotlin.jvm.JvmOverloads constructor(args: MutableList<String>, normalizer: JavacOptions? = null) {
    private val javacOpts: MutableList<String?> = java.util.ArrayList<String?>()

    private val directJars: MutableSet<String?> = LinkedHashSet<String?>()

    var strictJavaDeps: String? = null
        private set
    var fixDepsTool: String? = null
        private set

    var outputDepsProtoFile: String? = null
        private set
    val depsArtifacts: MutableSet<String?> = LinkedHashSet<String?>()

    /** This modes controls how a probablistic Java classpath reduction is used.  */
    enum class ReduceClasspathMode {
        BAZEL_REDUCED,
        BAZEL_FALLBACK,
        JAVABUILDER_REDUCED,
        NONE
    }

    /**
     * The flag --reduce_classpath_mode can be passed to JavaBuilder to request a compilation with
     * reduced classpath, computed from the compilations direct dependencies plus what was actually
     * required to build those. If this compilation fails with a specific error code, then a fallback
     * is done using the full (transitive) classpath.
     */
    private var reduceClasspathMode = ReduceClasspathMode.NONE

    private var fullClasspathLength = -1
    private var reducedClasspathLength = -1

    var generatedSourcesOutputJar: String? = null
        private set
    var manifestProtoPath: String? = null
        private set

    val sourceFiles: MutableList<String?> = java.util.ArrayList<String?>()
    val sourceJars: MutableList<String?> = java.util.ArrayList<String?>()

    val classPath: MutableList<String?> = java.util.ArrayList<String?>()
    val sourcePath: MutableList<String?> = java.util.ArrayList<String?>()
    val bootClassPath: MutableList<String?> = java.util.ArrayList<String?>()
    var system: String? = null
        private set

    val processorPath: MutableList<String?> = java.util.ArrayList<String?>()
    val processorNames: MutableList<String?> = java.util.ArrayList<String?>()

    var outputJar: String? = null
        private set
    var nativeHeaderOutput: String? = null
        private set

    val postProcessors: MutableMap<String?, MutableList<String?>?> = LinkedHashMap<String?, MutableList<String?>?>()

    private var compressJar = false

    var targetLabel: String? = null
        private set
    var injectingRuleKind: String? = null
        private set

    private val normalizer: JavacOptions?

    /**
     * Processes the command line arguments.
     * 
     * @throws InvalidCommandLineException on an invalid option being passed.
     */
    @Throws(InvalidCommandLineException::class)
    private fun processCommandlineArgs(argQueue: Deque<String>) {
        var arg: String? = argQueue.pollFirst()
        while (arg != null) {
            when (arg) {
                "--javacopts" -> {
                    com.google.devtools.build.buildjar.OptionsParser.Companion.readJavacopts(javacOpts, argQueue)
                    sourcePathFromJavacOpts()
                }

                "--direct_dependencies" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    directJars,
                    argQueue,
                    "--"
                )

                "--strict_java_deps" -> strictJavaDeps =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--experimental_fix_deps_tool" -> fixDepsTool =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--output_deps_proto" -> outputDepsProtoFile =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--deps_artifacts" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    depsArtifacts,
                    argQueue,
                    "--"
                )

                "--reduce_classpath" -> reduceClasspathMode = ReduceClasspathMode.JAVABUILDER_REDUCED
                "--reduce_classpath_mode" -> reduceClasspathMode =
                    com.google.devtools.build.buildjar.OptionsParser.ReduceClasspathMode.valueOf(
                        com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(
                            argQueue,
                            arg
                        )
                    )

                "--full_classpath_length" -> fullClasspathLength =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg).toInt()

                "--reduced_classpath_length" -> reducedClasspathLength =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg).toInt()

                "--generated_sources_output" -> generatedSourcesOutputJar =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--output_manifest_proto" -> manifestProtoPath =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--sources" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    sourceFiles,
                    argQueue,
                    "-"
                )

                "--source_jars" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    sourceJars,
                    argQueue,
                    "-"
                )

                "--classpath" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    classPath,
                    argQueue,
                    "-"
                )

                "--sourcepath" ->           // TODO(#970): Consider whether we want to use --sourcepath for resolving of #970.
                    com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                        sourcePath,
                        argQueue,
                        "-"
                    )

                "--bootclasspath" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    bootClassPath,
                    argQueue,
                    "-"
                )

                "--system" -> system =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--processorpath" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(
                    processorPath,
                    argQueue,
                    "-"
                )

                "--processors" -> com.google.devtools.build.buildjar.OptionsParser.Companion.collectProcessorArguments(
                    processorNames,
                    argQueue,
                    "-"
                )

                "--output" -> outputJar =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--native_header_output" -> nativeHeaderOutput =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--post_processor" -> addExternalPostProcessor(argQueue, arg)
                "--compress_jar" -> compressJar = true
                "--target_label" -> targetLabel =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                "--injecting_rule_kind" -> injectingRuleKind =
                    com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(argQueue, arg)

                else -> throw InvalidCommandLineException("unknown option : '" + arg + "'")
            }
            arg = argQueue.pollFirst()
        }
    }

    private fun sourcePathFromJavacOpts() {
        val it: MutableIterator<String> = javacOpts.iterator()
        while (it.hasNext()) {
            val curr = it.next()
            if (curr == "-sourcepath" && it.hasNext()) {
                it.remove()
                com.google.common.collect.Iterables.addAll<String?>(
                    sourcePath,
                    com.google.devtools.build.buildjar.OptionsParser.Companion.CLASSPATH_SPLITTER.split(it.next())
                )
                it.remove()
            }
        }
    }

    /**
     * Constructs an `OptionsParser` from a list of command args. Sets the same JavacRunner for
     * both compilation and annotation processing.
     * 
     * @param args the list of command line args.
     * @throws InvalidCommandLineException on any command line error.
     */
    /**
     * Constructs an `OptionsParser` from a list of command args. Sets the same JavacRunner for
     * both compilation and annotation processing.
     * 
     * @param args the list of command line args.
     * @throws InvalidCommandLineException on any command line error.
     */
    init {
        this.normalizer = normalizer
        processCommandlineArgs(com.google.devtools.build.buildjar.OptionsParser.Companion.expandArguments(args))
    }

    @Throws(InvalidCommandLineException::class)
    private fun addExternalPostProcessor(args: Deque<String>, arg: String?) {
        val processorName: String? = com.google.devtools.build.buildjar.OptionsParser.Companion.getArgument(args, arg)
        val arguments: MutableList<String?> = java.util.ArrayList<String?>()
        com.google.devtools.build.buildjar.OptionsParser.Companion.collectFlagArguments(arguments, args, "--")
        postProcessors.put(processorName, arguments)
    }

    fun getJavacOpts(): MutableList<String?> {
        return if (normalizer != null) normalizer.normalize(javacOpts) else javacOpts
    }

    fun directJars(): MutableSet<String?> {
        return directJars
    }

    fun reduceClasspathMode(): ReduceClasspathMode {
        return reduceClasspathMode
    }

    fun fullClasspathLength(): Int {
        return fullClasspathLength
    }

    fun reducedClasspathLength(): Int {
        return reducedClasspathLength
    }

    fun compressJar(): Boolean {
        return compressJar
    }

    companion object {
        /**
         * Pre-processes an argument list, expanding options @filename to read in the content of the file
         * and add it to the list of arguments.
         * 
         * @param args the List of arguments to pre-process.
         * @return the List of pre-processed arguments.
         * @throws java.io.IOException if one of the files containing options cannot be read.
         */
        @Throws(IOException::class)
        private fun expandArguments(args: MutableList<String>): Deque<String> {
            val expanded: Deque<String> = ArrayDeque<String>(args.size)
            for (arg in args) {
                com.google.devtools.build.buildjar.OptionsParser.Companion.expandArgument(expanded, arg)
            }
            return expanded
        }

        /**
         * Expands a single argument, expanding options @filename to read in the content of the file and
         * add it to the list of processed arguments. The @ itself can be escaped with @@.
         * 
         * @param expanded the list of processed arguments.
         * @param arg the argument to pre-process.
         * @throws java.io.IOException if one of the files containing options cannot be read.
         */
        @Throws(IOException::class)
        private fun expandArgument(expanded: Deque<String>, arg: String) {
            if (arg.startsWith("@@")) {
                expanded.add(arg.substring(1))
            } else if (arg.startsWith("@")) {
                for (line in java.nio.file.Files.readAllLines(
                    Paths.get(arg.substring(1)),
                    java.nio.charset.StandardCharsets.UTF_8
                )) {
                    if (line.length > 0) {
                        com.google.devtools.build.buildjar.OptionsParser.Companion.expandArgument(expanded, line)
                    }
                }
            } else {
                expanded.add(arg)
            }
        }

        /**
         * Collects the arguments for a command line flag until it finds a flag that starts with the
         * terminatorPrefix.
         * 
         * @param output where to put the collected flag arguments.
         * @param args
         * @param terminatorPrefix the terminator prefix to stop collecting of argument flags.
         */
        private fun collectFlagArguments(
            output: MutableCollection<String?>, args: Deque<String>, terminatorPrefix: String?
        ) {
            var arg: String? = args.pollFirst()
            while (arg != null) {
                if (arg.startsWith(terminatorPrefix)) {
                    args.addFirst(arg)
                    break
                }
                output.add(arg)
                arg = args.pollFirst()
            }
        }

        /**
         * Returns a list of javacopts. Reads options until a terminating `"--"` is reached, to
         * support parsing javacopts that start with `--` (e.g. --release).
         */
        private fun readJavacopts(javacopts: MutableList<String?>, argumentDeque: Deque<String>) {
            while (!argumentDeque.isEmpty()) {
                val arg: String = argumentDeque.pollFirst()
                if (arg == "--") {
                    return
                }
                javacopts.add(arg)
            }
            throw java.lang.IllegalArgumentException("javacopts should be terminated by `--`")
        }

        private val CLASSPATH_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.on(java.io.File.pathSeparatorChar).trimResults().omitEmptyStrings()

        /**
         * Collects the arguments for the --processors command line flag until it finds a flag that starts
         * with the terminatorPrefix.
         * 
         * @param output where to put the collected flag arguments.
         * @param args
         * @param terminatorPrefix the terminator prefix to stop collecting of argument flags.
         */
        @Throws(InvalidCommandLineException::class)
        private fun collectProcessorArguments(
            output: MutableList<String?>, args: Deque<String>, terminatorPrefix: String?
        ) {
            var arg: String? = args.pollFirst()
            while (arg != null) {
                if (arg.startsWith(terminatorPrefix)) {
                    args.addFirst(arg)
                    break
                }
                if (arg.contains(",")) {
                    throw InvalidCommandLineException("processor argument may not contain commas: " + arg)
                }
                output.add(arg)
                arg = args.pollFirst()
            }
        }

        @Throws(InvalidCommandLineException::class)
        private fun getArgument(args: Deque<String>, arg: String?): String? {
            try {
                return args.remove()
            } catch (e: java.util.NoSuchElementException) {
                throw InvalidCommandLineException(arg + ": missing argument", e)
            }
        }
    }
}
