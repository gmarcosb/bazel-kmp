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
package com.google.devtools.build.lib.blackbox.tests.workspace

import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkBaseExternalContext.readFile
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.BuilderRunner
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.nio.file.Path
import java.util.stream.Collectors

/** Utility class for helping JUnit black box workspace tests.  */
object WorkspaceTestUtils {
    private const val PATH = "PATH"

    /**
     * Create the BuilderRunner for workspace tests without MSYS/MINGW dependency on Windows.
     * 
     * @param context - BlackBoxTestContext instance
     * @return BuilderRunner for running Bazel
     */
    fun bazel(context: BlackBoxTestContext): BuilderRunner {
        if (com.google.devtools.build.lib.util.OS.WINDOWS != com.google.devtools.build.lib.util.OS.getCurrent()) {
            return context.bazel()
        }
        return context
            .bazel()
            .withEnv("BAZEL_SH", "C:/foo/bar/usr/bin/bash.exe")
            .withEnv(PATH, removeMsysFromPath(java.lang.System.getenv(PATH)))
    }

    private fun removeMsysFromPath(path: String): String? {
        if (com.google.devtools.build.lib.util.OS.WINDOWS != com.google.devtools.build.lib.util.OS.getCurrent()) {
            return path
        }
        val parts: Array<String?> = path.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return java.util.Arrays.stream<String?>(parts).filter { s: String? -> !s.contains("msys") }
            .collect(Collectors.joining(";"))
    }

    /**
     * Assert that the file exists and contains exactly one line with the certain text.
     * 
     * @param path - path to file
     * @param lines - lines of text expected in the file
     * @throws IOException if any file operation failed
     */
    @Throws(IOException::class)
    fun assertLinesExactly(path: Path, vararg lines: String?) {
        com.google.common.base.Preconditions.checkState(lines.size > 0)

        Truth.assertThat(java.nio.file.Files.exists(path)).isTrue()
        val realLines: MutableList<String?> = com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(path)
        Truth.assertThat(realLines.size).isEqualTo(lines.size)
        for (i in lines.indices) {
            Truth.assertThat(realLines.get(i)).isEqualTo(lines[i])
        }
    }
}
