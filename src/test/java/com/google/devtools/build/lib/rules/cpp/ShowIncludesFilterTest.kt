// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ScratchAttributeWriter.write
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.FilterOutputStream
import java.io.IOException

/** Test for [ShowIncludesFilter].  */
@RunWith(JUnit4::class)
class ShowIncludesFilterTest {
    private var showIncludesFilter: ShowIncludesFilter? = null
    private var output: java.io.ByteArrayOutputStream? = null
    private var filterOutputStream: FilterOutputStream? = null

    @Before
    @Throws(IOException::class)
    fun setUpOutputStreams() {
        showIncludesFilter = ShowIncludesFilter("foo.cpp")
        output = java.io.ByteArrayOutputStream()
        filterOutputStream = showIncludesFilter.getFilteredOutputStream(output)
    }

    private fun getBytes(str: String): ByteArray? {
        return str.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testNotMatch() {
        // Normal output message with newline
        filterOutputStream.write(getBytes("I am compiling\n"))
        Truth.assertThat(output.toString()).isEqualTo("I am compiling\n")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testNotMatchThenFlushing() {
        // Normal output message without newline
        filterOutputStream.write(getBytes("Still compiling"))
        Truth.assertThat(output.toString()).isEmpty()
        filterOutputStream.flush()
        // flush to output should succeed
        Truth.assertThat(output.toString()).isEqualTo("Still compiling")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMatchPartOfNotePrefix() {
        // Prefix of "Note: including file:"
        filterOutputStream.write(getBytes("Note: "))
        filterOutputStream.flush()
        // flush to output shouldn't work, because there's still a chance to match.
        Truth.assertThat(output.toString()).isEmpty()
        // "Note: other info" doesn't match "Note: including file:", it's ok to flush.
        filterOutputStream.write(getBytes("other info"))
        filterOutputStream.flush()
        Truth.assertThat(output.toString()).isEqualTo("Note: other info")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMatchAllOfNotePrefix() {
        // "Note: including file:" is the prefix
        filterOutputStream.write(getBytes("Note: including file: bar.h"))
        filterOutputStream.flush()
        // flush to output should not work, waiting for newline
        Truth.assertThat(output.toString()).isEmpty()
        filterOutputStream.write(getBytes("\n"))
        // It's a match, output should be filtered, dependency on bar.h should be found.
        Truth.assertThat(output.toString()).isEmpty()
        com.google.common.truth.Subject.contains("bar.h")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test // Regression tests for https://github.com/bazelbuild/bazel/issues/9172
    @Throws(IOException::class)
    fun testFindHeaderFromAbsolutePathUnderExecrootBase() {
        // "Note: including file:" is the prefix
        filterOutputStream.write(
            getBytes("Note: including file: C:\\tmp\\xxxx\\execroot\\__main__\\foo\\bar\\bar.h")
        )
        filterOutputStream.flush()
        // flush to output should not work, waiting for newline
        Truth.assertThat(output.toString()).isEmpty()
        filterOutputStream.write(getBytes("\n"))
        // It's a match, output should be filtered, dependency on bar.h should be found.
        Truth.assertThat(output.toString()).isEmpty()
        com.google.common.truth.Subject.contains("..\\__main__\\foo\\bar\\bar.h")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testFindHeaderFromAbsolutePathOutsideExecroot() {
        // "Note: including file:" is the prefix
        filterOutputStream.write(getBytes("Note: including file: C:\\system\\foo\\bar\\bar.h"))
        filterOutputStream.flush()
        // flush to output should not work, waiting for newline
        Truth.assertThat(output.toString()).isEmpty()
        filterOutputStream.write(getBytes("\n"))
        // It's a match, output should be filtered, dependency on bar.h should be found.
        Truth.assertThat(output.toString()).isEmpty()
        com.google.common.truth.Subject.contains("C:\\system\\foo\\bar\\bar.h")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMatchSourceFileName() {
        filterOutputStream.write(getBytes("foo.cpp\n"))
        // It's a match, output should be filtered, no dependency found.
        Truth.assertThat(output.toString()).isEmpty()
        assertThat(showIncludesFilter.getDependencies()).isEmpty()
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testMatchPartOfSourceFileName() {
        filterOutputStream.write(getBytes("foo"))
        filterOutputStream.flush()
        Truth.assertThat(output.toString()).isEmpty()

        filterOutputStream.write(getBytes(".h"))
        filterOutputStream.flush()
        Truth.assertThat(output.toString()).isEqualTo("foo.h")
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testSawPotentialUnsupportedShowIncludesLine() {
        // MSVC output with French non-UTF-8 locale.
        filterOutputStream.write(getBytes("Remarque"))
        filterOutputStream.write(0xFF)
        filterOutputStream.write(getBytes(": inclusion du fichier"))
        filterOutputStream.write(0xFF)
        filterOutputStream.write(getBytes(":  C:\\bazel\\execroot\\foo\n"))
        filterOutputStream.flush()

        Truth.assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).isNotEmpty()
        assertThat(showIncludesFilter.getDependencies()).isEmpty()
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testSawPotentialUnsupportedShowIncludesLine_nearMatches() {
        filterOutputStream.write(getBytes("foo: bar: C:\\bazel\\foo\n"))
        filterOutputStream.write(getBytes("foo: C:\\bazel\\execroot\\foo\n"))
        filterOutputStream.write(getBytes("foo: bar: baz: C:\\bazel\\execroot\\foo\n"))
        filterOutputStream.write(getBytes("foo: bar(123): C:\\bazel\\execroot\\foo\n"))
        filterOutputStream.write(getBytes("foo: bar: C:\\bazel\\execroot\\foo: baz\n"))
        filterOutputStream.write(getBytes("foo: bar: bazel\\execroot\\foo\n"))
        filterOutputStream.flush()

        Truth.assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).isNotEmpty()
        assertThat(showIncludesFilter.getDependencies()).isEmpty()
        assertThat(showIncludesFilter.sawPotentialUnsupportedShowIncludesLine()).isFalse()
    }
}
