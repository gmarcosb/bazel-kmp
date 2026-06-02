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
package net.starlark.java.syntax

/**
 * Base class for all expression nodes in the AST.
 * 
 * 
 * The only expressions permitted on the left-hand side of an assignment (such as 'lhs=rhs' or
 * 'for lhs in expr') are identifiers, dot expressions (x.y), list expressions ([expr, ...]), tuple
 * expressions ((expr, ...)), or parenthesized variants of those. In particular and unlike Python,
 * slice expressions and starred expressions cannot appear on the LHS. TODO(bazel-team): Add support
 * for assigning to slices (e.g. a[2:6] = [3]).
 */
abstract class Expression internal constructor(locs: FileLocations?, kind: Kind?) : Node(locs) {
    /**
     * Kind of the expression. This is similar to using instanceof, except that it's more efficient
     * and can be used in a switch/case.
     */
    enum class Kind {
        BINARY_OPERATOR,
        CALL,
        CAST,
        COMPREHENSION,
        CONDITIONAL,
        DICT_EXPR,
        DOT,
        ELLIPSIS,
        FLOAT_LITERAL,
        IDENTIFIER,
        INDEX,
        INT_LITERAL,
        ISINSTANCE,
        LAMBDA,
        LIST_EXPR,
        SLICE,
        STRING_LITERAL,
        UNARY_OPERATOR,
        TYPE_APPLICATION,
    }

    // Materialize kind as a field so its accessor can be non-virtual.
    private val kind: Kind?

    init {
        this.kind = kind
    }

    /**
     * Kind of the expression. This is similar to using instanceof, except that it's more efficient
     * and can be used in a switch/case.
     */
    // Final to avoid cost of virtual call (see #12967).
    fun kind(): Kind? {
        return kind
    }

    companion object {
        /** Parses an expression.  */
        /** Parses an expression with default options.  */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        @Throws(SyntaxError.Exception::class)
        fun parse(input: ParserInput?, options: FileOptions? = FileOptions.Companion.DEFAULT): Expression? {
            return Parser.Companion.parseExpression(input, options)
        }

        /**
         * Parses a type expression.
         * 
         * @param options parsing options; note that [FileOptions.allowStarlarkTypeSyntax] doesn't
         * need to be set - this method supports Starlark types implicitly.
         */
        /** Parses a type expression with default options.  */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        @Throws(SyntaxError.Exception::class)
        fun parseTypeExpression(
            input: ParserInput?,
            options: FileOptions? = FileOptions.Companion.DEFAULT
        ): Expression? {
            return Parser.Companion.parseTypeExpression(input, options)
        }
    }
}
