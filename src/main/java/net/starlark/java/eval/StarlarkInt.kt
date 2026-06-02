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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.math.BigInteger

/** The Starlark int data type.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "int",
    category = "core",
    doc = ("The type of integers in Starlark. Starlark integers may be of any magnitude; arithmetic"
            + " is exact. Examples of integer expressions:<br>"
            + "<pre class=\"language-python\">153\n"
            + "0x2A  # hexadecimal literal\n"
            + "0o54  # octal literal\n"
            + "23 * 2 + 5\n"
            + "100 / -7\n"
            + "100 % -7  # -5 (unlike in some other languages)\n"
            + "int(\"18\")\n"
            + "</pre>")
)
abstract class StarlarkInt
/** Only nested classes of `StarlarkInt` are allowed to inherit it.  */
private constructor() : net.starlark.java.eval.StarlarkValue, Comparable<StarlarkInt?> {
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.syntax.Types.INT
    }

    // Subclass for values exactly representable in a Java int.
    private class Int32(v: Int) : StarlarkInt() {
        val v: Int

        init {
            this.v = v
        }

        override fun toInt(what: String?): Int {
            return v
        }

        override fun toLong(what: String?): Long {
            return v.toLong()
        }

        override fun toLongFast(): Long {
            return v.toLong()
        }

        override fun toBigInteger(): BigInteger {
            return BigInteger.valueOf(v.toLong())
        }

        override fun toNumber(): Number {
            return v
        }

        override fun signum(): Int {
            return java.lang.Integer.signum(v)
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append(v)
        }

        override fun hashCode(): Int {
            return 0x316c5239 * java.lang.Integer.hashCode(v) xor 0x67c4a7d5
        }

        override fun equals(that: Any?): Boolean {
            return (that is Int32 && this.v == that.v)
                    || (that is net.starlark.java.eval.StarlarkFloat && net.starlark.java.eval.StarlarkInt.Companion.intEqualsFloat(
                this,
                that as net.starlark.java.eval.StarlarkFloat
            ))
        }
    }

    // Subclass for values exactly representable in a Java long.
    private class Int64(v: Long) : StarlarkInt() {
        val v: Long

        init {
            this.v = v
        }

        override fun toLong(what: String?): Long {
            return v
        }

        override fun toLongFast(): Long {
            return v
        }

        override fun toBigInteger(): BigInteger {
            return BigInteger.valueOf(v)
        }

        override fun toNumber(): Number {
            return v
        }

        override fun signum(): Int {
            return java.lang.Long.signum(v)
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append(v)
        }

        override fun hashCode(): Int {
            return 0x67c4a7d5 * java.lang.Long.hashCode(v) xor -0x116eb5e5
        }

        override fun equals(that: Any?): Boolean {
            return (that is Int64 && this.v == that.v)
                    || (that is net.starlark.java.eval.StarlarkFloat && net.starlark.java.eval.StarlarkInt.Companion.intEqualsFloat(
                this,
                that as net.starlark.java.eval.StarlarkFloat
            ))
        }
    }

    // Subclass for values not exactly representable in a long.
    private class Big(v: BigInteger) : StarlarkInt() {
        val v: BigInteger

        init {
            this.v = v
        }

        override fun toBigInteger(): BigInteger {
            return v
        }

        override fun toNumber(): Number {
            return v
        }

        override fun signum(): Int {
            return v.signum()
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append(v.toString())
        }

        override fun hashCode(): Int {
            return -0x116eb5e5 * v.hashCode() xor 0x6406918f
        }

        override fun equals(that: Any?): Boolean {
            return (that is Big && this.v == that.v)
                    || (that is net.starlark.java.eval.StarlarkFloat && net.starlark.java.eval.StarlarkInt.Companion.intEqualsFloat(
                this,
                that as net.starlark.java.eval.StarlarkFloat
            ))
        }
    }

    /** Returns the value of this StarlarkInt as a Number (Integer, Long, or BigInteger).  */
    abstract fun toNumber(): Number?

    /** Returns the signum of this StarlarkInt (-1, 0, or +1).  */
    abstract fun signum(): Int

    /** Returns this StarlarkInt as a string of decimal digits.  */
    override fun toString(): String {
        if (this is Int32) {
            return java.lang.Integer.toString(this.v)
        } else if (this is Int64) {
            return java.lang.Long.toString(this.v)
        } else {
            return toBigInteger().toString()
        }
    }

    abstract override fun repr(
        printer: net.starlark.java.eval.Printer?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    )

    /** Returns the signed int32 value of this StarlarkInt, or fails if not exactly representable.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    open fun toInt(what: String?): Int {
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "got %s for %s, want value in signed 32-bit range",
            this,
            what
        )
    }

    /** Returns the signed int64 value of this StarlarkInt, or fails if not exactly representable.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    open fun toLong(what: String?): Long {
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "got %s for %s, want value in the signed 64-bit range",
            this,
            what
        )
    }

    private class Overflow : java.lang.Exception()

    /**
     * Similar to [.toLong], but faster: exception is not allocated and stack trace is
     * not collected.
     */
    @Throws(net.starlark.java.eval.StarlarkInt.Overflow::class)
    protected open fun toLongFast(): Long {
        throw net.starlark.java.eval.StarlarkInt.Companion.OVERFLOW
    }

    /** Returns the nearest IEEE-754 double-precision value closest to this int, which may be ±Inf.  */
    fun toDouble(): Double {
        if (this is Int32) {
            return this.v.toDouble()
        } else if (this is Int64) {
            return this.v.toDouble()
        } else {
            return toBigInteger().doubleValue() // may be ±Inf
        }
    }

    /**
     * Returns the nearest IEEE-754 double-precision value closest to this int.
     * 
     * @throws EvalException is the int is to large to represent as a finite float value.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun toFiniteDouble(): Double {
        val d = toDouble()
        if (!java.lang.Double.isFinite(d)) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("int too large to convert to float")
        }
        return d
    }

    /** Returns the BigInteger value of this StarlarkInt.  */
    abstract fun toBigInteger(): BigInteger

    /**
     * Returns the value of this StarlarkInt as a Java signed 32-bit int.
     * 
     * @throws IllegalArgumentException if this int is not in that value range.
     */
    @Throws(java.lang.IllegalArgumentException::class)
    fun toIntUnchecked(): Int {
        if (this is Int32) {
            return this.v
        }
        // This operator is provided for fast access and case discrimination.
        // Use toInt(String) for user-visible errors.
        throw java.lang.IllegalArgumentException("not a signed 32-bit value")
    }

    /** Returns the result of truncating this value into the signed 32-bit range.  */
    fun truncateToInt(): Int {
        if (this is Int32) {
            return this.v
        } else if (this is Int64) {
            return this.v.toInt()
        } else {
            return toBigInteger().intValue()
        }
    }

    override fun isImmutable(): Boolean {
        return true
    }

    override fun truth(): Boolean {
        return this !== net.starlark.java.eval.StarlarkInt.Companion.ZERO
    }

    override fun compareTo(x: StarlarkInt): Int {
        return net.starlark.java.eval.StarlarkInt.Companion.compare(this, x)
    }

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.INT_CONSTRUCTOR
        }

        // A cache of small integers >= LEAST_SMALLINT.
        private val LEAST_SMALLINT = -128
        private val smallints = arrayOfNulls<Int32>(100000)

        @kotlin.jvm.JvmField
        val ZERO: StarlarkInt = net.starlark.java.eval.StarlarkInt.Companion.of(0)
        private val ONE: StarlarkInt = net.starlark.java.eval.StarlarkInt.Companion.of(1)
        private val MINUS_ONE: StarlarkInt = net.starlark.java.eval.StarlarkInt.Companion.of(-1)

        /** Returns the Starlark int value that represents x.  */
        @kotlin.jvm.JvmStatic
        fun of(x: Int): StarlarkInt {
            val index: Int = x - net.starlark.java.eval.StarlarkInt.Companion.LEAST_SMALLINT // (may overflow)
            if (0 <= index && index < net.starlark.java.eval.StarlarkInt.Companion.smallints.size) {
                var xi: Int32? = net.starlark.java.eval.StarlarkInt.Companion.smallints[index]
                if (xi == null) {
                    xi = net.starlark.java.eval.StarlarkInt.Int32(x)
                    net.starlark.java.eval.StarlarkInt.Companion.smallints[index] = xi
                }
                return xi!!
            }
            return net.starlark.java.eval.StarlarkInt.Int32(x)
        }

        /** Returns the Starlark int value that represents x.  */
        @kotlin.jvm.JvmStatic
        fun of(x: Long): StarlarkInt {
            if (x.toInt().toLong() == x) {
                return net.starlark.java.eval.StarlarkInt.Companion.of(x.toInt())
            }
            return net.starlark.java.eval.StarlarkInt.Int64(x)
        }

        /** Returns the Starlark int value that represents x.  */
        fun of(x: BigInteger): StarlarkInt {
            if (x.bitLength() < 64) {
                return net.starlark.java.eval.StarlarkInt.Companion.of(x.longValue())
            }
            return net.starlark.java.eval.StarlarkInt.Big(x)
        }

        /**
         * Returns the StarlarkInt value that most closely approximates x.
         * 
         * @throws IllegalArgumentException is x is not finite.
         */
        fun ofFiniteDouble(x: Double): StarlarkInt {
            return net.starlark.java.eval.StarlarkFloat.Companion.finiteDoubleToIntExact(x)
        }

        /**
         * Returns the int denoted by a literal string in the specified base, as if by the Starlark
         * expression `int(s, base)`.
         * 
         * @throws NumberFormatException if the input is invalid.
         */
        fun parse(s: String, base: Int): StarlarkInt {
            var s = s
            var base = base
            val stringForErrors: String? = s

            if (s.isEmpty()) {
                throw java.lang.NumberFormatException("empty string")
            }

            // +/- prefix?
            var isNegative = false
            var c: Char = s.charAt(0)
            if (c == '+') {
                s = s.substring(1)
            } else if (c == '-') {
                s = s.substring(1)
                isNegative = true
            }

            var digits = s

            // 0b 0o 0x prefix?
            if (s.length() > 1 && s.charAt(0) == '0') {
                var prefixBase = 0
                c = s.charAt(1)
                if (c == 'b' || c == 'B') {
                    prefixBase = 2
                } else if (c == 'o' || c == 'O') {
                    prefixBase = 8
                } else if (c == 'x' || c == 'X') {
                    prefixBase = 16
                }
                if (prefixBase != 0) {
                    if (base == 0 || base == prefixBase) {
                        base = prefixBase
                        digits = s.substring(2) // strip prefix
                    }
                }
            }

            // No prefix, no base? Use decimal.
            if (digits === s && base == 0) {
                // Don't infer base when input starts with '0' due to octal/decimal ambiguity.
                if (s.length() > 1 && s.charAt(0) == '0') {
                    throw java.lang.NumberFormatException(
                        "cannot infer base when string begins with a 0: "
                                + net.starlark.java.eval.Starlark.Companion.repr(
                            stringForErrors,
                            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                        )
                    )
                }
                base = 10
            }
            if (base < 2 || base > 36) {
                throw java.lang.NumberFormatException(
                    java.lang.String.format("invalid base %d (want 2 <= base <= 36)", base)
                )
            }

            // Do not allow Long.parseLong and new BigInteger to accept another +/- sign.
            if (digits.startsWith("+") || digits.startsWith("-")) {
                throw java.lang.NumberFormatException(
                    java.lang.String.format(
                        "invalid base-%d literal: %s",
                        base,
                        net.starlark.java.eval.Starlark.Companion.repr(
                            stringForErrors,
                            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                        )
                    )
                )
            }

            var result: StarlarkInt?
            try {
                result = net.starlark.java.eval.StarlarkInt.Companion.of(java.lang.Long.parseLong(digits, base))
            } catch (unused1: java.lang.NumberFormatException) {
                try {
                    result = net.starlark.java.eval.StarlarkInt.Companion.of(BigInteger(digits, base))
                } catch (unused2: java.lang.NumberFormatException) {
                    throw java.lang.NumberFormatException(
                        java.lang.String.format(
                            "invalid base-%d literal: %s",
                            base,
                            net.starlark.java.eval.Starlark.Companion.repr(
                                stringForErrors,
                                net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                            )
                        )
                    )
                }
            }
            return (if (isNegative) net.starlark.java.eval.StarlarkInt.Companion.uminus(result) else result)!!
        }

        // A preallocated exception used to indicate overflow errors without the cost of allocation.
        private val OVERFLOW: Overflow = net.starlark.java.eval.StarlarkInt.Overflow()

        // binary operators
        /** Returns signum(x - y).  */
        fun compare(x: StarlarkInt, y: StarlarkInt): Int {
            // If both arguments are big, we compare BigIntegers.
            // If neither argument is big, we compare longs.
            // If only one argument is big, its magnitude is greater
            // than the other operand, so only its sign matters.
            //
            // We avoid unnecessary branches.
            try {
                val xl = x.toLongFast()
                try {
                    val yl = y.toLongFast()
                    return java.lang.Long.compare(xl, yl) // (long, long)
                } catch (unused: Overflow) {
                    return -(y as Big).v.signum() // (long, big)
                }
            } catch (unused: Overflow) {
                return if (y is Big)
                    (x as Big).v.compareTo(y.v) // (big, big)
                else
                    (x as Big).v.signum() // (big, long)
            }
        }

        /** Returns x + y.  */
        @kotlin.jvm.JvmStatic
        fun add(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            if (x is Int32 && y is Int32) {
                val xl = x.v.toLong()
                val yl = y.v.toLong()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl + yl)
            }

            // We avoid Math.addExact and its overheads of exception allocation.
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                val zl = xl + yl
                val overflow = ((xl xor zl) and (yl xor zl)) < 0 // see Hacker's Delight, chapter 2
                if (!overflow) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(zl)
                }
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.add(ybig)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x - y.  */
        fun subtract(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            if (x is Int32 && y is Int32) {
                val xl = x.v.toLong()
                val yl = y.v.toLong()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl - yl)
            }

            // We avoid Math.subtractExact and its overhead of exception allocation.
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                val zl = xl - yl
                val overflow = ((xl xor yl) and (xl xor zl)) < 0 // see Hacker's Delight, chapter 2
                if (!overflow) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(zl)
                }
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.subtract(ybig)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x * y.  */
        @kotlin.jvm.JvmStatic
        fun multiply(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            // Fast path for common case: int32 * int32.
            if (x is Int32 && y is Int32) {
                val xl = x.v.toLong()
                val yl = y.v.toLong()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl * yl)
            }

            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()

                // Signed int128 multiplication, using Hacker's Delight 8-2
                // (High-Order Half of 64-Bit Product) extended to 128 bits.
                // TODO(adonovan): use Math.multiplyHigh when Java 9 becomes available.
                val xlo = xl and 0xFFFFFFFFL
                val xhi = xl shr 32
                val ylo = yl and 0xFFFFFFFFL
                val yhi = yl shr 32
                val zlo = xlo * ylo
                val t = xhi * ylo + (zlo ushr 32)
                var z1 = t and 0xFFFFFFFFL
                val z2 = t shr 32
                z1 += xlo * yhi

                // high and low arms of result
                val z128hi = xhi * yhi + z2 + (z1 shr 32)
                val z128lo = xl * yl

                // Check int128 result is within int64 range.
                if (z128hi == (z128lo and java.lang.Long.MIN_VALUE) shr 63) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(z128lo)
                }

                /* overflow */
            } catch (unused: Overflow) {
                /* fall through */
            }

            // Avoid unnecessary conversion to BigInteger if the other operand is -1, 0, 1.
            // (Also makes self-test below faster.)
            if (x === net.starlark.java.eval.StarlarkInt.Companion.ZERO || y === net.starlark.java.eval.StarlarkInt.Companion.ONE) {
                return x
            } else if (y === net.starlark.java.eval.StarlarkInt.Companion.ZERO || x === net.starlark.java.eval.StarlarkInt.Companion.ONE) {
                return y
            } else if (x === net.starlark.java.eval.StarlarkInt.Companion.MINUS_ONE) {
                return net.starlark.java.eval.StarlarkInt.Companion.uminus(y)
            } else if (y === net.starlark.java.eval.StarlarkInt.Companion.MINUS_ONE) {
                return net.starlark.java.eval.StarlarkInt.Companion.uminus(x)
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.multiply(ybig)
            val z: StarlarkInt = net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
            // cheap self-test
            if (z !is Big) {
                throw java.lang.AssertionError(
                    java.lang.String.format(
                        "bug in multiplication: %s * %s = %s, must be long multiplication", x, y, z
                    )
                )
            }
            return z
        }

        /** Returns x // y (floor of integer division).  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun floordiv(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            if (y === net.starlark.java.eval.StarlarkInt.Companion.ZERO) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("integer division by zero")
            }
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                // http://python-history.blogspot.com/2010/08/why-pythons-integer-division-floors.html
                if (xl == java.lang.Long.MIN_VALUE && yl == -1L) {
                    /* sole case in which quotient doesn't fit in long */
                } else {
                    val quo: Long = java.lang.Math.floorDiv(xl, yl)
                    return net.starlark.java.eval.StarlarkInt.Companion.of(quo)
                }
                /* overflow */
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val quorem: Array<BigInteger?> = xbig.divideAndRemainder(ybig)
            if ((xbig.signum() < 0) != (ybig.signum() < 0) && quorem[1].signum() != 0) {
                quorem[0] = quorem[0].subtract(BigInteger.ONE)
            }
            return net.starlark.java.eval.StarlarkInt.Companion.of(quorem[0])
        }

        /** Returns x % y.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun mod(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            if (y === net.starlark.java.eval.StarlarkInt.Companion.ZERO) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("integer modulo by zero")
            }
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                // In Starlark, the sign of the result is the sign of the divisor.
                return net.starlark.java.eval.StarlarkInt.Companion.of(java.lang.Math.floorMod(xl, yl))
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            var zbig: BigInteger = xbig.remainder(ybig)
            if ((x.signum() < 0) != (y.signum() < 0) && zbig.signum() != 0) {
                zbig = zbig.add(ybig)
            }
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x >> y.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun shiftRight(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            val yi = y.toInt("shift count")
            if (yi < 0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("negative shift count: %d", yi)
            }
            try {
                val xl = x.toLongFast()
                if (yi >= java.lang.Long.SIZE) {
                    return if (xl < 0) net.starlark.java.eval.StarlarkInt.Companion.of(-1) else net.starlark.java.eval.StarlarkInt.Companion.ZERO
                }
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl shr yi)
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val zbig: BigInteger = xbig.shiftRight(yi)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x << y.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun shiftLeft(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            val yi = y.toInt("shift count")
            if (yi < 0) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("negative shift count: %d", yi)
            } else if (yi >= 512) {
                throw net.starlark.java.eval.Starlark.Companion.errorf("shift count too large: %d", yi)
            }
            try {
                val xl = x.toLongFast()
                val z = xl shl yi // only uses low 6 bits of yi
                if ((z shr yi) == xl && yi < 64) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(z)
                }
                /* overflow */
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val zbig: BigInteger = xbig.shiftLeft(yi)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x ^ y.  */
        fun xor(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl xor yl)
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.xor(ybig)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x | y.  */
        fun or(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl or yl)
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.or(ybig)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns x & y.  */
        fun and(x: StarlarkInt, y: StarlarkInt): StarlarkInt {
            try {
                val xl = x.toLongFast()
                val yl = y.toLongFast()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl and yl)
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = y.toBigInteger()
            val zbig: BigInteger = xbig.and(ybig)
            return net.starlark.java.eval.StarlarkInt.Companion.of(zbig)
        }

        /** Returns ~x.  */
        fun bitnot(x: StarlarkInt): StarlarkInt {
            try {
                val xl = x.toLongFast()
                return net.starlark.java.eval.StarlarkInt.Companion.of(xl.inv())
            } catch (unused: Overflow) {
                /* fall through */
            }

            val xbig: BigInteger = (x as Big).v
            return net.starlark.java.eval.StarlarkInt.Companion.of(xbig.not())
        }

        /** Returns -x.  */
        fun uminus(x: StarlarkInt): StarlarkInt {
            if (x is Int32) {
                val xl = x.v.toLong()
                return net.starlark.java.eval.StarlarkInt.Companion.of(-xl)
            }

            if (x is Int64) {
                val xl = x.v
                if (xl != java.lang.Long.MIN_VALUE) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(-xl)
                }
            }

            val xbig: BigInteger = x.toBigInteger()
            val ybig: BigInteger = xbig.negate()
            return net.starlark.java.eval.StarlarkInt.Companion.of(ybig)
        }

        /** Reports whether int x exactly equals float y.  */
        fun intEqualsFloat(x: StarlarkInt, y: net.starlark.java.eval.StarlarkFloat): Boolean {
            val yf: Double = y.toDouble()
            return !java.lang.Double.isNaN(yf) && net.starlark.java.eval.StarlarkInt.Companion.compareIntAndDouble(
                x,
                yf
            ) == 0
        }

        /** Returns an exact three-valued comparison of int x with (non-NaN) double y.  */
        fun compareIntAndDouble(x: StarlarkInt, y: Double): Int {
            if (java.lang.Double.isInfinite(y)) {
                return if (y > 0) -1 else +1
            }

            // For Int32 and some Int64s, the toDouble conversion is exact.
            if (x is Int32
                || (x is Int64 && net.starlark.java.eval.StarlarkInt.Companion.longHasExactDouble(x.v))
            ) {
                // Avoid Double.compare: it believes -0.0 < 0.0.
                val xf = x.toDouble()
                if (xf > y) {
                    return +1
                } else if (xf < y) {
                    return -1
                }
                return 0
            }

            // If signs differ, we needn't look at magnitude.
            val xsign = x.signum()
            val ysign: Int = java.lang.Math.signum(y).toInt()
            if (xsign > ysign) {
                return +1
            } else if (xsign < ysign) {
                return -1
            }

            // Left-shift either the int or the float mantissa,
            // then compare the resulting integers.
            val shift: Int = net.starlark.java.eval.StarlarkFloat.Companion.getShift(y)
            var xbig: BigInteger = x.toBigInteger()
            if (shift < 0) {
                xbig = xbig.shiftLeft(-shift)
            }
            var ybig: BigInteger = BigInteger.valueOf(net.starlark.java.eval.StarlarkFloat.Companion.getMantissa(y))
            if (shift > 0) {
                ybig = ybig.shiftLeft(shift)
            }
            return xbig.compareTo(ybig)
        }

        private fun longHasExactDouble(x: Long): Boolean {
            return x.toDouble().toLong() == x
        }
    }
}
