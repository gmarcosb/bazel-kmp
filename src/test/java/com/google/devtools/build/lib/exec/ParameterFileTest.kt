// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ParameterFile

/** Tests for [ParameterFile].  */
@RunWith(JUnit4::class)
class ParameterFileTest : FoundationTestCase() {
    @org.junit.Test
    fun testDerive() {
        assertThat(ParameterFile.derivePath(PathFragment.create("a/b")))
            .isEqualTo(PathFragment.create("a/b-2.params"))
        assertThat(ParameterFile.derivePath(PathFragment.create("b")))
            .isEqualTo(PathFragment.create("b-2.params"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteAscii() {
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.ISO_8859_1,
                com.google.common.collect.ImmutableList.of<String?>("--foo", "--bar")
            )
        )
            .containsExactly("--foo", "--bar")
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.UTF_8,
                com.google.common.collect.ImmutableList.of<String?>("--foo", "--bar")
            )
        )
            .containsExactly("--foo", "--bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteLatin1() {
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.ISO_8859_1,
                com.google.common.collect.ImmutableList.of<String?>("--füü")
            )
        )
            .containsExactly("--füü")
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.UTF_8,
                com.google.common.collect.ImmutableList.of<String?>("--füü")
            )
        )
            .containsExactly("--füü")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteUtf8() {
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.ISO_8859_1,
                com.google.common.collect.ImmutableList.of<String?>("--lambda=λ")
            )
        )
            .containsExactly("--lambda=?")
        Truth.assertThat(
            writeContent(
                java.nio.charset.StandardCharsets.UTF_8,
                com.google.common.collect.ImmutableList.of<String?>("--lambda=λ")
            )
        )
            .containsExactly("--lambda=λ")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagsOnly() {
        assertThat(ParameterFile.flagsOnly(MIXED_ARGS)).containsExactly("--b", "--c=d").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonFlags() {
        assertThat(ParameterFile.nonFlags(MIXED_ARGS)).containsExactly("a", "e").inOrder()
    }

    companion object {
        private val MIXED_ARGS: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("a", "--b", "--c=d", "e")

        @Throws(java.lang.Exception::class)
        private fun writeContent(
            charset: java.nio.charset.Charset?,
            content: Iterable<String?>
        ): com.google.common.collect.ImmutableList<String?> {
            val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            ParameterFile.writeParameterFile(
                outputStream,
                com.google.common.collect.Iterables.transform<F?, T?>( // Bazel internally represents all strings as raw bytes in ISO-8859-1.
                    content,
                    com.google.common.base.Function { s: F? ->
                        String(
                            s.getBytes(charset),
                            java.nio.charset.StandardCharsets.ISO_8859_1
                        )
                    }),
                ParameterFileType.UNQUOTED
            )
            return com.google.common.collect.ImmutableList.builder<String?>()
                .add(*outputStream.toString(charset).split("\n".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()).build()
        }
    }
}
