// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs

/** Unit tests for the [WorkerSpawnStrategy].  */
@RunWith(JUnit4::class)
class WorkerSpawnStrategyTest {
    @org.junit.Rule
    var folder: TemporaryFolder = TemporaryFolder()
    private val fs: FileSystem = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expandArgumentsPreservesEmptyLines() {
        val flagfile: java.io.File = folder.newFile("flagfile.txt")

        val flags: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--hello", "", "--world")

        PrintWriter(
            java.nio.file.Files.newBufferedWriter(
                flagfile.toPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).use { pw ->
            flags.forEach(java.util.function.Consumer { x: String? -> pw.println(x) })
        }
        val path: Path = fs.getPath(flagfile.getAbsolutePath())
        val requestBuilder: WorkRequest.Builder = WorkRequest.newBuilder()
        val inputs: SandboxInputs =
            SandboxInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(PathFragment.create("flagfile.txt"), path),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        WorkerSpawnRunner.expandArgument(inputs, "@flagfile.txt", requestBuilder)

        assertThat(requestBuilder.getArgumentsList()).containsExactlyElementsIn(flags)
    }
}
