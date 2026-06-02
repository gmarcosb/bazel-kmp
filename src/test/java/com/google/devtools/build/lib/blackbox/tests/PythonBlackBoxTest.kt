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
package com.google.devtools.build.lib.blackbox.tests

import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.blackbox.bazel.PythonToolsSetup
import com.google.devtools.build.lib.blackbox.framework.BuilderRunner
import com.google.devtools.build.lib.blackbox.framework.ProcessResult
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup
import com.google.devtools.build.lib.blackbox.junit.AbstractBlackBoxTest
import com.google.devtools.build.lib.vfs.Path
import java.io.IOException
import java.nio.file.Path

/** End to end tests for building and running Python targets.  */
class PythonBlackBoxTest : AbstractBlackBoxTest() {
    val additionalTools: com.google.common.collect.ImmutableList<ToolsSetup?>
        get() = com.google.common.collect.ImmutableList.of<ToolsSetup?>((PythonToolsSetup()))

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileAndRunHelloWorldStub() {
        context().write(
            AbstractBlackBoxTest.Companion.MODULE_DOT_BAZEL,
            "bazel_dep(name = 'rules_python', version = '1.4.1')"
        )

        writeHelloWorldFiles()

        val bazel: BuilderRunner = context().bazel()
        bazel.build("//python/hello:hello")

        val result: ProcessResult = context().runBuiltBinary(bazel, "python/hello/hello", -1)
        Truth.assertThat(result.outString()).isEqualTo(HELLO)

        val binaryPath: Path = context().resolveBinPath(bazel, "python/hello/hello.par")
        Truth.assertThat(java.nio.file.Files.exists(binaryPath)).isFalse()
    }

    @Throws(IOException::class)
    private fun writeHelloWorldFiles() {
        context()
            .write(
                "python/hello/BUILD",
                "load('@rules_python//python:py_binary.bzl', 'py_binary')",
                "py_binary(name = 'hello', srcs = ['hello.py'])"
            )
        context().write("python/hello/hello.py", String.format("print ('%s')", HELLO))
    }

    companion object {
        private const val HELLO = "Hello, World!"
    }
}
