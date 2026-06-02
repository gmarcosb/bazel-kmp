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

/** An immutable implementation of a `StarlarkList`.  */
internal class RegularImmutableStarlarkList<E>(elems: Array<Any?>) :
    net.starlark.java.eval.ImmutableStarlarkList<E?>() {
    private val elems: Array<Any?>

    init {
        com.google.common.base.Preconditions.checkArgument(elems.getClass() == Array<Any>::class.java)
        this.elems = elems
    }

    override fun elems(): Array<Any?> {
        return elems
    }

    companion object {
        /**
         * A shared instance for the empty list with immutable mutability.
         * 
         * 
         * Other immutable empty list objects can exist, e.g. lists that were once mutable but whose
         * environments were then frozen. This instance is for empty lists that were always frozen from
         * the beginning.
         */
        val EMPTY: net.starlark.java.eval.StarlarkList<*> =
            net.starlark.java.eval.RegularImmutableStarlarkList<Any?>(net.starlark.java.eval.StarlarkList.Companion.EMPTY_ARRAY)
    }
}
