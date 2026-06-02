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
package net.starlark.java.eval

/**
 * A helper class that offers a subset of the functionality of Python's string#format.
 * 
 * 
 * Currently, both manual and automatic positional as well as named replacement fields are
 * supported. However, nested replacement fields are not allowed.
 */
internal class FormatParser {
    /**
     * Formats the given input string by using the given arguments
     * 
     * 
     * This method offers a subset of the functionality of Python's string#format
     * 
     * @param input The string to be formatted
     * @param args Positional arguments
     * @param kwargs Named arguments
     * @return The formatted string
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun format(
        input: String,
        args: MutableList<Any?>,
        kwargs: MutableMap<String?, Any?>,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ): String {
        val chars: CharArray = input.toCharArray()
        val output: java.lang.StringBuilder = java.lang.StringBuilder()
        val history: History = net.starlark.java.eval.FormatParser.History()

        var pos = 0
        while (pos < chars.size) {
            val current = chars[pos]
            var advancePos = 0

            if (current == '{') {
                advancePos = processOpeningBrace(chars, pos, args, kwargs, history, output, semantics)
            } else if (current == '}') {
                advancePos = processClosingBrace(chars, pos, output)
            } else {
                output.append(current)
            }

            pos += advancePos
            ++pos
        }

        return output.toString()
    }

    /**
     * Processes the expression after an opening brace (possibly a replacement field) and emits the
     * result to the output StringBuilder
     * 
     * @param chars The entire string
     * @param pos The position of the opening brace
     * @param args List of positional arguments
     * @param kwargs Map of named arguments
     * @param history Helper object that tracks information about previously seen positional
     * replacement fields
     * @param output StringBuilder that consumes the result
     * @return Number of characters that have been consumed by this method
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun processOpeningBrace(
        chars: CharArray,
        pos: Int,
        args: MutableList<Any?>,
        kwargs: MutableMap<String?, Any?>,
        history: History,
        output: java.lang.StringBuilder?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ): Int {
        val printer: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer(output)
        if (net.starlark.java.eval.FormatParser.Companion.has(chars, pos + 1, '{')) {
            // Escaped brace -> output and move to char after right brace
            printer.append("{")
            return 1
        }

        // Inside a replacement field
        val key = getFieldName(chars, pos)
        var value: Any? = null

        // Only positional replacement fields will lead to a valid index
        try {
            if (key.isEmpty() || net.starlark.java.eval.FormatParser.Companion.LIKELY_NUMERIC_MATCHER.matchesAllOf(key)) {
                val index = parsePositional(key, history)

                if (index < 0 || index >= args.size()) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf("No replacement found for index %d", index)
                }

                value = args.get(index)
            } else {
                value = getKwarg(kwargs, key)
            }
        } catch (nfe: java.lang.NumberFormatException) {
            // Non-integer index -> Named
            value = getKwarg(kwargs, key)
        }

        // Format object for output
        printer.str(value, semantics)

        // Advances the current position to the index of the closing brace of the
        // replacement field. Due to the definition of the enclosing for() loop,
        // the next iteration will examine the character right after the brace.
        return key.length() + 1
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getKwarg(kwargs: MutableMap<String?, Any?>, key: String?): Any? {
        if (!kwargs.containsKey(key)) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("Missing argument '%s'", key)
        }

        return kwargs.get(key)
    }

    /**
     * Processes a closing brace and emits the result to the output StringBuilder
     * 
     * @param chars The entire string
     * @param pos Position of the closing brace
     * @param output StringBuilder that consumes the result
     * @return Number of characters that have been consumed by this method
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun processClosingBrace(chars: CharArray, pos: Int, output: java.lang.StringBuilder): Int {
        if (!net.starlark.java.eval.FormatParser.Companion.has(chars, pos + 1, '}')) {
            // Invalid brace outside replacement field
            throw net.starlark.java.eval.Starlark.Companion.errorf("Found '}' without matching '{'")
        }

        // Escaped brace -> output and move to char after right brace
        output.append("}")
        return 1
    }

    /**
     * Extracts the name/index of the replacement field that starts at the specified location
     * 
     * @param chars Input string
     * @param openingBrace Position of the opening brace of the replacement field
     * @return Name or index of the current replacement field
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getFieldName(chars: CharArray, openingBrace: Int): String {
        val result: java.lang.StringBuilder = java.lang.StringBuilder()
        var foundClosingBrace = false

        for (pos in openingBrace + 1..<chars.size) {
            val current = chars[pos]

            if (current == '}') {
                foundClosingBrace = true
                break
            } else {
                if (current == '{') {
                    throw net.starlark.java.eval.Starlark.Companion.errorf("Nested replacement fields are not supported")
                } else if (net.starlark.java.eval.FormatParser.Companion.ILLEGAL_IN_FIELD.contains(current)) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "Invalid character '%s' inside replacement field",
                        current
                    )
                }

                result.append(current)
            }
        }

        if (!foundClosingBrace) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("Found '{' without matching '}'")
        }

        return result.toString()
    }

    /**
     * Converts the given key into an integer or assigns the next available index, if empty.
     * 
     * @param key Key to be converted
     * @param history Helper object that tracks information about previously seen positional
     * replacement fields
     * @return The integer equivalent of the key
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun parsePositional(key: String, history: History): Int {
        var result = -1

        try {
            if (key.isEmpty()) {
                // Automatic positional -> a new index value has to be assigned
                history.setAutomaticPositional()
                result = history.getNextPosition()
            } else {
                // This will fail if key is a named argument
                result = java.lang.Integer.parseInt(key)
                history.setManualPositional() // Only register if the conversion succeeds
            }
        } catch (mte: MixedTypeException) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("%s", mte.getMessage())
        }

        return result
    }

    /**
     * Exception for invalid combinations of replacement field types
     */
    private class MixedTypeException :
        java.lang.Exception("Cannot mix manual and automatic numbering of positional fields")

    /**
     * A wrapper to keep track of information about previous replacement fields
     */
    private class History {
        /** Different types of positional replacement fields  */
        internal enum class Positional {
            NONE,
            MANUAL,  // {0}, {1} etc.
            AUTOMATIC // {}
        }

        var type: Positional? = net.starlark.java.eval.FormatParser.History.Positional.NONE
        var position: Int = -1

        /**
         * Returns the next available index for an automatic positional replacement field
         * 
         * @return Next index
         */
        fun getNextPosition(): Int {
            ++position
            return position
        }

        /** Registers a manual positional replacement field  */
        @Throws(net.starlark.java.eval.FormatParser.MixedTypeException::class)
        fun setManualPositional() {
            setPositional(net.starlark.java.eval.FormatParser.History.Positional.MANUAL)
        }

        /** Registers an automatic positional replacement field  */
        @Throws(net.starlark.java.eval.FormatParser.MixedTypeException::class)
        fun setAutomaticPositional() {
            setPositional(net.starlark.java.eval.FormatParser.History.Positional.AUTOMATIC)
        }

        /**
         * Indicates that a positional replacement field of the specified type is being processed and
         * checks whether this conflicts with any previously seen replacement fields
         * 
         * @param current Type of current replacement field
         */
        @Throws(net.starlark.java.eval.FormatParser.MixedTypeException::class)
        fun setPositional(current: Positional?) {
            if (type == net.starlark.java.eval.FormatParser.History.Positional.NONE) {
                type = current
            } else if (type != current) {
                throw net.starlark.java.eval.FormatParser.MixedTypeException()
            }
        }
    }

    companion object {
        /**
         * Matches strings likely to be a number, faster alternative to relying solely on Integer.parseInt
         * and NumberFormatException to determine numericness.
         */
        private val LIKELY_NUMERIC_MATCHER: com.google.common.base.CharMatcher =
            com.google.common.base.CharMatcher.inRange('0', '9').or(com.google.common.base.CharMatcher.`is`('-'))

        private val ILLEGAL_IN_FIELD: com.google.common.collect.ImmutableSet<Char?> =
            com.google.common.collect.ImmutableSet.of<Char?>('.', '[', ']', ',')

        /**
         * Checks whether the given input string has a specific character at the given location
         * 
         * @param data Input string as character array
         * @param pos Position to be checked
         * @param needle Character to be searched for
         * @return True if string has the specified character at the given location
         */
        private fun has(data: CharArray, pos: Int, needle: Char): Boolean {
            return pos < data.size && data[pos] == needle
        }
    }
}
