// Copyright 2023 The Bazel Authors. All rights reserved.
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

/**
 * An implementation of non-singleton (empty or more than 1 element) Tuple using an `Object`
 * array.
 */
internal class RegularTuple(elems: Array<Any?>) : net.starlark.java.eval.Tuple() {
    val elems: Array<Any?>

    init {
        com.google.common.base.Preconditions.checkArgument(elems.size != 1)
        this.elems = elems
    }

    override fun isImmutable(): Boolean {
        for (x in elems) {
            if (!net.starlark.java.eval.Starlark.Companion.isImmutable(x)) {
                return false
            }
        }
        return true
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        for (x in elems) {
            net.starlark.java.eval.Starlark.Companion.checkHashable(x)
        }
    }

    override fun hashCode(): Int {
        return 9857 + 8167 * java.util.Arrays.hashCode(elems)
    }

    override fun get(i: Int): Any? {
        return elems[i]
    }

    override fun size(): Int {
        return elems.size
    }

    override fun contains(o: Any?): Boolean {
        // Tuple contains only valid Starlark objects (which are non-null)
        if (o == null) {
            return false
        }
        for (elem in elems) {
            if (o == elem) {
                return true
            }
        }
        return false
    }

    override fun subList(from: Int, to: Int): net.starlark.java.eval.Tuple? {
        com.google.common.base.Preconditions.checkPositionIndexes(from, to, elems.size)
        return net.starlark.java.eval.Tuple.Companion.wrap(java.util.Arrays.copyOfRange<Any?>(elems, from, to))
    }

    /** Returns a new array of class Object[] containing the tuple elements.  */
    override fun toArray(): Array<Any?> {
        return java.util.Arrays.copyOf<Any?, Any?>(elems, elems.size, Array<Any>::class.java)
    }

    override fun <T> toArray(a: Array<T?>): Array<T?> {
        if (a.size < elems.size) {
            return java.util.Arrays.copyOf<Any?, Any?>(elems, elems.size, a.getClass()) as Array<T?>
        } else {
            java.lang.System.arraycopy(elems, 0, a, 0, elems.size)
            java.util.Arrays.fill(a, elems.size, a.size, null)
            return a
        }
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        // Remark: RegularTuple doesn't support singleton tuples, so we don't need to special-case
        // the trailing comma for singleton-tuple string representation.
        printer.printList(this, "(", ", ", ")", semantics)
    }

    override fun getImmutableList(): com.google.common.collect.ImmutableList<Any?> {
        // Share the array with this (immutable) Tuple.
        return net.starlark.java.eval.Tuple.Companion.wrapImmutable<Any?>(elems)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getSlice(
        mu: net.starlark.java.eval.Mutability?,
        start: Int,
        stop: Int,
        step: Int
    ): net.starlark.java.eval.Tuple? {
        val indices: net.starlark.java.eval.RangeList = net.starlark.java.eval.RangeList(start, stop, step)
        val n: Int = indices.size()
        if (step == 1) { // common case
            return subList(indices.at(0), indices.at(n))
        }
        val res = arrayOfNulls<Any>(n)
        for (i in 0..<n) {
            res[i] = elems[indices.at(i)]
        }
        return net.starlark.java.eval.Tuple.Companion.wrap(res)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun repeat(n: net.starlark.java.eval.StarlarkInt): net.starlark.java.eval.Tuple? {
        if (n.signum() <= 0 || isEmpty()) {
            return net.starlark.java.eval.Tuple.Companion.empty()
        }

        val ni: Int = n.toInt("repeat")
        val sz = ni.toLong() * elems.size
        if (sz > net.starlark.java.eval.StarlarkList.Companion.MAX_ALLOC) {
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "excessive repeat (%d * %d elements)",
                elems.size,
                ni
            )
        }
        val res = arrayOfNulls<Any>(sz.toInt())
        for (i in 0..<ni) {
            java.lang.System.arraycopy(elems, 0, res, i * elems.size, elems.size)
        }
        return net.starlark.java.eval.Tuple.Companion.wrap(res)
    }

    companion object {
        // The shared (sole) empty tuple.
        val EMPTY: net.starlark.java.eval.Tuple = net.starlark.java.eval.RegularTuple(arrayOf<Any?>())
    }
}
