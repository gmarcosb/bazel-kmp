// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [SourceUrlMapper].  */
@RunWith(JUnit4::class)
class SourceUrlMapperTest {
    var mapper: SourceUrlMapper = SourceUrlMapper(
        "https://example.com/",
        "/tmp/io_bazel",
        com.google.common.collect.ImmutableMap.of<K?, V?>(
            "@//",
            "https://example.com/",
            "@_builtins//",
            "https://example.com/src/main/starlark/builtins_bzl/"
        )
    )

    @org.junit.Test
    fun urlOfFile() {
        assertThat(mapper.urlOfFile(java.io.File("/tmp/io_bazel/src/FooBar.java")))
            .isEqualTo("https://example.com/src/FooBar.java")
    }

    @org.junit.Test
    fun urlOfFile_throwsIfFileNotUnderSourceRoot() {
        val e: java.lang.IllegalArgumentException? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable { mapper.urlOfFile(java.io.File("/tmp/io_bazel_src/FooBar.java")) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("File '/tmp/io_bazel_src/FooBar.java' is expected to be under '/tmp/io_bazel'")
    }

    @org.junit.Test
    fun urlOfLabel() {
        assertThat(mapper.urlOfLabel("@_builtins//:foo/bar.bzl"))
            .isEqualTo("https://example.com/src/main/starlark/builtins_bzl/foo/bar.bzl")
        assertThat(mapper.urlOfLabel("//not/in/builtins/foo:bar.bzl"))
            .isEqualTo("https://example.com/not/in/builtins/foo/bar.bzl")
    }
}
