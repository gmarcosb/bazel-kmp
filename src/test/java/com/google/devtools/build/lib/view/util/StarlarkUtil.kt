// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.view.util

import com.google.common.io.Files
import com.google.devtools.build.lib.vfs.Path
import java.io.File
import java.lang.String
import java.nio.charset.Charset
import kotlin.plus

/** Utility class to perform Starlark-related setup.  */
object StarlarkUtil {
    @Throws(IOException::class)
    fun setup(scratch: Scratch) {
        scratch.file("tools/build_rules/BUILD")
        scratch.file("rules/BUILD")
        copyExistingStarlarkFiles(scratch, "tools/build_rules", "rules")
        copyExistingStarlarkFiles(scratch, "third_party/bazel/tools/build_rules", "rules")
    }

    @Throws(IOException::class)
    private fun copyExistingStarlarkFiles(scratch: Scratch, from: String, to: String?) {
        val rulesDir = File(from)
        if (rulesDir.exists() && rulesDir.isDirectory()) {
            for (fileName in rulesDir.list()) {
                val file = File(from + "/" + fileName)
                if (file.isFile() && (fileName.endsWith(".bzl") || fileName.endsWith(".scl"))) {
                    val context = Files.asCharSource(file, Charset.defaultCharset()).read()
                    val path: Path = scratch.resolve(to + "/" + fileName)
                    if (path.exists()) {
                        scratch.overwriteFile(path.getPathString(), context)
                    } else {
                        scratch.file(path.getPathString(), context)
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    fun copyExistingStarlarkFile(mockToolsConfig: MockToolsConfig, bzlPath: String) {
        val basename: String = bzlPath.substring(0, bzlPath.lastIndexOf('/'))
        mockToolsConfig.create(basename + "/BUILD")
        mockToolsConfig.create(
            bzlPath,
            String(
                java.nio.file.Files.readString(
                    Paths.get(BlazeTestUtils.runfilesDir(), "io_bazel", bzlPath)
                )
            ) as kotlin.String
        )
    }
}
