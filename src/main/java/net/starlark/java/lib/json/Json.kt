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
package net.starlark.java.lib.json

import com.google.common.collect.Ordering
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.*
import net.starlark.java.lib.StarlarkEncodable
import java.lang.Double
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Char
import kotlin.CharArray
import kotlin.Comparable
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.String
import kotlin.arrayOf
import kotlin.text.CharacterCodingException
import kotlin.text.StringBuilder

// Tests at //src/test/java/net/starlark/java/eval:testdata/json.sky
/**
 * Json defines the Starlark `json` module, which provides functions for encoding/decoding
 * Starlark values as JSON (https://tools.ietf.org/html/rfc8259).
 */
@StarlarkBuiltin(
    name = "json",
    category = "core.lib",
    doc = "Module json is a Starlark module of JSON-related functions."
)
class Json private constructor() : StarlarkValue {
    /**
     * Encodes a Starlark value as JSON.
     * 
     * 
     * An application-defined subclass of StarlarkValue may define its own JSON encoding by
     * implementing the [StarlarkEncodable] interface. Otherwise, the encoder tests for the
     * [Map], [StarlarkIterable], and [Structure] interfaces, in that order,
     * resulting in dict-like, list-like, and struct-like encoding, respectively. See the Starlark
     * documentation annotation for more detail.
     * 
     * 
     * Encoding any other value yields an error.
     */
    @StarlarkMethod(
        name = "encode",
        doc = ("<p>The encode function accepts one required positional argument, which it converts to"
                + " JSON by cases:\n"
                + "<ul>\n"
                + "<li>None, True, and False are converted to 'null', 'true', and 'false',"
                + " respectively.\n"
                + "<li>An int, no matter how large, is encoded as a decimal integer. Some decoders"
                + " may not be able to decode very large integers.\n"
                + "<li>A float is encoded using a decimal point or an exponent or both, even if its"
                + " numeric value is an integer. It is an error to encode a non-finite "
                + " floating-point value.\n"
                + "<li>A string value is encoded as a JSON string literal that denotes the value. "
                + " Each unpaired surrogate is replaced by U+FFFD.\n"
                + "<li>A dict is encoded as a JSON object, in lexicographical key order.  It is an"
                + " error if any key is not a string.\n"
                + "<li>A list or tuple is encoded as a JSON array.\n"
                + "<li>A struct-like value is encoded as a JSON object, in field name order.\n"
                + "</ul>\n"
                + "An application-defined type may define its own JSON encoding.\n"
                + "Encoding any other value yields an error.\n"),
        parameters = [Param(name = "x")],
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class, InterruptedException::class
    )
    fun encode(x: Any?, thread: StarlarkThread): String {
        val enc = Encoder(thread.getSemantics())
        try {
            enc.encode(x)
        } catch (unused: StackOverflowError) {
            throw Starlark.errorf("nesting depth limit exceeded")
        }
        return enc.out.toString()
    }

    private class Encoder(semantics: StarlarkSemantics?) {
        private val out = StringBuilder()
        private val semantics: StarlarkSemantics?

        init {
            this.semantics = semantics
        }

        @Throws(EvalException::class, InterruptedException::class)
        fun encode(x: Any?) {
            var x = x
            if (x is StarlarkEncodable) {
                x = x.objectForEncoding(semantics)
            }

            if (x === Starlark.NONE) {
                out.append("null")
                return
            }

            if (x is String) {
                appendQuoted(x)
                return
            }

            if (x is Boolean || x is StarlarkInt) {
                out.append(x)
                return
            }

            if (x is StarlarkFloat) {
                if (!Double.isFinite(x.toDouble())) {
                    throw Starlark.errorf("cannot encode non-finite float %s", x)
                }
                out.append(x.toString()) // always contains a decimal point or exponent
                return
            }

            // e.g. dict (must have string keys)
            if (x is MutableMap<*, *>) {
                // Sort keys for determinism.
                val keys: Array<Any?> = x.keySet().toArray()
                for (key in keys) {
                    if (key !is String) {
                        throw Starlark.errorf(
                            "%s has %s key, want string", Starlark.type(x), Starlark.type(key)
                        )
                    }
                }
                Arrays.sort(keys)

                // emit object
                out.append('{')
                var sep = ""
                for (key in keys) {
                    out.append(sep)
                    sep = ","
                    appendQuoted((key as kotlin.String?)!!)
                    out.append(':')
                    try {
                        encode(x.get(key))
                    } catch (ex: EvalException) {
                        throw Starlark.errorf(
                            "in %s key %s: %s",
                            Starlark.type(x), Starlark.repr(key, StarlarkSemantics.DEFAULT), ex.getMessage()
                        )
                    }
                }
                out.append('}')
                return
            }

            // e.g. tuple, list
            if (x is StarlarkIterable<*>) {
                out.append('[')
                var sep = ""
                var i = 0
                for (elem in x) {
                    out.append(sep)
                    sep = ","
                    try {
                        encode(elem)
                    } catch (ex: EvalException) {
                        throw Starlark.errorf("at %s index %d: %s", Starlark.type(x), i, ex.getMessage())
                    }
                    i++
                }
                out.append(']')
                return
            }

            // e.g. struct or a NativeInfo's EncodableStructure proxy.
            if (x is Structure) {
                // Sort keys for determinism.
                val fields =
                    Ordering.natural<Comparable<*>?>()
                        .sortedCopy<String?>(Starlark.dir(Mutability.IMMUTABLE, semantics, x))

                out.append('{')
                var sep = ""
                for (field in fields) {
                    out.append(sep)
                    sep = ","
                    appendQuoted(field)
                    out.append(":")
                    try {
                        val v =
                            Starlark.getattr(
                                Mutability.IMMUTABLE,
                                semantics,
                                x,
                                field,
                                null
                            ) // may fail (field not defined)
                        encode(v) // may fail (unexpected type)
                    } catch (ex: EvalException) {
                        throw Starlark.errorf("in %s field .%s: %s", Starlark.type(x), field, ex.getMessage())
                    }
                }
                out.append('}')
                return
            }

            throw Starlark.errorf("cannot encode %s as JSON", Starlark.type(x))
        }

        fun appendQuoted(s: String) {
            // We use String's code point iterator so that we can map
            // unpaired surrogates to U+FFFD in the output.
            // TODO(adonovan): if we ever get an isPrintable(codepoint)
            // function, use uXXXX escapes for non-printables.
            out.append('"')
            var i = 0
            val n: Int = s.length()
            while (i < n) {
                var cp: Int = s.codePointAt(i)

                // ASCII control code?
                if (cp < 0x20) {
                    when (cp) {
                        '\b' -> out.append("\\b")
                        '\f' -> out.append("\\f")
                        '\n' -> out.append("\\n")
                        '\r' -> out.append("\\r")
                        '\t' -> out.append("\\t")
                        else -> {
                            out.append("\\u00")
                            out.append(HEX[(cp shr 4) and 0xf])
                            out.append(HEX[cp and 0xf])
                        }
                    }
                    i++
                    continue
                }

                // printable ASCII (or DEL 0x7f)? (common case)
                if (cp < 0x80) {
                    if (cp == '"'.code || cp == '\\'.code) {
                        out.append('\\')
                    }
                    out.append(cp.toChar())
                    i++
                    continue
                }

                // non-ASCII
                if (Character.MIN_SURROGATE.code <= cp && cp <= Character.MAX_SURROGATE.code) {
                    cp = 0xFFFD // unpaired surrogate
                }
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            out.append('"')
        }
    }

    /** Parses a JSON string as a Starlark value.  */
    @StarlarkMethod(
        name = "decode",
        doc = ("The decode function has one required positional parameter: a JSON string.\n"
                + "It returns the Starlark value that the string denotes.\n"
                + "<ul><li><code>\"null\"</code>, <code>\"true\"</code> and <code>\"false\"</code>"
                + " are parsed as <code>None</code>, <code>True</code>, and <code>False</code>.\n"
                + "<li>Numbers are parsed as int, or as a float if they contain a decimal point or an"
                + " exponent. Although JSON has no syntax  for non-finite values, very large values"
                + " may be decoded as infinity.\n"
                + "<li>a JSON object is parsed as a new unfrozen Starlark dict. If the same key"
                + " string occurs more than once in the object, the last value for the key is kept.\n"
                + "<li>a JSON array is parsed as new unfrozen Starlark list.\n"
                + "</ul>\n"
                + "If <code>x</code> is not a valid JSON encoding and the optional"
                + " <code>default</code> parameter is specified (including specified as"
                + " <code>None</code>), this function returns the <code>default</code> value.\n"
                + "If <code>x</code> is not a valid JSON encoding and the optional"
                + " <code>default</code> parameter is <em>not</em> specified, this function fails."),
        parameters = [Param(name = "x", doc = "JSON string to decode."), Param(
            name = "default",
            named = true,
            doc = "If specified, the value to return when <code>x</code> cannot be decoded.",
            defaultValue = "unbound"
        )],
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class
    )
    fun decode(x: String, defaultValue: Any?, thread: StarlarkThread): Any? {
        try {
            return Decoder(
                thread.mutability(),
                x,
                thread
                    .getSemantics()
                    .getBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS)
            )
                .decode()
        } catch (e: EvalException) {
            if (defaultValue !== Starlark.UNBOUND) {
                return defaultValue
            } else {
                throw e
            }
        }
    }

    private class Decoder(mu: Mutability?, s: String, utf8ByteStrings: Boolean) {
        // The decoder necessarily makes certain representation choices
        // such as list vs tuple, struct vs dict, int vs float.
        // In principle, we could parameterize it to allow the caller to
        // control the returned types, but there's no compelling need yet.
        private val mu: Mutability?
        private val s: String // the input string
        private val utf8ByteStrings: Boolean
        private var i = 0 // current index in s

        init {
            this.mu = mu
            this.s = s
            this.utf8ByteStrings = utf8ByteStrings
        }

        // decode is the entry point into the decoder.
        @Throws(EvalException::class)
        fun decode(): Any? {
            try {
                val x = parse()
                if (skipSpace()) {
                    throw Starlark.errorf("unexpected character %s after value", quoteChar(s.charAt(i)))
                }
                return x
            } catch (unused: StackOverflowError) {
                throw Starlark.errorf("nesting depth limit exceeded")
            } catch (ex: EvalException) {
                throw Starlark.errorf("at offset %d, %s", i, ex.getMessage())
            }
        }

        // parse returns the next JSON value from the input.
        // It consumes leading but not trailing whitespace.
        @Throws(EvalException::class)
        fun parse(): Any? {
            var c = next()
            when (c) {
                '"' -> return parseString()

                'n' -> if (s.startsWith("null", i)) {
                    i += "null".length()
                    return Starlark.NONE
                }

                't' -> if (s.startsWith("true", i)) {
                    i += "true".length()
                    return true
                }

                'f' -> if (s.startsWith("false", i)) {
                    i += "false".length()
                    return false
                }

                '[' -> {
                    // array
                    val list = StarlarkList.newList<Any?>(mu)

                    i++ // '['
                    c = next()
                    if (c != ']') {
                        while (true) {
                            val elem = parse()
                            list.addElement(elem) // can't fail
                            c = next()
                            if (c != ',') {
                                if (c != ']') {
                                    throw Starlark.errorf("got %s, want ',' or ']'", quoteChar(c))
                                }
                                break
                            }
                            i++ // ','
                        }
                    }
                    i++ // ']'
                    return list
                }

                '{' -> {
                    // object
                    val dict = Dict.of<String?, Any?>(mu)

                    i++ // '{'
                    c = next()
                    if (c != '}') {
                        while (true) {
                            val key = parse()
                            if (key !is String) {
                                throw Starlark.errorf("got %s for object key, want string", Starlark.type(key))
                            }
                            c = next()
                            if (c != ':') {
                                throw Starlark.errorf("after object key, got %s, want ':' ", quoteChar(c))
                            }
                            i++ // ':'
                            val value = parse()
                            dict.putEntry(key, value) // can't fail
                            c = next()
                            if (c != ',') {
                                if (c != '}') {
                                    throw Starlark.errorf("in object, got %s, want ',' or '}'", quoteChar(c))
                                }
                                break
                            }
                            i++ // ','
                        }
                    }
                    i++ // '}'
                    return dict
                }

                else ->           // number?
                    if (isdigit(c) || c == '-') {
                        return parseNumber(c)
                    }
            }
            throw Starlark.errorf("unexpected character %s", quoteChar(c))
        }

        @Throws(EvalException::class)
        fun parseString(): String {
            i++ // '"'
            val str = StringBuilder()
            while (i < s.length()) {
                var c: Char = s.charAt(i)

                // end quote?
                if (c == '"') {
                    i++ // skip '"'
                    return str.toString()
                }

                // literal char?
                if (c != '\\') {
                    // reject unescaped control codes
                    if (c.code <= 0x1F) {
                        throw Starlark.errorf("invalid character '\\x%02x' in string literal", c.code)
                    }
                    i++ // consume
                    str.append(c)
                    continue
                }

                // escape: uXXXX or [\/bfnrt"]
                i++ // '\\'
                if (i == s.length()) {
                    throw Starlark.errorf("incomplete escape")
                }
                c = s.charAt(i)
                i++ // consume c
                when (c) {
                    '\\', '/', '"' -> str.append(c)
                    'b' -> str.append('\b')
                    'f' -> str.append('\f')
                    'n' -> str.append('\n')
                    'r' -> str.append('\r')
                    't' -> str.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length()) {
                            throw Starlark.errorf("incomplete \\uXXXX escape")
                        }
                        val utf16String = StringBuilder()
                        utf16String.append(parseUnicodeEscape())
                        // Parse any additional \\uXXXX escapes so that surrogate pairs are decoded correctly.
                        while (i + 6 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                            i += 2
                            utf16String.append(parseUnicodeEscape())
                        }
                        // Append the unescaped Unicode string as raw UTF-{8,16} bytes. See the comment on the
                        // CharsetEncoder instances above for why we can't use String#getBytes(...).
                        val byteBuffer: ByteBuffer?
                        try {
                            byteBuffer =
                                (if (utf8ByteStrings) createUtf8Encoder() else createUtf16Encoder())
                                    .encode(CharBuffer.wrap(utf16String))
                        } catch (e: CharacterCodingException) {
                            // Cannot happen because we replace with U+FFFD.
                            throw IllegalStateException(e)
                        }
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        if (utf8ByteStrings) {
                            str.append(String(bytes, StandardCharsets.ISO_8859_1))
                        } else {
                            str.append(String(bytes, StandardCharsets.UTF_16))
                        }
                    }

                    else -> throw Starlark.errorf("invalid escape '\\%s'", c)
                }
            }
            throw Starlark.errorf("unclosed string literal")
        }

        @Throws(EvalException::class)
        fun parseUnicodeEscape(): Char {
            var hex = 0
            for (j in 0..3) {
                val c: Char = s.charAt(i + j)
                val nybble: Int
                if (isdigit(c)) {
                    nybble = c.code - '0'.code
                } else if ('a' <= c && c <= 'f') {
                    nybble = 10 + c.code - 'a'.code
                } else if ('A' <= c && c <= 'F') {
                    nybble = 10 + c.code - 'A'.code
                } else {
                    throw Starlark.errorf("invalid hex char %s in \\uXXXX escape", quoteChar(c))
                }
                hex = (hex shl 4) or nybble
            }
            i += 4
            // 4 hex bytes -> 1 UTF-16 code unit
            return hex.toChar()
        }

        @Throws(EvalException::class)
        fun parseNumber(c: Char): Any? {
            // For now, allow any sequence of [0-9.eE+-]*.
            var c = c
            var isfloat = false // whether digit string contains [.Ee+-] (other than leading minus)
            var j = i
            j = i + 1
            while (j < s.length()) {
                c = s.charAt(j)
                if (isdigit(c)) {
                    // ok
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isfloat = true
                } else {
                    break
                }
                j++
            }

            val num: String = s.substring(i, j)

            var digits = i // s[digits:j] is the digit string
            if (s.charAt(i) == '-') {
                digits++
            }

            // Structural checks not performed by parse routines below.
            // Unlike most C-like languages,
            // JSON disallows a leading zero before a digit.
            if (digits == j // "-"
                || s.charAt(digits) == '.' // ".5"
                || s.charAt(j - 1) == '.' // "0."
                || num.contains(".e") // "5.e1"
                || (s.charAt(digits) == '0' && j - digits > 1 && isdigit(s.charAt(digits + 1)))
            ) { // "01"
                throw Starlark.errorf("invalid number: %s", num)
            }

            i = j

            // parse number literal
            try {
                if (isfloat) {
                    val x = Double.parseDouble(num)
                    return StarlarkFloat.of(x)
                } else {
                    return StarlarkInt.parse(num, 10)
                }
            } catch (unused: NumberFormatException) {
                throw Starlark.errorf("invalid number: %s", num)
            }
        }

        // skipSpace consumes leading spaces, and reports whether there is more input.
        fun skipSpace(): Boolean {
            while (i < s.length()) {
                val c: Char = s.charAt(i)
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return true
                }
                i++
            }
            return false
        }

        // next consumes leading spaces and returns the first non-space.
        @Throws(EvalException::class)
        fun next(): Char {
            if (skipSpace()) {
                return s.charAt(i)
            }
            throw Starlark.errorf("unexpected end of file")
        }

        companion object {
            private fun createUtf8Encoder(): CharsetEncoder {
                // The default encoding behavior for Java's UTF-8 encoder is to replace with '?', not the
                // Unicode replacement character U+FFFD. This also applies to String#getBytes(...).
                return StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("\uFFFD".getBytes(StandardCharsets.UTF_8))
            }

            private fun createUtf16Encoder(): CharsetEncoder {
                // The default encoding behavior for Java's UTF-16 encoder is to replace with the Unicode
                // replacement character U+FFFD, but this doesn't apply to String#getBytes(...).
                return StandardCharsets.UTF_16
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
            }
        }
    }

    @StarlarkMethod(
        name = "indent",
        doc = ("The indent function returns the indented form of a valid JSON-encoded string.\n"
                + "Each array element or object field appears on a new line, beginning with"
                + " the prefix string followed by one or more copies of the indent string, according"
                + " to its nesting depth.\n"
                + "The function accepts one required positional parameter, the JSON string,\n"
                + "and two optional keyword-only string parameters, prefix and indent,\n"
                + "that specify a prefix of each new line, and the unit of indentation.\n"
                + "If the input is not valid, the function may fail or return invalid output.\n"),
        parameters = [Param(name = "s"), Param(
            name = "prefix",
            positional = false,
            named = true,
            defaultValue = "''"
        ), Param(name = "indent", positional = false, named = true, defaultValue = "'\\t'")]
    )
    @Throws(
        EvalException::class
    )
    fun indent(s: String, prefix: String?, indent: String?): String {
        // Indentation can be efficiently implemented in a single pass, independent of encoding,
        // with no state other than a depth counter. This separation enables efficient indentation
        // of values obtained from, say, reading a file, without the need for decoding.

        val `in` = Indenter(prefix, indent, s)
        try {
            `in`.indent()
        } catch (unused: StringIndexOutOfBoundsException) {
            throw Starlark.errorf("input is not valid JSON")
        }
        return `in`.out.toString()
    }

    @StarlarkMethod(
        name = "encode_indent",
        doc = ("The encode_indent function is equivalent to <code>json.indent(json.encode(x),"
                + " ...)</code>. See <code>indent</code> for description of formatting parameters."),
        parameters = [Param(name = "x"), Param(
            name = "prefix",
            positional = false,
            named = true,
            defaultValue = "''"
        ), Param(name = "indent", positional = false, named = true, defaultValue = "'\\t'")],
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class, InterruptedException::class
    )
    fun encodeIndent(x: Any?, prefix: String?, indent: String?, thread: StarlarkThread): String {
        return indent(encode(x, thread), prefix, indent)
    }

    private class Indenter(prefix: String?, indent: String?, s: String) {
        private val out = StringBuilder()
        private val prefix: String?
        private val indent: String?
        private val s: String // input string
        private var i = 0 // current index in s, possibly out of bounds

        init {
            this.prefix = prefix
            this.indent = indent
            this.s = s
        }

        // Appends a single JSON value to str.
        // May throw StringIndexOutOfBoundsException.
        //
        // The current implementation is a rudimentary placeholder:
        // given invalid JSON, it produces garbage output.
        // TODO(adonovan): factor Decoder and Indenter using a
        // validating state machine, without loss of efficiency.
        // This requires different states after [, {, :, etc,
        // and a stack of open tokens.
        @Throws(EvalException::class)
        fun indent() {
            var depth = 0

            // token loop
            do { // while (depth > 0)
                var c = next()
                val start = i
                when (c) {
                    '"' -> {
                        c = s.charAt(++i)
                        while (c != '"') {
                            if (c == '\\') {
                                c = s.charAt(++i)
                                if (c == 'u') {
                                    i += 4
                                }
                            }
                            c = s.charAt(++i)
                        }
                        i++ // '"'
                        out.append(s, start, i)
                    }

                    'n' -> {
                        i += "null".length()
                        out.append(s, start, i)
                    }

                    't' -> {
                        i += "true".length()
                        out.append(s, start, i)
                    }

                    'f' -> {
                        i += "false".length()
                        out.append(s, start, i)
                    }

                    ',' -> {
                        i++
                        out.append(',')
                        newline(depth)
                    }

                    '[', '{' -> {
                        i++
                        out.append(c)
                        c = next()
                        if (c == ']' || c == '}') {
                            i++
                            out.append(c)
                        } else {
                            newline(++depth)
                        }
                    }

                    ']', '}' -> {
                        i++
                        newline(--depth)
                        out.append(c)
                    }

                    ':' -> {
                        i++
                        out.append(": ")
                    }

                    else -> {
                        // number
                        if (!(isdigit(c) || c == '-')) {
                            throw Starlark.errorf("unexpected character %s", quoteChar(c))
                        }
                        while (i < s.length()) {
                            c = s.charAt(++i)
                            if (!(isdigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                                break
                            }
                        }
                        out.append(s, start, i)
                    }
                }
            } while (depth > 0)
        }

        fun newline(depth: Int) {
            out.append('\n').append(prefix)
            for (i in 0..<depth) {
                out.append(indent)
            }
        }

        // skipSpace consumes leading spaces, and reports whether there is more input.
        fun skipSpace(): Boolean {
            while (i < s.length()) {
                val c: Char = s.charAt(i)
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return true
                }
                i++
            }
            return false
        }

        // next consumes leading spaces and returns the first non-space.
        @Throws(EvalException::class)
        fun next(): Char {
            if (skipSpace()) {
                return s.charAt(i)
            }
            throw Starlark.errorf("unexpected end of file")
        }
    }

    companion object {
        /**
         * The module instance. You may wish to add this to your predeclared environment under the name
         * "json".
         */
        @kotlin.jvm.JvmField
        val INSTANCE: Json = Json()

        private val HEX: CharArray = "0123456789abcdef".toCharArray()

        private fun isdigit(c: Char): Boolean {
            return c >= '0' && c <= '9'
        }

        // Returns a Starlark string literal that denotes c.
        private fun quoteChar(c: Char): String? {
            return Starlark.repr("" + c, StarlarkSemantics.DEFAULT)
        }
    }
}
