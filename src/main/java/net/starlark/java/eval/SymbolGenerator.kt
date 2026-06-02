// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue

/**
 * Class to be used when an object wants to be compared using reference equality. Since reference
 * equality is not usable when comparing objects across multiple Starlark evaluations, we use a more
 * stable method: an object identifying the [.owner] of the current Starlark context, and an
 * [.index] indicating how many reference-equal objects have already been created (and
 * therefore asked for a unique symbol for themselves). Global symbols may also be identified using
 * their exported name rather than an anonymous index.
 * 
 * 
 * Objects that want to use reference equality should instead call [.generate] on a
 * provided `SymbolGenerator` instance, and compare the returned object for equality, since it
 * will be stable across identical Starlark evaluations. Note that equality comparisons are
 * invalidated by any change to the inputs of a Starlark evaluation. For example, it is not valid to
 * compare two values that came from different Bazel builds with an intervening edit to a .bzl file.
 * 
 * 
 * For Starlark values that rely on this class, equality comparison across Starlark threads is
 * not guaranteed to be consistent until both threads are done running. This is due to the edge case
 * of one value being exported while the other is still unexported, since the export process can
 * change the equality token.
 */
class SymbolGenerator<T> private constructor(owner: T?) {
    private val owner: T?
    private var index = 0

    init {
        this.owner = owner
    }

    @kotlin.jvm.Synchronized
    fun generate(): Symbol<T?> {
        return net.starlark.java.eval.SymbolGenerator.LocalSymbol<T?>(owner, index++)
    }

    fun getOwner(): T? {
        return owner
    }

    /** Identifier for an object created by a uniquely defined Starlark thread.  */ // TODO(bazel-team): The name "Symbol", in the context of an interpreter, is a bit confusing.
    // Consider renaming to "Token" or similar.
    abstract class Symbol<T> {
        /**
         * Creates a new [GlobalSymbol] with the same owner as this symbol.
         * 
         * 
         * Objects may start with a [LocalSymbol] and are later exported with a global name.
         * This method can be used to create a suitable [GlobalSymbol].
         */
        fun exportAs(name: String?): GlobalSymbol<T?> {
            return net.starlark.java.eval.SymbolGenerator.GlobalSymbol.Companion.create<T?>(getOwner(), name)
        }

        abstract fun getOwner(): T?

        abstract fun isGlobal(): Boolean
    }

    internal class LocalSymbol<T> private constructor(owner: T?, index: Int) : Symbol<T?>() {
        private val owner: T?
        private val index: Int

        // This field can always be recomputed and its value depends on the hashCode of the owner, which
        // can well be non-deterministic. So we're better off making this a transient field.
        @Transient
        private var lazyHashCode = 0

        init {
            if (owner == null) {
                throw java.lang.NullPointerException("Null owner")
            }
            this.owner = owner
            this.index = index
        }

        override fun getOwner(): T? {
            return owner
        }

        override fun isGlobal(): Boolean {
            return false
        }

        override fun toString(): String {
            return "LocalSymbol{" + "owner=" + owner + ", " + "index=" + index + "}"
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is LocalSymbol<*>) {
                return false
            }
            return this.owner == o.owner && this.index == o.index
        }

        override fun hashCode(): Int {
            if (lazyHashCode == 0) {
                var hashCode = 1000003
                hashCode = hashCode xor owner!!.hashCode()
                hashCode *= 1000003
                hashCode = hashCode xor index
                this.lazyHashCode = hashCode
            }
            return lazyHashCode
        }
    }

    /**
     * An identifier for a global variable.
     * 
     * 
     * Intended as an optimization, allowing the lookup of a global variable from its GlobalSymbol,
     * e.g. for deserialization: the owner should be a wrapper object for a [Module], and we can
     * obtain the value from the symbol's name and [Module.getGlobal].
     */
    @AutoValue
    abstract class GlobalSymbol<T> : Symbol<T?>() {
        abstract fun getName(): String?

        override fun isGlobal(): Boolean {
            return true
        }

        companion object {
            private fun <T> create(owner: T?, name: String?): GlobalSymbol<T?> {
                return AutoValue_SymbolGenerator_GlobalSymbol(owner, name)
            }
        }
    }

    companion object {
        /** An identifier that can be used for constants.  */
        @kotlin.jvm.JvmField
        val CONSTANT_SYMBOL: Symbol<*> = net.starlark.java.eval.SymbolGenerator.Companion.createTransient().generate()

        /**
         * Creates a new symbol generator for the Starlark evaluation uniquely identified by the given
         * owner.
         * 
         * 
         * Precisely, two `SymbolGenerators` that have owners `o1` and `o2` are
         * considered to be for the same Starlark evaluation, if and only if `o1.equals(o2)`.
         */
        fun <T> create(owner: T?): SymbolGenerator<T?> {
            return net.starlark.java.eval.SymbolGenerator<T?>(owner)
        }

        /**
         * Creates a generator for a Starlark evaluation whose values don't require strict reference
         * equality checks.
         * 
         * 
         * This can be used in the following cases.
         * 
         * 
         *  * The result of a Starlark evaluation has a simple type (like numbers or strings) where
         * values are compared, not object references.
         *  * The result is temporary and it won't be stored, transmitted or regenerated while being
         * retained.
         * 
         * 
         * 
         * The "regenerated while being retained" condition may occur, for example, if a part of the
         * resulting value is retained somewhere in the process, but the value itself is evicted from a
         * cache and is subsequently regenerated.
         */
        @kotlin.jvm.JvmStatic
        fun createTransient(): SymbolGenerator<Any?> {
            return net.starlark.java.eval.SymbolGenerator.Companion.create<Any?>(Any())
        }
    }
}
