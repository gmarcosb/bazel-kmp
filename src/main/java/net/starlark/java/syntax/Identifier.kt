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
package net.starlark.java.syntax

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSet

/** Syntax node for an identifier.  */
class Identifier internal constructor(locs: FileLocations?, name: String, nameOffset: Int) :
    Expression(locs, Kind.IDENTIFIER) {
    @kotlin.jvm.JvmField
    private val name: String
    private val nameOffset: Int

    // Set by Resolver if applicable.
    @kotlin.jvm.JvmField
    private var binding: Resolver.Binding? = null

    init {
        this.name = name
        this.nameOffset = nameOffset
    }

    override fun getStartOffset(): Int {
        return nameOffset
    }

    override fun getEndOffset(): Int {
        return nameOffset + name.length
    }

    /**
     * Returns the name of the Identifier. If there were parse errors, misparsed regions may be
     * represented as an Identifier for which `!isValid(getName())`.
     */
    fun getName(): String {
        return name
    }

    fun isPrivate(): Boolean {
        return name.startsWith("_")
    }

    /**
     * Returns information about the binding (symbol) that the identifier refers to.
     * 
     * 
     * Set by the resolver.
     * 
     * 
     * May be null, even after resolving, if this identifier does not refer to a symbol. This
     * happens for instance with keyword arguments and object fields.
     */
    fun getBinding(): Resolver.Binding? {
        return binding
    }

    fun setBinding(bind: Resolver.Binding?) {
        Preconditions.checkState(this.binding == null)
        this.binding = bind
    }

    override fun accept(visitor: NodeVisitor) {
        visitor.visit(this)
    }

    companion object {
        /** Reports whether the string is a valid identifier.  */
        fun isValid(name: String): Boolean {
            // Keep consistent with Lexer.scanIdentifier.
            for (i in 0..<name.length) {
                val c = name.get(i)
                if (!(('a' <= c && c <= 'z')
                            || ('A' <= c && c <= 'Z')
                            || (i > 0 && '0' <= c && c <= '9')
                            || (c == '_'))
                ) {
                    return false
                }
            }
            return !name.isEmpty()
        }

        /**
         * Returns all names bound by an LHS expression.
         * 
         * 
         * Examples:
         * 
         * 
         *  * <`x = ...` binds x.
         *  * <`x, [y,z] = ..` binds x, y, z.
         *  * <`x[5] = ..` does not bind any names.
         * 
         */
        // TODO(adonovan): remove this function in due course.
        // - Resolver makes one pass to discover bindings than another to resolve uses.
        //   When it works in a single pass, it is more efficient to process bindings in order,
        //   deferring (rare) forward references until the end of the block.
        // - Eval calls boundIdentifiers for comprehensions. This can be eliminated when
        //   variables are assigned frame slot indices.
        // - Eval calls boundIdentifiers for the 'export' hack. This can be eliminated
        //   when we switch to compilation by emitting EXPORT instructions for the necessary
        //   bindings. (Ideally we would eliminate Bazel's export hack entirely.)
        @kotlin.jvm.JvmStatic
        fun boundIdentifiers(expr: Expression?): ImmutableSet<Identifier?> {
            if (expr is Identifier) {
                // Common case/fast path - skip the builder.
                return ImmutableSet.of<Identifier?>(expr)
            } else {
                val result = ImmutableSet.builder<Identifier?>()
                collectBoundIdentifiers(expr, result)
                return result.build()
            }
        }

        private fun collectBoundIdentifiers(
            lhs: Expression?, result: ImmutableSet.Builder<Identifier?>
        ) {
            if (lhs is Identifier) {
                result.add(lhs)
                return
            }
            if (lhs is ListExpression) {
                for (expression in lhs.getElements()) {
                    collectBoundIdentifiers(expression, result)
                }
            }
        }
    }
}
