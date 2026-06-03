// Copyright 2020 The Bazel Authors. All rights reserved.
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

import Mutability.Freezable
import StarlarkThread.CallStackEntry
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Mutability.Freezable
import net.starlark.java.eval.ScriptTest
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkCallable
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkThread.CallStackEntry
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Structure
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.SyntaxError.location
import net.starlark.java.syntax.TypeTable.errors
import java.util.HashMap
import java.util.HexFormat
import java.util.regex.PatternSyntaxException

/** Script-based tests of Starlark evaluator.  */
class ScriptTest {
    // Tests for Starlark.
    //
    // In each test file, chunks are separated by "\n---\n".
    // Each chunk is evaluated separately.
    // A comment containing
    //     ### regular expression
    // specifies an expected error on that line.
    // The part after '###', with leading/trailing spaces removed,
    // must be a valid regular expression matching the error.
    // If there is no "###", the test will succeed iff there is no error.
    //
    // Within the file, the assert_ and assert_eq functions may be used to
    // report errors without stopping the program. (They are not evaluation
    // errors that can be caught with a '###' expectation.)
    // TODO(adonovan): improve this test driver (following go.starlark.net):
    //
    // - extract support for "chunked files" into a library
    //   and reuse it for tests of lexer, parser, resolver.
    // - require that some frame of each EvalException match the file/line of the expectation.
    internal interface Reporter {
        fun reportError(thread: StarlarkThread?, message: String?)
    }

    @StarlarkMethod(
        name = "assert_",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "cond"), net.starlark.java.annot.Param(
            name = "msg",
            defaultValue = "'assertion failed'"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun assertStarlark(cond: Any?, msg: String?, thread: StarlarkThread): Any? {
        if (!Starlark.truth(cond)) {
            reportErrorf(thread, "assert_: %s", msg)
        }
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "assert_eq",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "x"), net.starlark.java.annot.Param(name = "y")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun assertEq(x: Any, y: Any?, thread: StarlarkThread): Any? {
        if (x != y) {
            if (x is String && y is String) {
                val encoding: java.nio.charset.Charset =
                    if (thread.getSemantics().getBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS))
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    else
                        java.nio.charset.StandardCharsets.UTF_8
                reportErrorf(
                    thread,
                    "assert_eq: %s (%s) != %s (%s)",
                    Starlark.repr(x, StarlarkSemantics.DEFAULT),
                    HexFormat.of().formatHex(x.toByteArray(encoding)),
                    Starlark.repr(y, StarlarkSemantics.DEFAULT),
                    HexFormat.of().formatHex(y.toByteArray(encoding))
                )
            } else {
                reportErrorf(
                    thread,
                    "assert_eq: %s != %s",
                    Starlark.repr(x, StarlarkSemantics.DEFAULT),
                    Starlark.repr(y, StarlarkSemantics.DEFAULT)
                )
            }
        }
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "assert_fails",
        doc = "assert_fails asserts that evaluation of f() fails with the specified error",
        parameters = [net.starlark.java.annot.Param(
            name = "f",
            doc = "the Starlark function to call"
        ), net.starlark.java.annot.Param(
            name = "wantError",
            doc = "a regular expression matching the expected error message"
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun assertFails(f: StarlarkCallable?, wantError: String?, thread: StarlarkThread): Any? {
        val pattern: java.util.regex.Pattern?
        try {
            pattern = java.util.regex.Pattern.compile(wantError)
        } catch (unused: PatternSyntaxException) {
            throw Starlark.errorf("invalid regexp: %s", wantError)
        }

        try {
            Starlark.call(
                thread,
                f,
                com.google.common.collect.ImmutableList.of<Any?>(),
                com.google.common.collect.ImmutableMap.of<String?, Any?>()
            )
            reportErrorf(thread, "evaluation succeeded unexpectedly (want error matching %s)", wantError)
        } catch (ex: net.starlark.java.eval.EvalException) {
            // Verify error matches expectation.
            val msg: String? = ex.message
            if (!pattern.matcher(msg).find()) {
                reportErrorf(thread, "regular expression (%s) did not match error (%s)", pattern, msg)
            }
        }
        return Starlark.NONE
    }

    // Constructor for simple structs, for testing.
    @StarlarkMethod(name = "struct", documented = false, extraKeywords = net.starlark.java.annot.Param(name = "kwargs"))
    @Throws(net.starlark.java.eval.EvalException::class)
    fun struct(kwargs: Dict<String?, Any?>): Struct {
        return ImmutableStruct(com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(kwargs))
    }

    @StarlarkMethod(
        name = "mutablestruct",
        documented = false,
        extraKeywords = net.starlark.java.annot.Param(name = "kwargs")
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun mutablestruct(kwargs: Dict<String?, Any?>): Struct {
        return MutableStruct(kwargs)
    }

    @StarlarkMethod(
        name = "freeze",
        doc = "Shallow-freezes the operand. With no argument, freezes the thread.",
        parameters = [net.starlark.java.annot.Param(name = "x", defaultValue = "unbound")],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun freeze(x: Any?, thread: StarlarkThread) {
        if (x === Starlark.UNBOUND) {
            thread.mutability().close()
            return
        }

        if (x is Freezable) {
            (x as Freezable).unsafeShallowFreeze()
        } else {
            throw Starlark.errorf("%s value is not freezable", Starlark.type(x))
        }
    }

    @StarlarkMethod(
        name = "int_mul_slow",
        doc = "Slow but reliable integer multiplication with round-trip to BigInteger",
        parameters = [net.starlark.java.annot.Param(name = "x"), net.starlark.java.annot.Param(name = "y")]
    )
    fun intMulSlow(x: StarlarkInt, y: StarlarkInt): StarlarkInt? {
        return StarlarkInt.of(x.toBigInteger().multiply(y.toBigInteger()))
    }

    // A trivial struct-like class with Starlark fields defined by a map.
    private open class Struct(val fields: MutableMap<String?, Any?>) : StarlarkValue, Structure {
        val fieldNames: com.google.common.collect.ImmutableList<String?>
            get() = com.google.common.collect.ImmutableList.copyOf<String?>(fields.keys)

        override fun getValue(name: String?): Any? {
            return fields.get(name)
        }

        override fun getErrorMessageForUnknownField(name: String?): String? {
            return null
        }

        override fun repr(p: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            // This repr function prints only the fields.
            // Any methods are still accessible through dir/getattr/hasattr.
            p.append(Starlark.type(this))
            p.append("(")
            var sep = ""
            for (e in fields.entries) {
                p.append(sep).append(e.key).append(" = ").repr(e.value, semantics)
                sep = ", "
            }
            p.append(")")
        }
    }

    @StarlarkBuiltin(name = "struct")
    private class ImmutableStruct(fields: com.google.common.collect.ImmutableMap<String?, Any?>) : Struct(fields)

    @StarlarkBuiltin(name = "mutablestruct")
    private class MutableStruct(fields: Dict<String?, Any?>) : Struct(fields) {
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun setField(field: String?, value: Any) {
            if (value == "bad") {
                throw Starlark.errorf("bad field value")
            }
            (fields as Dict<String?, Any?>).putEntry(field, value)
        }
    }

    companion object {
        @com.google.errorprone.annotations.FormatMethod
        private fun reportErrorf(thread: StarlarkThread, format: String, vararg args: Any?) {
            thread.getThreadLocal<Reporter?>(net.starlark.java.eval.ScriptTest.Reporter::class.java)
                .reportError(thread, String.format(format, *args))
        }

        private var ok = true

        @Suppress("deprecation") // intentional use of ParserInput.fromLatin1()
        @Throws(java.lang.Exception::class)
        @kotlin.jvm.JvmStatic
        fun main(args: Array<String>) {
            var root: java.io.File = java.io.File("third_party/bazel") // blaze
            if (!root.exists()) {
                root = java.io.File(".") // bazel
            }
            val testdata: java.io.File = java.io.File(root, "src/test/java/net/starlark/java/eval/testdata")
            for (name in testdata.list()) {
                val file: java.io.File = java.io.File(testdata, name)
                val content: String =
                    com.google.common.io.Files.asCharSource(file, java.nio.charset.StandardCharsets.UTF_8).read()
                var linenum = 1
                for (chunk in com.google.common.base.Splitter.on("\n---\n").split(content)) {
                    // prepare chunk
                    val buf: java.lang.StringBuilder = java.lang.StringBuilder()
                    for (i in 1..<linenum) {
                        buf.append('\n')
                    }
                    buf.append(chunk)
                    if (false) {
                        java.lang.System.err.printf("%s:%d: <<%s>>\n", file, linenum, buf)
                    }

                    // extract expectations: ### "regular expression"
                    val expectations: MutableMap<java.util.regex.Pattern, Int?> =
                        HashMap<java.util.regex.Pattern, Int?>()
                    var i: Int = chunk.indexOf("###")
                    while (i >= 0) {
                        var j: Int = chunk.indexOf("\n", i)
                        if (j < 0) {
                            j = chunk.length
                        }

                        val line = linenum + newlines(chunk.substring(0, i))
                        val comment: String = chunk.substring(i + 3, j)
                        i = j

                        // Compile regular expression in comment.
                        val pattern: java.util.regex.Pattern?
                        try {
                            pattern = java.util.regex.Pattern.compile(comment.trim { it <= ' ' })
                        } catch (ex: PatternSyntaxException) {
                            java.lang.System.err.printf("%s:%d: invalid regexp: %s\n", file, line, ex.message)
                            ok = false
                            i = chunk.indexOf("###", i)
                            continue
                        }

                        if (false) {
                            java.lang.System.err.printf("%s:%d: expectation '%s'\n", file, line, pattern)
                        }
                        expectations.put(pattern, line)
                        i = chunk.indexOf("###", i)
                    }

                    // parse & execute
                    val utf8ByteStrings: Boolean =
                        java.lang.Boolean.getBoolean("net.starlark.java.eval.ScriptTest.utf8ByteStrings")
                    val input: net.starlark.java.syntax.ParserInput?
                    if (utf8ByteStrings) {
                        input = net.starlark.java.syntax.ParserInput.fromLatin1(
                            buf.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8), file.toString()
                        )
                    } else {
                        input = net.starlark.java.syntax.ParserInput.fromString(buf.toString(), file.toString())
                    }
                    val predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                        com.google.common.collect.ImmutableMap.builder<String?, Any?>()
                    Starlark.addMethods(predeclared, ScriptTest()) // e.g. assert_eq
                    predeclared.put("json", net.starlark.java.lib.json.Json.INSTANCE)
                        .put("_utf8_byte_strings", utf8ByteStrings)

                    val semanticsBuilder: net.starlark.java.eval.StarlarkSemantics.Builder = StarlarkSemantics.builder()
                    if (utf8ByteStrings) {
                        semanticsBuilder.setBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS, true)
                    }
                    val semantics: StarlarkSemantics? = semanticsBuilder.build()
                    val module: net.starlark.java.eval.Module? =
                        net.starlark.java.eval.Module.withPredeclared(semantics, predeclared.buildOrThrow())
                    try {
                        Mutability.createAllowingShallowFreeze("test").use { mu ->
                            val thread: StarlarkThread = StarlarkThread.createTransient(mu, semantics)
                            thread.setThreadLocal<Reporter?>(
                                net.starlark.java.eval.ScriptTest.Reporter::class.java,
                                net.starlark.java.eval.ScriptTest.Reporter { thread: StarlarkThread?, message: String? ->
                                    Companion.reportError(
                                        thread,
                                        message!!
                                    )
                                })
                            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
                        }
                    } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                        // parser/resolver errors
                        //
                        // Static errors cannot be suppressed by expectations:
                        // it would be dangerous because the presence of a static
                        // error prevents execution of any dynamic assertions in
                        // a chunk. Tests of static errors belong in syntax/.
                        for (err in ex.errors()) {
                            java.lang.System.err.println(err) // includes location
                            ok = false
                        }
                    } catch (ex: net.starlark.java.eval.EvalException) {
                        // evaluation error
                        //
                        // TODO(adonovan): the old logic checks only that each error is matched
                        // by at least one expectation. Instead, ensure that errors
                        // and exceptions match exactly. Furthermore, look only at errors
                        // whose stack has a frame with a file/line that matches the expectation.
                        // This requires inspecting EvalException stack.
                        // (There can be at most one dynamic error per chunk.
                        // Do we even need to allow multiple expectations?)
                        if (!expected(expectations, ex.message)) {
                            java.lang.System.err.println(ex.getMessageWithStack())
                            ok = false
                        }
                    } catch (ex: Throwable) {
                        // unhandled exception (incl. InterruptedException)
                        java.lang.System.err.printf(
                            "%s:%d: unhandled %s in this chunk: %s\n",
                            file, linenum, ex.javaClass.getSimpleName(), ex.message
                        )
                        ex.printStackTrace()
                        ok = false
                    }

                    // unmatched expectations
                    for (e in expectations.entries) {
                        java.lang.System.err.printf("%s:%d: unmatched expectation: %s\n", file, e.value, e.key)
                        ok = false
                    }

                    // advance line number
                    linenum += newlines(chunk) + 2 // for "\n---\n"
                }
            }
            if (!ok) {
                java.lang.System.exit(1)
            }
        }

        // Called by assert_ and assert_eq when the test encounters an error.
        // Does not stop the program; multiple failures may be reported in a single run.
        private fun reportError(thread: StarlarkThread, message: String) {
            var message = message
            java.lang.System.err.printf("Traceback (most recent call last):\n")
            var stack: MutableList<CallStackEntry> = thread.getCallStack()
            stack = stack.subList(0, stack.size - 1) // pop the built-in function
            for (fr in stack) {
                java.lang.System.err.printf("%s: called from %s\n", fr.location, fr.name)
            }
            if (thread.getSemantics().getBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS)) {
                // Reencode the message for display.
                message = String(
                    message.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1),
                    java.nio.charset.StandardCharsets.UTF_8
                )
            }
            java.lang.System.err.println("Error: " + message)
            ok = false
        }

        private fun expected(expectations: MutableMap<java.util.regex.Pattern, Int?>, message: String?): Boolean {
            for (pattern in expectations.keys) {
                if (pattern.matcher(message).find()) {
                    expectations.remove(pattern)
                    return true
                }
            }
            return false
        }

        private fun newlines(s: String): Int {
            var n = 0
            for (i in 0..<s.length) {
                if (s.get(i) == '\n') {
                    n++
                }
            }
            return n
        }
    }
}
