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
package com.google.devtools.build.buildjar.jarhelper

import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A class for creating Jar files. Allows normalization of Jar entries by setting their timestamp to
 * the DOS epoch. All Jar entries are sorted alphabetically.
 */
class JarCreator(path: Path?) : JarHelper(path) {
    /** A source of bytes to be added to a Jar file.  */
    interface JarEntrySource {
        @Throws(IOException::class)
        fun bytes(): ByteArray?

        @Throws(IOException::class)
        fun size(): Int

        @get:Throws(IOException::class)
        val isDirectory: Boolean

        @Throws(IOException::class)
        fun copyTo(out: JarOutputStream?)

        @Throws(IOException::class)
        fun exists(): Boolean
    }

    @kotlin.jvm.JvmRecord
    private data class PathJarEntrySource(val path: Path?) : JarEntrySource {
        override fun isDirectory(): Boolean {
            return Files.isDirectory(path)
        }

        @Throws(IOException::class)
        override fun size(): Int {
            return Files.size(path).toInt()
        }

        @Throws(IOException::class)
        override fun bytes(): ByteArray? {
            return Files.readAllBytes(path)
        }

        @Throws(IOException::class)
        override fun copyTo(out: JarOutputStream) {
            Files.copy(path, out)
        }

        override fun exists(): Boolean {
            return Files.exists(path)
        }
    }

    @kotlin.jvm.JvmRecord
    private data class ByteArrayJarEntrySource(val bytes: ByteArray?) : JarEntrySource {
        override fun isDirectory(): Boolean {
            return false
        }

        override fun size(): Int {
            return bytes!!.size
        }

        override fun bytes(): ByteArray? {
            return bytes
        }

        @Throws(IOException::class)
        override fun copyTo(out: JarOutputStream) {
            out.write(bytes)
        }

        override fun exists(): Boolean {
            return true
        }
    }

    // Map from Jar entry names to files. Use TreeMap so we can establish a canonical order for the
    // entries regardless in what order they get added.
    private val jarEntries = TreeMap<String?, JarEntrySource?>()
    private var manifestPath: Path? = null
    private var mainClass: String? = null
    private var targetLabel: String? = null
    private var injectingRuleKind: String? = null

    private fun addEntry(entryName: String, source: JarEntrySource?): Boolean {
        var entryName = entryName
        if (entryName.startsWith("/")) {
            entryName = entryName.substring(1)
        } else if (entryName.length >= 3 && Character.isLetter(entryName.get(0))
            && entryName.get(1) == ':' && (entryName.get(2) == '\\' || entryName.get(2) == '/')
        ) {
            // Windows absolute path, e.g. "D:\foo" or "e:/blah".
            // Windows paths are case-insensitive, and support both backslashes and forward slashes.
            entryName = entryName.substring(3)
        } else if (entryName.startsWith("./")) {
            entryName = entryName.substring(2)
        }
        return jarEntries.put(entryName, source) == null
    }

    /**
     * Adds an entry to the Jar file, normalizing the name.
     * 
     * @param entryName the name of the entry in the Jar file
     * @param path the path of the input for the entry
     * @return true iff a new entry was added
     */
    @CanIgnoreReturnValue
    fun addEntry(entryName: String, path: Path?): Boolean {
        return addEntry(entryName, PathJarEntrySource(path))
    }

    /**
     * Adds an entry to the Jar file, normalizing the name.
     * 
     * @param entryName the name of the entry in the Jar file
     * @param bytes the content for the entry
     * @return true iff a new entry was added
     */
    @CanIgnoreReturnValue
    fun addEntry(entryName: String, bytes: ByteArray?): Boolean {
        return addEntry(entryName, ByteArrayJarEntrySource(bytes))
    }

    /**
     * Adds the contents of a directory to the Jar file. All files below this directory will be added
     * to the Jar file using the name relative to the directory as the name for the Jar entry.
     * 
     * @param directory the directory to add to the jar
     */
    fun addDirectory(directory: Path) {
        require(Files.exists(directory)) { "directory does not exist: " + directory }
        try {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path?>() {
                    override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes?): FileVisitResult {
                        if (path != directory) {
                            // For consistency with legacy behaviour, include entries for directories except for
                            // the root.
                            addEntry(path,  /* isDirectory= */true)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                        addEntry(path,  /* isDirectory= */false)
                        return FileVisitResult.CONTINUE
                    }

                    fun addEntry(path: Path?, isDirectory: Boolean) {
                        val sb = StringBuilder()
                        var first = true
                        for (entry in directory.relativize(path)) {
                            if (!first) {
                                // use `/` as the directory separator for jar paths, even on Windows
                                sb.append('/')
                            }
                            sb.append(entry.getFileName())
                            first = false
                        }
                        if (isDirectory) {
                            sb.append('/')
                        }
                        jarEntries.put(sb.toString(), PathJarEntrySource(path))
                    }
                })
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    /**
     * Sets the main.class entry for the manifest. A value of `null` (the default) will
     * omit the entry.
     * 
     * @param mainClass the fully qualified name of the main class
     */
    fun setMainClass(mainClass: String?) {
        this.mainClass = mainClass
    }

    fun setJarOwner(targetLabel: String?, injectingRuleKind: String?) {
        this.targetLabel = targetLabel
        this.injectingRuleKind = injectingRuleKind
    }

    /**
     * Sets filename for the manifest content. If this is set the manifest will be read from this file
     * otherwise the manifest content will get generated on the fly.
     * 
     * @param manifestPath the filename of the manifest file.
     */
    fun setManifestPath(manifestPath: Path?) {
        this.manifestPath = manifestPath
    }

    @Throws(IOException::class)
    private fun manifestContent(): ByteArray? {
        if (manifestPath != null) {
            Files.newInputStream(manifestPath).use { `in` ->
                return manifestContentImpl(Manifest(`in`))
            }
        } else {
            return manifestContentImpl(Manifest())
        }
    }

    @Throws(IOException::class)
    private fun manifestContentImpl(manifest: Manifest): ByteArray? {
        val attributes = manifest.getMainAttributes()
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
        val createdBy = Attributes.Name("Created-By")
        if (attributes.getValue(createdBy) == null) {
            attributes.put(createdBy, "bazel")
        }
        if (mainClass != null) {
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass)
        }
        if (targetLabel != null) {
            attributes.put(JarHelper.Companion.TARGET_LABEL, targetLabel)
        }
        if (injectingRuleKind != null) {
            attributes.put(JarHelper.Companion.INJECTING_RULE_KIND, injectingRuleKind)
        }
        if (multiRelease) {
            attributes.put(JarHelper.Companion.MULTI_RELEASE, "true")
        }
        val out = ByteArrayOutputStream()
        manifest.write(out)
        return out.toByteArray()
    }

    /**
     * Executes the creation of the Jar file.
     * 
     * @throws IOException if the Jar cannot be written or any of the entries cannot be read.
     */
    @Throws(IOException::class)
    fun execute() {
        Files.newOutputStream(jarPath).use { os ->
            BufferedOutputStream(os).use { bos ->
                JarOutputStream(bos).use { out ->

                    // Create the manifest entry in the Jar file
                    writeManifestEntry(out, manifestContent())
                    for (entry in jarEntries.entries) {
                        copyEntry(out, entry.key, entry.value)
                    }
                }
            }
        }
    }

    companion object {
        /** A simple way to create Jar file using the JarCreator class.  */
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            if (args.size < 1) {
                System.err.println("usage: CreateJar output [root directories]")
                System.exit(1)
            }
            val output = Path.of(args[0])
            val createJar = JarCreator(output)
            for (i in 1..<args.size) {
                createJar.addDirectory(Path.of(args[i]))
            }
            createJar.setCompression(true)
            createJar.setVerbose(true)
            val start = System.currentTimeMillis()
            try {
                createJar.execute()
            } catch (e: IOException) {
                e.printStackTrace()
                System.exit(1)
            }
            val stop = System.currentTimeMillis()
            System.err.println((stop - start).toString() + "ms.")
        }
    }
}
