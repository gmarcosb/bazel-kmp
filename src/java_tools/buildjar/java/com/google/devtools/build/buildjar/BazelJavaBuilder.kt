// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar

import com.google.devtools.build.lib.worker.ProtoWorkerMessageProcessor

/** The JavaBuilder main called by bazel.  */
class BazelJavaBuilder {
    fun parseAndBuild(args: MutableList<String?>?, workDir: Path?, pw: PrintWriter): Int {
        try {
            val build: JavaLibraryBuildRequest = parse(args, workDir)
            if (build.getDependencyModule().reduceClasspath())
                ReducedClasspathJavaLibraryBuilder()
            else
                SimpleJavaLibraryBuilder().use { builder ->
                    return build(builder, build, pw)
                }
        } catch (e: InvalidCommandLineException) {
            pw.println(CMDNAME + " threw exception: " + e.message)
            return 1
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            return 1
        }
    }

    /**
     * Uses `builder` to build the target passed in `buildRequest`. All errors and
     * diagnostics should be written to `err`.
     * 
     * @return An error code, 0 is success, any other value is an error.
     */
    @Throws(java.lang.Exception::class)
    protected fun build(
        builder: SimpleJavaLibraryBuilder, buildRequest: JavaLibraryBuildRequest?, err: java.io.Writer
    ): Int {
        val result: BlazeJavacResult = builder.run(buildRequest)
        if (result.status() == com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.REQUIRES_FALLBACK) {
            return 0
        }
        for (d in result.diagnostics()) {
            err.write(d.getFormatted() + "\n")
        }
        err.write(result.output())
        return if (result.isOk()) 0 else 1
    }

    /**
     * Parses the list of arguments into a [JavaLibraryBuildRequest]. The returned [ ] object can be then used to configure the compilation itself.
     * 
     * @throws IOException if the argument list contains a file (with the @ prefix) and reading that
     * file failed
     * @throws InvalidCommandLineException on any command line error
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, InvalidCommandLineException::class)
    fun parse(args: MutableList<String?>?, workDir: Path?): JavaLibraryBuildRequest {
        val optionsParser: com.google.devtools.build.buildjar.OptionsParser =
            com.google.devtools.build.buildjar.OptionsParser(
                args,
                JavacOptions.Companion.createWithWarningsAsErrorsDefault(com.google.common.collect.ImmutableList.of<String?>())
            )
        val plugins: com.google.common.collect.ImmutableList<BlazeJavaCompilerPlugin?> =
            com.google.common.collect.ImmutableList.of<E?>(ErrorPronePlugin(BazelScannerSuppliers.bazelChecks()))
        return JavaLibraryBuildRequest(
            optionsParser,
            plugins,
            com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder(),
            workDir
        )
    }

    companion object {
        private const val CMDNAME = "BazelJavaBuilder"

        /** The main method of the BazelJavaBuilder.  */
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            val builder = BazelJavaBuilder()
            if (args.size == 1 && args[0] == "--persistent_worker") {
                val workerHandler: WorkRequestHandler =
                    WorkRequestHandlerBuilder(
                        WorkRequestCallback(
                            { workRequest, printWriter ->
                                builder.parseAndBuild(
                                    workRequest.getArgumentsList(),
                                    Path.of(workRequest.getSandboxDir()),
                                    printWriter
                                )
                            }),
                        java.lang.System.err,
                        ProtoWorkerMessageProcessor(java.lang.System.`in`, java.lang.System.out)
                    )
                        .setCpuUsageBeforeGc(java.time.Duration.ofSeconds(10))
                        .build()
                var exitCode = 1
                try {
                    workerHandler.processRequests()
                    exitCode = 0
                } catch (e: IOException) {
                    java.lang.System.err.println(e.message)
                } finally {
                    // Prevent hanging threads from keeping the worker alive.
                    java.lang.System.exit(exitCode)
                }
            } else {
                val pw: PrintWriter =
                    PrintWriter(OutputStreamWriter(java.lang.System.err, java.nio.charset.Charset.defaultCharset()))
                var returnCode: Int
                try {
                    returnCode = builder.parseAndBuild(java.util.Arrays.asList<String?>(*args), Path.of(""), pw)
                } finally {
                    pw.flush()
                }
                java.lang.System.exit(returnCode)
            }
        }
    }
}
