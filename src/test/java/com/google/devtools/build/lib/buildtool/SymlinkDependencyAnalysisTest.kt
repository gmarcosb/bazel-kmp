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

import com.google.devtools.build.lib.vfs.Path

/**
 * Test that symbolic links are handled correctly by the dependency analysis:
 * that changes of the link target cause a rebuild.
 */
@RunWith(JUnit4::class)
class SymlinkDependencyAnalysisTest : BuildIntegrationTestCase() {
    @Throws(java.lang.Exception::class)
    private fun buildAndReturnOutput(): String? {
        buildTarget("//symlink")
        return readContentAsLatin1String(com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//symlink:out")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlinkTargetChangeCausesRebuild() {
        val buildFile: Path =
            write(
                "symlink/BUILD",
                """
            genrule(
                name = "symlink",
                srcs = ["link"],
                outs = ["out"],
                cmd = "/bin/cp ${'$'}(location link) ${'$'}(location out)",
            )
            
            """.trimIndent()
            )
        val target: Path = write("symlink/target", "foo")

        val link: Path = buildFile.getParentDirectory().getChild("link")
        link.createSymbolicLink(target)

        target.setLastModifiedTime(10000)
        Truth.assertThat(buildAndReturnOutput()).isEqualTo("foo\n") // first build

        write("symlink/target", "bar")
        target.setLastModifiedTime(20000)
        Truth.assertThat(buildAndReturnOutput()).isEqualTo("bar\n") // should do a rebuild
    }
}
