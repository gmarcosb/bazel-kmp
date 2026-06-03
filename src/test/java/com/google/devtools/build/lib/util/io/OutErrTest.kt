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
package com.google.devtools.build.lib.util.io

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream

/** Tests [OutErr].  */
@RunWith(JUnit4::class)
class OutErrTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val outErr: OutErr = OutErr.create(out, err)

    @Test
    fun testRetainsOutErr() {
        assertThat(outErr.getOutputStream()).isSameInstanceAs(out)
        assertThat(outErr.getErrorStream()).isSameInstanceAs(err)
    }

    @Test
    fun testPrintsToOut() {
        outErr.printOut("Hello, world.")
        Truth.assertThat(String(out.toByteArray())).isEqualTo("Hello, world.")
    }

    @Test
    fun testPrintsToErr() {
        outErr.printErr("Hello, moon.")
        Truth.assertThat(String(err.toByteArray())).isEqualTo("Hello, moon.")
    }

    @Test
    fun testPrintsToOutWithANewline() {
        outErr.printOutLn("With a newline.")
        Truth.assertThat(String(out.toByteArray())).isEqualTo("With a newline.\n")
    }

    @Test
    fun testPrintsToErrWithANewline() {
        outErr.printErrLn("With a newline.")
        Truth.assertThat(String(err.toByteArray())).isEqualTo("With a newline.\n")
    }

    @Test
    fun testPrintsTwoLinesToOut() {
        outErr.printOutLn("line 1")
        outErr.printOutLn("line 2")
        Truth.assertThat(String(out.toByteArray())).isEqualTo("line 1\nline 2\n")
    }

    @Test
    fun testPrintsTwoLinesToErr() {
        outErr.printErrLn("line 1")
        outErr.printErrLn("line 2")
        Truth.assertThat(String(err.toByteArray())).isEqualTo("line 1\nline 2\n")
    }
}
