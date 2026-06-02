// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.blackbox.framework

import com.google.common.collect.Lists
import com.google.common.truth.Truth
import com.google.devtools.build.lib.util.OS
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

/** Helper class for work with java.nio.file.Path.  */
object PathUtils {
    /**
     * Recursively delete a directory in a supposition that it might be still used by external
     * process. (i.e. shutting down Bazel) Does not follow the symbolic links.
     */
    @Throws(IOException::class)
    fun deleteTreeWithRetry(directory: Path) {
        if (OS.WINDOWS == OS.getCurrent()) {
            // We are doing multiple attempts for deleting the directory, because on Windows
            // files, still opened by the external process, can not be deleted.
            // This behavior is a copy of shell integration tests behavior as of 2018/08/17.
            var attempt = 120
            while (true) {
                try {
                    deleteTree(directory)
                    return
                } catch (e: IOException) {
                    --attempt
                    if (attempt <= 0) {
                        throw e
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (e1: InterruptedException) {
                        // The user interrupted; propagate interruption status.
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } else {
            deleteTree(directory)
        }
    }

    /** Recursively delete a directory. Does not follow the symbolic links.  */
    @Throws(IOException::class)
    fun deleteTree(directory: Path) {
        if (Files.exists(directory)) {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path?>() {
                    @Throws(IOException::class)
                    override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                        try {
                            Files.delete(file)
                        } catch (e: AccessDeniedException) {
                            if (!file.toFile().setWritable(true)) {
                                throw IOException(
                                    String.format("Can not make %s writeable", file.toAbsolutePath().toString())
                                )
                            }
                            Files.delete(file)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    @Throws(IOException::class)
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
                        // The code here is necessary to address the problem of deleting
                        // junction (symlink) directories on Windows (which might point to non-existing
                        // directory already and by that reason can not be read as directory
                        // (Files.walkFileTree does not detect that it is a symlink on Windows)).
                        try {
                            if (Files.deleteIfExists(dir)) {
                                // `dir` was an empty directory or a junction (= directory symlink).
                                return FileVisitResult.SKIP_SUBTREE
                            }
                        } catch (e: DirectoryNotEmptyException) {
                            // `dir` was a non-empty directory. Proceed to visit its children.
                        }
                        return FileVisitResult.CONTINUE
                    }

                    @Throws(IOException::class)
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
        }
    }

    /**
     * Recursively copy the contents of source into the target; target does not have to exist, it can
     * exist. Does not follow the symbolic links.
     */
    @Throws(IOException::class)
    fun copyTree(source: Path, target: Path) {
        if (!Files.exists(source)) {
            throw IOException(
                String.format(
                    "Can not copy: source directory %s does not exist",
                    source.toAbsolutePath().toString()
                )
            )
        }
        Files.createDirectories(target)
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path?>() {
                var currentTarget: Path = target

                @Throws(IOException::class)
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (source != dir) {
                        currentTarget = currentTarget.resolve(dir.getFileName().toString())
                        Files.createDirectories(currentTarget)
                    }
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    Files.copy(file, currentTarget.resolve(file.getFileName().toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                    currentTarget = currentTarget.getParent()
                    return FileVisitResult.CONTINUE
                }
            })
    }

    /**
     * Creates the file under the `directory/subPath`. Will also create all subdirectories,
     * if they do not exist.
     * 
     * @param directory directory under which to create the subdirectories tree with a file
     * @param subPath subpath under `directory` under which file will be created
     * @return Path to created file
     * @throws IOException in case file or subdirectory can not be created
     */
    @Throws(IOException::class)
    fun createFile(directory: Path, subPath: String): Path {
        return createFile(resolve(directory, subPath))
    }

    /**
     * Creates the file in `path` location. Will also create all subdirectories, if they do
     * not exist.
     * 
     * @param path location where to create the file
     * @return Path to created file
     * @throws IOException in case file or subdirectory can not be created
     */
    @Throws(IOException::class)
    fun createFile(path: Path): Path {
        Files.createDirectories(path.getParent())
        if (Files.exists(path)) {
            return path
        }
        Files.createFile(path)
        return path
    }

    /**
     * Resolves the Path to the file or directory under the `
     * directory/parts[0]/parts[1]/.../parts[n]`.
     * 
     * @param directory root directory for resolve
     * @param parts parts of the path relative to directory
     * @return resolved Path
     */
    fun resolve(directory: Path, vararg parts: String): Path {
        var current = directory
        for (part in parts) {
            var part = part
            if (OS.WINDOWS == OS.getCurrent()) {
                part = part.replace('/', '\\')
            }
            current = current.resolve(part)
        }
        return current
    }

    /**
     * Reads the file under the `directory/parts[0]/parts[1]/.../parts[n]` using
     * ISO_8859_1.
     * 
     * @param directory root directory for resolve
     * @param parts parts of the path relative to directory
     * @return the List<String> of lines of the file
     * @throws IOException in case file can not be read
    </String> */
    @Throws(IOException::class)
    fun readFile(directory: Path, vararg parts: String): MutableList<String?> {
        return PathUtils.readFile(resolve(directory, *parts))
    }

    /**
     * Reads the `file` using ISO_8859_1.
     * 
     * @param file file to read
     * @return the List<String></String> of lines of the file
     * @throws IOException in case file can not be read
     */
    @Throws(IOException::class)
    fun readFile(file: Path): MutableList<String?> {
        return Files.readAllLines(file, StandardCharsets.ISO_8859_1)
    }

    /**
     * Writes the file in the `directory/subPath` location using ISO_8859_1. Overrides the
     * file if it exists, creates the file if it does not exist.
     * 
     * @param directory root directory, under which the subtree with the file is created
     * @param subPath path under `directory`, under which the file is created
     * @param lines lines to be written
     * @return Path to created file
     * @throws IOException in case file can not be written
     */
    @Throws(IOException::class)
    fun writeFileInDir(directory: Path, subPath: String, vararg lines: String?): Path {
        return writeFile(resolve(directory, subPath), *lines)
    }

    /**
     * Writes the file in the `directory/subPath` location using ISO_8859_1. Overrides the
     * file if it exists, creates the file if it does not exist.
     * 
     * @param directory root directory, under which the subtree with the file is created
     * @param subPath path under `directory`, under which the file is created
     * @param lines lines to be written
     * @return Path to created file
     * @throws IOException in case file can not be written
     */
    @Throws(IOException::class)
    fun writeFileInDir(directory: Path, subPath: String, lines: MutableList<String?>): Path {
        return writeFile(resolve(directory, subPath), lines)
    }

    /**
     * Writes the file in the `path` location using ISO_8859_1. Overrides the file if it
     * exists, creates the file if it does not exist.
     * 
     * @param path location where to write the file
     * @param lines lines to be written
     * @throws IOException in case file can not be written
     */
    @Throws(IOException::class)
    fun writeFile(path: Path, vararg lines: String?): Path {
        Files.createDirectories(path.getParent())
        return Files.write(path, Lists.newArrayList<String?>(*lines), StandardCharsets.ISO_8859_1)
    }

    /**
     * Writes the file in the `path` location using ISO_8859_1. Overrides the file if it
     * exists, creates the file if it does not exist.
     * 
     * @param path location where to write the file
     * @param lines lines to be written
     * @throws IOException in case file can not be written
     */
    @Throws(IOException::class)
    fun writeFile(path: Path, lines: MutableList<String?>): Path {
        Files.createDirectories(path.getParent())
        return Files.write(path, lines, StandardCharsets.ISO_8859_1)
    }

    /**
     * Writes the BUILD file under `directory` using ISO_8859_1. Overrides the file if it
     * exists, creates the file if it does not exist.
     * 
     * @param directory directory to write BUILD file under
     * @param lines lines to be written
     * @throws IOException in case file can not be written
     */
    @Throws(IOException::class)
    fun writeBuild(directory: Path, vararg lines: String?) {
        val buildFile = createFile(directory, "BUILD")
        writeFile(buildFile, *lines)
    }

    /**
     * Replaces the symlink file with the contents of the file it refers to.
     * 
     * @param path Path to file that will be replaced. Must be a symlink.
     * @throws IOException if files can not be read or written
     */
    @Throws(IOException::class)
    fun replaceWithSymlinkContents(path: Path) {
        Truth.assertThat(Files.isSymbolicLink(path)).isTrue()
        val target = Files.readSymbolicLink(path)
        Files.delete(path)
        Files.write(path, Files.readAllBytes(target))
    }

    /**
     * Appends `lines` of text to the `path`.
     * 
     * @param path path of the file to be appended to
     * @param lines lines of the text
     * @throws IOException if file can not be appended to
     */
    @Throws(IOException::class)
    fun append(path: Path, vararg lines: String?) {
        Files.write(path, Arrays.asList<String?>(*lines), StandardOpenOption.APPEND)
    }

    /**
     * Make a file or directory tree writable.
     * 
     * 
     * If the path is a directory, make all files under it (and all of its subdirectories)
     * writable.
     * 
     * @param path file or directory to make writable
     */
    @Throws(IOException::class)
    fun setTreeWritable(path: Path) {
        if (!Files.exists(path)) {
            throw IOException(
                String.format(
                    "Can not recursively modify files inside %s: directory does not exist",
                    path.toAbsolutePath().toString()
                )
            )
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path?>() {
                @Throws(IOException::class)
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (!dir.toFile().canWrite() && !dir.toFile().setWritable(true)) {
                        throw IOException(
                            String.format("Can not make %s writeable", dir.toAbsolutePath().toString())
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                @Throws(IOException::class)
                override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                    if (!file.toFile().setWritable(true)) {
                        throw IOException(
                            String.format("Can not make %s writeable", file.toAbsolutePath().toString())
                        )
                    }
                    return FileVisitResult.CONTINUE
                }
            })
    }

    /**
     * Returns the string to be used to refer to passed path in the Starlark file or directory. For
     * Windows, we need to use forward slashes, so on ecan not use the standard Path#toString().
     * 
     * @param path the path to file
     * @return the string to use in Starlark file to point to passed path
     */
    fun pathForStarlarkFile(path: Path): String {
        if (OS.WINDOWS == OS.getCurrent()) {
            return path.toString().replace("\\", "/")
        }
        return path.toString()
    }

    /**
     * Returns the file:///... URI to the passed path. Ensures the 'file:' is followed by three
     * forward slahes on all platforms.
     * 
     * @param path path to refer to
     * @return file:///... URI to the passed path
     */
    fun pathToFileURI(path: Path): String {
        if (OS.WINDOWS == OS.getCurrent()) {
            return "file:///" + path.toString().replace("\\", "/")
        }
        return "file://" + path.toString()
    }
}
