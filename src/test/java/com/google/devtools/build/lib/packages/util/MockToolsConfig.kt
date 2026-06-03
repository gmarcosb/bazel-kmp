// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.util

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.util.FileSystems
import java.nio.file.Files

/** Configuration for the mock client setup that we use for testing.  */
class MockToolsConfig(rootDirectory: Path, realFileSystem: Boolean, runfilesDirectoryOpt: Path?) {
    private val rootDirectory: Path
    val isRealFileSystem: Boolean

    // Allow the injection of the runfiles directory where actual tools are found.
    // TestUtil.getRunfilesDir() caches the value of the "TEST_SRCDIR" system property, which makes
    // it impossible to change if it doesn't get set early in test configuration setup.
    private val runfilesDirectory: Path?

    constructor(rootDirectory: Path) : this(rootDirectory, false, null)

    constructor(rootDirectory: Path, realFileSystem: Boolean) : this(rootDirectory, realFileSystem, null)

    init {
        this.rootDirectory = rootDirectory
        this.isRealFileSystem = realFileSystem
        if (!realFileSystem) {
            this.runfilesDirectory = null
        } else if (runfilesDirectoryOpt == null) {
            // Turning the absolute path string from runfilesDir into a Path object.
            this.runfilesDirectory = rootDirectory.getRelative(BlazeTestUtils.runfilesDir())
        } else {
            this.runfilesDirectory = runfilesDirectoryOpt
        }
    }

    fun getPath(relativePath: String): Path {
        Preconditions.checkState(!relativePath.startsWith("/"), relativePath)
        return rootDirectory.getRelative(relativePath)
    }

    @Throws(IOException::class)
    fun create(relativePath: String, vararg lines: String?): Path {
        val path: Path = getPath(relativePath)
        if (!path.exists()) {
            FileSystemUtils.writeIsoLatin1(path, lines)
        } else if (lines.size > 0) {
            val existingContent = String(FileSystemUtils.readContentAsLatin1(path))

            val newContent = StringBuilder()
            for (line in lines) {
                newContent.append(line)
                newContent.append('\n')
            }

            if (newContent.toString().trim { it <= ' ' } != existingContent.trim { it <= ' ' }) {
                throw IOException(
                    ("Conflict: '"
                            + relativePath
                            + "':\n'"
                            + newContent
                            + "'\n vs \n'"
                            + existingContent
                            + "'")
                )
            }
        }
        return path
    }

    @Throws(IOException::class)
    fun overwrite(relativePath: String, vararg lines: String?): Path {
        val path: Path = getPath(relativePath)
        if (path.exists()) {
            path.deleteTree()
        }
        return create(relativePath, *lines)
    }

    @Throws(IOException::class)
    fun append(relativePath: String, vararg lines: String?): Path {
        require(relativePath != "WORKSPACE") { "DO NOT write into the WORKSPACE file in mocks. Use MODULE.bazel instead" }
        val path: Path = getPath(relativePath)
        if (!path.exists()) {
            return create(relativePath, *lines)
        }

        FileSystemUtils.appendIsoLatin1(path, lines)
        return path
    }

    /**
     * Links a tool into the workspace by creating a symbolic link to a real file. The target location
     * in the workspace uses the same relative path as the given path to the tool in the runfiles
     * tree. Use this if you do not need to rename or relocate the file, i.e., if the location in the
     * workspace and the runfiles tree matches. Otherwise use [.linkTool].
     * 
     * @param relativePath the relative path within the runfiles tree of the current test
     * @throws IOException
     */
    @Throws(IOException::class)
    fun linkTool(relativePath: String) {
        Preconditions.checkState(this.isRealFileSystem)
        linkTool(relativePath, relativePath)
    }

    /**
     * Links a tool into the workspace by creating a symbolic link to a real file.
     * 
     * @param relativePath the relative path within the runfiles tree of the current test
     * @param dest the relative path in the mock client
     * @throws IOException
     */
    @Throws(IOException::class)
    fun linkTool(relativePath: String?, dest: String) {
        Preconditions.checkState(this.isRealFileSystem)
        var target: Path = runfilesDirectory.getRelative(TestConstants.WORKSPACE_NAME + "/" + relativePath)
        if (!target.exists()) {
            // In some cases we run tests in a special client with a ../READONLY/ path where we may also
            // find the runfiles. Try that, too.
            val readOnlyClientPath: Path =
                getPath("../READONLY/" + TestConstants.WORKSPACE_NAME + "/" + relativePath)
            if (!readOnlyClientPath.exists()) {
                throw IOException("target does not exist " + target)
            } else {
                target = readOnlyClientPath
            }
        }
        val path: Path = getPath(dest)
        path.getParentDirectory().createDirectoryAndParents()
        path.delete()
        path.createSymbolicLink(target)
    }

    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun copyTool(relativePath: String?, dest: String? = relativePath) {
        val rlocationPath: PathFragment =
            PathFragment.create(TestConstants.WORKSPACE_NAME).getRelative(relativePath)
        copyTool(rlocationPath, dest)
    }

    @Throws(IOException::class)
    fun copyTool(rlocationPath: PathFragment, dest: String?) {
        // Tests are assumed to be run from the main repository only.
        val runfiles: Runfiles = Runfiles.preload().withSourceRepository("")
        val source: Path? =
            FileSystems.getNativeFileSystem()
                .getPath(runfiles.rlocation(rlocationPath.getPathString()))
        overwrite(dest!!, FileSystemUtils.readLinesAsLatin1(source).toArray({ _Dummy_.__Array__() }))
    }

    /**
     * Convenience method to copy multiple tools. Same as calling [.copyTool] for each
     * parameter.
     */
    @Throws(IOException::class)
    fun copyTools(vararg tools: String?) {
        for (tool in tools) {
            copyTool(tool)
        }
    }

    /**
     * Convenience method to link multiple tools. Same as calling [.linkTool] for each
     * parameter.
     */
    @Throws(IOException::class)
    fun linkTools(vararg tools: String) {
        for (tool in tools) {
            linkTool(tool)
        }
    }

    @Throws(IOException::class)
    fun copyDirectory(relativeDirPath: String?, depth: Int, useEmptyBuildFiles: Boolean) {
        val runfiles: Runfiles = Runfiles.preload().withSourceRepository("")
        copyDirectory(
            PathFragment.create(
                runfiles.rlocation(
                    PathFragment.create(TestConstants.WORKSPACE_NAME)
                        .getRelative(relativeDirPath)
                        .getPathString()
                )
            ),
            relativeDirPath,
            depth,
            useEmptyBuildFiles
        )
    }

    @Throws(IOException::class)
    fun copyDirectory(path: PathFragment, to: String?, depth: Int, useEmptyBuildFiles: Boolean) {
        // Tests are assumed to be run from the main repository only.
        val source: Path =
            FileSystems.getNativeFileSystem().getPath(path).getPathFile().toPath()
        Files.walk(source, depth).use { stream ->
            stream
                .filter { f: Path? -> f.toFile().isFile() }
                .map<String?> { f: Path? -> source.relativize(f).toString() }
                .filter { f: String? -> !f.isEmpty() }
                .forEach { f: String? ->
                    try {
                        if (f.endsWith("BUILD") && useEmptyBuildFiles) {
                            create(to + "/" + f)
                        } else {
                            copyTool(path.getRelative(f), to + "/" + f)
                        }
                    } catch (e: IOException) {
                        throw RuntimeException(e)
                    }
                }
        }
    }
}
