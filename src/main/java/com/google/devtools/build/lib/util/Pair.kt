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
package com.google.devtools.build.lib.util

/** An immutable, semantic-free ordered pair of nullable values. Avoid using it in public APIs.  */
class Pair<A, B>
/**
 * Constructor.  It is usually easier to call [.of].
 */(
    /**
     * The first element of the pair.
     */
    @kotlin.jvm.JvmField val first: A?,
    /**
     * The second element of the pair.
     */
    @kotlin.jvm.JvmField val second: B?
) {
    override fun toString(): String {
        return "(" + first + ", " + second + ")"
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is Pair<*, *>) {
            return false
        }
        val p = o
        return first == p.first && second == p.second
    }

    override fun hashCode(): Int {
        val hash1 = if (first == null) 0 else first.hashCode()
        val hash2 = if (second == null) 0 else second.hashCode()
        return 31 * hash1 + hash2
    }

    companion object {
        /**
         * Creates a new pair containing the given elements in order.
         */
        fun <A, B> of(first: A?, second: B?): Pair<A?, B?> {
            return com.google.devtools.build.lib.util.Pair<A?, B?>(first, second)
        }
    }
}
