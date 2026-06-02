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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/** An immutable [StarlarkList] that lazily invokes a supplier to obtain its elements.  */
class LazyImmutableStarlarkList<E> internal constructor(supplier: net.starlark.java.eval.StarlarkList.SerializableListSupplier<E?>?) :
    net.starlark.java.eval.ImmutableStarlarkList<E?>(), net.starlark.java.eval.Compactable {
    private var supplier: net.starlark.java.eval.StarlarkList.SerializableListSupplier<E?>?

    @kotlin.concurrent.Volatile
    private var elems: Array<Any?>?

    init {
        this.supplier = supplier
    }

    override fun size(): Int {
        return elems().length
    }

    override fun get(i: Int): E? {
        val elems = elems()
        com.google.common.base.Preconditions.checkElementIndex(i, elems.size)
        return elems[i] as E?
    }

    override fun elems(): Array<Any?> {
        if (elems == null) {
            synchronized(this) {
                if (elems == null) {
                    elems = supplier.get().toArray()
                    supplier = null
                }
            }
        }
        return elems!!
    }

    override fun unsafeOptimizeMemoryLayout(): net.starlark.java.eval.StarlarkList<E?>? {
        if (elems != null) {
            return net.starlark.java.eval.StarlarkList.Companion.wrap<E?>(
                net.starlark.java.eval.Mutability.Companion.IMMUTABLE,
                elems
            )
        }
        return this
    }
}
