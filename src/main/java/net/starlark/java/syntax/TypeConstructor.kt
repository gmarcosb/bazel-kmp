// Copyright 2026 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.errorprone.annotations.CanIgnoreReturnValue

/**
 * A factory for creating [StarlarkType]s, parameterized by zero or more type arguments.
 * 
 * 
 * Conceptually, a type constructor corresponds to what the user informally thinks of as "a
 * type": a program symbol, like `list`, that can appear within a type expression. The usage
 * of a constructor in a type expression yields an actual type, like `list[int]`. In the case
 * of basic types like `None` that are not parameterized, there is both a trivial nullary type
 * constructor and an underlying singleton type, where the constructor just wraps the underlying
 * type.
 */
interface TypeConstructor {
    /** Exception thrown when a [TypeConstructor] is called with invalid arguments.  */
    class Failure internal constructor(message: String?) : Exception(message)

    /**
     * An argument to a type constructor's [.createStarlarkType] method.
     * 
     * 
     * Conceptually, a type argument is the result of evaluating a subexpression of a type
     * expression. Whereas the overall type expression must yield a [StarlarkType], a
     * subexpression can also yield other objects such as an ellipsis or a list of other arguments.
     * These are needed for type expressions like `tuple[Any, ...]` and `Callable[[int], bool]`.
     */
    // TODO: #27370 - Support other type arguments besides StarlarkType, Ellipsis, and EmptyTuple when
    // we need them
    interface Arg {
        /** An ellipsis type argument, `...`.  */
        class Ellipsis private constructor() : Arg {
            override fun toString(): String {
                return "..."
            }
        }

        /** An empty tuple type argument, `()`.  */
        class EmptyTuple private constructor() : Arg {
            override fun toString(): String {
                return "()"
            }
        }

        /** A dictionary with string keys and type values, e.g. `{"foo": T, "bar": U}`.  */
        class TypeDict internal constructor(types: ImmutableMap<String?, StarlarkType?>) : Arg {
            private val types: ImmutableMap<String?, StarlarkType?>

            init {
                this.types = types
            }

            fun getTypes(): ImmutableMap<String?, StarlarkType?> {
                return types
            }

            override fun toString(): String {
                return print(StringBuilder(), types).toString()
            }

            companion object {
                @CanIgnoreReturnValue
                fun print(buf: StringBuilder, types: ImmutableMap<String?, StarlarkType?>): StringBuilder {
                    buf.append('{')
                    var first = true
                    for (entry in types.entrySet()) {
                        if (!first) {
                            buf.append(", ")
                        }
                        NodePrinter.Companion.printStringLiteral(buf, entry.getKey())
                        buf.append(": ")
                        buf.append(entry.getValue())
                        first = false
                    }
                    buf.append('}')
                    return buf
                }
            }
        }

        companion object {
            val ELLIPSIS: Ellipsis = Arg.Ellipsis()
            val EMPTY_TUPLE: EmptyTuple = Arg.EmptyTuple()
        }
    }

    /**
     * Returns the result of applying this constructor to the given type arguments
     * 
     * @throws Failure if the usage of this constructor is invalid (typically due to a mismatch in the
     * number of arguments)
     */
    @Throws(Failure::class)
    fun createStarlarkType(argsTuple: ImmutableList<Arg?>?): StarlarkType?
}
