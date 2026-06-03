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

import com.google.devtools.build.lib.testutil.MoreAsserts
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A test for [AnsiTerminalPrinter].
 */
@RunWith(JUnit4::class)
class AnsiTerminalPrinterTest {
    private var stream: ByteArrayOutputStream? = null
    private var printer: AnsiTerminalPrinter? = null

    @Before
    @Throws(Exception::class)
    fun createPrinter() {
        stream = ByteArrayOutputStream(1000)
        printer = AnsiTerminalPrinter(stream, true)
    }

    private fun setPlainPrinter() {
        printer = AnsiTerminalPrinter(stream, false)
    }

    private fun assertString(string: String?) {
        Truth.assertThat(stream.toString()).isEqualTo(string)
    }

    private fun assertRegex(regex: String?) {
        MoreAsserts.assertStdoutContainsRegex(regex, stream.toString(), "")
    }

    @Test
    @Throws(Exception::class)
    fun testPlainPrinter() {
        setPlainPrinter()
        printer.print(
            ("1" + Mode.INFO + "2" + Mode.ERROR + "3" + Mode.WARNING + "4"
                    + Mode.DEFAULT + "5")
        )
        assertString("12345")
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultModeIsDefault() {
        printer.print("1" + Mode.DEFAULT + "2")
        assertString("12")
    }

    @Test
    @Throws(Exception::class)
    fun testDuplicateMode() {
        printer.print("_A_" + Mode.INFO)
        printer.print("_B_" + Mode.INFO + "_C_")
        assertRegex("^_A_.+_B__C_$")
    }

    @Test
    @Throws(Exception::class)
    fun testModeCodes() {
        printer.print(
            (Mode.INFO + "XXX" + Mode.ERROR + "XXX" + Mode.WARNING + "XXX" + Mode.DEFAULT
                    + "XXX" + Mode.INFO + "XXX" + Mode.ERROR + "XXX" + Mode.WARNING + "XXX" + Mode.DEFAULT)
        )
        val codes: Array<String?> =
            stream.toString().split("XXX".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        Truth.assertThat<String?>(codes).hasLength(8)
        for (i in 0..3) {
            Truth.assertThat(codes[i]).isNotEmpty()
            Truth.assertThat(codes[i + 4]).isEqualTo(codes[i])
        }
        Truth.assertThat(codes[0] == codes[1]).isFalse()
        Truth.assertThat(codes[0] == codes[2]).isFalse()
        Truth.assertThat(codes[0] == codes[3]).isFalse()
        Truth.assertThat(codes[1] == codes[2]).isFalse()
        Truth.assertThat(codes[1] == codes[3]).isFalse()
        Truth.assertThat(codes[2] == codes[3]).isFalse()
    }
}
