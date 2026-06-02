// Copyright 2015 The Bazel Authors. All rights reserved.
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
 * A Starlark value that support indexed access (`object[key]`) and membership tests (`key in object`).
 * 
 * 
 * Implementations of this interface come in three flavors: map-like, sequence-like, and
 * string-like.
 * 
 * 
 *  * For map-like objects, 'x in y' should return True when 'y[x]' is valid; otherwise, it
 * should either be False or a failure. Examples: dict.
 *  * For sequence-like objects, 'x in y' should return True when 'x == y[i]' for some integer
 * 'i'; otherwise, it should either be False or a failure. Examples: list, tuple, and string
 * (which, notably, is not a [Sequence]).
 *  * For string-like objects, 'x in y' should return True when 'x' is a substring of 'y', i.e.
 * 'x[i] == y[i + n]' for some 'n' and all i in [0, len(x)). Examples: string.
 * 
 */
interface StarlarkIndexable : net.starlark.java.eval.StarlarkMembershipTestable {
    /** Returns the value associated with the given key.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any?

    /**
     * A variant of [StarlarkIndexable] that also provides a StarlarkThread instance on method
     * calls.
     */
    // TODO(brandjon): Consider replacing this subinterface by changing StarlarkIndexable's methods'
    // signatures to take StarlarkThread in place of StarlarkSemantics.
    interface Threaded {
        /** {@see StarlarkIndexable.getIndex}  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getIndex(
            starlarkThread: net.starlark.java.eval.StarlarkThread?,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            key: Any?
        ): Any?

        /** {@see StarlarkIndexable.containsKey}  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun containsKey(
            starlarkThread: net.starlark.java.eval.StarlarkThread?,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            key: Any?
        ): Boolean
    }
}
