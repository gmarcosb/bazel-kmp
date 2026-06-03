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
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.javac.BlazeJavaCompiler
import com.google.devtools.build.buildjar.javac.BlazeJavacArguments
import com.google.devtools.build.buildjar.javac.BlazeJavacResult
import com.google.devtools.build.buildjar.javac.CancelCompilerPlugin.CancelRequestException
import com.google.devtools.build.buildjar.javac.FormattedDiagnostic
import com.google.devtools.build.buildjar.javac.WerrorCustomOption
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.sun.source.util.JavacTask
import com.sun.tools.javac.api.ClientCodeWrapper
import com.sun.tools.javac.api.JavacTaskImpl
import com.sun.tools.javac.api.JavacTool
import com.sun.tools.javac.file.CacheFSInfo
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.util.PropagatedException
import java.io.IOError
import java.io.IOException
import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.StandardLocation

/**
 * Main class for our custom patched javac.
 * 
 * 
 * This main class tweaks the standard javac log class by changing the compiler's context to use
 * our custom log class. This custom log class modifies javac's output to list all errors after all
 * warnings.
 */
object BlazeJavacMain {
    private val INCOMPATIBLE_SYSTEM_CLASS_PATH_ERROR: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        "(?s)bad class file: /modules/.*class file has wrong version (?<version>[4-9][0-9])\\."
    )

    private val UNSUPPORTED_CLASS_VERSION_ERROR: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        "^(?<class>[^ ]*) has been compiled by a more recent version of the Java Runtime "
                + "\\(class file version (?<version>[4-9][0-9])\\."
    )

    /**
     * Sets up a BlazeJavaCompiler with the given plugins within the given context.
     * 
     * @param context JavaCompiler's associated Context
     */
    @com.google.common.annotations.VisibleForTesting
    fun setupBlazeJavaCompiler(
        plugins: com.google.common.collect.ImmutableList<BlazeJavaCompilerPlugin>,
        context: com.sun.tools.javac.util.Context
    ) {
        for (plugin in plugins) {
            plugin.initializeContext(context)
        }
        BlazeJavaCompiler.Companion.preRegister(context, plugins)
    }

    fun compile(arguments: BlazeJavacArguments): BlazeJavacResult {
        val javacArguments: MutableList<String?>? = arguments.javacOptions()
        try {
            processPluginArgs(
                arguments.plugins(), arguments.javacOptions(), arguments.blazeJavacOptions()
            )
        } catch (e: CancelRequestException) {
            return BlazeJavacResult.Companion.cancelled(e.message)
        } catch (e: InvalidCommandLineException) {
            return BlazeJavacResult.Companion.error(e.message)
        }

        val maybeWerrorCustom: java.util.Optional<WerrorCustomOption?> =
            arguments.blazeJavacOptions().stream()
                .filter { arg: String? -> arg.startsWith("-Werror:") }
                .collect(com.google.common.collect.MoreCollectors.toOptional<String?>())
                .map<WerrorCustomOption?>(java.util.function.Function { arg: String? ->
                    WerrorCustomOption.Companion.create(
                        arg
                    )
                })

        val context: com.sun.tools.javac.util.Context = com.sun.tools.javac.util.Context()
        BlazeJavacStatistics.preRegister(context)
        CacheFSInfo.preRegister(context)
        setupBlazeJavaCompiler(arguments.plugins(), context)
        val builder: com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder =
            context.get<com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder>(com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder::class.java)

        var status: com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status =
            com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.ERROR
        val errOutput: java.io.StringWriter = java.io.StringWriter()
        // TODO(cushon): where is this used when a diagnostic listener is registered? Consider removing
        // it and handling exceptions directly in callers.
        val errWriter: PrintWriter = PrintWriter(errOutput)
        val diagnosticsBuilder: com.google.devtools.build.buildjar.javac.FormattedDiagnostic.Listener =
            com.google.devtools.build.buildjar.javac.FormattedDiagnostic.Listener(
                arguments.failFast(),
                maybeWerrorCustom,
                context,
                arguments.workDir()
            )

        // Initialize parts of context that the filemanager depends on
        context.put<DiagnosticListener<*>?>(DiagnosticListener::class.java, diagnosticsBuilder)
        com.sun.tools.javac.util.Log.instance(context).setWriters(errWriter)
        val options: com.sun.tools.javac.util.Options = com.sun.tools.javac.util.Options.instance(context)
        options.put("-Xlint:path", "path")
        options.put("expandJarClassPaths", "false")

        try {
            ClassloaderMaskingFileManager(context).use { fileManager ->
                setLocations(fileManager, arguments)
                val task: JavacTask =
                    JavacTool.create()
                        .getTask(
                            errWriter,
                            fileManager,
                            diagnosticsBuilder,
                            javacArguments,  /* classes= */
                            com.google.common.collect.ImmutableList.of<String?>(),
                            fileManager.getJavaFileObjectsFromPaths(arguments.sourceFiles()),
                            context
                        )
                try {
                    status = fromResult((task as JavacTaskImpl).doCall())
                } catch (e: PropagatedException) {
                    throw e.cause
                }
            }
        } catch (t: java.lang.Exception) {
            val cause: Throwable? = t.cause
            if (cause is CancelRequestException) {
                return BlazeJavacResult.Companion.cancelled(cause.message)
            }
            val matcher: java.util.regex.Matcher?
            if (cause is java.lang.UnsupportedClassVersionError
                && (UNSUPPORTED_CLASS_VERSION_ERROR.matcher(cause.message).also { matcher = it }).find()
            ) {
                // Java 8 corresponds to class file major version 52.
                val processorVersion: Int = java.lang.Integer.parseUnsignedInt(matcher.group("version")) - 44
                errWriter.printf(
                    ("The Java %d runtime used to run javac is not recent enough to run the processor %s, "
                            + "which has been compiled targeting Java %d. Either register a Java toolchain "
                            + "with a newer java_runtime or, if this processor has been built with Bazel, "
                            + "specify a lower --tool_java_language_version.%n"),
                    java.lang.Runtime.version().feature(),
                    matcher.group("class").replace('/', '.'),
                    processorVersion
                )
            }
            t.printStackTrace(errWriter)
            status = com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.CRASH
        }
        errWriter.flush()
        val diagnostics: com.google.common.collect.ImmutableList<FormattedDiagnostic?> = diagnosticsBuilder.build()

        diagnostics.stream()
            .map<java.util.Optional<String?>?> { d: FormattedDiagnostic? ->
                maybeGetJavaConfigurationError(
                    arguments,
                    d
                )
            }
            .flatMap<String?> { obj: java.util.Optional<kotlin.String?>? -> obj.stream() }
            .findFirst()
            .ifPresent(java.util.function.Consumer { csq: String? -> errOutput.append(csq) })

        var werror: Boolean =
            diagnostics.stream()
                .anyMatch { d: FormattedDiagnostic? -> d.getCode() == "compiler.err.warnings.and.werror" }
        if (status == com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.OK && diagnosticsBuilder.werror()) {
            errOutput.append("error: warnings found and -Werror specified\n")
            status = com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.ERROR
            werror = true
        }

        return BlazeJavacResult.Companion.createFullResult(
            status, filterDiagnostics(werror, diagnostics), errOutput.toString(), builder.build()
        )
    }

    private fun fromResult(result: com.sun.tools.javac.main.Main.Result): com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status {
        when (result) {
            com.sun.tools.javac.main.Main.Result.OK -> return com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.OK
            com.sun.tools.javac.main.Main.Result.ERROR, com.sun.tools.javac.main.Main.Result.CMDERR, com.sun.tools.javac.main.Main.Result.SYSERR -> return com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.ERROR
            com.sun.tools.javac.main.Main.Result.ABNORMAL -> return com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.CRASH
        }
        throw java.lang.AssertionError(result)
    }

    private val IGNORED_DIAGNOSTIC_CODES: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            "compiler.note.deprecated.filename",
            "compiler.note.deprecated.plural",
            "compiler.note.deprecated.recompile",
            "compiler.note.deprecated.filename.additional",
            "compiler.note.deprecated.plural.additional",
            "compiler.note.unchecked.filename",
            "compiler.note.unchecked.plural",
            "compiler.note.unchecked.recompile",
            "compiler.note.unchecked.filename.additional",
            "compiler.note.unchecked.plural.additional",
            "compiler.warn.sun.proprietary",  // avoid warning spam when enabling processor options for an entire tree, only a subset
            // of which actually runs the processor
            "compiler.warn.proc.unmatched.processor.options",  // don't want about v54 class files when running javac9 on JDK 10
            // TODO(cushon): remove after the next javac update
            "compiler.warn.big.major.version",  // don't want about incompatible processor source versions when running javac9 on JDK 10
            // TODO(cushon): remove after the next javac update
            "compiler.warn.proc.processor.incompatible.source.version",  // https://github.com/bazelbuild/bazel/issues/5985
            "compiler.warn.unknown.enum.constant",
            "compiler.warn.unknown.enum.constant.reason",  // b/379318817
            "compiler.warn.annotation.method.not.found",
            "compiler.warn.annotation.method.not.found.reason"
        )

    private fun filterDiagnostics(
        werror: Boolean, diagnostics: com.google.common.collect.ImmutableList<FormattedDiagnostic?>
    ): com.google.common.collect.ImmutableList<FormattedDiagnostic?> {
        return diagnostics.stream()
            .filter { d: FormattedDiagnostic? ->
                shouldReportDiagnostic(
                    werror,
                    d
                )
            }  // Print errors last to make them more visible.
            .sorted(
                java.util.Comparator.comparing<FormattedDiagnostic?, Diagnostic.Kind?>(java.util.function.Function { obj: FormattedDiagnostic? -> obj.getKind() })
                    .reversed()
            )
            .collect(com.google.common.collect.ImmutableList.toImmutableList<FormattedDiagnostic?>())
    }

    private fun shouldReportDiagnostic(werror: Boolean, diagnostic: FormattedDiagnostic): Boolean {
        if (!IGNORED_DIAGNOSTIC_CODES.contains(diagnostic.getCode())) {
            return true
        }
        // show compiler.warn.sun.proprietary if we're running with -Werror
        if (werror && diagnostic.getKind() != Diagnostic.Kind.NOTE) {
            return true
        }
        return false
    }

    private fun maybeGetJavaConfigurationError(
        arguments: BlazeJavacArguments, diagnostic: Diagnostic<*>
    ): java.util.Optional<String?> {
        if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
            return java.util.Optional.empty<String?>()
        }
        val matcher: java.util.regex.Matcher?
        if ((diagnostic.getCode() != "compiler.err.cant.access") || arguments.system() == null || !(INCOMPATIBLE_SYSTEM_CLASS_PATH_ERROR.matcher(
                diagnostic.getMessage(Locale.ENGLISH)
            ).also { matcher = it })
                .find()
        ) {
            return java.util.Optional.empty<String?>()
        }
        // The output path is of the form $PRODUCT-out/$CPU-$MODE[-exec-...]/bin/...
        val isForTool: Boolean = arguments.classOutput().subpath(1, 2).toString().contains("-exec-")
        // Java 8 corresponds to class file major version 52.
        val systemClasspathVersion: Int = java.lang.Integer.parseUnsignedInt(matcher.group("version")) - 44
        return java.util.Optional.of<String?>(
            String.format(
                ("error: [BazelJavaConfiguration] The Java %d runtime used to run javac is not recent "
                        + "enough to compile for the Java %d runtime in %s. Either register a Java "
                        + "toolchain with a newer java_runtime or specify a lower %s.\n"),
                java.lang.Runtime.version().feature(),
                systemClasspathVersion,
                arguments.system(),
                if (isForTool) "--tool_java_runtime_version" else "--java_runtime_version"
            )
        )
    }

    /** Processes Plugin-specific arguments and removes them from the args array.  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(InvalidCommandLineException::class)
    fun processPluginArgs(
        plugins: com.google.common.collect.ImmutableList<BlazeJavaCompilerPlugin>,
        standardJavacopts: com.google.common.collect.ImmutableList<String?>?,
        blazeJavacopts: com.google.common.collect.ImmutableList<String?>?
    ) {
        for (plugin in plugins) {
            plugin.processArgs(standardJavacopts, blazeJavacopts)
        }
    }

    private fun setLocations(fileManager: JavacFileManager, arguments: BlazeJavacArguments) {
        try {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, arguments.classPath())
            // modular dependencies must be on the module path, not the classpath
            fileManager.setLocationFromPaths(StandardLocation.MODULE_PATH, arguments.classPath())

            fileManager.setLocationFromPaths(
                StandardLocation.CLASS_OUTPUT,
                com.google.common.collect.ImmutableList.of<Path?>(arguments.classOutput())
            )
            if (arguments.nativeHeaderOutput() != null) {
                fileManager.setLocationFromPaths(
                    StandardLocation.NATIVE_HEADER_OUTPUT,
                    com.google.common.collect.ImmutableList.of<Path?>(arguments.nativeHeaderOutput())
                )
            }

            var sourcePath: com.google.common.collect.ImmutableList<Path?> = arguments.sourcePath()
            if (sourcePath.isEmpty()) {
                // javac expects a module-info-relative source path to be set when compiling modules,
                // otherwise it reports an error:
                // "file should be on source path, or on patch path for module"
                val moduleInfos: com.google.common.collect.ImmutableList<Path?> =
                    arguments.sourceFiles().stream()
                        .filter { f: Path? -> f.getFileName().toString() == "module-info.java" }
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Path?>())
                if (moduleInfos.size == 1) {
                    sourcePath = com.google.common.collect.ImmutableList.of<Path?>(
                        com.google.common.collect.Iterables.getOnlyElement<Path?>(moduleInfos).toAbsolutePath()
                            .getParent()
                    )
                }
            }
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcePath)

            val system: Path? = arguments.system()
            if (system != null) {
                fileManager.setLocationFromPaths(
                    StandardLocation.SYSTEM_MODULES,
                    com.google.common.collect.ImmutableList.of<Path?>(system)
                )
            }
            // The bootclasspath may legitimately be empty if --release is being used.
            val bootClassPath: MutableCollection<Path?> = arguments.bootClassPath()
            if (!bootClassPath.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.PLATFORM_CLASS_PATH, bootClassPath)
            }
            fileManager.setLocationFromPaths(
                StandardLocation.ANNOTATION_PROCESSOR_PATH, arguments.processorPath()
            )
            if (arguments.sourceOutput() != null) {
                fileManager.setLocationFromPaths(
                    StandardLocation.SOURCE_OUTPUT,
                    com.google.common.collect.ImmutableList.of<Path?>(arguments.sourceOutput())
                )
            }
        } catch (e: IOException) {
            throw IOError(e)
        }
    }

    /**
     * Ensure that classes that appear in the API between JavaBuilder and plugins are consistently
     * loaded by the same classloader. 'Plugins' here means both annotation processors and Error Prone
     * plugins. The annotation processor API is defined in the JDK and doesn't require any special
     * handling, since the versions in the system classloader will always be loaded preferentially.
     * For Error Prone plugins, we want to ensure that classes in the API are loaded from the same
     * classloader as JavaBuilder, but that other classes referenced by plugins are loaded from the
     * processor classpath to avoid plugins seeing stale versions of classes from the releases
     * JavaBuilder jar.
     */
    @ClientCodeWrapper.Trusted
    private class ClassloaderMaskingFileManager(context: com.sun.tools.javac.util.Context?) :
        JavacFileManager(context, true, java.nio.charset.StandardCharsets.UTF_8) {
        override fun getClassLoader(urls: Array<java.net.URL?>): java.lang.ClassLoader {
            return URLClassLoader(
                urls,
                object : java.lang.ClassLoader(java.lang.ClassLoader.getPlatformClassLoader()) {
                    @Throws(java.lang.ClassNotFoundException::class)
                    override fun findClass(name: String): java.lang.Class<*>? {
                        if (name.startsWith("com.google.errorprone.")
                            || name.startsWith("com.google.common.collect.")
                            || name.startsWith("com.google.common.base.")
                            || name.startsWith("com.google.common.regex.")
                            || name.startsWith("org.checkerframework.errorprone.dataflow.")
                            || name.startsWith("com.google.devtools.build.buildjar.javac.statistics.")
                        ) {
                            return java.lang.Class.forName(name)
                        }
                        throw java.lang.ClassNotFoundException(name)
                    }
                })
        }
    }
}
