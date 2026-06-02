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
package com.google.devtools.build.lib.analysis.stringtemplate

import com.google.common.collect.ImmutableSet

/**
 * Simple string template expansion. String templates consist of text interspersed with
 * `$(variable)` or `$(function value)` references, which are replaced by
 * strings.
 */
class TemplateExpander private constructor(expression: String) {
    private val buffer: CharArray
    private val length: Int
    private var offset = 0

    init {
        buffer = expression.toCharArray()
        length = buffer.size
    }

    @Throws(ExpansionException::class)
    private fun expand(context: TemplateContext, depth: Int): Expansion {
        val result = StringBuilder()
        val lookedUpVariables = ImmutableSet.builder<String?>()
        while (offset < length) {
            val c = buffer[offset]
            if (c == '$') { // variable
                offset++
                if (offset >= length) {
                    throw ExpansionException("unterminated $")
                }
                if (buffer[offset] == '$') {
                    result.append('$')
                } else {
                    val `var` = scanVariable()
                    val spaceIndex: Int = `var`.indexOf(' ')
                    if (spaceIndex < 0) {
                        var value = context.lookupVariable(`var`)
                        lookedUpVariables.add(`var`)
                        // To prevent infinite recursion for the ignored shell variables
                        if (value != `var`) {
                            // recursively expand using Make's ":=" semantics:
                            val expansion: Expansion = expand(value, context, depth + 1)
                            value = expansion.expansion
                            lookedUpVariables.addAll(expansion.lookedUpVariables)
                        }
                        result.append(value)
                    } else {
                        val name: String = `var`.substring(0, spaceIndex)
                        // Trim the string to remove leading and trailing whitespace.
                        val param: String = `var`.substring(spaceIndex + 1).trim { it <= ' ' }
                        val value = context.lookupFunction(name, param)
                        lookedUpVariables.add(name)
                        result.append(value)
                    }
                }
            } else {
                result.append(c)
            }
            offset++
        }
        return Expansion.Companion.create(result.toString(), lookedUpVariables.build())
    }

    /**
     * Starting at the current position, scans forward until the name of a template variable has been
     * consumed. Returns the variable name and advances the position. If the variable is a potential
     * shell variable returns the shell variable expression itself, so that we can let the shell
     * handle the expansion.
     * 
     * @return the name of the variable found at the current point.
     * @throws ExpansionException if the variable reference was ill-formed.
     */
    @Throws(ExpansionException::class)
    private fun scanVariable(): String {
        val c = buffer[offset]
        when (c) {
            '(' -> {
                // looks like $(SRCS)
                offset++
                val start = offset
                while (offset < length && buffer[offset] != ')') {
                    offset++
                }
                if (offset >= length) {
                    throw ExpansionException("unterminated variable reference")
                }
                return String(buffer, start, offset - start)
                // We only parse ${variable} syntax to provide a better error message.
            }

            '{' -> {
                // looks like ${SRCS}
                offset++
                val start = offset
                while (offset < length && buffer[offset] != '}') {
                    offset++
                }
                if (offset >= length) {
                    throw ExpansionException("unterminated variable reference")
                }
                val expr = String(buffer, start, offset - start)
                throw ExpansionException(
                    ("'\${"
                            + expr
                            + "}' syntax is not supported; use '$("
                            + expr
                            + ")' instead for \"Make\" variables, or escape the '$' as "
                            + "'$$' if you intended this for the shell")
                )
            }

            '@', '<', '^' -> {
                return c.toString()
            }

            else -> {
                val start = offset
                while (offset + 1 < length && Character.isJavaIdentifierPart(buffer[offset + 1])) {
                    offset++
                }
                val expr = String(buffer, start, offset + 1 - start)
                throw ExpansionException(
                    ("'$"
                            + expr
                            + "' syntax is not supported; use '$("
                            + expr
                            + ")' instead for \"Make\" variables, or escape the '$' as "
                            + "'$$' if you intended this for the shell")
                )
            }
        }
    }

    @get:Throws(ExpansionException::class)
    private val singleVariable: String?
        /**
         * @return the variable name if the variable spans from offset to the end of the buffer, otherwise
         * null
         * @throws ExpansionException if the variable reference was ill-formed
         */
        get() {
            if (buffer[offset] == '$') {
                offset++
                val result = scanVariable()
                if (offset + 1 == length) {
                    return result
                }
            }
            return null
        }

    companion object {
        /**
         * If the string contains a single variable, return the expansion of that variable. Otherwise,
         * return null.
         */
        @kotlin.jvm.JvmStatic
        @Throws(ExpansionException::class)
        fun expandSingleVariable(expression: String, context: TemplateContext): String? {
            val `var` = TemplateExpander(expression).singleVariable
            return if (`var` != null) context.lookupVariable(`var`) else null
        }

        /**
         * Expands all references to template variables embedded within string "expr", using the provided
         * [TemplateContext] instance to expand individual variables.
         * 
         * @param expression the string to expand.
         * @param context the context which defines the expansion of each individual variable
         * @return the expansion of "expr"
         * @throws ExpansionException if "expr" contained undefined or ill-formed variables references
         */
        @kotlin.jvm.JvmStatic
        @Throws(ExpansionException::class)
        fun expand(expression: String, context: TemplateContext): Expansion {
            if (expression.indexOf('$') < 0) {
                return Expansion.Companion.create(expression, ImmutableSet.of<String?>())
            }
            return expand(expression, context, 0)
        }

        // Helper method for counting recursion depth.
        @Throws(ExpansionException::class)
        private fun expand(expression: String, context: TemplateContext, depth: Int): Expansion {
            if (depth > 10) { // plenty!
                throw ExpansionException(
                    String.format("potentially unbounded recursion during expansion of '%s'", expression)
                )
            }
            return TemplateExpander(expression).expand(context, depth)
        }
    }
}
