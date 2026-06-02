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

/** A mutable implementation of StarlarkList.  */
internal class MutableStarlarkList<E>(mutability: net.starlark.java.eval.Mutability?, elems: Array<Any?>) :
    net.starlark.java.eval.StarlarkList<E?>(), net.starlark.java.eval.Compactable {
    // The implementation strategy is similar to ArrayList,
    // but without the extra indirection of using ArrayList.
    // elems[0:size] holds the logical elements, and elems[size:] are not used.
    // elems.getClass() == Object[].class. This is necessary to avoid ArrayStoreException.
    private var size: Int
    private var iteratorCount = 0 // number of active iterators (unused once frozen)
    private var elems: Array<Any?> // elems[i] == null  iff  i >= size

    /** Final except for [.unsafeShallowFreeze]; must not be modified any other way.  */
    private var mutability: net.starlark.java.eval.Mutability

    init {
        com.google.common.base.Preconditions.checkArgument(elems.getClass() == Array<Any>::class.java)
        this.elems = if (elems.size == 0) net.starlark.java.eval.StarlarkList.Companion.EMPTY_ARRAY else elems
        this.size = elems.size
        this.mutability = if (mutability == null) net.starlark.java.eval.Mutability.Companion.IMMUTABLE else mutability
    }

    override fun isImmutable(): Boolean {
        return mutability().isFrozen()
    }

    override fun updateIteratorCount(delta: Int): Boolean {
        if (mutability().isFrozen()) {
            return false
        }
        if (delta > 0) {
            iteratorCount++
        } else if (delta < 0) {
            iteratorCount--
        }
        return iteratorCount > 0
    }

    override fun mutability(): net.starlark.java.eval.Mutability {
        return mutability
    }

    override fun unsafeShallowFreeze() {
        net.starlark.java.eval.Mutability.Freezable.Companion.checkUnsafeShallowFreezePrecondition(this)
        this.mutability = net.starlark.java.eval.Mutability.Companion.IMMUTABLE
    }

    override fun getImmutableList(): com.google.common.collect.ImmutableList<E?> {
        // Optimization: a frozen array needn't be copied.
        // If the entire array is full, we can wrap it directly.
        if (elems.size == size && mutability().isFrozen()) {
            return net.starlark.java.eval.Tuple.Companion.wrapImmutable<E?>(elems)
        }

        return com.google.common.collect.ImmutableList.copyOf<E?>(this)
    }

    override fun get(i: Int): E? {
        com.google.common.base.Preconditions.checkElementIndex(i, size)
        return elems[i] as E? // unchecked
    }

    override fun size(): Int {
        return size
    }

    override fun contains(o: Any?): Boolean {
        // StarlarkList contains only valid Starlark objects (which are non-null)
        if (o == null) {
            return false
        }
        for (i in 0..<size) {
            val elem = elems[i]
            if (o == elem) {
                return true
            }
        }
        return false
    }

    // Postcondition: elems.length >= mincap.
    private fun grow(mincap: Int) {
        val oldcap = elems.size
        if (oldcap < mincap) {
            var newcap = oldcap + (oldcap shr 1) // grow by at least 50%
            if (newcap < mincap) {
                newcap = mincap
            }
            elems = java.util.Arrays.copyOf<Any?>(elems, newcap)
        }
    }

    // Grow capacity enough to insert given number of elements
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun growAdditional(additional: Int) {
        val mincap: Int = net.starlark.java.eval.StarlarkList.Companion.addSizesAndFailIfExcessive(size, additional)
        grow(mincap)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElement(element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        growAdditional(1)
        elems[size++] = element
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElementAt(index: Int, element: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        growAdditional(1)
        java.lang.System.arraycopy(elems, index, elems, index + 1, size - index)
        elems[index] = element
        size++
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addElements(elements: Iterable<out E?>) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        if (elements is MutableStarlarkList<*>) {
            // (safe even if this == that)
            growAdditional(elements.size)
            java.lang.System.arraycopy(elements.elems, 0, this.elems, this.size, elements.size)
            this.size += elements.size
        } else if (elements is MutableCollection<*>) {
            // collection of known size
            growAdditional(elements.size())
            for (x in elements) {
                elems[size++] = x
            }
        } else {
            // iterable
            for (x in elements) {
                growAdditional(1)
                elems[size++] = x
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun removeElementAt(index: Int) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        val n = size - index - 1
        if (n > 0) {
            java.lang.System.arraycopy(elems, index + 1, elems, index, n)
        }
        elems[--size] = null // aid GC
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun setElementAt(index: Int, value: E?) {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        com.google.common.base.Preconditions.checkArgument(index < size)
        elems[index] = value
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun clearElements() {
        net.starlark.java.eval.Starlark.Companion.checkMutable(this)
        for (i in 0..<size) {
            elems[i] = null // aid GC
        }
        size = 0
    }

    /** Returns a new array of class Object[] containing the list elements.  */
    override fun toArray(): Array<Any?> {
        return java.util.Arrays.copyOf<Any?, Any?>(elems, size, Array<Any>::class.java)
    }

    override fun <T> toArray(a: Array<T?>): Array<T?> {
        if (a.size < size) {
            return java.util.Arrays.copyOf<Any?, Any?>(elems, size, a.getClass()) as Array<T?>
        } else {
            java.lang.System.arraycopy(elems, 0, a, 0, size)
            java.util.Arrays.fill(a, size, a.size, null)
            return a
        }
    }

    override fun elems(): Array<Any?> {
        return elems
    }

    override fun unsafeOptimizeMemoryLayout(): net.starlark.java.eval.StarlarkList<E?>? {
        com.google.common.base.Preconditions.checkState(mutability.isFrozen())
        if (elems.size > size) {
            // shrink the Object array
            elems = java.util.Arrays.copyOf<Any?>(elems, size)
        }
        // Give the caller an immutable specialization of StarlarkList.
        return net.starlark.java.eval.StarlarkList.Companion.wrap<E?>(
            net.starlark.java.eval.Mutability.Companion.IMMUTABLE,
            elems
        )
    }
}
