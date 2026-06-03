// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Tests for `native.glob` function.  */
@RunWith(JUnit4::class)
class NativeGlobTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun glob_simple() {
        makeFile("test/starlark/file1.txt")
        makeFile("test/starlark/file2.txt")
        makeFile("test/starlark/file3.txt")

        makeGlobFilegroup("test/starlark/BUILD", "glob(['*'])")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:BUILD",
                "//test/starlark:file1.txt",
                "//test/starlark:file2.txt",
                "//test/starlark:file3.txt"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun glob_not_empty() {
        makeGlobFilegroup("test/starlark/BUILD", "glob(['foo*'], allow_empty=False)")

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    assertAttrLabelList(
                        "//test/starlark:files",
                        "srcs",
                        com.google.common.collect.ImmutableList.of<String?>()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("allow_empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun glob_simple_subdirs() {
        makeFile("test/starlark/sub/file1.txt")
        makeFile("test/starlark/sub2/file2.txt")
        makeFile("test/starlark/sub3/file3.txt")

        makeGlobFilegroup("test/starlark/BUILD", "glob(['**'])")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:BUILD",
                "//test/starlark:sub/file1.txt",
                "//test/starlark:sub2/file2.txt",
                "//test/starlark:sub3/file3.txt"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun glob_incremental() {
        makeFile("test/starlark/file1.txt")
        makeGlobFilegroup("test/starlark/BUILD", "glob(['**'])")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark:BUILD", "//test/starlark:file1.txt")
        )

        scratch.file("test/starlark/file2.txt")
        scratch.file("test/starlark/sub/subfile3.txt")

        // Poke SkyFrame to tell it what changed.
        invalidateSkyFrameFiles(
            "test/starlark", "test/starlark/file2.txt", "test/starlark/sub/subfile3.txt"
        )

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark:BUILD",
                "//test/starlark:file1.txt",
                "//test/starlark:file2.txt",
                "//test/starlark:sub/subfile3.txt"
            )
        )
    }

    /**
     * Constructs a BUILD file containing a single rule with uses glob() to list files look for a rule
     * called :files in it.
     */
    @Throws(IOException::class)
    private fun makeGlobFilegroup(buildPath: String?, glob: String?) {
        scratch.file(buildPath, "filegroup(", "   name = 'files',", "   srcs = " + glob, ")")
    }

    @Throws(java.lang.Exception::class)
    private fun assertAttrLabelList(target: String?, attrName: String?, expectedLabels: MutableList<String?>) {
        val cfgTarget: ConfiguredTargetAndData = getConfiguredTargetAndData(target)
        assertThat(cfgTarget).isNotNull()

        val labels: com.google.common.collect.ImmutableList<Label?> =
            expectedLabels.stream().map<Label?> { label: String? -> this.makeLabel(label) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Label?>())

        val configuredAttributeMapper: ConfiguredAttributeMapper =
            getMapperFromConfiguredTargetAndTarget(cfgTarget)
        assertThat(configuredAttributeMapper.get(attrName, BuildType.LABEL_LIST))
            .containsExactlyElementsIn(labels)
    }

    private fun makeLabel(label: String?): Label? {
        try {
            return Label.parseCanonical(label)
        } catch (e: java.lang.Exception) {
            // Always fails the test.
            Truth.assertThat(e).isNull()
            return null
        }
    }

    @Throws(java.lang.Exception::class)
    private fun invalidateSkyFrameFiles(vararg files: String?) {
        val builder: ModifiedFileSet.Builder = ModifiedFileSet.builder()

        for (f in files) {
            builder.modify(PathFragment.create(f))
        }

        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, builder.build(), Root.fromPath(rootDirectory)
            )
    }

    @Throws(IOException::class)
    private fun makeFile(fileName: String?) {
        scratch.file(fileName, "Content: " + fileName)
    }
}
