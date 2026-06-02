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

import java.util.RandomAccess

/**
 * A Sequence is a finite iterable sequence of Starlark values, such as a list or tuple.
 * 
 * 
 * Sequences implement the read-only operations of the [List] interface, but not its update
 * operations, similar to `ImmutableList`. The specification of `List` governs how such
 * methods behave and in particular how they report errors. Subclasses of sequence may define ad-hoc
 * mutator methods, such as [StarlarkList.extend], exposed to Starlark, or Java, or both.
 * 
 * 
 * In principle, subclasses of Sequence could also define the standard update operations of List,
 * but there appears to be little demand, and doing so carries some risk of obscuring unintended
 * mutations to Starlark values that would currently cause the program to crash.
 */
interface Sequence<E>
    : net.starlark.java.eval.StarlarkValue, MutableList<E?>, RandomAccess, net.starlark.java.eval.StarlarkIndexable,
    net.starlark.java.eval.StarlarkIterable<E?> {
    override fun truth(): Boolean {
        return !isEmpty()
    }

    /** Returns an ImmutableList object with the current underlying contents of this Sequence.  */
    fun getImmutableList(): com.google.common.collect.ImmutableList<E?>? {
        return com.google.common.collect.ImmutableList.copyOf<E?>(this)
    }

    /** Retrieves an entry from a Sequence.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): E? {
        val index: Int = net.starlark.java.eval.Starlark.Companion.toInt(key, "sequence index")
        return get(net.starlark.java.eval.EvalUtils.getSequenceIndex(index, size()))
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
        return contains(key)
    }

    /**
     * Returns the slice of this sequence, `this[start:stop:step]`. <br></br>
     * For positive strides (`step > 0`), `0 <= start <= stop <= size()`. <br></br>
     * For negative strides (`step < 0`), `-1 <= stop <= start < size()`. <br></br>
     * The caller must ensure that the start and stop indices are valid and that step is non-zero.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getSlice(mu: net.starlark.java.eval.Mutability?, start: Int, stop: Int, step: Int): Sequence<E?>?

    companion object {
        /**
         * Compares two sequences of values. Sequences compare equal if corresponding elements compare
         * equal using `x[i] == y[i]`. Otherwise, the result is the ordered comparison of the first
         * element for which `x[i] != y[i]`. If one list is a prefix of another, the result is the
         * comparison of the list's sizes.
         * 
         * @throws ClassCastException if any comparison failed.
         */
        fun compare(x: MutableList<*>, y: MutableList<*>): Int {
            for (i in 0..<java.lang.Math.min(x.size(), y.size())) {
                val xelem: Any = x.get(i)!!
                val yelem: Any = y.get(i)!!

                // First test for equality. This avoids an unnecessary
                // ordered comparison, which may be unsupported despite
                // the values being equal. Also, it is potentially more
                // expensive. For example, list==list need not look at
                // the elements if the lengths are unequal.
                if (xelem === yelem || xelem == yelem) {
                    continue
                }

                // The ordered comparison of unequal elements should
                // always be nonzero unless compareTo is inconsistent.
                val cmp: Int = net.starlark.java.eval.Starlark.Companion.compareUnchecked(xelem, yelem)
                check(cmp != 0) {
                    java.lang.String.format(
                        "x.equals(y) yet x.compareTo(y)==%d (x: %s, y: %s)",
                        cmp,
                        net.starlark.java.eval.Starlark.Companion.type(xelem),
                        net.starlark.java.eval.Starlark.Companion.type(yelem)
                    )
                }
                return cmp
            }
            return java.lang.Integer.compare(x.size(), y.size())
        }

        /**
         * Compares two sequences of value for equality. Sequences compare equal if they are the same size
         * and corresponding elements compare equal.
         */
        fun sameElems(x: MutableList<*>, y: MutableList<*>): Boolean {
            if (x === y) {
                return true
            }
            if (x.size() != y.size()) {
                return false
            }
            for (i in x.indices) {
                val xelem: Any = x.get(i)!!
                val yelem: Any? = y.get(i)

                if (xelem !== yelem && xelem != yelem) {
                    return false
                }
            }
            return true
        }

        /**
         * Casts a non-null Starlark value `x` to a `Sequence<T>`, after checking that each
         * element is an instance of `elemType`. On error, it throws an EvalException whose message
         * includes `what`, ideally a string literal, as a description of the role of `x`.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <T> cast(x: Any?, elemType: java.lang.Class<T?>, what: String?): Sequence<T?> {
            com.google.common.base.Preconditions.checkNotNull<Any?>(x)
            if (x !is Sequence<*>) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "for %s, got %s, want sequence",
                    what,
                    net.starlark.java.eval.Starlark.Companion.type(x)
                )
            }
            var i = 0
            for (elem in x) {
                if (!elemType.isAssignableFrom(elem.getClass())) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "at index %d of %s, got element of type %s, want %s",
                        i,
                        what,
                        net.starlark.java.eval.Starlark.Companion.type(elem),
                        net.starlark.java.eval.Starlark.Companion.classType(elemType)
                    )
                }
                i++
            }
            val result:  // safe
                    Sequence<T?> = x
            return result
        }

        /** Like [.cast], but if x is None, returns an immutable empty list.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun <T> noneableCast(x: Any?, type: java.lang.Class<T?>, what: String?): Sequence<T?>? {
            return if (x === net.starlark.java.eval.Starlark.Companion.NONE) net.starlark.java.eval.StarlarkList.Companion.empty<T?>() else net.starlark.java.eval.Sequence.Companion.cast<T?>(
                x,
                type,
                what
            )
        }
    }
}
