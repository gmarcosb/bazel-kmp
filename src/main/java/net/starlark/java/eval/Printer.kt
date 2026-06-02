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
package net.starlark.java.eval

import net.starlark.java.eval.Printer.Companion.format
import java.util.MissingFormatWidthException

/**
 * A printer of Starlark values.
 * 
 * 
 * Subclasses may override methods such as [.repr] and [.printList] to alter the
 * formatting behavior.
 */
// TODO(adonovan): disallow printing of objects that are not Starlark values.
open class Printer @kotlin.jvm.JvmOverloads constructor(buffer: java.lang.StringBuilder = java.lang.StringBuilder()) {
    private val buffer: java.lang.StringBuilder

    // Stack of values in the middle of being printed.
    // Each renders as "..." if recursively encountered,
    // indicating a cycle.
    private var stack: Array<Any?>?
    private var depth = 0

    /** Creates a printer that writes to the given buffer.  */
    /** Creates a printer that uses a fresh buffer.  */
    init {
        this.buffer = buffer
    }

    /** Appends a char to the printer's buffer  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun append(c: Char): Printer {
        buffer.append(c)
        return this
    }

    /** Appends a char sequence to the printer's buffer  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun append(s: CharSequence?): Printer {
        buffer.append(s)
        return this
    }

    /** Appends a char subsequence to the printer's buffer  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun append(s: CharSequence?, start: Int, end: Int): Printer {
        buffer.append(s, start, end)
        return this
    }

    /** Appends an integer to the printer's buffer  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun append(i: Int): Printer {
        buffer.append(i)
        return this
    }

    /** Appends a long integer to the printer's buffer  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun append(l: Long): Printer {
        buffer.append(l)
        return this
    }

    /**
     * Appends a list to the printer's buffer. List elements are rendered with `repr`.
     * 
     * 
     * May be overridden by subclasses.
     * 
     * @param list the list of objects to repr (each as with repr)
     * @param before a string to print before the list items, e.g. an opening bracket
     * @param separator a separator to print between items
     * @param after a string to print after the list items, e.g. a closing bracket
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun printList(
        list: Iterable<*>,
        before: String?,
        separator: String?,
        after: String?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ): Printer {
        this.append(before)
        var sep: String? = ""
        for (elem in list) {
            this.append(sep)
            sep = separator
            this.repr(elem!!, semantics)
        }
        return this.append(after)
    }

    override fun toString(): String {
        return buffer.toString()
    }

    /**
     * Appends the `StarlarkValue.debugPrint` representation of a value (as used by the Starlark
     * `print` statement) to the printer's buffer.
     * 
     * 
     * Implementations of StarlarkValue may define their own behavior of `debugPrint`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun debugPrint(o: Any, thread: net.starlark.java.eval.StarlarkThread): Printer? {
        if (o is net.starlark.java.eval.StarlarkValue) {
            (o as net.starlark.java.eval.StarlarkValue).debugPrint(this, thread)
            return this
        }

        return this.str(o, thread.getSemantics())
    }

    /**
     * Appends the `StarlarkValue.str` representation of a value to the printer's buffer. Unlike
     * `repr(x)`, it does not quote strings at top level, though strings and other values
     * appearing as elements of other structures are quoted as if by `repr`.
     * 
     * 
     * Implementations of StarlarkValue may define their own behavior of `str`.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun str(o: Any, semantics: net.starlark.java.eval.StarlarkSemantics?): Printer? {
        if (o is String) {
            return this.append(o)
        } else if (o is net.starlark.java.eval.StarlarkValue) {
            (o as net.starlark.java.eval.StarlarkValue).str(this, semantics)
            return this
        } else {
            return this.repr(o, semantics)
        }
    }

    /**
     * Appends the `StarlarkValue.repr` (quoted) representation of a value to the printer's
     * buffer. The quoted form is often a Starlark expression that evaluates to the value.
     * 
     * 
     * Implementations of StarlarkValue may define their own behavior of `repr`.
     * 
     * 
     * Cyclic values are rendered as `...` if they are recursively encountered by the same
     * printer. (Implementations of [StarlarkValue.repr] should avoid calling `Starlark#repr`, as it creates another printer, which hide cycles from the cycle detector.)
     * 
     * 
     * In addition to Starlark values, `repr` also prints instances of classes Map, List,
     * Map.Entry, or Class. All other values are formatted using their `toString` method.
     * TODO(adonovan): disallow that.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    open fun repr(o: Any, semantics: net.starlark.java.eval.StarlarkSemantics?): Printer? {
        // atomic values (leaves of the object graph)
        when (o) {
            null -> {
                // Java null is not a valid Starlark value, but sometimes printers are used on non-Starlark
                // values such as Locations or Nodes.
                return append("null")
            }

            -> {
                return appendQuoted(s)
            }

            -> {
                starlarkInt.repr(this, semantics)
                return this
            }

            -> {
                return append(if (b) "True" else "False")
            }

            -> {
                return append(i) // a non-Starlark value
            }

            -> {
                return append(net.starlark.java.eval.Starlark.Companion.classType(aClass)) // a non-Starlark value
            }

            else -> {}
        }

        // compound values (may form cycles in the object graph)
        if (!push(o)) {
            return append("...") // elided cycle
        }
        try {
            when (o) {
                -> value.repr(this, semantics)
                -> printList(map.entrySet(), "{", ", ", "}", semantics)
                -> printList(list, "[", ", ", "]", semantics)
                -> this.repr(entry.getKey(), semantics)!!.append(": ").repr(entry.getValue(), semantics)
                else ->  // All other non-Starlark Java values (e.g. Node, Location).
                    // Starlark code cannot access values of o that would reach here,
                    // and native code is already trusted to be deterministic.
                    append(o.toString())
            }
        } finally {
            pop()
        }

        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun appendQuoted(s: String): Printer {
        this.append('"')
        val len: Int = s.length()
        for (i in 0..<len) {
            val c: Char = s.charAt(i)
            escapeCharacter(c)
        }
        return this.append('"')
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun appendTripleQuoted(s: String, quoteChar: Char): Printer {
        com.google.common.base.Preconditions.checkArgument(
            quoteChar == net.starlark.java.eval.Printer.Companion.Q1 || quoteChar == net.starlark.java.eval.Printer.Companion.Q2,
            "quoteChar must be ' or \""
        )
        val delimiter: String = java.lang.String.valueOf(quoteChar).repeat(3)
        this.append(delimiter)
        val otherQuote: Char =
            (if (quoteChar == net.starlark.java.eval.Printer.Companion.Q2) net.starlark.java.eval.Printer.Companion.Q1 else net.starlark.java.eval.Printer.Companion.Q2)
        for (i in 0..<s.length()) {
            val c: Char = s.charAt(i)
            if (c == otherQuote || c == '\n') {
                this.append(c)
            } else if (c == quoteChar) {
                // Escape quoteChar only if it would otherwise be interpreted as the start of the closing
                // delimiter:
                if (i + 1 == s.length()) {
                    // ... if it immediately precedes the closing delimiter
                    this.append('\\').append(c)
                } else if (i + 3 <= s.length() && s.substring(i, i + 3) == delimiter) {
                    // ... or if it's part of an embedded triple-quote substring.
                    this.append('\\').append(c)
                } else {
                    this.append(c)
                }
            } else {
                escapeCharacter(c)
            }
        }
        return this.append(delimiter)
    }

    /**
     * Appends a "pretty" quoted string representation of `s` to the printer's buffer.
     * 
     * 
     * It heuristically chooses between single-line double quotes, and triple quotes (either ''' or
     * """) based on the content of the string to minimize escaping.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun appendPrettyQuoted(s: String): Printer {
        if (!s.contains("\n")) {
            return appendQuoted(s)
        }
        return appendTripleQuoted(s, net.starlark.java.eval.Printer.Companion.determineQuoteChar(s))
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun backslashChar(c: Char): Printer {
        return this.append('\\').append(c)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun escapeCharacter(c: Char): Printer? {
        if (c == '"') {
            return backslashChar(c)
        }
        return when (c) {
            '\\' -> backslashChar('\\')
            '\r' -> backslashChar('r')
            '\n' -> backslashChar('n')
            '\t' -> backslashChar('t')
            else -> {
                if (c.code < 32) {
                    // TODO(bazel-team): support \x escapes
                    this.append(java.lang.String.format("\\x%02x", c.code))
                }
                this.append(c) // no need to support UTF-8
            }
        }
    }

    // Reports whether x is already present on the visitation stack, pushing it if not.
    private fun push(x: Any?): Boolean {
        // cyclic?
        for (i in 0..<depth) {
            if (x === stack!![i]) {
                return false
            }
        }

        if (stack == null) {
            this.stack = arrayOfNulls<Any>(4)
        } else if (depth == stack!!.size) {
            this.stack = java.util.Arrays.copyOf<Any?>(stack, 2 * stack!!.size)
        }
        this.stack!![depth++] = x
        return true
    }

    private fun pop() {
        this.stack!![--depth] = null
    }

    companion object {
        private const val Q1 = '\''
        private const val Q2 = '"'

        private fun determineQuoteChar(s: String): Char {
            var doubleQuotes = 0
            var singleQuotes = 0
            for (i in 0..<s.length()) {
                val c: Char = s.charAt(i)
                if (c == net.starlark.java.eval.Printer.Companion.Q2) {
                    doubleQuotes++
                } else if (c == net.starlark.java.eval.Printer.Companion.Q1) {
                    singleQuotes++
                }
            }

            var quoteChar: Char = net.starlark.java.eval.Printer.Companion.Q2
            val startsOrEndsWithDouble = s.startsWith("\"") || s.endsWith("\"")
            val startsOrEndsWithSingle = s.startsWith("'") || s.endsWith("'")

            if (doubleQuotes > singleQuotes) {
                quoteChar = net.starlark.java.eval.Printer.Companion.Q1
            } else if (startsOrEndsWithDouble && !startsOrEndsWithSingle) {
                quoteChar = net.starlark.java.eval.Printer.Companion.Q1
            }

            return quoteChar
        }

        /**
         * Appends a string, formatted as if by Starlark's `str % tuple` operator, to the printer's
         * buffer.
         * 
         * 
         * Supported conversions:
         * 
         * 
         *  * `%s` (convert as if by `str()`)
         *  * `%r` (convert as if by `repr()`)
         *  * `%d` (convert an integer to its decimal representation)
         * 
         * 
         * To encode a literal percent character, escape it as `%%`. It is an error to have a
         * non-escaped `%` at the end of the string or followed by any character not listed above.
         * 
         * @param format the format string
         * @param arguments an array containing arguments to substitute into the format operators in order
         * @throws IllegalFormatException if the format string is invalid or the arguments do not match it
         */
        fun format(
            printer: Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            format: String,
            vararg arguments: Any?
        ) {
            net.starlark.java.eval.Printer.Companion.formatWithList(
                printer,
                semantics,
                format,
                java.util.Arrays.asList<Any?>(*arguments)
            )
        }

        /** Same as [.format], but with a list instead of variadic args.  */
        // see b/178189609
        fun formatWithList(
            printer: Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            pattern: String,
            arguments: MutableList<*>
        ) {
            // N.B. MissingFormatWidthException is the only kind of IllegalFormatException
            // whose constructor can take and display arbitrary error message, hence its use below.
            // TODO(adonovan): this suggests we're using the wrong exception. Throw IAE?

            val length: Int = pattern.length()
            val argLength: Int = arguments.size()
            var i = 0 // index of next character in pattern
            var a = 0 // index of next argument in arguments

            while (i < length) {
                val p: Int = pattern.indexOf('%'.code, i)
                if (p == -1) {
                    printer.append(pattern, i, length)
                    break
                }
                if (p > i) {
                    printer.append(pattern, i, p)
                }
                if (p == length - 1) {
                    throw MissingFormatWidthException(
                        "incomplete format pattern ends with %: " + net.starlark.java.eval.Starlark.Companion.repr(
                            pattern,
                            semantics
                        )
                    )
                }
                val conv: Char = pattern.charAt(p + 1)
                i = p + 2

                // %%: literal %
                if (conv == '%') {
                    printer.append('%')
                    continue
                }

                // get argument
                if (a >= argLength) {
                    throw MissingFormatWidthException(
                        ("not enough arguments for format pattern "
                                + net.starlark.java.eval.Starlark.Companion.repr(pattern, semantics)
                                + ": "
                                + net.starlark.java.eval.Starlark.Companion.repr(
                            net.starlark.java.eval.Tuple.Companion.copyOf(
                                arguments
                            ), semantics
                        ))
                    )
                }
                val arg: Any = arguments.get(a++)!!

                when (conv) {
                    'd', 'o', 'x', 'X' -> {
                        val n: Number? =
                            when (arg) {
                                -> starlarkInt.toNumber()
                                -> integer
                                -> {
                                    val d: Double = starlarkFloat.toDouble()
                                    try {
                                        net.starlark.java.eval.StarlarkInt.Companion.ofFiniteDouble(d).toNumber()
                                    } catch (unused: java.lang.IllegalArgumentException) {
                                        throw MissingFormatWidthException("got " + arg + ", want a finite number")
                                    }
                                }

                                else -> throw MissingFormatWidthException(
                                    java.lang.String.format(
                                        "got %s for '%%%c' format, want int or float",
                                        net.starlark.java.eval.Starlark.Companion.type(arg), conv
                                    )
                                )
                            }
                        printer.append(
                            java.lang.String.format(
                                if (conv == 'd') "%d" else if (conv == 'o') "%o" else if (conv == 'x') "%x" else "%X", n
                            )
                        )
                    }

                    'e', 'f', 'g', 'E', 'F', 'G' -> {
                        val v: Double =
                            when (arg) {
                                -> integer.toDouble()
                                -> starlarkInt.toDouble()
                                -> starlarkFloat.toDouble()
                                else -> throw MissingFormatWidthException(
                                    java.lang.String.format(
                                        "got %s for '%%%c' format, want int or float",
                                        net.starlark.java.eval.Starlark.Companion.type(arg), conv
                                    )
                                )
                            }
                        printer.append(net.starlark.java.eval.StarlarkFloat.Companion.format(v, conv))
                    }

                    'r' -> printer.repr(arg, semantics)
                    's' -> printer.str(arg, semantics)
                    else ->  // The call to Starlark.repr doesn't cause an infinite recursion
                        // because it's only used to format a string properly.
                        throw MissingFormatWidthException(
                            java.lang.String.format(
                                "unsupported format character \"%s\" at index %s in %s",
                                conv, p + 1, net.starlark.java.eval.Starlark.Companion.repr(pattern, semantics)
                            )
                        )
                }
            }
            if (a < argLength) {
                throw MissingFormatWidthException("not all arguments converted during string formatting")
            }
        }
    }
}
