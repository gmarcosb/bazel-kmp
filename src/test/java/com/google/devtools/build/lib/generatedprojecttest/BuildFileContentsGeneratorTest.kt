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
package com.google.devtools.build.lib.generatedprojecttest

import com.google.devtools.build.lib.vfs.FileSystemUtils

/**
 * Tests for `BuildFileContentsGenerator`.
 */
@RunWith(JUnit4::class)
class BuildFileContentsGeneratorTest {
    /**
     * The generator being tested.
     */
    private val generator: BuildFileContentsGenerator = BuildFileContentsGenerator()

    @org.junit.Test
    @Throws(java.lang.IllegalStateException::class)
    fun testSetDefaultPackageVisibility() {
        generator.setDefaultPackageVisibility("//visibility:private")
        Truth.assertThat(generator.getContents())
            .startsWith("package(default_visibility = ['//visibility:private'])")
    }

    @org.junit.Test
    @Throws(java.lang.IllegalStateException::class)
    fun defaultPackageVisibilityIsAddedToStartOfBuildFile() {
        generator.addRule(BuildRuleBuilder("cc_library", generator.uniqueRuleName()))
        generator.setDefaultPackageVisibility("//visibility:private")
        Truth.assertThat(generator.getContents())
            .startsWith("package(default_visibility = ['//visibility:private'])")
    }

    @org.junit.Test
    @Throws(java.lang.IllegalStateException::class)
    fun defaultPackageVisibilityDefaultsToPublic() {
        generator.addRule(BuildRuleBuilder("cc_library", generator.uniqueRuleName()))
        Truth.assertThat(generator.getContents())
            .startsWith("package(default_visibility = ['//visibility:public'])")
    }

    @org.junit.Test
    @Throws(java.lang.IllegalStateException::class)
    fun settingDefaultPackageVisibilityTwiceCausesException() {
        generator.setDefaultPackageVisibility("//visibility:private")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { generator.setDefaultPackageVisibility("//visibility:private") })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testContentsSyntax() {
        // TODO(blaze-team): (2012) write various simple generator examples to test the generated syntax
        val builder: TestProjectBuilder = TestProjectBuilder("tmp")
        val generator: BuildFileContentsGenerator = BuildFileContentsGenerator()
        builder.createFileInDir("/a", "BUILD", generator)
        val scratch: Scratch = builder.getScratch()
        val path: Path = scratch.resolve("/tmp/a/BUILD")

        val bytes: ByteArray? = FileSystemUtils.readWithKnownFileSize(path, path.getFileSize())
        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLatin1(bytes, path.toString())
        val file: net.starlark.java.syntax.StarlarkFile = net.starlark.java.syntax.StarlarkFile.parse(input)
        for (error in file.errors()) {
            java.lang.System.err.println(error)
        }
        Truth.assertThat(file.ok()).isTrue()
    }
}
