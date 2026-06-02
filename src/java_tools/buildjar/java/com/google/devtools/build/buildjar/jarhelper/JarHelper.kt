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

import com.google.devtools.build.buildjar.jarhelper.JarCreator.JarEntrySource
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import kotlin.collections.HashSet
import kotlin.collections.MutableSet

/**
 * A simple helper class for creating Jar files. All Jar entries are sorted alphabetically. Allows
 * normalization of Jar entries by setting the timestamp of non-.class files to the DOS epoch.
 * Timestamps of .class files are set to the DOS epoch + 2 seconds (The zip timestamp granularity)
 * Adjusting the timestamp for .class files is necessary since otherwise javac will recompile java
 * files if both the java file and its .class file are present.
 */
open class JarHelper(// The path to the Jar we want to create
    protected val jarPath: Path?
) {
    // The properties to describe how to create the Jar
    protected var storageMethod: Int = JarEntry.DEFLATED
    protected var verbose: Boolean = false
    protected var multiRelease: Boolean = false

    // The state needed to create the Jar
    protected val names: MutableSet<String?> = HashSet<String?>()

    fun multiRelease(multiRelease: Boolean) {
        this.multiRelease = multiRelease
    }

    /**
     * Enables or disables compression for the Jar file entries.
     * 
     * @param compression if true enables compressions for the Jar file entries.
     */
    fun setCompression(compression: Boolean) {
        storageMethod = if (compression) JarEntry.DEFLATED else JarEntry.STORED
    }

    /**
     * Enables or disables verbose messages.
     * 
     * @param verbose if true enables verbose messages.
     */
    fun setVerbose(verbose: Boolean) {
        this.verbose = verbose
    }

    /**
     * Writes an entry with specific contents to the jar. Directory entries must include the trailing
     * '/'.
     */
    @Throws(IOException::class)
    protected fun writeEntry(out: JarOutputStream, name: String, content: ByteArray) {
        if (names.add(name)) {
            // Create a new entry
            val entry = JarEntry(name)
            entry.setTimeLocal(normalizedTimestamp(name))
            val size = content.size
            entry.setSize(size.toLong())
            if (size == 0) {
                entry.setMethod(JarEntry.STORED)
                entry.setCrc(0)
                out.putNextEntry(entry)
            } else {
                entry.setMethod(storageMethod)
                if (storageMethod == JarEntry.STORED) {
                    val crc = CRC32()
                    crc.update(content)
                    entry.setCrc(crc.getValue())
                }
                out.putNextEntry(entry)
                out.write(content)
            }
            out.closeEntry()
        }
    }

    /**
     * Writes a standard Java manifest entry into the JarOutputStream. This includes the directory
     * entry for the "META-INF" directory
     * 
     * @param content the Manifest content to write to the manifest entry.
     * @throws IOException
     */
    @Throws(IOException::class)
    protected fun writeManifestEntry(out: JarOutputStream, content: ByteArray) {
        val oldStorageMethod = storageMethod
        // Do not compress small manifest files, the compressed one is frequently
        // larger than the original. The threshold of 256 bytes is somewhat arbitrary.
        if (content.size < 256) {
            storageMethod = JarEntry.STORED
        }
        try {
            writeEntry(out, MANIFEST_DIR, byteArrayOf())
            writeEntry(out, MANIFEST_NAME, content)
        } finally {
            storageMethod = oldStorageMethod
        }
    }

    /**
     * Copies file or directory entries from the file system into the jar. Directory entries will be
     * detected and their names automatically '/' suffixed.
     */
    @Throws(IOException::class)
    protected fun copyEntry(out: JarOutputStream, name: String, source: JarEntrySource) {
        var name = name
        if (!names.contains(name)) {
            if (!source.exists()) {
                throw FileNotFoundException(source.toString() + " (No such file or directory)")
            }
            val isDirectory = source.isDirectory()
            if (isDirectory && !name.endsWith("/")) {
                name = name + '/' // always normalize directory names before checking set
            }
            if (names.add(name)) {
                if (verbose) {
                    System.err.println("adding " + source)
                }
                // Create a new entry
                val size = (if (isDirectory) 0 else source.size()).toLong()
                val outEntry = JarEntry(name)
                val newtime: LocalDateTime? = normalizedTimestamp(name)
                outEntry.setTimeLocal(newtime)
                outEntry.setSize(size)
                if (size == 0L) {
                    outEntry.setMethod(JarEntry.STORED)
                    outEntry.setCrc(0)
                    out.putNextEntry(outEntry)
                } else {
                    val storageMethod = if (name == "protobuf.meta") JarEntry.STORED else this.storageMethod
                    outEntry.setMethod(storageMethod)
                    if (storageMethod == JarEntry.STORED) {
                        // ZipFile requires us to calculate the CRC-32 for any STORED entry.
                        // It would be nicer to do this via DigestInputStream, but
                        // the architecture of ZipOutputStream requires us to know the CRC-32
                        // before we write the data to the stream.
                        val bytes = source.bytes()
                        val crc = CRC32()
                        crc.update(bytes)
                        outEntry.setCrc(crc.getValue())
                        out.putNextEntry(outEntry)
                        out.write(bytes)
                    } else {
                        out.putNextEntry(outEntry)
                        source.copyTo(out)
                    }
                }
                out.closeEntry()
            }
        }
    }

    companion object {
        const val MANIFEST_DIR: String = "META-INF/"
        val MANIFEST_NAME: String = JarFile.MANIFEST_NAME

        /**
         * Normalize timestamps to 2010-1-1.
         * 
         * 
         * The ZIP format uses MS-DOS timestamps (see [APPNOTE.TXT](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)) which use
         * 1980-1-1 as the epoch. To work around this, [ZipEntry] uses portability-reducing ZIP
         * extensions to store pre-1980 timestamps, which can occasionally [](https://bugs.openjdk.java.net/browse/JDK-8246129>cause</a> <a
        href=)//openjdk.markmail.org/thread/wzw7zfilk5j7uzqk>issues. For that reason, using a
         * fixed post-1980 timestamp is preferred. At Google, the timestamp of 2010-1-1 is used by
         * convention in deterministic jar archives.
         */
        val DEFAULT_TIMESTAMP: LocalDateTime = LocalDateTime.of(2010, 1, 1, 0, 0, 0)

        // These attributes are used by JavaBuilder, Turbine, and ijar.
        // They must all be kept in sync.
        val TARGET_LABEL: Attributes.Name = Attributes.Name("Target-Label")
        val INJECTING_RULE_KIND: Attributes.Name = Attributes.Name("Injecting-Rule-Kind")

        val MULTI_RELEASE: Attributes.Name = Attributes.Name("Multi-Release")

        /**
         * This is used to adjust the timestamp for class files to slightly after the normalized time.
         * 
         * 
         * ZIP timestamps have a resolution of 2 seconds, see http://www.info-zip.org/FAQ.html#limits.
         * 
         * 
         * Javac will, when loading a class X, prefer a source file to a class file, if both files have
         * the same timestamp.
         */
        val MINIMUM_TIMESTAMP_INCREMENT: Duration = Duration.ofSeconds(2)

        /**
         * Returns the normalized timestamp for a jar entry based on its name. This is necessary since
         * javac will, when loading a class X, prefer a source file to a class file, if both files have
         * the same timestamp. Therefore, we need to adjust the timestamp for class files to slightly
         * after the normalized time.
         * 
         * @param name The name of the file for which we should return the normalized timestamp.
         * @return the time for a new Jar file entry in milliseconds since the epoch.
         */
        private fun normalizedTimestamp(name: String): LocalDateTime? {
            if (name.endsWith(".class")) {
                return DEFAULT_TIMESTAMP.plus(MINIMUM_TIMESTAMP_INCREMENT)
            } else {
                return DEFAULT_TIMESTAMP
            }
        }
    }
}
