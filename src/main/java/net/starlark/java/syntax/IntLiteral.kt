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

import java.math.BigInteger

/**
 * Syntax node for an int literal. The literal's value may be negative, since the parser simplifies
 * a unary minus operation applied on a positive int literal into a negative int literal.
 */
class IntLiteral internal constructor(locs: FileLocations?, tokenOffset: Int, endOffset: Int, value: Number?) :
    Expression(locs, Kind.INT_LITERAL) {
    private val tokenOffset: Int
    private val endOffset: Int
    @kotlin.jvm.JvmField
    private val value: Number? // = Integer | Long | BigInteger

    /**
     * Constructs an IntLiteral.
     * 
     * 
     * `value` must be either an Integer or Long or BigInteger, and the smallest type capable
     * of exactly representing the number must be used.
     */
    init {
        this.tokenOffset = tokenOffset
        this.endOffset = endOffset
        this.value = value
    }

    /**
     * Returns the value denoted by this literal as an Integer, Long, or BigInteger, using the
     * narrowest type capable of exactly representing the value.
     */
    fun getValue(): Number? {
        return value
    }

    /**
     * Returns the value denoted by this literal as an Integer, or null if it can't be represented
     * exactly.
     */
    fun getIntValueExact(): Int? {
        return if (value is Int) value else null
    }

    override fun getStartOffset(): Int {
        return tokenOffset
    }

    override fun getEndOffset(): Int {
        return endOffset
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    companion object {
        /**
         * Returns the value denoted by a non-negative integer literal with an optional base prefix (but
         * no +/- sign), using the narrowest type of Integer, Long, or BigInteger capable of exactly
         * representing the value.
         * 
         * @throws NumberFormatException if the string is not a valid literal.
         */
        fun scan(str: String): Number {
            var str = str
            val orig: String? = str
            var radix = 10
            if (str.length > 1 && str.get(0) == '0') {
                when (str.get(1)) {
                    'x', 'X' -> {
                        radix = 16
                        str = str.substring(2)
                    }

                    'o', 'O' -> {
                        radix = 8
                        str = str.substring(2)
                    }

                    else -> throw NumberFormatException(
                        "invalid octal literal: " + str + " (use '0o" + str.substring(1) + "')"
                    )
                }
            }

            try {
                val v: Long = str.toLong(radix)
                if (v == v.toInt().toLong()) {
                    return v.toInt()
                }
                return v
            } catch (unused: NumberFormatException) {
                /* fall through */
            }
            try {
                return BigInteger(str, radix)
            } catch (unused: NumberFormatException) {
                throw NumberFormatException("invalid base-" + radix + " integer literal: " + orig)
            }
        }
    }
}
