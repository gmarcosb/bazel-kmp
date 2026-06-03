// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows.util

import com.google.common.base.Strings
import com.google.devtools.build.lib.vfs.FileSystem
import org.junit.Assert
import java.io.File
import java.nio.file.Files

/** Utilities for running Java tests on Windows.  */
class WindowsTestUtil(
    /** A path where temp files can be created. It is NOT owned by this class.  */
    private val scratchRoot: String
) {
    /**
     * Create directory junctions then assert their existence.
     * 
     * 
     * Each key in the map is a junction path, relative to [.scratchRoot]. These are the link
     * names.
     * 
     * 
     * Each value in the map is a directory or junction path, also relative to [ ][.scratchRoot]. These are the link targets.
     */
    @Throws(Exception::class)
    fun createJunctions(links: MutableMap<String?, String?>) {
        for (e in links.entries) {
            WindowsFileOperations.createJunction(
                scratchRoot + "/" + e.key, scratchRoot + "/" + e.value
            )
        }
    }

    /**
     * Create symbolic links.
     * 
     * 
     * Each key in the map is a symlink path relative to [.scratchRoot]. These are the link
     * names.
     * 
     * 
     * Each value in the map is a file path relative to [.scratchRoot]. These are the link
     * targets.
     */
    @Throws(Exception::class)
    fun createSymlinks(links: MutableMap<String?, String?>) {
        for (entry in links.entries) {
            WindowsFileOperations.createSymlink(
                scratchRoot + "/" + entry.key, scratchRoot + "/" + entry.value
            )
        }
    }

    /** Delete everything under [.scratchRoot]/path.  */
    @Throws(IOException::class)
    fun deleteAllUnder(path: String) {
        var path = path
        if (Strings.isNullOrEmpty(path)) {
            path = scratchRoot
        } else {
            path = scratchRoot + "\\" + path
        }
        if (File(path).exists()) {
            runCommand("cmd.exe /c rd /s /q \"" + path + "\"")
        }
    }

    /** Create a directory under `path`, relative to [.scratchRoot].  */
    @Throws(IOException::class)
    fun scratchDir(path: String): Path? {
        return Files.createDirectories(File(scratchRoot, path).toPath())
    }

    /** Create a file with the given contents under `path`, relative to [.scratchRoot].  */
    @Throws(IOException::class)
    fun scratchFile(path: String, vararg contents: String?): Path? {
        val fd = File(scratchRoot, path)
        Files.createDirectories(fd.toPath().getParent())
        FileWriter(fd).use { w ->
            for (line in contents) {
                w.write(line)
                w.write('\n'.code)
            }
        }
        return fd.toPath()
    }

    @Throws(IOException::class)
    fun createVfsPath(fs: FileSystem, path: String?): Path {
        return fs.getPath(scratchRoot + "/" + path)
    }

    companion object {
        /** Run a Command Prompt command.  */
        @Throws(IOException::class)
        fun runCommand(cmd: String?) {
            val p = Runtime.getRuntime().exec(cmd)
            try {
                // Wait no more than 5 seconds to create all junctions.
                p.waitFor(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Assert.fail("Failed to execute command; cmd: " + cmd)
            }
            Truth.assertWithMessage("Command failed: " + cmd).that(p.exitValue()).isEqualTo(0)
        }
    }
}
