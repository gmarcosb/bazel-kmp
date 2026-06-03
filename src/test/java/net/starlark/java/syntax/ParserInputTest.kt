// Copyright 2006 The Bazel Authors. All Rights Reserved.
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
package net.starlark.java.syntax

import com.google.common.truth.Truth
import net.starlark.java.syntax.ParserInput.getFile
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/** A test case for [ParserInput].  */
@RunWith(JUnit4::class)
class ParserInputTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun testFromLatin1() {
        val content = "éclair"
        val bytes: ByteArray? = content.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        val input: net.starlark.java.syntax.ParserInput =
            net.starlark.java.syntax.ParserInput.fromLatin1(bytes, "foo.txt")
        Truth.assertThat(String(input.getContent())).isEqualTo(content)
        Truth.assertThat(input.getFile()).isEqualTo("foo.txt")
    }

    @org.junit.Test
    fun testFromString() {
        val content = "Content provided as a string."
        val pathName = "/the/name/of/the/content.txt"
        val input: net.starlark.java.syntax.ParserInput =
            net.starlark.java.syntax.ParserInput.fromString(content, pathName)
        Truth.assertThat(String(input.getContent())).isEqualTo(content)
        Truth.assertThat(input.getFile()).isEqualTo(pathName)
    }

    @org.junit.Test
    fun testFromCharArray() {
        val content = "Content provided as a string."
        val pathName = "/the/name/of/the/content.txt"
        val contentChars: CharArray = content.toCharArray()
        val input: net.starlark.java.syntax.ParserInput =
            net.starlark.java.syntax.ParserInput.fromCharArray(contentChars, pathName)
        Truth.assertThat(String(input.getContent())).isEqualTo(content)
        Truth.assertThat(input.getFile()).isEqualTo(pathName)
    }

    @org.junit.Test
    fun testWillNotTryToReadInputFileIfContentProvidedAsString() {
        net.starlark.java.syntax.ParserInput.fromString("Content provided as string.", "/will/not/try/to/read")
    }

    @org.junit.Test
    fun testWillNotTryToReadInputFileIfContentProvidedAsChars() {
        val content: CharArray = "Content provided as char array.".toCharArray()
        net.starlark.java.syntax.ParserInput.fromCharArray(content, "/will/not/try/to/read")
    }
}
