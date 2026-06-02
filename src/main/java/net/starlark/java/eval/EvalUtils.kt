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

import java.util.IllegalFormatException
import java.util.LinkedHashMap

/** Internal declarations used by the evaluator.  */
internal object EvalUtils {
    fun addIterator(x: Any?) {
        if (x is net.starlark.java.eval.Mutability.Freezable) {
            (x as net.starlark.java.eval.Mutability.Freezable).updateIteratorCount(+1)
        }
    }

    fun removeIterator(x: Any?) {
        if (x is net.starlark.java.eval.Mutability.Freezable) {
            (x as net.starlark.java.eval.Mutability.Freezable).updateIteratorCount(-1)
        }
    }

    // The following functions for indexing and slicing match the behavior of Python.
    /**
     * Resolves a positive or negative index to an index in the range [0, length), or throws
     * EvalException if it is out of range. If the index is negative, it counts backward from length.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getSequenceIndex(index: Int, length: Int): Int {
        var actualIndex = index
        if (actualIndex < 0) {
            actualIndex += length
        }
        if (actualIndex < 0 || actualIndex >= length) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "index out of range (index is %d, but sequence has %d elements)", index, length
            )
        }
        return actualIndex
    }

    /** Evaluates an eager binary operation, `x op y`. (Excludes AND and OR.)  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun binaryOp(
        op: net.starlark.java.syntax.TokenKind,
        x: Any,
        y: Any,
        starlarkThread: net.starlark.java.eval.StarlarkThread
    ): Any? {
        val semantics: net.starlark.java.eval.StarlarkSemantics = starlarkThread.getSemantics()
        val mu: net.starlark.java.eval.Mutability = starlarkThread.mutability()
        when (op) {
            net.starlark.java.syntax.TokenKind.PLUS -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int + int
                    return net.starlark.java.eval.StarlarkInt.Companion.add(
                        x as net.starlark.java.eval.StarlarkInt,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int + float
                    val z: Double =
                        (x as net.starlark.java.eval.StarlarkInt).toFiniteDouble() + (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                }
            } else if (x is String) {
                if (y is String) {
                    // string + string
                    return x + y
                }
            } else if (x is net.starlark.java.eval.Tuple) {
                if (y is net.starlark.java.eval.Tuple) {
                    // tuple + tuple
                    return net.starlark.java.eval.Tuple.Companion.concat(
                        x as net.starlark.java.eval.Tuple,
                        y as net.starlark.java.eval.Tuple
                    )
                }
            } else if (x is net.starlark.java.eval.StarlarkList<*>) {
                if (y is net.starlark.java.eval.StarlarkList<*>) {
                    // list + list
                    return net.starlark.java.eval.StarlarkList.Companion.concat<Any?>(
                        x as net.starlark.java.eval.StarlarkList<*>,
                        y as net.starlark.java.eval.StarlarkList<*>,
                        mu
                    )
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float + float
                    val z: Double = xf + (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float + int
                    val z: Double = xf + (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                }
            }

            net.starlark.java.syntax.TokenKind.PIPE -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int | int
                    return net.starlark.java.eval.StarlarkInt.Companion.or(
                        x as net.starlark.java.eval.StarlarkInt,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                }
            } else if (x is MutableMap<*, *>) {
                if (y is MutableMap<*, *>) {
                    // map | map (usually dicts)
                    val union: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>(x)
                    union.putAll(y)
                    return if (mu.isFrozen()) net.starlark.java.eval.CompactImmutableDict.Companion.copyOf<Any?, Any?>(
                        union
                    ) else net.starlark.java.eval.Dict.Companion.wrap<Any?, Any?>(mu, union)
                }
            } else if (x is MutableSet<*> && y is MutableSet<*>) {
                // set | set
                if (semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
                    return net.starlark.java.eval.StarlarkSet.Companion.empty<Any?>()
                        .union(net.starlark.java.eval.Tuple.Companion.of(x, y), starlarkThread)
                }
            }

            net.starlark.java.syntax.TokenKind.AMPERSAND -> if (x is net.starlark.java.eval.StarlarkInt && y is net.starlark.java.eval.StarlarkInt) {
                // int & int
                return net.starlark.java.eval.StarlarkInt.Companion.and(
                    x as net.starlark.java.eval.StarlarkInt,
                    y as net.starlark.java.eval.StarlarkInt
                )
            } else if (x is MutableSet<*> && y is MutableSet<*>) {
                // set & set
                if (semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
                    val xStarlarkSet: net.starlark.java.eval.StarlarkSet<*> =
                        if (x is net.starlark.java.eval.StarlarkSet<*>)
                            x as net.starlark.java.eval.StarlarkSet<*>
                        else
                            net.starlark.java.eval.StarlarkSet.Companion.checkedCopyOf(mu, x)
                    return xStarlarkSet.intersection(net.starlark.java.eval.Tuple.Companion.of(y), starlarkThread)
                }
            }

            net.starlark.java.syntax.TokenKind.CARET -> if (x is net.starlark.java.eval.StarlarkInt && y is net.starlark.java.eval.StarlarkInt) {
                // int ^ int
                return net.starlark.java.eval.StarlarkInt.Companion.xor(
                    x as net.starlark.java.eval.StarlarkInt,
                    y as net.starlark.java.eval.StarlarkInt
                )
            } else if (x is MutableSet<*> && y is MutableSet<*>) {
                // set ^ set
                if (semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
                    val xStarlarkSet: net.starlark.java.eval.StarlarkSet<*> =
                        if (x is net.starlark.java.eval.StarlarkSet<*>)
                            x as net.starlark.java.eval.StarlarkSet<*>
                        else
                            net.starlark.java.eval.StarlarkSet.Companion.checkedCopyOf(mu, x)
                    return xStarlarkSet.symmetricDifference(y, starlarkThread)
                }
            }

            net.starlark.java.syntax.TokenKind.GREATER_GREATER -> if (x is net.starlark.java.eval.StarlarkInt && y is net.starlark.java.eval.StarlarkInt) {
                // x >> y
                return net.starlark.java.eval.StarlarkInt.Companion.shiftRight(
                    x as net.starlark.java.eval.StarlarkInt,
                    y as net.starlark.java.eval.StarlarkInt
                )
            }

            net.starlark.java.syntax.TokenKind.LESS_LESS -> if (x is net.starlark.java.eval.StarlarkInt && y is net.starlark.java.eval.StarlarkInt) {
                // x << y
                return net.starlark.java.eval.StarlarkInt.Companion.shiftLeft(
                    x as net.starlark.java.eval.StarlarkInt,
                    y as net.starlark.java.eval.StarlarkInt
                )
            }

            net.starlark.java.syntax.TokenKind.MINUS -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int - int
                    return net.starlark.java.eval.StarlarkInt.Companion.subtract(
                        x as net.starlark.java.eval.StarlarkInt,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int - float
                    val z: Double =
                        (x as net.starlark.java.eval.StarlarkInt).toFiniteDouble() - (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float - float
                    val z: Double = xf - (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float - int
                    val z: Double = xf - (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                }
            } else if (x is MutableSet<*> && y is MutableSet<*>) {
                // set - set
                if (semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_ENABLE_STARLARK_SET)) {
                    val xStarlarkSet: net.starlark.java.eval.StarlarkSet<*> =
                        if (x is net.starlark.java.eval.StarlarkSet<*>)
                            x as net.starlark.java.eval.StarlarkSet<*>
                        else
                            net.starlark.java.eval.StarlarkSet.Companion.checkedCopyOf(mu, x)
                    return xStarlarkSet.difference(net.starlark.java.eval.Tuple.Companion.of(y), starlarkThread)
                }
            }

            net.starlark.java.syntax.TokenKind.STAR -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int * int
                    return net.starlark.java.eval.StarlarkInt.Companion.multiply(
                        x,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                } else if (y is String) {
                    // int * string
                    return net.starlark.java.eval.EvalUtils.repeatString(y, x)
                } else if (y is net.starlark.java.eval.Tuple) {
                    //  int * tuple
                    return (y as net.starlark.java.eval.Tuple).repeat(x)
                } else if (y is net.starlark.java.eval.StarlarkList<*>) {
                    // int * list
                    return (y as net.starlark.java.eval.StarlarkList<*>).repeat(x, mu)
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int * float
                    val z: Double = x.toFiniteDouble() * (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
                }
            } else if (x is String) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // string * int
                    return net.starlark.java.eval.EvalUtils.repeatString(x, y as net.starlark.java.eval.StarlarkInt)
                }
            } else if (x is net.starlark.java.eval.Tuple) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // tuple * int
                    return (x as net.starlark.java.eval.Tuple).repeat(y as net.starlark.java.eval.StarlarkInt)
                }
            } else if (x is net.starlark.java.eval.StarlarkList<*>) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // list * int
                    return (x as net.starlark.java.eval.StarlarkList<*>).repeat(
                        y as net.starlark.java.eval.StarlarkInt,
                        mu
                    )
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float * float
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(xf * (y as net.starlark.java.eval.StarlarkFloat).toDouble())
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float * int
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(xf * (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble())
                }
            }

            net.starlark.java.syntax.TokenKind.SLASH -> if (x is net.starlark.java.eval.StarlarkInt) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int / int
                    return net.starlark.java.eval.StarlarkFloat.Companion.div(
                        xf,
                        (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    )
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int / float
                    return net.starlark.java.eval.StarlarkFloat.Companion.div(
                        xf,
                        (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    )
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float / float
                    return net.starlark.java.eval.StarlarkFloat.Companion.div(
                        xf,
                        (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    )
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float / int
                    return net.starlark.java.eval.StarlarkFloat.Companion.div(
                        xf,
                        (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    )
                }
            }

            net.starlark.java.syntax.TokenKind.SLASH_SLASH -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int // int
                    return net.starlark.java.eval.StarlarkInt.Companion.floordiv(
                        x as net.starlark.java.eval.StarlarkInt,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int // float
                    val xf: Double = (x as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    val yf: Double = (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.floordiv(xf, yf)
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float // float
                    return net.starlark.java.eval.StarlarkFloat.Companion.floordiv(
                        xf,
                        (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    )
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float // int
                    return net.starlark.java.eval.StarlarkFloat.Companion.floordiv(
                        xf,
                        (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    )
                }
            }

            net.starlark.java.syntax.TokenKind.PERCENT -> if (x is net.starlark.java.eval.StarlarkInt) {
                if (y is net.starlark.java.eval.StarlarkInt) {
                    // int % int
                    return net.starlark.java.eval.StarlarkInt.Companion.mod(
                        x as net.starlark.java.eval.StarlarkInt,
                        y as net.starlark.java.eval.StarlarkInt
                    )
                } else if (y is net.starlark.java.eval.StarlarkFloat) {
                    // int % float
                    val xf: Double = (x as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    val yf: Double = (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return net.starlark.java.eval.StarlarkFloat.Companion.mod(xf, yf)
                }
            } else if (x is String) {
                // string % any
                try {
                    if (y is net.starlark.java.eval.Tuple) {
                        return net.starlark.java.eval.Starlark.Companion.formatWithList(
                            semantics,
                            x,
                            y as net.starlark.java.eval.Tuple
                        )
                    } else {
                        return net.starlark.java.eval.Starlark.Companion.format(semantics, x, y)
                    }
                } catch (ex: IllegalFormatException) {
                    throw net.starlark.java.eval.EvalException(ex)
                }
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                if (y is net.starlark.java.eval.StarlarkFloat) {
                    // float % float
                    return net.starlark.java.eval.StarlarkFloat.Companion.mod(
                        xf,
                        (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    )
                } else if (y is net.starlark.java.eval.StarlarkInt) {
                    // float % int
                    return net.starlark.java.eval.StarlarkFloat.Companion.mod(
                        xf,
                        (y as net.starlark.java.eval.StarlarkInt).toFiniteDouble()
                    )
                }
            }

            net.starlark.java.syntax.TokenKind.EQUALS_EQUALS -> return x == y

            net.starlark.java.syntax.TokenKind.NOT_EQUALS -> return x != y

            net.starlark.java.syntax.TokenKind.LESS -> return net.starlark.java.eval.EvalUtils.compare(x, y) < 0

            net.starlark.java.syntax.TokenKind.LESS_EQUALS -> return net.starlark.java.eval.EvalUtils.compare(x, y) <= 0

            net.starlark.java.syntax.TokenKind.GREATER -> return net.starlark.java.eval.EvalUtils.compare(x, y) > 0

            net.starlark.java.syntax.TokenKind.GREATER_EQUALS -> return net.starlark.java.eval.EvalUtils.compare(
                x,
                y
            ) >= 0

            net.starlark.java.syntax.TokenKind.IN -> if (y is net.starlark.java.eval.StarlarkMembershipTestable) {
                return (y as net.starlark.java.eval.StarlarkMembershipTestable).containsKey(semantics, x)
            } else if (y is net.starlark.java.eval.StarlarkIndexable.Threaded) {
                return (y as net.starlark.java.eval.StarlarkIndexable.Threaded).containsKey(
                    starlarkThread,
                    semantics,
                    x
                )
            } else if (y is String) {
                if (x !is String) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "'in <string>' requires string as left operand, not '%s'",
                        net.starlark.java.eval.Starlark.Companion.type(x)
                    )
                }
                return y.contains(x)
            }

            net.starlark.java.syntax.TokenKind.NOT_IN -> {
                val z: Any? = net.starlark.java.eval.EvalUtils.binaryOp(
                    net.starlark.java.syntax.TokenKind.IN,
                    x,
                    y,
                    starlarkThread
                )
                if (z != null) {
                    return !net.starlark.java.eval.Starlark.Companion.truth(z)
                }
            }

            else -> throw java.lang.AssertionError("not a binary operator: " + op)
        }

        // custom binary operator?
        if (x is net.starlark.java.eval.HasBinary) {
            val z: Any? = (x as net.starlark.java.eval.HasBinary).binaryOp(op, y, true)
            if (z != null) {
                return z
            }
        }
        if (y is net.starlark.java.eval.HasBinary) {
            val z: Any? = (y as net.starlark.java.eval.HasBinary).binaryOp(op, x, false)
            if (z != null) {
                return z
            }
        }

        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "unsupported binary operation: %s %s %s",
            net.starlark.java.eval.Starlark.Companion.type(x),
            op,
            net.starlark.java.eval.Starlark.Companion.type(y)
        )
    }

    // Defines the behavior of the language's ordered comparison operators (< <= => >).
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun compare(x: Any?, y: Any?): Int {
        try {
            return net.starlark.java.eval.Starlark.Companion.compareUnchecked(x, y)
        } catch (ex: java.lang.ClassCastException) {
            throw net.starlark.java.eval.EvalException(ex.getMessage())
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun repeatString(s: String, `in`: net.starlark.java.eval.StarlarkInt): String {
        val n: Int = `in`.toInt("repeat")
        if (n <= 0) {
            return ""
        } else if (s.length().toLong() * n.toLong() > java.lang.Integer.MAX_VALUE) {
            // Would exceed max length of a java String.
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "excessive repeat (%d * %d characters)",
                s.length(),
                n
            )
        } else {
            return s.repeat(n)
        }
    }

    /** Evaluates a unary operation.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun unaryOp(op: net.starlark.java.syntax.TokenKind, x: Any): Any {
        when (op) {
            net.starlark.java.syntax.TokenKind.NOT -> return !net.starlark.java.eval.Starlark.Companion.truth(x)

            net.starlark.java.syntax.TokenKind.MINUS -> if (x is net.starlark.java.eval.StarlarkInt) {
                return net.starlark.java.eval.StarlarkInt.Companion.uminus(x as net.starlark.java.eval.StarlarkInt) // -int
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                return net.starlark.java.eval.StarlarkFloat.Companion.of(-(x as net.starlark.java.eval.StarlarkFloat).toDouble()) // -float
            }

            net.starlark.java.syntax.TokenKind.PLUS -> if (x is net.starlark.java.eval.StarlarkInt) {
                return x // +int
            } else if (x is net.starlark.java.eval.StarlarkFloat) {
                return x // +float
            }

            net.starlark.java.syntax.TokenKind.TILDE -> if (x is net.starlark.java.eval.StarlarkInt) {
                return net.starlark.java.eval.StarlarkInt.Companion.bitnot(x as net.starlark.java.eval.StarlarkInt) // ~int
            }

            else -> {}
        }
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "unsupported unary operation: %s%s",
            op,
            net.starlark.java.eval.Starlark.Companion.type(x)
        )
    }

    /**
     * Returns the element of sequence or mapping `object` indexed by `key`.
     * 
     * @throws EvalException if `object` is not a sequence or mapping.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun index(starlarkThread: net.starlark.java.eval.StarlarkThread, `object`: Any, key: Any): Any? {
        val mu: net.starlark.java.eval.Mutability = starlarkThread.mutability()
        val semantics: net.starlark.java.eval.StarlarkSemantics = starlarkThread.getSemantics()

        if (`object` is net.starlark.java.eval.StarlarkIndexable.Threaded) {
            return (`object` as net.starlark.java.eval.StarlarkIndexable.Threaded).getIndex(
                starlarkThread,
                semantics,
                key
            )
        } else if (`object` is net.starlark.java.eval.StarlarkIndexable) {
            val result: Any? = (`object` as net.starlark.java.eval.StarlarkIndexable).getIndex(semantics, key)
            // TODO(bazel-team): We shouldn't have this fromJava call here. If it's needed at all,
            // it should go in the implementations of StarlarkIndexable#getIndex that produce non-Starlark
            // values.
            return if (result == null) null else net.starlark.java.eval.Starlark.Companion.fromJava(result, mu)
        } else if (`object` is String) {
            var index: Int = net.starlark.java.eval.Starlark.Companion.toInt(key, "string index")
            index = net.starlark.java.eval.EvalUtils.getSequenceIndex(index, `object`.length())
            return net.starlark.java.eval.StringModule.Companion.memoizedCharToString(`object`.charAt(index))
        } else {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "type '%s' has no operator [](%s)",
                net.starlark.java.eval.Starlark.Companion.type(`object`),
                net.starlark.java.eval.Starlark.Companion.type(key)
            )
        }
    }

    /**
     * Updates an object as if by the Starlark statement `object[key] = value`.
     * 
     * @throws EvalException if the object is not a list or dict.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun setIndex(`object`: Any, key: Any?, value: Any?) {
        if (`object` is net.starlark.java.eval.Dict<*, *>) {
            val dict: net.starlark.java.eval.Dict<Any?, Any?> = `object` as net.starlark.java.eval.Dict<Any?, Any?>
            dict.putEntry(key, value)
        } else if (`object` is net.starlark.java.eval.StarlarkList<*>) {
            val list: net.starlark.java.eval.StarlarkList<Any?> = `object` as net.starlark.java.eval.StarlarkList<Any?>
            var index: Int = net.starlark.java.eval.Starlark.Companion.toInt(key, "list index")
            index = net.starlark.java.eval.EvalUtils.getSequenceIndex(index, list.size())
            list.setElementAt(index, value)
        } else {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "can only assign an element in a dictionary or a list, not in a '%s'",
                net.starlark.java.eval.Starlark.Companion.type(`object`)
            )
        }
    }

    /** Updates the named field of x as if by the Starlark statement `x.field = value`.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun setField(x: Any, field: String?, value: Any?) {
        if (x is net.starlark.java.eval.Structure) {
            (x as net.starlark.java.eval.Structure).setField(field, value)
        } else {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "cannot set .%s field of %s value",
                field,
                net.starlark.java.eval.Starlark.Companion.type(x)
            )
        }
    }
}
