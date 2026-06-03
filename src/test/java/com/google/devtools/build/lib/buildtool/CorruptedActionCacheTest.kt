// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.util.LoggingUtil

/** Validates corrupted action cache behavior.  */
@RunWith(JUnit4::class)
class CorruptedActionCacheTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCorruptionActionCacheErrorMessage() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["out"],
            cmd = "echo 123 >${'$'}(OUTS)",
        )
        
        """.trimIndent()
        )

        buildTarget("//foo:foo")

        // Remove caches from memory while preserving files on disk.
        val outputBase: Path = getCommandEnvironment().getOutputBase()
        outputBase.getChild("action_cache").renameTo(outputBase.getChild("action_cache_temp"))
        getCommandEnvironment().getBlazeWorkspace().clearCaches()
        outputBase.getChild("action_cache_temp").renameTo(outputBase.getChild("action_cache"))

        // Corrupt one of the data files by deleting the last byte.
        val corruptedPath: Path? = outputBase.getChild("action_cache").getChild("filename_index.blaze")
        val content: ByteArray = FileSystemUtils.readContent(corruptedPath)
        FileSystemUtils.writeContent(corruptedPath, content.copyOf(content.size - 1))

        // Don't crash when we try to log a warning message about the corrupt cache.
        LoggingUtil.installRemoteLoggerForTesting(null)

        // Build should still succeed but there should be an action cache warning message.
        assertThat(buildTarget("//foo:foo").getSuccess()).isTrue()
        Truth.assertThat(events.warnings()).hasSize(1)
        events.assertContainsWarning("Error during action cache initialization")
        events.assertContainsWarning("Data may be incomplete, potentially causing rebuilds")
    }
}
