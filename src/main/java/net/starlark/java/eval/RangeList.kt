// Copyright 2018 The Bazel Authors. All rights reserved.
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

import java.util.AbstractList

/**
 * A sequence returned by the `range` function invocation.
 * 
 * 
 * Instead of eagerly allocating an array with all elements of the sequence, this class uses
 * simple math to compute a value at each index. This is particularly useful when range is huge or
 * only a few elements from it are used.
 * 
 * 
 * The start, stop, step, and size of the range must all fit within 32-bit signed integers.
 * 
 * 
 * Eventually `range` function should produce an instance of the `range` type as is
 * the case in Python 3, but for now to preserve backwards compatibility with Python 2, `list`
 * is returned.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "range",
    category = "core",
    doc = ("A language built-in type to support ranges. Example of range literal:<br>"
            + "<pre class=language-python>x = range(1, 10, 3)</pre>"
            + "Accessing elements is possible using indexing (starts from <code>0</code>):<br>"
            + "<pre class=language-python>e = x[1]   # e == 2</pre>"
            + "Ranges do not support the <code>+</code> operator for concatenation."
            + "Similar to strings, ranges support slice operations:"
            + "<pre class=language-python>range(10)[1:3]   # range(1, 3)\n"
            + "range(10)[::2]  # range(0, 10, 2)\n"
            + "range(10)[3:0:-1]  # range(3, 0, -1)</pre>"
            + "Ranges are immutable, as in Python 3.")
)
@javax.annotation.concurrent.Immutable
internal class RangeList(start: Int, stop: Int, step: Int) : AbstractList<net.starlark.java.eval.StarlarkInt?>(),
    net.starlark.java.eval.Sequence<net.starlark.java.eval.StarlarkInt?> {
    private val start: Int
    private val stop: Int
    private val step: Int
    private val size: Int // (derived)

    init {
        com.google.common.base.Preconditions.checkArgument(step != 0)

        this.start = start
        this.stop = stop
        this.step = step

        // compute size.
        // Python version:
        // https://github.com/python/cpython/blob/09bb918a61031377d720f1a0fa1fe53c962791b6/Objects/rangeobject.c#L144
        val low: Int // [low,high) is a half-open interval
        val high: Int
        val absStep: Long
        if (step > 0) {
            low = start
            high = stop
            absStep = step.toLong()
        } else {
            low = stop
            high = start
            absStep = -step.toLong()
        }
        if (low >= high) {
            this.size = 0
        } else {
            val diff = high.toLong() - low - 1
            val size = diff / absStep + 1
            if (size.toInt().toLong() != size) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "len(%s) exceeds signed 32-bit range",
                    net.starlark.java.eval.Starlark.Companion.repr(
                        this,
                        net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                    )
                )
            }
            this.size = size.toInt()
        }
    }

    override fun contains(x: Any?): Boolean {
        if (x !is net.starlark.java.eval.StarlarkInt) {
            return false
        }
        try {
            val i: Int = (x as net.starlark.java.eval.StarlarkInt).toIntUnchecked()

            // constant-time implementation
            if (step > 0) {
                return start <= i && i < stop && (i - start) % step == 0
            } else {
                return stop < i && i <= start && (i - start) % step == 0
            }
        } catch (ex: java.lang.IllegalArgumentException) {
            return false // x is not a signed 32-bit int
        }
    }

    override fun get(index: Int): net.starlark.java.eval.StarlarkInt {
        if (index < 0 || index >= size()) {
            throw java.lang.ArrayIndexOutOfBoundsException(index.toString() + ":" + this)
        }
        return net.starlark.java.eval.StarlarkInt.Companion.of(at(index))
    }

    override fun size(): Int {
        return size
    }

    override fun hashCode(): Int {
        if (size == 0) {
            return 234982346
        } else if (size == 1) {
            return java.lang.Integer.hashCode(start)
        } else {
            return java.util.Objects.hash(start, size, step)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RangeList) {
            return false
        }
        val that = other

        // Two RangeLists compare equal if they denote the same sequence.
        if (this.size != that.size) {
            return false // sequences differ in length
        }
        if (this.size == 0) {
            return true // both sequences are empty
        }
        if (this.start != that.start) {
            return false // first element differs
        }
        return this.size == 1 || this.step == that.step
    }

    override fun iterator(): MutableIterator<net.starlark.java.eval.StarlarkInt?> {
        return object : com.google.common.collect.UnmodifiableIterator<net.starlark.java.eval.StarlarkInt?>() {
            var cursor: Long = start.toLong() // returned by next() if hasNext() is true

            override fun hasNext(): Boolean {
                return if (step > 0) (cursor < stop) else (cursor > stop)
            }

            override fun next(): net.starlark.java.eval.StarlarkInt {
                if (!hasNext()) {
                    throw java.util.NoSuchElementException()
                }
                // If cursor is valid, it's guaranteed to be in [start, stop) range, thus a 32-bit value.
                val current = cursor.toInt()
                cursor += step.toLong()
                return net.starlark.java.eval.StarlarkInt.Companion.of(current)
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getSlice(
        mu: net.starlark.java.eval.Mutability?,
        start: Int,
        stop: Int,
        step: Int
    ): net.starlark.java.eval.Sequence<net.starlark.java.eval.StarlarkInt?> {
        var stop = stop
        var sliceStep = step.toLong() * this.step.toLong()
        if (sliceStep != sliceStep.toInt().toLong()) {
            // It is not an error to take a slice of a RangeList such that the slice step * list step
            // doesn't fit in a 32-bit int; the result ought to be a RangeList containing only one
            // element (the start). Since difference between 2 successive elements of a RangeList must be
            // a 32-bit int, clamping the step to Integer.MAX_VALUE or MIN_VALUE and moving stop to start
            // +/- 1 gives us the 1-element RangeList we need.
            sliceStep =
                (if (sliceStep > 0) java.lang.Integer.MAX_VALUE else java.lang.Integer.MIN_VALUE).toLong() // note sliceStep != 0
            if (stop > start) {
                stop = start + 1
            } else if (stop < start) {
                stop = start - 1
            }
        }
        return net.starlark.java.eval.RangeList(at(start), at(stop), sliceStep.toInt())
    }

    // Like get, but without bounds check or Integer allocation.
    fun at(i: Int): Int {
        return start + step * i
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        if (step == 1) {
            printer.append(java.lang.String.format("range(%d, %d)", start, stop))
        } else {
            printer.append(java.lang.String.format("range(%d, %d, %d)", start, stop, step))
        }
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.Types.SequenceType {
        return net.starlark.java.syntax.Types.sequence(net.starlark.java.syntax.Types.INT)
    }
}
