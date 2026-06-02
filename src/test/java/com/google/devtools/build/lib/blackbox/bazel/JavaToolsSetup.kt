// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.blackbox.bazel

import com.google.common.truth.Truth
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.nio.file.Path
import java.util.stream.Collectors

/** Setup for Bazel Java tools  */
class JavaToolsSetup : ToolsSetup {
    @Throws(IOException::class)
    override fun setup(context: BlackBoxTestContext) {
        val jdkDirectory: Path = context.getWorkDir().resolve("tools/jdk")
        val buildFiles: MutableList<Path?> =
            java.nio.file.Files.list(jdkDirectory)
                .filter { path: Path? -> path.getFileName().toString().startsWith("BUILD.") }
                .collect(Collectors.toList())
        Truth.assertThat(buildFiles.size).isAtMost(1)
        if (!buildFiles.isEmpty()) {
            val buildFile: Path = jdkDirectory.resolve("BUILD")
            java.nio.file.Files.copy(buildFiles.get(0), buildFile)
            Truth.assertThat(buildFile.toFile().setWritable(true)).isTrue()
        }
    }
}
