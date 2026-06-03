// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.buildjar.proto.JavaCompilation.Manifest

/**
 * A JavaBuilder that supports non-standard JDKs and unmodified javac's.
 * 
 * 
 * Does not support:
 * 
 * 
 *  * Error Prone
 *  * strict Java deps
 *  * Android desugaring
 *  * coverage instrumentation
 *  * genclass handling for IDEs
 * 
 */
class VanillaJavaBuilder : java.io.Closeable {
    /** Cache of opened zip filesystems.  */
    private val filesystems: MutableMap<Path?, java.nio.file.FileSystem> = HashMap<Path?, java.nio.file.FileSystem>()

    @Throws(IOException::class)
    private fun getJarFileSystem(sourceJar: Path): java.nio.file.FileSystem? {
        var fs: java.nio.file.FileSystem? = filesystems.get(sourceJar)
        if (fs == null) {
            filesystems.put(
                sourceJar,
                FileSystems.newFileSystem(sourceJar, null as java.lang.ClassLoader?).also { fs = it })
        }
        return fs
    }

    /** Return result of a [VanillaJavaBuilder] build.  */
    class VanillaJavaBuilderResult(private val ok: Boolean, private val output: String?) {
        /** True if the compilation was succesfull.  */
        fun ok(): Boolean {
            return ok
        }

        /** Log output from the compilation.  */
        fun output(): String? {
            return output
        }
    }

    @Throws(IOException::class)
    fun run(args: MutableList<String?>?): VanillaJavaBuilderResult {
        val optionsParser: com.google.devtools.build.buildjar.OptionsParser?
        try {
            optionsParser = com.google.devtools.build.buildjar.OptionsParser(args)
        } catch (e: InvalidCommandLineException) {
            return VanillaJavaBuilderResult(false, e.message)
        }
        val diagnosticCollector: DiagnosticCollector<JavaFileObject?> = DiagnosticCollector<JavaFileObject?>()
        val output: java.io.StringWriter = java.io.StringWriter()
        val javaCompiler: javax.tools.JavaCompiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        val tempDir: Path = java.nio.file.Files.createTempDirectory("_tmp")
        val nativeHeaderDir: Path = tempDir.resolve("native_headers")
        java.nio.file.Files.createDirectories(nativeHeaderDir)
        val sourceGenDir: Path = tempDir.resolve("sources")
        java.nio.file.Files.createDirectories(sourceGenDir)
        val classDir: Path = tempDir.resolve("classes")
        java.nio.file.Files.createDirectories(classDir)
        val ok: Boolean
        javaCompiler.getStandardFileManager(
            diagnosticCollector,
            Locale.ENGLISH,
            java.nio.charset.StandardCharsets.UTF_8
        ).use { fileManager ->
            setLocations(optionsParser, fileManager, nativeHeaderDir, sourceGenDir, classDir)
            val sources: com.google.common.collect.ImmutableList<JavaFileObject?> =
                getSources(optionsParser, fileManager)
            if (sources.isEmpty()) {
                ok = true
            } else {
                val task: CompilationTask =
                    javaCompiler.getTask(
                        PrintWriter(output, true),
                        fileManager,
                        diagnosticCollector,
                        JavacOptions.Companion.removeBazelSpecificFlags(
                            JavacOptions.Companion.normalizeOptionsWithNormalizers(
                                optionsParser.getJavacOpts(), ReleaseOptionNormalizer()
                            )
                        ),
                        com.google.common.collect.ImmutableList.of<String?>(),  /*classes*/
                        sources
                    )
                setProcessors(optionsParser, fileManager, task)
                ok = task.call()
            }
        }
        if (ok) {
            writeOutput(classDir, optionsParser)
            writeNativeHeaderOutput(optionsParser, nativeHeaderDir)
        }
        writeGeneratedSourceOutput(sourceGenDir, optionsParser)
        // the jdeps output doesn't include any information about dependencies, but Bazel still expects
        // the file to be created
        if (optionsParser.getOutputDepsProtoFile() != null) {
            java.nio.file.Files.newOutputStream(Paths.get(optionsParser.getOutputDepsProtoFile())).use { os ->
                Deps.Dependencies.newBuilder()
                    .setRuleLabel(optionsParser.getTargetLabel())
                    .setSuccess(ok)
                    .build()
                    .writeTo(os)
            }
        }
        // TODO(cushon): support manifest protos & genjar
        if (optionsParser.getManifestProtoPath() != null) {
            java.nio.file.Files.newOutputStream(Paths.get(optionsParser.getManifestProtoPath())).use { os ->
                Manifest.getDefaultInstance().writeTo(os)
            }
        }

        for (diagnostic in diagnosticCollector.getDiagnostics()) {
            val code: String = diagnostic.getCode()
            if (code.startsWith("compiler.note.deprecated")
                || code.startsWith("compiler.note.unchecked")
                || code == "compiler.warn.sun.proprietary"
            ) {
                continue
            }
            val message: java.lang.StringBuilder = java.lang.StringBuilder()
            if (diagnostic.getSource() != null) {
                message.append(diagnostic.getSource().getName())
                if (diagnostic.getLineNumber() != -1L) {
                    message.append(':').append(diagnostic.getLineNumber())
                }
                message.append(": ")
            }
            message.append(diagnostic.getKind().toString().lowercase(Locale.ENGLISH))
            message.append(": ").append(diagnostic.getMessage(Locale.ENGLISH)).append(java.lang.System.lineSeparator())
            output.write(message.toString())
        }
        return VanillaJavaBuilderResult(ok, output.toString())
    }

    /** Returns the sources to compile, including any source jar entries.  */
    @Throws(IOException::class)
    private fun getSources(
        optionsParser: com.google.devtools.build.buildjar.OptionsParser, fileManager: StandardJavaFileManager
    ): com.google.common.collect.ImmutableList<JavaFileObject?> {
        val sources: com.google.common.collect.ImmutableList.Builder<JavaFileObject?> =
            com.google.common.collect.ImmutableList.builder<JavaFileObject?>()
        sources.addAll(fileManager.getJavaFileObjectsFromStrings(optionsParser.getSourceFiles()))
        for (sourceJar in optionsParser.getSourceJars()) {
            for (root in getJarFileSystem(Paths.get(sourceJar)).getRootDirectories()) {
                java.nio.file.Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path?>() {
                        @Throws(IOException::class)
                        override fun visitFile(path: Path, attrs: BasicFileAttributes?): FileVisitResult {
                            if (path.getFileName().toString().endsWith(".java")) {
                                sources.add(SourceJarFileObject(root, path))
                            }
                            return FileVisitResult.CONTINUE
                        }
                    })
            }
        }
        return sources.build()
    }

    @Throws(IOException::class)
    override fun close() {
        for (fs in filesystems.values) {
            fs.close()
        }
    }

    /**
     * Wraps a [Path] as a [JavaFileObject]; used to avoid extracting source jar entries
     * to disk when using file managers that don't support nio.
     */
    private class SourceJarFileObject(root: Path, path: Path) : SimpleJavaFileObject(
        java.net.URI.create("file:/" + root + "!" + root.resolve(path)),
        JavaFileObject.Kind.SOURCE
    ) {
        private val path: Path

        init {
            this.path = path
        }

        @Throws(IOException::class)
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence? {
            return java.nio.file.Files.readString(path)
        }
    }

    companion object {
        @Throws(IOException::class)
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            if (args.size == 1 && args[0] == "--persistent_worker") {
                java.lang.System.exit(runPersistentWorker())
            } else {
                VanillaJavaBuilder().use { builder ->
                    val result: VanillaJavaBuilderResult =
                        builder.run(com.google.common.collect.ImmutableList.copyOf<String?>(args))
                    java.lang.System.err.print(result.output())
                    java.lang.System.exit(if (result.ok()) 0 else 1)
                }
            }
        }

        private fun runPersistentWorker(): Int {
            while (true) {
                try {
                    val request: WorkRequest? = WorkRequest.parseDelimitedFrom(java.lang.System.`in`)
                    if (request == null) {
                        break
                    }
                    val result: VanillaJavaBuilderResult
                    VanillaJavaBuilder().use { builder ->
                        result = builder.run(request.getArgumentsList())
                    }
                    /* As soon as we write the response, bazel will start cleaning
         * up the working tree. The VanillaJavaBuilder must be fully
         * closed at this point.
         */
                    val response: WorkResponse =
                        WorkResponse.newBuilder()
                            .setOutput(result.output())
                            .setExitCode(if (result.ok()) 0 else 1)
                            .setRequestId(request.getRequestId())
                            .build()
                    response.writeDelimitedTo(java.lang.System.out)
                    java.lang.System.out.flush()
                } catch (e: IOException) {
                    e.printStackTrace()
                    return 1
                }
            }
            return 0
        }

        /** Sets the compilation search paths and output directories.  */
        @Throws(IOException::class)
        private fun setLocations(
            optionsParser: com.google.devtools.build.buildjar.OptionsParser,
            fileManager: StandardJavaFileManager,
            nativeHeaderDir: Path,
            sourceGenDir: Path,
            classDir: Path
        ) {
            fileManager.setLocation(StandardLocation.CLASS_PATH, toFiles(optionsParser.getClassPath()))
            val bootClassPath: Iterable<java.io.File?> = toFiles(optionsParser.getBootClassPath())
            // The bootclasspath may legitimately be empty if --release is being used.
            if (!com.google.common.collect.Iterables.isEmpty(bootClassPath)) {
                fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, bootClassPath)
            }
            fileManager.setLocation(
                StandardLocation.ANNOTATION_PROCESSOR_PATH, toFiles(optionsParser.getProcessorPath())
            )
            setOutputLocation(fileManager, StandardLocation.SOURCE_OUTPUT, sourceGenDir)
            if (optionsParser.getNativeHeaderOutput() != null) {
                setOutputLocation(fileManager, StandardLocation.NATIVE_HEADER_OUTPUT, nativeHeaderDir)
            }
            setOutputLocation(fileManager, StandardLocation.CLASS_OUTPUT, classDir)
        }

        @Throws(IOException::class)
        private fun setOutputLocation(
            fileManager: StandardJavaFileManager, location: StandardLocation?, path: Path
        ) {
            createOutputDirectory(path)
            fileManager.setLocation(location, com.google.common.collect.ImmutableList.of<java.io.File?>(path.toFile()))
        }

        /** Sets the compilation's annotation processors.  */
        private fun setProcessors(
            optionsParser: com.google.devtools.build.buildjar.OptionsParser,
            fileManager: StandardJavaFileManager,
            task: CompilationTask
        ) {
            val processorLoader: java.lang.ClassLoader =
                fileManager.getClassLoader(StandardLocation.ANNOTATION_PROCESSOR_PATH)
            val processors: com.google.common.collect.ImmutableList.Builder<javax.annotation.processing.Processor?> =
                com.google.common.collect.ImmutableList.builder<javax.annotation.processing.Processor?>()
            for (processor in optionsParser.getProcessorNames()) {
                try {
                    processors.add(
                        processorLoader.loadClass(processor).getConstructor()
                            .newInstance() as javax.annotation.processing.Processor?
                    )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.LinkageError(e.message, e)
                }
            }
            task.setProcessors(processors.build())
        }

        /** Writes a jar containing any sources generated by annotation processors.  */
        @Throws(IOException::class)
        private fun writeGeneratedSourceOutput(
            sourceGenDir: Path,
            optionsParser: com.google.devtools.build.buildjar.OptionsParser
        ) {
            if (optionsParser.getGeneratedSourcesOutputJar() == null) {
                return
            }
            val jar: JarCreator = JarCreator(Path.of(optionsParser.getGeneratedSourcesOutputJar()))
            jar.setCompression(optionsParser.compressJar())
            jar.addDirectory(sourceGenDir)
            jar.execute()
        }

        @Throws(IOException::class)
        private fun writeNativeHeaderOutput(
            optionsParser: com.google.devtools.build.buildjar.OptionsParser,
            nativeHeaderDir: Path
        ) {
            if (optionsParser.getNativeHeaderOutput() == null) {
                return
            }
            val jar: JarCreator = JarCreator(Path.of(optionsParser.getNativeHeaderOutput()))
            try {
                jar.setCompression(optionsParser.compressJar())
                jar.addDirectory(nativeHeaderDir)
            } finally {
                jar.execute()
            }
        }

        /** Writes the class output jar, including any resource entries.  */
        @Throws(IOException::class)
        private fun writeOutput(classDir: Path, optionsParser: com.google.devtools.build.buildjar.OptionsParser) {
            val jar: JarCreator = JarCreator(Path.of(optionsParser.getOutputJar()))
            jar.setCompression(optionsParser.compressJar())
            jar.addDirectory(classDir)
            jar.execute()
        }

        private fun toFiles(classPath: MutableList<String>?): com.google.common.collect.ImmutableList<java.io.File?> {
            if (classPath == null) {
                return com.google.common.collect.ImmutableList.of<java.io.File?>()
            }
            val files: com.google.common.collect.ImmutableList.Builder<java.io.File?> =
                com.google.common.collect.ImmutableList.builder<java.io.File?>()
            for (path in classPath) {
                files.add(java.io.File(path))
            }
            return files.build()
        }

        @Throws(IOException::class)
        private fun createOutputDirectory(dir: Path) {
            if (java.nio.file.Files.exists(dir)) {
                try {
                    com.google.common.io.MoreFiles.deleteRecursively(
                        dir,
                        com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE
                    )
                } catch (e: IOException) {
                    throw IOException("Cannot clean output directory '" + dir + "'", e)
                }
            }
            java.nio.file.Files.createDirectories(dir)
        }
    }
}
