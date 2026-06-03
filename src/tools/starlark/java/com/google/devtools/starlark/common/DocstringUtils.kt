// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.starlark.common

import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import java.lang.String
import java.util.*
import java.util.regex.Pattern
import kotlin.Boolean
import kotlin.Int

/** Utilities to extract and parse docstrings.  */
object DocstringUtils {
    /**
     * Parses a trimmed docstring.
     * 
     * 
     * The format of the docstring is as follows
     * 
     * <pre>`"""One-line summary: must be followed and may be preceded by a blank line. Optional additional description like this. If it's a function docstring and the function has more than one argument, the docstring has to document these parameters as follows: Args:   parameter1: description of the first parameter. Each parameter line     should be indented by one, preferably two, spaces (as here).   parameter2: description of the second     parameter that spans two lines. Each additional line should have a     hanging indentation of at least one, preferably two, additional spaces (as here).   another_parameter (unused, mutable): a parameter may be followed     by additional attributes in parentheses Returns:   Description of the return value.   Should be indented by at least one, preferably two spaces (as here)   Can span multiple lines. """ `</pre>
     * 
     * 
     * We expect the docstring to already be trimmed and dedented to a minimal common indentation
     * level by [Starlark.trimDocString] or an equivalent PEP-257 style trim() implementation;
     * note that [StarlarkFunction.getDocumentation] returns a correctly trimmed and dedented
     * doc string.
     * 
     * @param doc a docstring of the format described above
     * @param parseErrors a list to which parsing error messages are written
     * @return the parsed docstring information
     */
    fun parseDocstring(doc: String, parseErrors: MutableList<DocstringParseError?>): DocstringInfo {
        val parser = DocstringParser(doc)
        val result = parser.parse()
        parseErrors.addAll(parser.errors)
        return result
    }

    /** Encapsulates information about a Starlark function docstring.  */
    class DocstringInfo(
        /** The one-line summary at the start of the docstring.  */
        val summary: String?,
        parameters: MutableList<ParameterDoc?>,
        /** Documentation of the return value from the 'Returns:' section, or empty if there is none.  */
        val returns: String,
        /** Deprecation warning from the 'Deprecated:' section, or empty if there is none.  */
        val deprecated: String?,
        /** Rest of the docstring that is not part of any of the special sections above.  */
        val longDescription: String
    ) {
        /** Returns the one-line summary of the docstring.  */

        /**
         * Returns a list containing information about parameter documentation for the parameters of the
         * documented function.
         */
        /** Documentation of function parameters from the 'Args:' section.  */
        val parameters: MutableList<ParameterDoc?>

        /**
         * Returns the documentation of the return value from the 'Returns:' section, or empty if there
         * is none.
         */

        /**
         * Returns the deprecation warning from the 'Deprecated:' section, or empty if there is none.
         */

        /**
         * Returns the long-form description of the docstring. (Everything after the one-line summary
         * and before special sections such as "Args:".
         */

        init {
            this.parameters = ImmutableList.copyOf<ParameterDoc?>(parameters)
        }

        val isSingleLineDocstring: Boolean
            get() = longDescription.isEmpty() && parameters.isEmpty() && returns.isEmpty()
    }

    /**
     * Contains information about the documentation for function parameters of a Starlark function.
     */
    class ParameterDoc(val parameterName: String?, attributes: MutableList<String?>, val description: String?) {
        val attributes: MutableList<String?> // e.g. a type annotation, "unused", "mutable"

        init {
            this.attributes = ImmutableList.copyOf<String?>(attributes)
        }
    }

    private class DocstringParser(docstring: String) {
        /**
         * Current line number within the docstring, 1-based; 0 indicates that parsing has not started;
         * `lineNumber > lines.size()` indicates EOF.
         */
        private var lineNumber = 0

        /** Whether we've seen a special section, e.g. 'Args:', already.  */
        private var specialSectionsStarted = false

        /** List of all parsed lines in the docstring.  */
        private val lines: ImmutableList<String?>

        /** Iterator over lines.  */
        private val linesIter: MutableIterator<String>

        /** The current line in the docstring.  */
        private var line = ""

        /** Errors that occurred so far.  */
        private val errors: MutableList<DocstringParseError?> = ArrayList<DocstringParseError?>()

        /**
         * Move on to the next line and update the parser's internal state accordingly.
         * 
         * @return whether there are lines remaining to be parsed
         */
        fun nextLine(): Boolean {
            if (linesIter.hasNext()) {
                line = linesIter.next()
                lineNumber++
                return true
            } else {
                line = ""
                lineNumber = lines.size + 1
                return false
            }
        }

        fun eof(): Boolean {
            return lineNumber > lines.size
        }

        fun error(message: String?) {
            error(this.lineNumber, message)
        }

        fun error(lineNumber: Int, message: String?) {
            errors.add(DocstringParseError(message, lineNumber, lines.get(lineNumber - 1)))
        }

        fun parseArgumentSection(
            params: MutableList<ParameterDoc?>, returns: String, deprecated: String
        ) {
            checkSectionStart(!params.isEmpty())
            if (!returns.isEmpty()) {
                error("'Args:' section should go before the 'Returns:' section")
            }
            if (!deprecated.isEmpty()) {
                error("'Args:' section should go before the 'Deprecated:' section")
            }
            params.addAll(parseParameters())
        }

        fun parse(): DocstringInfo {
            val summary = line
            var nonStandardDeprecation = checkForNonStandardDeprecation(line)
            if (!nextLine()) {
                return DocstringInfo(summary, mutableListOf<ParameterDoc?>(), "", nonStandardDeprecation, "")
            }
            if (!line.isEmpty()) {
                error("the one-line summary should be followed by a blank line")
            } else {
                nextLine()
            }
            val longDescriptionLines: MutableList<String?> = ArrayList<String?>()
            val params: MutableList<ParameterDoc?> = ArrayList<ParameterDoc?>()
            var returns = ""
            var deprecated = ""
            var descriptionBodyAfterSpecialSectionsReported = false
            while (!eof()) {
                when (line) {
                    "Args:" -> parseArgumentSection(params, returns, deprecated)
                    "Arguments:" -> parseArgumentSection(params, returns, deprecated)
                    "Returns:" -> {
                        checkSectionStart(!returns.isEmpty())
                        if (!deprecated.isEmpty()) {
                            error("'Returns:' section should go before the 'Deprecated:' section")
                        }
                        returns = parseSectionAfterHeading()
                    }

                    "Deprecated:" -> {
                        checkSectionStart(!deprecated.isEmpty())
                        deprecated = parseSectionAfterHeading()
                    }

                    else -> {
                        if (specialSectionsStarted && !descriptionBodyAfterSpecialSectionsReported) {
                            error("description body should go before the special sections")
                            descriptionBodyAfterSpecialSectionsReported = true
                        }
                        if (deprecated.isEmpty() && nonStandardDeprecation.isEmpty()) {
                            nonStandardDeprecation = checkForNonStandardDeprecation(line)
                        }
                        if (line.startsWith("Returns: ")) {
                            error(
                                ("the return value should be documented in a section, like this:\n\n"
                                        + "Returns:\n"
                                        + "  <documentation here>\n\n"
                                        + "For more details, please have a look at the documentation.")
                            )
                        }
                        longDescriptionLines.add(line)
                        nextLine()
                    }
                }
            }
            if (deprecated.isEmpty()) {
                deprecated = nonStandardDeprecation
            }
            return DocstringInfo(
                summary, params, returns, deprecated, String.join("\n", longDescriptionLines)
            )
        }

        fun checkSectionStart(duplicateSection: Boolean) {
            specialSectionsStarted = true
            if (duplicateSection) {
                error("duplicate '" + line + "' section")
            }
        }

        fun checkForNonStandardDeprecation(line: kotlin.String): kotlin.String {
            if (line.lowercase().startsWith("deprecated:") || line.contains("DEPRECATED")) {
                error(
                    ("use a 'Deprecated:' section for deprecations, similar to a 'Returns:' section:\n\n"
                            + "Deprecated:\n"
                            + "  <reason and alternative>\n\n"
                            + "For more details, please have a look at the documentation.")
                )
                return line
            }
            return ""
        }

        init {
            this.lines = ImmutableList.copyOf<kotlin.String?>(Splitter.on("\n").split(docstring))
            this.linesIter = lines.iterator()
            // Load the summary line
            nextLine()
        }

        fun parseParameters(): MutableList<ParameterDoc?> {
            val sectionLineNumber = lineNumber
            nextLine()
            val params: MutableList<ParameterDoc?> = ArrayList<ParameterDoc?>()
            var expectedParamLineIndentation = -1
            while (!eof()) {
                if (line.isEmpty()) {
                    nextLine()
                    continue
                }
                val actualIndentation = getIndentation(line)
                if (actualIndentation == 0) {
                    break
                }
                val trimmedLine: kotlin.String?
                if (expectedParamLineIndentation == -1) {
                    expectedParamLineIndentation = actualIndentation
                }
                if (expectedParamLineIndentation != actualIndentation) {
                    error(
                        ("inconsistent indentation of parameter lines (before: "
                                + expectedParamLineIndentation
                                + "; here: "
                                + actualIndentation
                                + " spaces)")
                    )
                }
                val paramLineNumber = lineNumber
                trimmedLine = line.substring(actualIndentation)
                val matcher = paramLineMatcher.matcher(trimmedLine)
                if (!matcher.matches()) {
                    error(
                        ("invalid parameter documentation"
                                + " (expected format: \"parameter_name: documentation\")."
                                + " For more details, please have a look at the documentation.")
                    )
                    nextLine()
                    continue
                }
                val parameterName = Preconditions.checkNotNull<kotlin.String>(matcher.group("name"))
                val attributesString = matcher.group("attributes")
                val description = StringBuilder(matcher.group("description"))
                val attributes =
                    if (attributesString == null) mutableListOf<kotlin.String?>() else
                        Arrays.asList<kotlin.String?>(*attributesSeparator.split(attributesString))
                parseContinuedParamDescription(actualIndentation, description)
                val parameterDescription: kotlin.String = description.toString().trim { it <= ' ' }
                if (parameterDescription.isEmpty()) {
                    error(paramLineNumber, "empty parameter description for '" + parameterName + "'")
                }
                params.add(ParameterDoc(parameterName, attributes, parameterDescription))
            }
            if (params.isEmpty()) {
                error(sectionLineNumber, "section is empty or badly formatted")
            }
            return params
        }

        /** Parses additional lines that can come after "param: foo" in an 'Args' section.  */
        fun parseContinuedParamDescription(
            baselineIndentation: Int, description: StringBuilder
        ) {
            // Two iterations: first buffer lines and find the minimal indent, then trim to the min
            val buffer: MutableList<kotlin.String> = ArrayList<kotlin.String>()
            var continuationIndentation = Int.Companion.MAX_VALUE
            while (nextLine()) {
                if (!line.isEmpty()) {
                    if (getIndentation(line) <= baselineIndentation) {
                        break
                    }
                    continuationIndentation = min(getIndentation(line), continuationIndentation)
                }
                buffer.add(line)
            }

            for (bufLine in buffer) {
                description.append('\n')
                if (!bufLine.isEmpty()) {
                    val trimmedLine: kotlin.String = bufLine.substring(continuationIndentation)
                    description.append(trimmedLine)
                }
            }
        }

        fun parseSectionAfterHeading(): kotlin.String {
            val sectionLineNumber = lineNumber
            nextLine()
            val contents = StringBuilder()
            var firstLine = true
            while (!eof()) {
                val trimmedLine: kotlin.String?
                if (line.isEmpty()) {
                    trimmedLine = line
                } else if (getIndentation(line) == 0) {
                    break
                } else {
                    if (getIndentation(line) < 2) {
                        error(
                            "text in a section has to be indented by two spaces"
                                    + " (relative to the left margin of the docstring)"
                        )
                        trimmedLine = line.substring(getIndentation(line))
                    } else {
                        trimmedLine = line.substring(2)
                    }
                }
                if (!firstLine) {
                    contents.append('\n')
                }
                contents.append(trimmedLine)
                nextLine()
                firstLine = false
            }
            val result: kotlin.String = contents.toString().trim { it <= ' ' }
            if (result.isEmpty()) {
                error(sectionLineNumber, "section is empty")
            }
            return result
        }

        companion object {
            private fun getIndentation(line: kotlin.String): Int {
                var index = 0
                while (index < line.length && line.get(index) == ' ') {
                    index++
                }
                return index
            }

            private val paramLineMatcher: Pattern = Pattern.compile(
                "\\s*(?<name>[*\\w]+)( \\(\\s*(?<attributes>.*)\\s*\\))?: (?<description>.*)"
            )

            private val attributesSeparator: Pattern = Pattern.compile("\\s*,\\s*")
        }
    }

    /** Contains error information to reflect a docstring parse error.  */
    class DocstringParseError(
        /** Returns a descriptive method about the error which occurred.  */
        val message: kotlin.String?,
        /**
         * Returns the line number (skipping leading blank lines, if any) in the original doc string
         * which contains this error.
         */
        val lineNumber: Int,
        /** Returns the contents of the original line that caused the parse error.  */
        val line: kotlin.String?
    ) {
        override fun toString(): kotlin.String {
            return lineNumber.toString() + ": " + message
        }
    }
}
