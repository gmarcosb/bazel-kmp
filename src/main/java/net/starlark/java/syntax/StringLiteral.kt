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

import kotlin.collections.ArrayList

/** Syntax node for a string literal.  */
class StringLiteral internal constructor(locs: FileLocations?, startOffset: Int, value: String?, endOffset: Int) :
    Expression(locs, Kind.STRING_LITERAL) {
    // See skyframe.serialization.StringLiteralCodec for custom serialization logic.
    private val startOffset: Int
    @kotlin.jvm.JvmField
    private val value: String?
    private val endOffset: Int

    init {
        this.startOffset = startOffset
        this.value = value
        this.endOffset = endOffset
    }

    /** Returns the value denoted by the string literal  */
    fun getValue(): String? {
        return value
    }

    fun getLocation(): Location {
        return locs.getLocation(startOffset)
    }

    override fun getStartOffset(): Int {
        return startOffset
    }

    override fun getEndOffset(): Int {
        // TODO(adonovan): when we switch to compilation,
        // making syntax trees ephemeral, we can afford to
        // record the raw literal. This becomes:
        //   return startOffset + raw.length().
        return endOffset
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    // -- hooks to support Skyframe serialization without creating a dependency --
    /** Returns an opaque serializable object that may be passed to [.fromSerialization].  */
    fun getFileLocations(): Any {
        return locs
    }

    companion object {
        /**
         * Returns the value denoted by the Starlark string literal within s.
         * 
         * @throws IllegalArgumentException if s does not contain a valid string literal.
         */
        // TODO(bazel-team): We should in principle have an overload that allows non-default FileOptions.
        // But currently no FileOptions affect the behavior of this method, except to possibly make it
        // throw IAE on non-ASCII data.
        @kotlin.jvm.JvmStatic
        fun unquote(s: String): String? {
            // TODO(adonovan): once we have byte compilation, make this function
            // independent of the Lexer, which should only validate string literals
            // but not unquote them. Clients (e.g. the compiler) can unquote on demand.
            val errors = ArrayList<SyntaxError?>()
            val lexer = Lexer(ParserInput.Companion.fromLines(s), errors, FileOptions.Companion.DEFAULT)
            lexer.nextToken()
            require(errors.isEmpty()) { errors.get(0)!!.message() }
            require(!(lexer.start != 0 || lexer.end != s.length() || lexer.kind != TokenKind.STRING)) { "invalid syntax" }
            return lexer.value as String?
        }

        /** Constructs a StringLiteral from its serialized components.  */
        fun fromSerialization(
            fileLocations: Any?, startOffset: Int, value: String?, endOffset: Int
        ): StringLiteral {
            return StringLiteral(fileLocations as FileLocations?, startOffset, value, endOffset)
        }
    }
}
