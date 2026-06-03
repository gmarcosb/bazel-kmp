// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package net.starlark.java.eval

import net.starlark.java.eval.StarlarkSemantics.DEFAULT

/**
 * Test properties of the evaluator's datatypes and utility functions without actually creating any
 * parse trees.
 */
@RunWith(JUnit4::class)
class PrinterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrinter() {
        // Note that str and repr only differ on behaviour of strings at toplevel.
        Truth.assertThat(str(createObjWithStr())).isEqualTo("<str marker>")
        assertThat(Starlark.repr(createObjWithStr(), DEFAULT)).isEqualTo("<repr marker>")

        Truth.assertThat(str("foo\nbar")).isEqualTo("foo\nbar")
        assertThat(Starlark.repr("foo\nbar", DEFAULT)).isEqualTo("\"foo\\nbar\"")
        Truth.assertThat(str("'")).isEqualTo("'")
        assertThat(Starlark.repr("'", DEFAULT)).isEqualTo("\"'\"")
        Truth.assertThat(str("\"")).isEqualTo("\"")
        assertThat(Starlark.repr("\"", DEFAULT)).isEqualTo("\"\\\"\"")
        Truth.assertThat(str(StarlarkInt.of(3))).isEqualTo("3")
        assertThat(Starlark.repr(StarlarkInt.of(3), DEFAULT)).isEqualTo("3")
        assertThat(Starlark.repr(Starlark.NONE, DEFAULT)).isEqualTo("None")

        val list: MutableList<*> = StarlarkList.of(null, "foo", "bar")
        val tuple: MutableList<*> = Tuple.of("foo", "bar")

        Truth.assertThat(str(Tuple.of(StarlarkInt.of(1), list, StarlarkInt.of(3))))
            .isEqualTo("(1, [\"foo\", \"bar\"], 3)")
        assertThat(Starlark.repr(Tuple.of(StarlarkInt.of(1), list, StarlarkInt.of(3)), DEFAULT))
            .isEqualTo("(1, [\"foo\", \"bar\"], 3)")
        Truth.assertThat(str(StarlarkList.of(null, StarlarkInt.of(1), tuple, StarlarkInt.of(3))))
            .isEqualTo("[1, (\"foo\", \"bar\"), 3]")
        assertThat(
            Starlark.repr(
                StarlarkList.of(null, StarlarkInt.of(1), tuple, StarlarkInt.of(3)), DEFAULT
            )
        )
            .isEqualTo("[1, (\"foo\", \"bar\"), 3]")

        val dict: MutableMap<Any?, Any?> =
            com.google.common.collect.ImmutableMap.of<Any?, Any?>(
                StarlarkInt.of(1), tuple, StarlarkInt.of(2), list, "foo", StarlarkList.of(null)
            )
        Truth.assertThat(str(dict)).isEqualTo("{1: (\"foo\", \"bar\"), 2: [\"foo\", \"bar\"], \"foo\": []}")
        assertThat(Starlark.repr(dict, DEFAULT))
            .isEqualTo("{1: (\"foo\", \"bar\"), 2: [\"foo\", \"bar\"], \"foo\": []}")
    }

    private fun checkFormatPositionalFails(errorMessage: String?, format: String?, vararg arguments: Any?) {
        val e: IllegalFormatException? =
            org.junit.Assert.assertThrows<IllegalFormatException?>(
                IllegalFormatException::class.java,
                org.junit.function.ThrowingRunnable { format(format, *arguments) })
        Truth.assertThat(e).hasMessageThat().isEqualTo(errorMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputOrderOfMap() {
        val map: MutableMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()
        map.put(StarlarkInt.of(5), StarlarkInt.of(5))
        map.put(StarlarkInt.of(3), StarlarkInt.of(3))
        map.put("foo", StarlarkInt.of(42))
        map.put(StarlarkInt.of(7), "bar")
        Truth.assertThat(str(Starlark.fromJava(map, null)))
            .isEqualTo("{5: 5, 3: 3, \"foo\": 42, 7: \"bar\"}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFormatPositional() {
        Truth.assertThat(formatWithList("%s %d", Tuple.of("foo", StarlarkInt.of(3)))).isEqualTo("foo 3")
        Truth.assertThat(format("%s %d", "foo", StarlarkInt.of(3))).isEqualTo("foo 3")

        // %d allows Integer or StarlarkInt
        Truth.assertThat(format("%d %d", StarlarkInt.of(123), 456)).isEqualTo("123 456")

        Truth.assertThat(format("%s %s %s", StarlarkInt.of(1), null, StarlarkInt.of(3)))
            .isEqualTo("1 null 3")

        // Note: formatToString doesn't perform scalar x -> (x) conversion;
        // The %-operator is responsible for that.
        Truth.assertThat(formatWithList("", Tuple.of())).isEmpty()
        Truth.assertThat(format("%s", "foo")).isEqualTo("foo")
        Truth.assertThat(format("%s", 3.14159)).isEqualTo("3.14159")
        checkFormatPositionalFails(
            "not all arguments converted during string formatting", "%s", 1, 2, 3
        )
        Truth.assertThat(format("%%%s", "foo")).isEqualTo("%foo")
        checkFormatPositionalFails(
            "not all arguments converted during string formatting", "%%s", "foo"
        )
        checkFormatPositionalFails(
            "unsupported format character \" \" at index 1 in \"% %s\"", "% %s", "foo"
        )
        Truth.assertThat(
            format(
                "%s",
                StarlarkList.of(null, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
        )
            .isEqualTo("[1, 2, 3]")
        Truth.assertThat(format("%s", Tuple.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))))
            .isEqualTo("(1, 2, 3)")
        Truth.assertThat(format("%s", StarlarkList.of(null))).isEqualTo("[]")
        Truth.assertThat(format("%s", Tuple.of())).isEqualTo("()")
        Truth.assertThat(format("%% %d %r %s", StarlarkInt.of(1), "2", "3")).isEqualTo("% 1 \"2\" 3")

        checkFormatPositionalFails("got string for '%d' format, want int or float", "%d", "1")
        checkFormatPositionalFails(
            "unsupported format character \".\" at index 1 in \"%.3g\"", "%.3g", 1
        )
        checkFormatPositionalFails(
            "unsupported format character \".\" at index 1 in \"%.3g\"", "%.3g", 1, 2
        )
        checkFormatPositionalFails(
            "unsupported format character \".\" at index 1 in \"%.s\"", "%.s", 1
        )
        checkFormatPositionalFails("not enough arguments for format pattern \"%.s\": ()", "%.s")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrettyQuoted() {
        // Single-line strings should use ordinary double quotes.
        Truth.assertThat(prettyQuoted("foo")).isEqualTo("\"foo\"")
        Truth.assertThat(prettyQuoted("foo\"bar")).isEqualTo("\"foo\\\"bar\"")
        Truth.assertThat(prettyQuoted("foo\\bar")).isEqualTo("\"foo\\\\bar\"")

        // Multiline default is triple double quotes
        Truth.assertThat(prettyQuoted("one\ntwo"))
            .isEqualTo(
                """
            ${'"'}${'"'}${'"'}one
            two${'"'}${'"'}${'"'}
            """.trimIndent()
            )

        // Escaping inside triple double quotes
        // Double quotes should be escaped (force """ by having more single quotes)
        Truth.assertThat(prettyQuoted("one\"two 'three' 'four'\nfive"))
            .isEqualTo(
                """
            ${'"'}${'"'}${'"'}one"two 'three' 'four'
            five${'"'}${'"'}${'"'}
            """.trimIndent()
            )
        // Backslashes should be escaped
        // Escaping inside triple double quotes (backslashes, control characters, hex escapes)
        Truth.assertThat(prettyQuoted("one\\two\none\rtwo\ntabs\tthree\none\u0001two\nthree"))
            .isEqualTo(
                """
            ${'"'}${'"'}${'"'}one\\two
            one\rtwo
            tabs\tthree
            one\x01two
            three${'"'}${'"'}${'"'}
            """.trimIndent()
            )

        // Heuristic: switch to triple single quotes (''') if double quotes are dominant
        Truth.assertThat(prettyQuoted("one\"two\"three\nfour"))
            .isEqualTo(
                """
            '''one"two"three
            four'''
            """.trimIndent()
            )

        // Escaping inside triple single quotes (''')
        // Single quotes should be escaped
        Truth.assertThat(prettyQuoted("one\"two\" 'three\nfour"))
            .isEqualTo(
                """
            '''one"two" 'three
            four'''
            """.trimIndent()
            )

        // Heuristic: switch to ''' if starting/ending with double quote (boundary issue)
        Truth.assertThat(prettyQuoted("\"one\ntwo"))
            .isEqualTo(
                """
            '''"one
            two'''
            """.trimIndent()
            )
        Truth.assertThat(prettyQuoted("one\ntwo\""))
            .isEqualTo(
                """
            '''one
            two"'''
            """.trimIndent()
            )

        // Boundary with single quote: keep triple double quotes
        Truth.assertThat(prettyQuoted("'one\ntwo"))
            .isEqualTo(
                """
            ${'"'}${'"'}${'"'}'one
            two${'"'}${'"'}${'"'}
            """.trimIndent()
            )
    }

    private fun createObjWithStr(): StarlarkValue {
        return object : StarlarkValue() {
            public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
                printer.append("<repr marker>")
            }

            public override fun str(printer: Printer, semantics: StarlarkSemantics?) {
                printer.append("<str marker>")
            }
        }
    }

    companion object {
        private fun str(o: Any?): String {
            return Starlark.str(o, DEFAULT)
        }

        private fun format(fmt: String?, vararg args: Any?): String {
            return Starlark.format(DEFAULT, fmt, args)
        }

        private fun formatWithList(fmt: String?, args: MutableList<*>?): String {
            return Starlark.formatWithList(DEFAULT, fmt, args)
        }

        private fun prettyQuoted(s: String?): String {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            Printer(sb).appendPrettyQuoted(s)
            return sb.toString()
        }
    }
}
