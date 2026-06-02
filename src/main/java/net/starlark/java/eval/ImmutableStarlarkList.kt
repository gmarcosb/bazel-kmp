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

/** Partial implementation of an immutable `StarlarkList`.  */
internal abstract class ImmutableStarlarkList<E> : net.starlark.java.eval.StarlarkList<E?>() {
    override fun isImmutable(): Boolean {
        return true
    }

    override fun updateIteratorCount(delta: Int): Boolean {
        return false
    }

    override fun mutability(): net.starlark.java.eval.Mutability? {
        return net.starlark.java.eval.Mutability.Companion.IMMUTABLE
    }

    override fun unsafeShallowFreeze() {
        net.starlark.java.eval.Mutability.Freezable.Companion.checkUnsafeShallowFreezePrecondition(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElement(element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElementAt(index: Int, element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElements(elements: Iterable<out E?>?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun removeElementAt(index: Int) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun setElementAt(index: Int, value: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun clearElements() {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
    }

    override fun getImmutableList(): com.google.common.collect.ImmutableList<E?> {
        // Optimization: a frozen array needn't be copied.
        // If the entire array is full, we can wrap it directly.
        return net.starlark.java.eval.Tuple.Companion.wrapImmutable<E?>(elems())
    }

    override fun get(i: Int): E? {
        val elems: Array<Any?> = elems()
        com.google.common.base.Preconditions.checkElementIndex(i, elems.size)
        return elems[i] as E? // unchecked
    }

    override fun size(): Int {
        return elems().length
    }

    override fun contains(o: Any?): Boolean {
        // StarlarkList contains only valid Starlark objects (which are non-null)
        if (o == null) {
            return false
        }
        val elems: Array<Any?> = elems()
        val size = elems.size
        for (i in 0..<size) {
            val elem = elems[i]
            if (o == elem) {
                return true
            }
        }
        return false
    }

    /** Returns a new array of class Object[] containing the list elements.  */
    override fun toArray(): Array<Any?> {
        val elems: Array<Any?> = elems()
        return java.util.Arrays.copyOf<Any?, Any?>(elems, elems.size, Array<Any>::class.java)
    }

    override fun <T> toArray(a: Array<T?>): Array<T?> {
        val elems: Array<Any?> = elems()
        if (a.size < elems.size) {
            return java.util.Arrays.copyOf<Any?, Any?>(elems, elems.size, a.getClass()) as Array<T?>
        } else {
            java.lang.System.arraycopy(elems, 0, a, 0, elems.size)
            java.util.Arrays.fill(a, elems.size, a.size, null)
            return a
        }
    }
}
