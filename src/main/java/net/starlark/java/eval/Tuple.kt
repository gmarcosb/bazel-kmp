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
package net.starlark.java.eval

import java.util.AbstractCollection
import java.util.AbstractList

/** A Tuple is an immutable finite sequence of values.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "tuple", category = "core", doc = ("The built-in tuple type. Example tuple expressions:<br>"
            + "<pre class=language-python>x = (1, 2, 3)</pre>"
            + "Accessing elements is possible using indexing (starts from <code>0</code>):<br>"
            + "<pre class=language-python>e = x[1]   # e == 2</pre>"
            + "Lists support the <code>+</code> operator to concatenate two tuples. Example:<br>"
            + "<pre class=language-python>x = (1, 2) + (3, 4)   # x == (1, 2, 3, 4)\n"
            + "x = (\"a\", \"b\")\n"
            + "x += (\"c\",)            # x == (\"a\", \"b\", \"c\")</pre>"
            + "Similar to lists, tuples support slice operations:"
            + "<pre class=language-python>('a', 'b', 'c', 'd')[1:3]   # ('b', 'c')\n"
            + "('a', 'b', 'c', 'd')[::2]  # ('a', 'c')\n"
            + "('a', 'b', 'c', 'd')[3:0:-1]  # ('d', 'c', 'b')</pre>"
            + "Tuples are immutable, therefore <code>x[1] = \"a\"</code> is not supported.")
)
abstract class Tuple  // Prohibit instantiation outside of package.
internal constructor() : AbstractList<Any>(), net.starlark.java.eval.Sequence<Any?>, Comparable<Tuple?> {
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        val elementTypes: com.google.common.collect.ImmutableList.Builder<net.starlark.java.syntax.StarlarkType?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<net.starlark.java.syntax.StarlarkType?>(size())
        for (elem in this) {
            elementTypes.add(net.starlark.java.eval.Starlark.Companion.getStarlarkType(elem, semantics))
        }
        return net.starlark.java.syntax.Types.tuple(elementTypes.build())
    }

    override fun compareTo(that: Tuple): Int {
        return net.starlark.java.eval.Sequence.Companion.compare(this, that)
    }

    override fun equals(that: Any?): Boolean {
        // This slightly violates the java.util.List equivalence contract
        // because it considers the class, not just the elements.
        // This is needed because in Starlark tuples are never equal to lists, however in Java they both
        // implement List interface.
        return this === that || (that is Tuple && net.starlark.java.eval.Sequence.Companion.sameElems(this, that))
    }

    // TODO(adonovan): StarlarkValue has 3 String methods yet still we need this fourth. Why?
    override fun toString(): String {
        return net.starlark.java.eval.Starlark.Companion.repr(
            this,
            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
        )
    }

    /** Returns a Tuple containing n consecutive repeats of this tuple.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    abstract fun repeat(n: net.starlark.java.eval.StarlarkInt?): Tuple?

    companion object {
        fun getAssociatedTypeConstructor(): net.starlark.java.syntax.TypeConstructor {
            return net.starlark.java.syntax.Types.TUPLE_CONSTRUCTOR
        }

        /** Returns the empty tuple.  */
        fun empty(): Tuple {
            return net.starlark.java.eval.RegularTuple.Companion.EMPTY
        }

        /** Returns a Tuple that wraps the specified array, which must not be subsequently modified.  */
        fun wrap(array: Array<Any?>): Tuple {
            when (array.size) {
                0 -> return net.starlark.java.eval.RegularTuple.Companion.EMPTY
                1 -> return net.starlark.java.eval.SingletonTuple(array[0])
                else -> return net.starlark.java.eval.RegularTuple(array)
            }
        }

        /** Returns a tuple containing the given elements.  */
        fun copyOf(seq: Iterable<*>): Tuple? {
            if (seq is Tuple) {
                return seq as Tuple
            }
            return net.starlark.java.eval.Tuple.Companion.wrap(
                com.google.common.collect.Iterables.toArray<Any?>(
                    seq,
                    Any::class.java
                )
            )
        }

        /** Returns a tuple containing the given elements.  */
        @kotlin.jvm.JvmStatic
        fun of(vararg elems: Any?): Tuple {
            return net.starlark.java.eval.Tuple.Companion.wrap(java.util.Arrays.copyOf<Any?>(elems, elems.size))
        }

        /** Returns a two-element tuple.  */
        fun pair(a: Any?, b: Any?): Tuple {
            // Equivalent to of(a, b) but avoids variadic array allocation.
            return net.starlark.java.eval.Tuple.Companion.wrap(arrayOf<Any?>(a, b))
        }

        /** Returns a three-element tuple.  */
        fun triple(a: Any?, b: Any?, c: Any?): Tuple {
            // Equivalent to of(a, b, c) but avoids variadic array allocation.
            return net.starlark.java.eval.Tuple.Companion.wrap(arrayOf<Any?>(a, b, c))
        }

        /** Returns a tuple that is the concatenation of two tuples.  */
        fun concat(x: Tuple, y: Tuple): Tuple {
            if (x.isEmpty()) {
                return y
            } else if (y.isEmpty()) {
                return x
            } else {
                val xelems =
                    if (x is net.starlark.java.eval.SingletonTuple)
                        arrayOf<Any?>((x as net.starlark.java.eval.SingletonTuple).elem)
                    else
                        (x as net.starlark.java.eval.RegularTuple).elems
                val yelems =
                    if (y is net.starlark.java.eval.SingletonTuple)
                        arrayOf<Any?>((y as net.starlark.java.eval.SingletonTuple).elem)
                    else
                        (y as net.starlark.java.eval.RegularTuple).elems
                return net.starlark.java.eval.Tuple.Companion.wrap(
                    com.google.common.collect.ObjectArrays.concat<Any?>(
                        xelems,
                        yelems,
                        Any::class.java
                    )
                )
            }
        }

        /**
         * Returns a new ImmutableList<T> backed by `array`, which must not be subsequently
         * modified.
        </T> */
        // TODO(adonovan): move this somewhere more appropriate.
        fun <T> wrapImmutable(array: Array<Any?>): com.google.common.collect.ImmutableList<T?> {
            // Construct an ImmutableList that shares the array.
            // ImmutableList relies on the implementation of Collection.toArray
            // not subsequently modifying the returned array.
            return com.google.common.collect.ImmutableList.copyOf<T?>(
                object : AbstractCollection<T?>() {
                    override fun toArray(): Array<Any?> {
                        return array
                    }

                    override fun size(): Int {
                        return array.size
                    }

                    override fun iterator(): MutableIterator<T?>? {
                        throw java.lang.UnsupportedOperationException()
                    }
                })
        }
    }
}
