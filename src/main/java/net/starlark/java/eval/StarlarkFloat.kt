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

import java.math.BigInteger
import java.util.Locale

/** The Starlark float data type.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "float",
    category = "core",
    doc = "The type of floating-point numbers in Starlark."
)
class StarlarkFloat private constructor(v: Double) : net.starlark.java.eval.StarlarkValue, Comparable<StarlarkFloat?> {
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.syntax.Types.FLOAT
    }

    private val v: Double

    /** Returns the value of this float.  */
    fun toDouble(): Double {
        return v
    }

    override fun toString(): String {
        return net.starlark.java.eval.StarlarkFloat.Companion.format(v, 'g')
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append(toString())
    }

    override fun isImmutable(): Boolean {
        return true
    }

    override fun truth(): Boolean {
        return this.v != 0.0
    }

    /**
     * Defines a total order over float values. Positive and negative zero values compare equal. NaN
     * compares equal to itself and greater than +Inf.
     */
    override fun compareTo(that: StarlarkFloat): Int {
        val x = this.v
        val y = that.v
        if (x > y) {
            return +1
        } else if (x < y) {
            return -1
        } else if (x == y) {
            return 0 // 0.0 == -0.0
        }

        // At least one operand is NaN.
        // Canonicalize NaNs using doubleToLongBits and compare bits.
        val xbits: Long = java.lang.Double.doubleToLongBits(x)
        val ybits: Long = java.lang.Double.doubleToLongBits(y)
        return java.lang.Long.compare(xbits, ybits) // NaN > non-NaN
    }

    override fun hashCode(): Int {
        // Equal float and int values must yield the same hash.
        if (java.lang.Double.isFinite(v) && v == java.lang.Math.rint(v)) {
            return net.starlark.java.eval.StarlarkInt.Companion.ofFiniteDouble(v).hashCode()
        }

        // For non-integral values we can use a cheaper hash.
        // Hashing the bits is consistent with equals
        // because v is neither 0.0 nor -0.0.
        val bits: Long = java.lang.Double.doubleToLongBits(v) // canonicalizes NaNs
        return (bits xor (bits ushr 32)).toInt()
    }

    override fun equals(that: Any?): Boolean {
        return (that is StarlarkFloat && net.starlark.java.eval.StarlarkFloat.Companion.equal(this.v, that.v))
                || (that is net.starlark.java.eval.StarlarkInt && net.starlark.java.eval.StarlarkInt.Companion.intEqualsFloat(
            that as net.starlark.java.eval.StarlarkInt,
            this
        ))
    }

    init {
        this.v = v
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.FLOAT_CONSTRUCTOR
        }

        /** Returns the Starlark float value that represents x.  */
        @kotlin.jvm.JvmStatic
        fun of(v: Double): StarlarkFloat {
            return net.starlark.java.eval.StarlarkFloat(v)
        }

        // equal is an equivalence relation consistent with hashCode and compareTo.
        private fun equal(x: Double, y: Double): Boolean {
            return x == y || (java.lang.Double.isNaN(x) && java.lang.Double.isNaN(y))
        }

        // Performs printf-style string conversion of a double value v.
        // conv is one of [efgEFG].
        fun format(v: Double, conv: Char): String? {
            if (!java.lang.Double.isFinite(v)) {
                if (v == java.lang.Double.POSITIVE_INFINITY) {
                    return "+inf"
                } else if (v == java.lang.Double.NEGATIVE_INFINITY) {
                    return "-inf"
                } else {
                    return "nan"
                }
            }

            var s: String
            when (conv) {
                'e' -> s = java.lang.String.format(Locale.US, "%e", v)
                'E' -> s = java.lang.String.format(Locale.US, "%E", v)
                'f', 'F' -> s = java.lang.String.format(Locale.US, "%f", v)
                'g' -> s = java.lang.String.format(Locale.US, "%.17g", v) // use DBL_DECIMAL_DIG places
                'G' -> s = java.lang.String.format(Locale.US, "%.17G", v)
                else -> throw java.lang.IllegalArgumentException("unsupported conversion: " + conv)
            }

            // %g is the default format used by str.
            // It always includes a '.' or an 'e', to make clear that
            // the value is a float, not an int.
            //
            // TODO(adonovan): round the value to the minimal precision required
            // to avoid ambiguity. This requires computing the decimal digit
            // strings of the adjacent floating-point values and then taking the
            // shortest prefix sufficient to distinguish v from them, or using a
            // more sophisticated algorithm such as Florian Loitsch's Grisu3 or
            // Ulf Adams' Ryu.  (Is there an easy way to compute the required
            // precision without materializing the digits? If so we could delegate
            // to format("%*g", prec, v).)
            //
            // For now, we just clean up the output of Java's %.17g implementation,
            // which is unambiguous, but may yield unnecessarily long digit strings
            // such as 1000000000000.0.
            if (conv == 'g' || conv == 'G') {
                val e: Int = s.indexOf((if (conv == 'g') 'e' else 'E').code)
                if (e < 0) {
                    val dot: Int = s.indexOf('.'.code)
                    if (dot < 0) {
                        // Ensure result always has a decimal point if no exponent.
                        // "123" -> "123.0"
                        s += ".0"
                    } else {
                        // Remove trailing zeros after decimal point.
                        // "1.110" => "1.11"
                        // "1.000" => "1.0"
                        var i: Int
                        i = s.length() - 1
                        while (i > dot + 1 && s.charAt(i) == '0') {
                            i--
                        }
                        s = s.substring(0, i + 1)
                    }
                } else {
                    // Remove trailing zeros from mantissa.
                    // "1.23000e+45" => "1.23e+45"
                    // "1.00000e+45" => "1e+45"
                    var i: Int
                    i = e - 1
                    while (s.charAt(i) == '0') {
                        i--
                    }
                    if (s.charAt(i) == '.') {
                        i--
                    }
                    if (i < e - 1) {
                        s =
                            java.lang.StringBuilder(i + 1 + s.length() - e)
                                .append(s, 0, i + 1) // "1.23"
                                .append(s, e, s.length()) // "e+45"
                                .toString()
                    }
                }
            }

            return s
        }

        /** Returns x // y (floor of division).  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun floordiv(x: Double, y: Double): StarlarkFloat {
            if (y == 0.0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("integer division by zero")
            }
            return net.starlark.java.eval.StarlarkFloat.Companion.of(java.lang.Math.floor(x / y))
        }

        /** Returns x / y (floating-point division).  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun div(x: Double, y: Double): StarlarkFloat {
            if (y == 0.0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("floating-point division by zero")
            }
            return net.starlark.java.eval.StarlarkFloat.Companion.of(x / y)
        }

        /** Returns x % y (floating-point remainder).  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun mod(x: Double, y: Double): StarlarkFloat {
            if (y == 0.0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("floating-point modulo by zero")
            }
            // In Starlark, the sign of the result is the sign of the divisor.
            var z = x % y
            if ((x < 0) != (y < 0) && z != 0.0) {
                z += y
            }
            return net.starlark.java.eval.StarlarkFloat.Companion.of(z)
        }

        /**
         * Returns the Starlark int value closest to x, truncating towards zero.
         * 
         * @throws IllegalArgumentException if x is not finite (NaN or ±Inf).
         */
        fun finiteDoubleToIntExact(x: Double): net.starlark.java.eval.StarlarkInt {
            // small value?
            if (java.lang.Long.MIN_VALUE <= x && x <= java.lang.Long.MAX_VALUE) {
                return net.starlark.java.eval.StarlarkInt.Companion.of(x.toLong())
            }

            // Shift must be positive, because we just handled all small values.
            val shift: Int = net.starlark.java.eval.StarlarkFloat.Companion.getShift(x)
            check(shift > 0) { "non-positive shift" }

            // Shift mantissa by exponent.
            val mantissa: Long = net.starlark.java.eval.StarlarkFloat.Companion.getMantissa(x)
            return net.starlark.java.eval.StarlarkInt.Companion.of(BigInteger.valueOf(mantissa).shiftLeft(shift))
        }

        private val EXPONENT_MASK = (1 shl 11) - 1

        // Returns the effective signed mantissa of x.
        // Precondition: x is finite.
        fun getMantissa(x: Double): Long {
            val bits: Long = java.lang.Double.doubleToRawLongBits(x)
            var mantissa = bits and ((1L shl 52) - 1)
            val exp = ((bits ushr 52).toInt()) and net.starlark.java.eval.StarlarkFloat.Companion.EXPONENT_MASK
            when (exp) {
                0 -> {}
                net.starlark.java.eval.StarlarkFloat.Companion.EXPONENT_MASK -> throw java.lang.IllegalArgumentException(
                    "not finite: " + x
                )

                else -> mantissa = mantissa or (1L shl 52)
            }
            return if (x < 0) -mantissa else mantissa
        }

        // Returns the effective left (+) or right (-) shift required of the value returned by
        // getMantissa(x).
        // Precondition: x is finite.
        fun getShift(x: Double): Int {
            val bits: Long = java.lang.Double.doubleToRawLongBits(x)
            var exp = ((bits ushr 52).toInt()) and net.starlark.java.eval.StarlarkFloat.Companion.EXPONENT_MASK
            when (exp) {
                0 -> exp -= 1022
                net.starlark.java.eval.StarlarkFloat.Companion.EXPONENT_MASK -> throw java.lang.IllegalArgumentException(
                    "not finite: " + x
                )

                else -> exp -= 1023
            }
            return exp - 52
        }
    }
}
