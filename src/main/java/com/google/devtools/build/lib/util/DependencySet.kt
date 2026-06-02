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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.util.DependencySet
import com.google.devtools.build.lib.util.StringEncoding
import java.io.IOException
import java.io.PrintStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Representation of a set of file dependencies for a given output file. There are generally one
 * input dependency and a bunch of include dependencies. The files are stored as `Path`s and
 * may be relative or absolute.
 * 
 * 
 * The serialized format read and written is equivalent and compatible with the ".d" file
 * produced by the -MM for a given out (.o) file.
 * 
 * 
 * The file format looks like:
 * 
 * <pre>
 * {outfile}:  \
 * {infile} \
 * {include} \
 * ... \
 * {include}
</pre> * 
 * 
 * @see "http://gcc.gnu.org/onlinedocs/gcc-4.2.1/gcc/Preprocessor-Options.html.Preprocessor-Options"
 */
class DependencySet(root: com.google.devtools.build.lib.vfs.Path) {
    /**
     * The set of dependent files that this DependencySet embodies. They are all Path with the same
     * FileSystem A tree set is used to ensure that we write them out in a consistent order.
     */
    private val dependencies: MutableCollection<com.google.devtools.build.lib.vfs.Path> =
        java.util.ArrayList<com.google.devtools.build.lib.vfs.Path>()

    private val root: com.google.devtools.build.lib.vfs.Path

    /** Get output file name for which dependencies are included in this DependencySet.  */
    @kotlin.jvm.JvmField
    var outputFileName: String? = null

    /** Constructs a new empty DependencySet instance.  */
    init {
        this.root = root
    }

    /**
     * Gets an unmodifiable view of the set of dependencies in [Path] form from this
     * DependencySet instance.
     */
    fun getDependencies(): MutableCollection<com.google.devtools.build.lib.vfs.Path?> {
        return Collections.unmodifiableCollection<com.google.devtools.build.lib.vfs.Path?>(dependencies)
    }

    /**
     * Adds a given collection of dependencies in Path form to this DependencySet instance. Paths are
     * converted to root-relative
     */
    @com.google.common.annotations.VisibleForTesting // only called from DependencySetTest
    fun addDependencies(deps: MutableCollection<com.google.devtools.build.lib.vfs.Path>) {
        for (d in deps) {
            com.google.common.base.Preconditions.checkArgument(d.startsWith(root))
            dependencies.add(d)
        }
    }

    /** Adds a given dependency to this DependencySet instance.  */
    private fun addDependency(dep: String) {
        var dep = dep
        dep = translatePath(dep)!!
        val depPath: com.google.devtools.build.lib.vfs.Path? = root.getRelative(dep)
        dependencies.add(depPath)
    }

    private fun translatePath(path: String): String? {
        if (com.google.devtools.build.lib.util.OS.Companion.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS) {
            return path
        }
        return com.google.devtools.build.lib.util.DependencySet.WindowsPath.removeWorkspace(
            com.google.devtools.build.lib.util.DependencySet.WindowsPath.translateWindowsPath(
                path
            )
        )
    }

    /** Reads a dotd file into this DependencySet instance.  */
    @Throws(IOException::class)
    fun read(dotdFile: com.google.devtools.build.lib.vfs.Path?): DependencySet {
        val content: ByteArray = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(dotdFile)
        try {
            return process(content)
        } catch (e: IOException) {
            throw IOException("Error processing " + dotdFile + ": " + e.message)
        }
    }

    /**
     * Parses a .d file.
     * 
     * 
     * Performance-critical! In large C++ builds there are lots of .d files to read, and some of
     * them reach into hundreds of kilobytes.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun process(content: ByteArray): DependencySet {
        val n = content.size
        if (n > 0 && content[n - 1] != '\n'.code.toByte()) {
            throw IOException("File does not end in a newline")
            // From now on, we can safely peek ahead one character when not at a newline.
        }
        // Our write position in content[]; we use the prefix as working space to build strings.
        var w = 0
        // Have we seen a leading "mumble.o:" on this line yet?  If not, we ignore
        // any dependencies we parse.  This is bug-for-bug compatibility with our
        // MSVC wrapper, which generates invalid .d files :(
        var sawTarget = false
        var r = 0
        while (r < n) {
            val c = content[r++]
            when (c) {
                ' ' -> {
                    // If we haven't yet seen the colon delimiting the target name,
                    // keep scanning.  We do this to cope with "foo.o : \" which is
                    // valid Makefile syntax produced by the cuda compiler.
                    if (sawTarget && w > 0) {
                        addDependency(String(content, 0, w, java.nio.charset.StandardCharsets.ISO_8859_1))
                        w = 0
                    }
                    continue
                }

                '\r' -> {
                    // Ignore, should be followed by a \n.
                    continue
                }

                '\n' -> {
                    // This closes a filename.
                    // (Arguably if !sawTarget && w > 0 we should report an error,
                    // as that suggests the .d file is malformed.)
                    if (sawTarget && w > 0) {
                        addDependency(String(content, 0, w, java.nio.charset.StandardCharsets.ISO_8859_1))
                    }
                    w = 0
                    sawTarget = false // reset for new line
                    continue
                }

                ':' ->           // Normally this indicates the target name, but it might be part of a
                    // filename on Windows.  Peek ahead at the next character.
                    when (content[r]) {
                        ' ', '\n', '\r' -> {
                            if (w > 0) {
                                outputFileName = String(content, 0, w, java.nio.charset.StandardCharsets.ISO_8859_1)
                                w = 0
                                sawTarget = true
                            }
                            continue
                        }

                        else -> {
                            content[w++] = c // copy a colon to filename
                            continue
                        }
                    }

                '\\' ->           // Peek ahead at the next character.
                    when (content[r]) {
                        ' ' -> {
                            content[w++] = ' '.code.toByte() // copy a space to the filename
                            ++r // skip over the space
                            continue
                        }

                        '\n' -> {
                            ++r // skip over the newline
                            continue
                        }

                        '\r' -> {
                            // One backslash can escape \r\n, so peek one more character.
                            if (content[++r] == '\n'.code.toByte()) {
                                ++r
                            }
                            continue
                        }

                        else -> {
                            content[w++] = c // copy a backlash to the filename
                            continue
                        }
                    }

                '$' -> {
                    if (content[r] == '$'.code.toByte()) {
                        content[w++] = '$'.code.toByte()
                        ++r
                        continue
                    }

                    content[w++] = c
                }

                else -> content[w++] = c
            }
        }
        return this
    }

    /**
     * Writes this DependencySet object for a specified output file under the root dir, and with a
     * given suffix.
     */
    @Throws(IOException::class)
    fun write(outFile: com.google.devtools.build.lib.vfs.Path, suffix: String?) {
        val dotdFile: com.google.devtools.build.lib.vfs.Path =
            outFile.getRelative(
                com.google.devtools.build.lib.vfs.FileSystemUtils.replaceExtension(
                    outFile.asFragment(),
                    suffix
                )
            )

        PrintStream(dotdFile.getOutputStream()).use { out ->
            out.print(outFile.relativeTo(root).toString() + ": ")
            for (d in dependencies) {
                out.print(" \\\n  " + d.getPathString()) // should already be root relative
            }
            out.println()
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is DependencySet
                && other.dependencies == dependencies
    }

    override fun hashCode(): Int {
        return dependencies.hashCode()
    }

    private object WindowsPath {
        private val UNIX_ROOT: AtomicReference<String?> = AtomicReference<String?>(null)

        private val EXECROOT_BASE_HEADER_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(".*execroot[\\\\/](?<headerPath>.*)")

        fun removeWorkspace(path: String?): String? {
            var path = path
            val m: java.util.regex.Matcher =
                com.google.devtools.build.lib.util.DependencySet.WindowsPath.EXECROOT_BASE_HEADER_PATTERN.matcher(path)
            if (m.matches()) {
                path = "../" + m.group("headerPath")
            }
            return path
        }

        fun translateWindowsPath(path: String): String {
            val n = path.length
            if (n == 0 || path.get(0) != '/') {
                return path
            }
            if (n >= 2 && com.google.devtools.build.lib.util.DependencySet.WindowsPath.isAsciiLetter(path.get(1)) && (n == 2 || path.get(
                    2
                ) == '/')
            ) {
                return com.google.common.base.Ascii.toUpperCase(path.get(1)).toString() + ":/" + path.substring(2)
            } else {
                val unixRoot: String = com.google.devtools.build.lib.util.DependencySet.WindowsPath.getUnixRoot()
                return unixRoot + path
            }
        }

        fun isAsciiLetter(c: Char): Boolean {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
        }

        val unixRoot: String
            get() {
                var value: String? = com.google.devtools.build.lib.util.DependencySet.WindowsPath.UNIX_ROOT.get()
                if (value == null) {
                    val jvmFlag = "bazel.windows_unix_root"
                    value = com.google.devtools.build.lib.util.DependencySet.WindowsPath.determineUnixRoot(jvmFlag)
                    checkNotNull(value) {
                        String.format(
                            ("\"%1\$s\" JVM flag is not set. Use the --host_jvm_args flag. "
                                    + "For example: "
                                    + "\"--host_jvm_args=-D%1\$s=c:/msys64\"."),
                            jvmFlag
                        )
                    }
                    value = value.replace('\\', '/')
                    if (value!!.length > 3 && value.endsWith("/")) {
                        value = value.substring(0, value.length - 1)
                    }
                    com.google.devtools.build.lib.util.DependencySet.WindowsPath.UNIX_ROOT.set(value)
                }
                return value!!
            }

        fun determineUnixRoot(jvmArgName: String): String? {
            // Get the path from a JVM flag, if specified.
            var path: String? = StringEncoding.platformToInternal(java.lang.System.getProperty(jvmArgName))
            if (path == null) {
                return null
            }
            path = path.trim { it <= ' ' }
            if (path.isEmpty()) {
                return null
            }
            return path
        }
    }
}
