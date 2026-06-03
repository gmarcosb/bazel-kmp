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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.vfs.DigestHashFunction

/**
 * Allow tests to easily manage scratch files in a FileSystem.
 */
class Scratch {
    private val fileSystem: FileSystem
    private var workingDir: Path? = null

    /**
     * Create a new ScratchFileSystem using the [InMemoryFileSystem]
     */
    constructor(workingDir: String?) : this(
        InMemoryFileSystem(
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            DigestHashFunction.SHA256
        ), workingDir
    )

    /**
     * Create a new ScratchFileSystem using the given `Path`.
     */
    constructor(workingDir: Path) {
        this.fileSystem = workingDir.getFileSystem()
        this.workingDir = workingDir
    }

    /**
     * Create a new ScratchFileSystem using the supplied FileSystem.
     */
    constructor(fileSystem: FileSystem) : this(fileSystem, "/")

    /**
     * Create a new ScratchFileSystem using the supplied FileSystem.
     */
    /**
     * Create a new ScratchFileSystem using the [InMemoryFileSystem]
     */
    @kotlin.jvm.JvmOverloads
    constructor(
        fileSystem: FileSystem = InMemoryFileSystem(
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            DigestHashFunction.SHA256
        ), workingDir: String? = "/"
    ) {
        this.fileSystem = fileSystem
        this.workingDir = fileSystem.getPath(workingDir)
    }

    /**
     * Returns the FileSystem in use.
     */
    fun getFileSystem(): FileSystem {
        return fileSystem
    }

    fun setWorkingDir(workingDir: String?) {
        this.workingDir = fileSystem.getPath(workingDir)
    }

    /**
     * Resolves `pathName` relative to the working directory. Note that this will not create any
     * entity in the filesystem; i.e., the file that the object is describing may not exist in the
     * filesystem.
     */
    fun resolve(pathName: String?): Path {
        return workingDir.getRelative(pathName)
    }

    /**
     * Resolves `pathName` relative to the working directory. Note that this will not create any
     * entity in the filesystem; i.e., the file that the object is describing may not exist in the
     * filesystem.
     */
    fun resolve(pathName: PathFragment?): Path {
        return workingDir.getRelative(pathName)
    }

    /**
     * Create a directory in the scratch filesystem, with the given path name.
     */
    @Throws(IOException::class)
    fun dir(pathName: String?): Path {
        val dir: Path = resolve(pathName)
        if (!dir.exists()) {
            dir.createDirectoryAndParents()
        }
        if (!dir.isDirectory()) {
            throw IOException("Exists, but is not a directory: " + pathName)
        }
        return dir
    }

    @Throws(IOException::class)
    fun file(pathName: String?, vararg lines: String?): Path {
        return file(pathName, DEFAULT_CHARSET, *lines)
    }

    /**
     * Create a scratch file in the scratch filesystem, with the given pathName, consisting of a set
     * of lines. The method returns a Path instance for the scratch file.
     */
    @Throws(IOException::class)
    fun file(pathName: String?, charset: java.nio.charset.Charset?, vararg lines: String?): Path {
        val file: Path = newFile(pathName)
        FileSystemUtils.writeContent(file, charset, linesAsString(*lines))
        file.setLastModifiedTime(-1L)
        return file
    }

    /**
     * Create a scratch file in the given filesystem, with the given pathName, consisting of a set of
     * lines. The method returns a Path instance for the scratch file.
     */
    @Throws(IOException::class)
    fun file(pathName: String?, content: ByteArray?): Path {
        val file: Path = newFile(pathName)
        FileSystemUtils.writeContent(file, content)
        return file
    }

    @Throws(IOException::class)
    fun readFile(pathName: String?): String {
        resolve(pathName).getInputStream().use { `in` ->
            return String(com.google.common.io.ByteStreams.toByteArray(`in`), DEFAULT_CHARSET)
        }
    }

    /** Like `scratch.file`, but the lines are added to the end if the file already exists.  */
    @Throws(IOException::class)
    fun appendFile(pathName: String?, lines: MutableCollection<String?>): Path {
        return appendFile(pathName, *lines.toTypedArray<String?>())
    }

    /** Like `scratch.file`, but the lines are added to the end if the file already exists.  */
    @Throws(IOException::class)
    fun appendFile(pathName: String?, vararg lines: String?): Path {
        return appendFile(pathName, DEFAULT_CHARSET, *lines)
    }

    /** Like `scratch.file`, but the lines are added to the end if the file already exists.  */
    @Throws(IOException::class)
    fun appendFile(pathName: String?, charset: java.nio.charset.Charset?, vararg lines: String?): Path {
        val path: Path = resolve(pathName)

        val content: java.lang.StringBuilder = java.lang.StringBuilder()
        if (path.exists()) {
            content.append(readFile(pathName))
            content.append("\n")
        }
        content.append(linesAsString(*lines))

        return overwriteFile(pathName, content.toString())
    }

    /**
     * Like `scratch.file`, but the file is first deleted if it already
     * exists.
     */
    @Throws(IOException::class)
    fun overwriteFile(pathName: String?, lines: MutableCollection<String?>): Path {
        return overwriteFile(pathName, *lines.toTypedArray<String?>())
    }

    /**
     * Like `scratch.file`, but the file is first deleted if it already
     * exists.
     */
    @Throws(IOException::class)
    fun overwriteFile(pathName: String?, vararg lines: String?): Path {
        return overwriteFile(pathName, DEFAULT_CHARSET, *lines)
    }

    /**
     * Like `scratch.file`, but the file is first deleted if it already
     * exists.
     */
    @Throws(IOException::class)
    fun overwriteFile(pathName: String?, charset: java.nio.charset.Charset?, vararg lines: String?): Path {
        val oldFile: Path = resolve(pathName)
        val newMTime: Long = if (oldFile.exists()) oldFile.getLastModifiedTime() + 1 else -1
        oldFile.delete()
        val newFile: Path = file(pathName, charset, *lines)
        newFile.setLastModifiedTime(newMTime)
        return newFile
    }

    /**
     * Deletes the specified scratch file, using the same specification as [Path.delete].
     */
    @Throws(IOException::class)
    fun deleteFile(pathName: String?): Boolean {
        return resolve(pathName).delete()
    }

    /** Creates a new scratch file, ensuring parents exist.  */
    @Throws(IOException::class)
    private fun newFile(pathName: String?): Path {
        val file: Path = resolve(pathName)
        val parentDir: Path = file.getParentDirectory()
        if (!parentDir.exists()) {
            parentDir.createDirectoryAndParents()
        }
        if (file.exists()) {
            throw IOException(
                "Could not create scratch file (file exists) "
                        + pathName
            )
        }
        return file
    }

    @Throws(IOException::class)
    fun copyFile(sourceFile: String?, destFile: String?) {
        val contents = readFile(sourceFile)
        overwriteFile(destFile, contents)
    }

    companion object {
        private val DEFAULT_CHARSET: java.nio.charset.Charset = java.nio.charset.StandardCharsets.ISO_8859_1

        /**
         * Converts the lines into a String with linebreaks. Useful for creating
         * in-memory input for a file, for example.
         */
        private fun linesAsString(vararg lines: String?): String {
            val builder: java.lang.StringBuilder = java.lang.StringBuilder()
            for (line in lines) {
                builder.append(line)
                builder.append('\n')
            }
            return builder.toString()
        }
    }
}
