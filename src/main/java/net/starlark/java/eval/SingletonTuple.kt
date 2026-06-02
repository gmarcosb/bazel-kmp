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

/** A specific implementation of a Tuple that has exactly 1 element.  */
internal class SingletonTuple(elem: Any) : net.starlark.java.eval.Tuple() {
    val elem: Any

    init {
        this.elem = elem
    }

    override fun isImmutable(): Boolean {
        return net.starlark.java.eval.Starlark.Companion.isImmutable(elem)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        net.starlark.java.eval.Starlark.Companion.checkHashable(elem)
    }

    override fun hashCode(): Int {
        // This produces the same results as RegularTuple.hashCode(),
        return 9857 + 8167 * (31 + elem.hashCode())
    }

    override fun get(i: Int): Any {
        com.google.common.base.Preconditions.checkElementIndex(i, 1)
        return elem
    }

    override fun size(): Int {
        return 1
    }

    override fun contains(o: Any?): Boolean {
        // Tuple contains only valid Starlark objects (which are non-null)
        if (o == null) {
            return false
        }
        return o == elem
    }

    override fun subList(from: Int, to: Int): net.starlark.java.eval.Tuple? {
        com.google.common.base.Preconditions.checkPositionIndexes(from, to, 1)
        return if (from <= 0 && to >= 1) this else net.starlark.java.eval.Tuple.Companion.empty()
    }

    /** Returns a new array of class Object[] containing the tuple elements.  */
    override fun toArray(): Array<Any?> {
        return arrayOf<Any?>(elem)
    }

    override fun <T> toArray(a: Array<T?>): Array<T?> {
        if (a.size < 1) {
            return arrayOf<Any?>(elem) as Array<T?>
        } else {
            a[0] = elem as T?
            java.util.Arrays.fill(a, 1, a.size, null)
            return a
        }
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append('(').repr(elem, semantics).append(",)")
    }

    override fun getImmutableList(): com.google.common.collect.ImmutableList<Any?> {
        return com.google.common.collect.ImmutableList.of<Any?>(elem)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getSlice(
        mu: net.starlark.java.eval.Mutability?,
        start: Int,
        stop: Int,
        step: Int
    ): net.starlark.java.eval.Tuple? {
        val indices: net.starlark.java.eval.RangeList = net.starlark.java.eval.RangeList(start, stop, step)
        return if (indices.isEmpty()) net.starlark.java.eval.Tuple.Companion.empty() else this
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun repeat(n: net.starlark.java.eval.StarlarkInt): net.starlark.java.eval.Tuple? {
        if (n.signum() <= 0) {
            return net.starlark.java.eval.Tuple.Companion.empty()
        }

        val ni: Int = n.toInt("repeat")
        if (ni > net.starlark.java.eval.StarlarkList.Companion.MAX_ALLOC) {
            throw net.starlark.java.eval.Starlark.Companion.errorf("excessive repeat (%d * %d elements)", 1, ni)
        }
        val res = arrayOfNulls<Any>(ni)
        java.util.Arrays.fill(res, 0, ni, elem)
        return net.starlark.java.eval.Tuple.Companion.wrap(res)
    }
}
