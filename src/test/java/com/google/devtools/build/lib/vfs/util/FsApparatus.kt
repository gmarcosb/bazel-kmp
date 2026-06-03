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
package com.google.devtools.build.lib.vfs.util

import com.google.devtools.build.lib.util.StringUtilities

/**
 * Base class for a testing apparatus for a scratch filesystem.
 */
class FsApparatus {
    /* ---------- State that the apparatus initializes / operates on --------- */
    protected var fileSystem: FileSystem? = null
    protected var workingDir: Path? = null

    private constructor() {
        fileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        workingDir = fileSystem.getPath("/")
    }

    constructor(fs: FileSystem?, cwd: Path?) {
        fileSystem = fs
        workingDir = cwd
    }

    constructor(fs: FileSystem) {
        fileSystem = fs
        workingDir = fs.getPath("/")
    }

    fun fs(): FileSystem? {
        return fileSystem
    }

    /**
     * Creates a scratch file in the scratch filesystem with the given `pathName` with
     * `lines` being its content. The method returns a Path instance for the scratch file.
     */
    @Throws(IOException::class)
    fun file(pathName: String?, vararg lines: String?): Path {
        val file: Path = path(pathName)
        val parentDir: Path = file.getParentDirectory()
        if (!parentDir.exists()) {
            parentDir.createDirectoryAndParents()
        }
        if (file.exists()) {
            throw IOException(
                "Could not create scratch file (file exists) "
                        + file
            )
        }
        val fileContent: String? = StringUtilities.joinLines(lines)
        FileSystemUtils.writeContentAsLatin1(file, fileContent)
        return file
    }

    /**
     * Creates or recreates a scratch file just like [.file] but tolerating an existing file.
     */
    @Throws(IOException::class)
    fun overwriteFile(pathName: String?, vararg lines: String?): Path {
        try {
            path(pathName).delete()
        } catch (e: FileNotFoundException) {
            // Ignored.
        }
        return file(pathName, *lines)
    }

    /**
     * Initializes this apparatus (if it hasn't been initialized yet), and creates
     * a directory in the scratch filesystem, with the given `pathName`.
     * Creates parent directories as necessary.
     */
    @Throws(IOException::class)
    fun dir(pathName: String?): Path {
        val dir: Path = path(pathName)
        if (!dir.exists()) {
            dir.createDirectoryAndParents()
        }
        if (!dir.isDirectory()) {
            throw IOException("Exists, but is not a directory: " + dir)
        }
        return dir
    }

    /**
     * Resolves `pathName` relative to the working directory. Note that this will not create any
     * entity in the filesystem; i.e., the file that the object is describing may not exist in the
     * filesystem.
     */
    fun path(pathName: String?): Path {
        return workingDir.getRelative(pathName)
    }

    /**
     * Create a fresh directory in the system temporary directory, instead of the
     * testing directory provided by the testing framework. This path is usually
     * shorter than a path starting with TestUtil.getTmpDir(). We care about the
     * length because of the path length restriction for Unix local socket files.
     * 
     * Clients are responsible for deleting the directory after tests.
     */
    @Throws(IOException::class)
    fun createUnixTempDir(): Path {
        if (fileSystem is InMemoryFileSystem) {
            throw IOException(
                "Can not create Unix temporary directories in "
                        + "an in-memory file system"
            )
        }
        val file: java.io.File = java.io.File.createTempFile("scratch", "tmp")
        val path: Path = fileSystem.getPath(file.getAbsolutePath())
        path.delete()
        path.createDirectory()
        return path
    }

    companion object {
        fun newInMemory(): FsApparatus {
            return FsApparatus()
        }

        // TestUtil.getTmpDir is slow, so cache the result here
        private val TMP_DIR: String? =
            java.io.File(com.google.devtools.build.lib.testutil.TestUtils.tmpDir(), "bs").toString()


        /**
         * When using a Native file system, absolute paths will be treated as absolute paths on the unix
         * file system, as opposed to paths relative to the backing temp directory. So for simplicity,
         * you ought to only use relative paths for FsApparatus#file, FsApparatus#dir, and
         * FsApparatus#path. Otherwise, be aware of the following issue
         * 
         * Path p1 = scratch.path(...);
         * Path p2 = scratch.path(p1.getPathString());
         * 
         * We'd like the invariant that p1.equals(p2) regardless if scratch is in-memory or not, but this
         * does not hold with our usage of Unix filesystems.
         */
        fun newNative(): FsApparatus {
            val fs: FileSystem = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()
            val wd: Path = fs.getPath(TMP_DIR)

            try {
                wd.deleteTree()
            } catch (e: IOException) {
                throw java.lang.AssertionError(e.message)
            }

            return FsApparatus(fs, wd)
        }
    }
}
