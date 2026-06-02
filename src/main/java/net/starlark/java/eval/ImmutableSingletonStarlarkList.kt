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

/** An immutable singleton implementation of a `StarlarkList`.  */
internal class ImmutableSingletonStarlarkList<E>(elem: Any?) : net.starlark.java.eval.ImmutableStarlarkList<E?>() {
    private val elem: Any?

    init {
        this.elem = elem
    }

    override fun getImmutableList(): com.google.common.collect.ImmutableList<E?> {
        return com.google.common.collect.ImmutableList.of<E?>(elem as E?)
    }

    override fun get(i: Int): E? {
        com.google.common.base.Preconditions.checkElementIndex(i, 1)
        return elem as E? // unchecked
    }

    override fun size(): Int {
        return 1
    }

    override fun contains(o: Any?): Boolean {
        // StarlarkList contains only valid Starlark objects (which are non-null)
        if (o == null) {
            return false
        }
        return o == elem
    }

    /** Returns a new array of class Object[] containing the list elements.  */
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

    override fun elems(): Array<Any?> {
        return arrayOf<Any?>(elem)
    }
}
