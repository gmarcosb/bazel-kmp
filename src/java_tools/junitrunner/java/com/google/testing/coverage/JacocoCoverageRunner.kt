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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getPath
import com.google.testing.coverage.BranchCoverageDetail
import com.google.testing.coverage.BranchDetailAnalyzer
import com.google.testing.coverage.JacocoLCOVFormatter
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.syntax.Identifier.getName
import org.jacoco.agent.rt.IAgent
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.analysis.IBundleCoverage
import org.jacoco.core.tools.ExecFileLoader
import org.jacoco.report.IReportVisitor
import org.jacoco.report.ISourceFileLocator
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.file.StandardOpenOption
import java.util.Enumeration
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap
import java.util.jar.JarFile
import java.util.jar.JarInputStream

/**
 * Runner class used to generate code coverage report when using Jacoco offline instrumentation.
 * 
 * 
 * The complete list of features available for Jacoco offline instrumentation:
 * http://www.eclemma.org/jacoco/trunk/doc/offline.html
 * 
 * 
 * The structure is roughly following the canonical Jacoco example:
 * http://www.eclemma.org/jacoco/trunk/doc/examples/java/ReportGenerator.java
 * 
 * 
 * The following environment variables are expected:
 * 
 * 
 *  * JAVA_COVERAGE_FILE - specifies final location of the generated lcov file.
 *  * JACOCO_METADATA_JAR - specifies jar containing uninstrumented classes to be analyzed.
 * 
 */
class JacocoCoverageRunner {
    private val classesJars: com.google.common.collect.ImmutableList<java.io.File>
    private val executionData: java.io.InputStream?
    private val reportFile: java.io.File
    private var execFileLoader: ExecFileLoader? = null
    private var uninstrumentedClasses: HashMap<String?, ByteArray?>? = null
    private var pathsForCoverage: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>()

    /**
     * Creates a new coverage runner extracting the classes jars from a wrapper file. Uses
     * javaRunfilesRoot to compute the absolute path of the jars inside the wrapper file.
     */
    constructor(
        jacocoExec: java.io.InputStream?,
        reportPath: String,
        wrapperFile: java.io.File,
        javaRunfilesRoot: String?
    ) {
        executionData = jacocoExec
        reportFile = java.io.File(reportPath)
        this.classesJars = getFilesFromFileList(wrapperFile, javaRunfilesRoot)
    }

    constructor(jacocoExec: java.io.InputStream?, reportPath: String, vararg metadataJars: java.io.File?) {
        executionData = jacocoExec
        reportFile = java.io.File(reportPath)
        this.classesJars = com.google.common.collect.ImmutableList.copyOf<java.io.File?>(metadataJars)
    }

    constructor(
        jacocoExec: java.io.InputStream?,
        reportPath: String,
        uninstrumentedClasses: HashMap<String?, ByteArray?>?,
        pathsForCoverage: com.google.common.collect.ImmutableSet<String?>,
        vararg metadataJars: java.io.File?
    ) {
        executionData = jacocoExec
        reportFile = java.io.File(reportPath)
        this.classesJars = com.google.common.collect.ImmutableList.copyOf<java.io.File?>(metadataJars)
        this.uninstrumentedClasses = uninstrumentedClasses
        this.pathsForCoverage = pathsForCoverage
    }

    @Throws(IOException::class)
    fun create() {
        // Read the jacoco.exec file. Multiple data files could be merged at this point
        execFileLoader = ExecFileLoader()
        execFileLoader.load(executionData)

        // Run the structure analyzer on a single class folder or jar file to build up the coverage
        // model. Typically you would create a bundle for each class folder and each jar you want in
        // your report. If you have more than one bundle you may need to add a grouping node to the
        // report. The lcov formatter doesn't seem to care, and we're only using one bundle anyway.
        val bundleCoverage: IBundleCoverage? = analyzeStructure()

        val branchDetails: MutableMap<String?, BranchCoverageDetail?> = analyzeBranch()
        createReport(bundleCoverage, branchDetails)
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun createReport(
        bundleCoverage: IBundleCoverage?, branchDetails: MutableMap<String?, BranchCoverageDetail?>?
    ) {
        val formatter: JacocoLCOVFormatter = JacocoLCOVFormatter(createPathsSet())
        PrintWriter(
            java.nio.file.Files.newBufferedWriter(
                reportFile.toPath(),
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        ).use { writer ->
            val visitor: IReportVisitor = formatter.createVisitor(writer, branchDetails)
            // Initialize the report with all of the execution and session information. At this point the
            // report doesn't know about the structure of the report being created.
            visitor.visitInfo(
                execFileLoader.getSessionInfoStore().getInfos(),
                execFileLoader.getExecutionDataStore().getContents()
            )

            // Populate the report structure with the bundle coverage information.
            // Call visitGroup if you need groups in your report.

            // Note the API requires a sourceFileLocator because the HTML and XML formatters display a
            // page of code annotated with coverage information. Having the source files is not actually
            // needed for generating the lcov report.
            visitor.visitBundle(
                bundleCoverage,
                object : ISourceFileLocator() {
                    @Throws(IOException::class)
                    override fun getSourceFile(packageName: String?, fileName: String?): java.io.Reader? {
                        return null
                    }

                    val tabWidth: Int
                        get() = 0
                })

            // Signal end of structure information to allow report to write all information out
            visitor.visitEnd()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun analyzeStructure(): IBundleCoverage? {
        val coverageBuilder: CoverageBuilder = CoverageBuilder()
        val analyzer: org.jacoco.core.analysis.Analyzer =
            org.jacoco.core.analysis.Analyzer(execFileLoader.getExecutionDataStore(), coverageBuilder)
        val alreadyInstrumentedClasses: MutableSet<String?> = HashSet<String?>()
        if (uninstrumentedClasses == null) {
            for (classesJar in classesJars) {
                analyzeUninstrumentedClassesFromJar(analyzer, classesJar, alreadyInstrumentedClasses)
            }
        } else {
            for (entry in uninstrumentedClasses.entries) {
                analyzer.analyzeClass(entry.value, entry.key)
            }
        }

        // TODO(bazel-team): Find out where the name of the bundle can pop out in the report.
        return coverageBuilder.getBundle("isthisevenused")
    }

    // Additional pass to process the branch details of the classes
    @Throws(IOException::class)
    private fun analyzeBranch(): MutableMap<String?, BranchCoverageDetail?> {
        val analyzer: BranchDetailAnalyzer =
            BranchDetailAnalyzer(execFileLoader.getExecutionDataStore())

        val result: MutableMap<String?, BranchCoverageDetail?> = TreeMap<String?, BranchCoverageDetail?>()
        val alreadyInstrumentedClasses: MutableSet<String?> = HashSet<String?>()
        if (uninstrumentedClasses == null) {
            for (classesJar in classesJars) {
                analyzeUninstrumentedClassesFromJar(analyzer, classesJar, alreadyInstrumentedClasses)
                result.putAll(analyzer.getBranchDetails())
            }
        } else {
            for (entry in uninstrumentedClasses.entries) {
                analyzer.analyzeClass(entry.value, entry.key)
            }
            result.putAll(analyzer.getBranchDetails())
        }
        return result
    }

    /**
     * Analyzes all uninstrumented class files found in the given jar.
     * 
     * 
     * The uninstrumented classes are named using the .class.uninstrumented suffix.
     */
    @Throws(IOException::class)
    private fun analyzeUninstrumentedClassesFromJar(
        analyzer: org.jacoco.core.analysis.Analyzer, jar: java.io.File, alreadyInstrumentedClasses: MutableSet<String?>
    ) {
        val jarFile: JarFile = JarFile(jar)
        val jarFileEntries: Enumeration<java.util.jar.JarEntry> = jarFile.entries()
        while (jarFileEntries.hasMoreElements()) {
            val jarEntry: java.util.jar.JarEntry = jarFileEntries.nextElement()
            val jarEntryName: String = jarEntry.getName()
            if (jarEntryName.endsWith(".class.uninstrumented")
                && !alreadyInstrumentedClasses.contains(jarEntryName)
            ) {
                analyzer.analyzeAll(jarFile.getInputStream(jarEntry), jarEntryName)
                alreadyInstrumentedClasses.add(jarEntryName)
            }
        }
    }

    /**
     * Creates a [Set] containing the paths of the covered Java files.
     * 
     * 
     * The paths are retrieved from a txt file that is found inside each jar containing
     * uninstrumented classes. Each line of the txt file represents a path to be added to the set.
     * 
     * 
     * This set is needed by [JacocoLCOVFormatter] in order to output the correct path for
     * each covered class.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class)
    fun createPathsSet(): com.google.common.collect.ImmutableSet<String?> {
        if (!pathsForCoverage.isEmpty()) {
            return pathsForCoverage
        }
        val execPathsSetBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        for (classJar in classesJars) {
            addEntriesToExecPathsSet(classJar, execPathsSetBuilder)
        }
        val result: com.google.common.collect.ImmutableSet<String?> = execPathsSetBuilder.build()
        return result
    }

    companion object {
        /**
         * Adds to the given [Set] the paths found in a txt file inside the given jar.
         * 
         * 
         * If a jar contains uninstrumented classes it will also contain a txt file with the paths of
         * each of these classes, called "-paths-for-coverage.txt". This file expects one path per line
         * specified as either:
         * 
         * 
         *  * A single path (e.g. /dir/com/example/Foo.java).
         *  * A mapping between source and class paths delimited with by /// (e.g.
         * /dir/Foo.java////com/example/Foo.java).
         * 
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class)
        fun addEntriesToExecPathsSet(
            jar: java.io.File,
            execPathsSetBuilder: com.google.common.collect.ImmutableSet.Builder<String?>
        ) {
            val jarFile: JarFile = JarFile(jar)
            val jarFileEntries: Enumeration<java.util.jar.JarEntry> = jarFile.entries()
            while (jarFileEntries.hasMoreElements()) {
                val jarEntry: java.util.jar.JarEntry = jarFileEntries.nextElement()
                val jarEntryName: String = jarEntry.getName()
                if (jarEntryName.endsWith("-paths-for-coverage.txt")) {
                    val bufferedReader: BufferedReader =
                        BufferedReader(
                            java.io.InputStreamReader(
                                jarFile.getInputStream(jarEntry),
                                java.nio.charset.StandardCharsets.UTF_8
                            )
                        )
                    var line: String?
                    while ((bufferedReader.readLine().also { line = it }) != null) {
                        execPathsSetBuilder.add(line)
                    }
                }
            }
        }

        @Throws(java.lang.Exception::class)
        private fun getMainClass(insideDeployJar: Boolean): java.lang.Class<*> {
            var mainClass: java.lang.Class<*>
            // If we're running inside a deploy jar we have to open the manifest and read the value of
            // "Coverage-Main-Class", set by bazel.
            // Note ClassLoader#getResource() will only return the first result, most likely a manifest
            // from the bootclasspath.
            if (insideDeployJar) {
                if (JacocoCoverageRunner::class.java.getClassLoader() != null) {
                    val manifests: Enumeration<java.net.URL?> =
                        JacocoCoverageRunner::class.java.getClassLoader().getResources("META-INF/MANIFEST.MF")
                    while (manifests.hasMoreElements()) {
                        val manifest: java.util.jar.Manifest =
                            java.util.jar.Manifest(manifests.nextElement().openStream())
                        val attributes: java.util.jar.Attributes = manifest.getMainAttributes()
                        val className: String? = attributes.getValue("Coverage-Main-Class")
                        if (className != null) {
                            // Some test frameworks use dummy Coverage-Main-Class in the deploy jars
                            // which should be ignored by JacocoCoverageRunner.
                            try {
                                mainClass = java.lang.Class.forName(className)
                                return mainClass
                            } catch (e: java.lang.ClassNotFoundException) {
                                // ignore this class and move on
                            }
                        }
                    }
                }
            }
            // Check JACOCO_MAIN_CLASS after making sure we're not running inside a deploy jar, otherwise
            // the deploy jar will be invoked using the wrong main class.
            val jacocoMainClass: String? = java.lang.System.getenv("JACOCO_MAIN_CLASS")
            if (jacocoMainClass != null) {
                return java.lang.Class.forName(jacocoMainClass)
            }
            throw java.lang.IllegalStateException(
                ("JACOCO_METADATA_JAR/JACOCO_MAIN_CLASS environment variables not set, and no"
                        + " META-INF/MANIFEST.MF on the classpath has a Coverage-Main-Class attribute. "
                        + " Cannot determine the name of the main class for the code under test.")
            )
        }

        @Throws(IOException::class)
        private fun getUniquePath(pathTemplate: String?, suffix: String?): String {
            // If pathTemplate is null, we're likely executing from a deploy jar and the test framework
            // did not properly set the environment for coverage reporting. This alone is not a reason for
            // throwing an exception, we're going to run anyway and write the coverage data to a temporary,
            // throw-away file.
            if (pathTemplate == null) {
                return java.io.File.createTempFile("coverage", suffix).getPath()
            } else {
                // bazel sets the path template to a file with the .dat extension. lcov_merger matches all
                // files having '.dat' in their name, so instead of appending we change the extension.
                val absolutePathTemplate: java.io.File = java.io.File(pathTemplate).getAbsoluteFile()
                var prefix: String = absolutePathTemplate.getName()
                val lastDot: Int = prefix.lastIndexOf('.')
                if (lastDot != -1) {
                    prefix = prefix.substring(0, lastDot)
                }
                return java.io.File.createTempFile(prefix, suffix, absolutePathTemplate.getParentFile()).getPath()
            }
        }

        /**
         * Returns an immutable list containing all the file paths found in mainFile. It uses the
         * javaRunfilesRoot prefix for every found file to compute its absolute path.
         */
        @Throws(IOException::class)
        private fun getFilesFromFileList(
            mainFile: java.io.File,
            javaRunfilesRoot: String?
        ): com.google.common.collect.ImmutableList<java.io.File> {
            val metadataFiles: MutableList<String?> =
                com.google.common.io.Files.readLines(mainFile, java.nio.charset.StandardCharsets.UTF_8)
            val convertedMetadataFiles: com.google.common.collect.ImmutableList.Builder<java.io.File?> =
                com.google.common.collect.ImmutableList.Builder<java.io.File?>()
            for (metadataFile in metadataFiles) {
                convertedMetadataFiles.add(java.io.File(javaRunfilesRoot + "/" + metadataFile))
            }
            return convertedMetadataFiles.build()
        }

        private fun getUrls(
            classLoader: java.lang.ClassLoader,
            jarIsWrapped: Boolean,
            wrappedJar: String?
        ): Array<java.net.URL>? {
            // jarIsWrapped is a legacy parameter; it should be removed once we are sure Bazel will no
            // longer set JACOCO_IS_JAR_WRAPPED in java_stub_template
            val urls: Array<java.net.URL>? = getClassLoaderUrls(classLoader)
            if (urls == null || urls.size == 0) {
                return urls
            }
            // If the classpath was too long then a temporary top-level jar is created containing nothing
            // but a manifest with the original classpath. Those are the URLs we are looking for.
            var classPathUrl: java.net.URL? = null
            if (!com.google.common.base.Strings.isNullOrEmpty(wrappedJar)) {
                for (url in urls) {
                    if (url.getPath().endsWith(wrappedJar)) {
                        classPathUrl = url
                    }
                }
                if (classPathUrl == null) {
                    java.lang.System.err.println("Classpath JAR " + wrappedJar + " not provided")
                    return null
                }
            } else if (jarIsWrapped && urls.size == 1) {
                classPathUrl = urls[0]
            }
            if (classPathUrl != null) {
                try {
                    val jarClassPath: String =
                        JarInputStream(classPathUrl.openStream())
                            .getManifest()
                            .getMainAttributes()
                            .getValue("Class-Path")
                    val urlStrings: Array<String?> =
                        jarClassPath.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val newUrls: Array<java.net.URL> = arrayOfNulls<java.net.URL>(urlStrings.size)
                    for (i in urlStrings.indices) {
                        newUrls[i] = java.net.URL(urlStrings[i])
                    }
                    return newUrls
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    return null
                }
            }
            return urls
        }

        private fun getClassLoaderUrls(classLoader: java.lang.ClassLoader): Array<java.net.URL>? {
            if (classLoader is URLClassLoader) {
                return (classLoader as URLClassLoader).getURLs()
            }

            // java 9 and later
            if (classLoader.javaClass.getName().startsWith("jdk.internal.loader.ClassLoaders$")) {
                try {
                    val field: java.lang.reflect.Field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
                    field.setAccessible(true)
                    val unsafe: sun.misc.Unsafe = field.get(null) as sun.misc.Unsafe

                    var ucpField: java.lang.reflect.Field
                    try {
                        // Java 9-15:
                        // jdk.internal.loader.ClassLoaders.AppClassLoader.ucp
                        ucpField = classLoader.javaClass.getDeclaredField("ucp")
                    } catch (e: java.lang.NoSuchFieldException) {
                        // Java 16+:
                        // jdk.internal.loader.BuiltinClassLoader.ucp
                        // https://github.com/openjdk/jdk/commit/03a4df0acd103702e52dcd01c3f03fda4d7b04f5#diff-32cc12c0e3172fe5f2da1f65a75fa1cb920c39040d06323c83ad2c4d84e095aaL147
                        ucpField = classLoader.javaClass.getSuperclass().getDeclaredField("ucp")
                    }
                    val ucpFieldOffset: Long = unsafe.objectFieldOffset(ucpField)
                    val ucpObject: Any? = unsafe.getObject(classLoader, ucpFieldOffset)

                    // jdk.internal.loader.URLClassPath.path
                    val pathField: java.lang.reflect.Field = ucpField.getType().getDeclaredField("path")
                    val pathFieldOffset: Long = unsafe.objectFieldOffset(pathField)
                    val path: java.util.ArrayList<java.net.URL?> =
                        unsafe.getObject(ucpObject, pathFieldOffset) as java.util.ArrayList<java.net.URL?>

                    return path.toTypedArray<java.net.URL?>()
                } catch (e: java.lang.Exception) {
                    return null
                }
            }
            return null
        }

        @Throws(java.lang.Exception::class)
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            var metadataFile: String? = java.lang.System.getenv("JACOCO_METADATA_JAR")
            val jarWrappedValue: String? = java.lang.System.getenv("JACOCO_IS_JAR_WRAPPED")
            val wrappedJarValue: String? = java.lang.System.getenv("CLASSPATH_JAR")
            val wasWrappedJar = if (jarWrappedValue != null) (jarWrappedValue != "0") else false

            var metadataFiles: Array<java.io.File?>? = null
            var deployJars = 0
            val uninstrumentedClasses: HashMap<String?, ByteArray?> = HashMap<String?, ByteArray?>()
            val pathsForCoverageBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.Builder<String?>()
            val classLoader: java.lang.ClassLoader = java.lang.ClassLoader.getSystemClassLoader()
            val urls: Array<java.net.URL>? = getUrls(classLoader, wasWrappedJar, wrappedJarValue)
            if (urls != null) {
                metadataFiles = arrayOfNulls<java.io.File>(urls.size)
                for (i in urls.indices) {
                    val file: String = urls[i].toURI().getPath()
                    metadataFiles[i] = java.io.File(file)
                    // Special case for when there is only one deploy jar on the classpath.
                    if (file.endsWith("_deploy.jar")) {
                        metadataFile = file
                        deployJars++
                    }
                    if (file.endsWith(".jar")) {
                        // Collect
                        // - uninstrumented class files for coverage before starting the actual test
                        // - paths considered for coverage
                        // Collecting these in the shutdown hook is too expensive (we only have a 5s budget).
                        val jarFile: JarFile = JarFile(file)
                        val jarFileEntries: Enumeration<java.util.jar.JarEntry> = jarFile.entries()
                        while (jarFileEntries.hasMoreElements()) {
                            val jarEntry: java.util.jar.JarEntry = jarFileEntries.nextElement()
                            val jarEntryName: String = jarEntry.getName()
                            if (jarEntryName.endsWith(".class.uninstrumented")
                                && !uninstrumentedClasses.containsKey(jarEntryName)
                            ) {
                                uninstrumentedClasses.put(
                                    jarEntryName,
                                    com.google.common.io.ByteStreams.toByteArray(jarFile.getInputStream(jarEntry))
                                )
                            } else if (jarEntryName.endsWith("-paths-for-coverage.txt")) {
                                val bufferedReader: BufferedReader =
                                    BufferedReader(
                                        java.io.InputStreamReader(
                                            jarFile.getInputStream(jarEntry),
                                            java.nio.charset.StandardCharsets.UTF_8
                                        )
                                    )
                                var line: String?
                                while ((bufferedReader.readLine().also { line = it }) != null) {
                                    pathsForCoverageBuilder.add(line)
                                }
                            }
                        }
                    }
                }
            }

            val pathsForCoverage: com.google.common.collect.ImmutableSet<String?> = pathsForCoverageBuilder.build()
            val metadataFileFinal = metadataFile
            val metadataFilesFinal: Array<java.io.File?>? = metadataFiles
            val javaRunfilesRoot: String? = java.lang.System.getenv("JACOCO_JAVA_RUNFILES_ROOT")

            var hasOneFile = false
            if (metadataFile != null
                && (metadataFile.endsWith("_merged_instr.jar") || metadataFile.endsWith("_deploy.jar"))
            ) {
                // bazel can set JACOCO_METADATA_JAR to either one file (a deploy jar
                // or a merged jar) or to multiple jars.
                hasOneFile = true
            }
            val hasOneFileFinal = hasOneFile

            val coverageReportBase: String? = java.lang.System.getenv("JAVA_COVERAGE_FILE")

            // Disable Jacoco's default output mechanism, which runs as a shutdown hook. We generate the
            // report in our own shutdown hook below, and we want to avoid the data race (shutdown hooks are
            // not guaranteed any particular order). Note that also by default, Jacoco appends coverage
            // data, which can have surprising results if running tests locally or somehow encountering
            // the previous .exec file.
            java.lang.System.setProperty("jacoco-agent.output", "none")

            // We have no use for this sessionId property, but leaving it blank results in a DNS lookup
            // at runtime. A minor annoyance: the documentation insists the property name is "sessionId",
            // however on closer inspection of the source code, it turns out to be "sessionid"...
            java.lang.System.setProperty("jacoco-agent.sessionid", "default")

            // A JVM shutdown hook has a fixed amount of time (OS-dependent) before it is terminated.
            // For our purpose, it's more than enough to scan through the instrumented jar and match up
            // the bytecode with the coverage data. It wouldn't be enough for scanning the entire classpath,
            // or doing something else terribly inefficient.
            java.lang.Runtime.getRuntime()
                .addShutdownHook(
                    object : java.lang.Thread() {
                        override fun run() {
                            try {
                                // If the test spawns multiple JVMs, they will race to write to the same files. We
                                // need to generate unique paths for each execution. lcov_merger simply collects
                                // all the .dat files in the current directory anyway, so we don't need to worry
                                // about merging them.
                                val coverageReport = getUniquePath(coverageReportBase, ".dat")
                                val coverageData = getUniquePath(coverageReportBase, ".exec")

                                // Get a handle on the Jacoco Agent and write out the coverage data. Other options
                                // included talking to the agent via TCP (useful when gathering coverage from
                                // multiple JVMs), or via JMX (the agent's MXBean is called
                                // 'org.jacoco:type=Runtime'). As we're running in the same JVM, these options
                                // seemed overkill, we can just refer to the Jacoco runtime as RT.
                                // See http://www.eclemma.org/jacoco/trunk/doc/agent.html for all the options
                                // available.
                                var dataInputStream: ByteArrayInputStream?
                                try {
                                    val agent: IAgent = org.jacoco.agent.rt.RT.getAgent()
                                    val data: ByteArray = agent.getExecutionData(false)
                                    FileOutputStream(coverageData, true).use { fs ->
                                        fs.write(data)
                                    }
                                    // We append to the output file, but run report generation only for the coverage
                                    // data from this JVM. The output file may contain data from other
                                    // subprocesses, etc.
                                    dataInputStream = ByteArrayInputStream(data)
                                } catch (e: java.lang.IllegalStateException) {
                                    // In this case, we didn't execute a single instrumented file, so the agent
                                    // isn't live. There's no coverage to report, but it's otherwise a successful
                                    // invocation.
                                    dataInputStream = ByteArrayInputStream(ByteArray(0))
                                }

                                if (metadataFileFinal != null || metadataFilesFinal != null) {
                                    val metadataJars: Array<java.io.File?>?
                                    if (metadataFilesFinal != null) {
                                        metadataJars = metadataFilesFinal
                                    } else {
                                        metadataJars =
                                            if (hasOneFileFinal)
                                                arrayOf<java.io.File>(java.io.File(metadataFileFinal))
                                            else
                                                getFilesFromFileList(java.io.File(metadataFileFinal), javaRunfilesRoot)
                                                    .toTypedArray<java.io.File?>()
                                    }
                                    if (uninstrumentedClasses.isEmpty()) {
                                        JacocoCoverageRunner(dataInputStream, coverageReport, *metadataJars)
                                            .create()
                                    } else {
                                        JacocoCoverageRunner(
                                            dataInputStream,
                                            coverageReport,
                                            uninstrumentedClasses,
                                            pathsForCoverage,
                                            *metadataJars
                                        )
                                            .create()
                                    }
                                }
                            } catch (e: IOException) {
                                e.printStackTrace()
                                java.lang.Runtime.getRuntime().halt(1)
                            }
                        }
                    })

            // If running inside a deploy jar the classpath contains only that deploy jar.
            // It can happen that multiple deploy jars are on the classpath. In that case we are running
            // from a regular java binary where all the environment (e.g. JACOCO_MAIN_CLASS) is set
            // accordingly.
            val insideDeployJar =
                (deployJars == 1) && (metadataFilesFinal == null || metadataFilesFinal.size == 1)
            val mainClass: java.lang.Class<*> = getMainClass(insideDeployJar)
            val main: java.lang.reflect.Method = mainClass.getMethod("main", Array<String>::class.java)
            main.setAccessible(true)
            // Another option would be to run the tests in a separate JVM, let Jacoco dump out the coverage
            // data, wait for the subprocess to finish and then generate the lcov report. The only benefit
            // of doing this is not being constrained by the hard 5s limit of the shutdown hook. Setting up
            // the subprocess to match all JVM flags, runtime classpath, bootclasspath, etc is doable.
            // We'd share the same limitation if the system under test uses shutdown hooks internally, as
            // there's no way to collect coverage data on that code.
            main.invoke(null, *arrayOf<Any?>(args))
        }
    }
}
