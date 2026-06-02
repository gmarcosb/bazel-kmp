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
import com.google.devtools.build.lib.blackbox.tests.workspace.RepoWithRuleWritingTextGenerator
import com.google.devtools.build.lib.vfs.Path
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path

/** Test for [RepoWithRuleWritingTextGenerator].  */
@RunWith(JUnit4::class)
class RepoWithRuleWritingTextGeneratorTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun testOutput() {
        val directory: Path? = java.nio.file.Files.createTempDirectory("test_repo_output")
        try {
            val generator: RepoWithRuleWritingTextGenerator = RepoWithRuleWritingTextGenerator(directory)

            val repository: Path = generator.setupRepository()
            Truth.assertThat(repository).isEqualTo(directory)
            Truth.assertThat(java.nio.file.Files.exists(repository)).isTrue()

            val buildText: String = java.lang.String.join(
                "\n",
                com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(repository.resolve("BUILD"))
            )
            Truth.assertThat(buildText).isEqualTo(BUILD_TEXT)
            Truth.assertThat(generator.getPkgTarTarget()).isEqualTo("pkg_tar_write_text")
        } finally {
            com.google.devtools.build.lib.blackbox.framework.PathUtils.deleteTree(directory)
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testOutputWithParameters() {
        val directory: Path? = java.nio.file.Files.createTempDirectory("test_repo_output_with_parameters")
        try {
            val generator: RepoWithRuleWritingTextGenerator =
                RepoWithRuleWritingTextGenerator(directory)
                    .withTarget("target")
                    .withOutFile("file")
                    .withOutputText("text")

            val repository: Path = generator.setupRepository()
            Truth.assertThat(repository).isEqualTo(directory)
            Truth.assertThat(java.nio.file.Files.exists(repository)).isTrue()

            val buildText: String = java.lang.String.join(
                "\n",
                com.google.devtools.build.lib.blackbox.framework.PathUtils.readFile(repository.resolve("BUILD"))
            )
            Truth.assertThat(buildText).isEqualTo(BUILD_TEXT_PARAMS)
            Truth.assertThat(generator.getPkgTarTarget()).isEqualTo("pkg_tar_target")
        } finally {
            com.google.devtools.build.lib.blackbox.framework.PathUtils.deleteTree(directory)
        }
    }

    @org.junit.Test
    fun testStaticMethods() {
        val loadText: String? = RepoWithRuleWritingTextGenerator.loadRule("@my_repo")
        Truth.assertThat(loadText).isEqualTo("load('@my_repo//:helper.bzl', 'write_to_file')")

        val callText: String? =
            RepoWithRuleWritingTextGenerator.callRule("my_target", "filename", "out_text")
        Truth.assertThat(callText)
            .isEqualTo("write_to_file(name = 'my_target', filename = 'filename', text ='out_text')")
    }

    companion object {
        private val BUILD_TEXT = ("load(\"@bazel_tools//tools/build_defs/pkg:pkg.bzl\", \"pkg_tar\")\n"
                + "load('//:helper.bzl', 'write_to_file')\n"
                + "write_to_file(name = 'write_text', filename = 'out', text ='HELLO')\n"
                + "pkg_tar(name = \"pkg_tar_write_text\", srcs = glob([\"*\"]),)")
        private val BUILD_TEXT_PARAMS = ("load(\"@bazel_tools//tools/build_defs/pkg:pkg.bzl\", \"pkg_tar\")\n"
                + "load('//:helper.bzl', 'write_to_file')\n"
                + "write_to_file(name = 'target', filename = 'file', text ='text')\n"
                + "pkg_tar(name = \"pkg_tar_target\", srcs = glob([\"*\"]),)")
    }
}
