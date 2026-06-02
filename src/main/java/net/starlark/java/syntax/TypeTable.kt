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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * The static type information for a Starlark file.
 * 
 * 
 * At first glance, one may think this information ought to be directly embedded in a [ ]'s AST nodes. But storing it separately has some benefits: for one thing, we can do a
 * second static typechecking pass post-evaluation without having evaluation mutate the AST. And it
 * supports Bazel's model of Starlark evaluation under which compilation (whose output needs to be
 * immutable) is separated from type-checking and evaluation.
 * 
 * 
 * Initialized by [TypeTagger] and further refined by [TypeChecker].
 */
class TypeTable private constructor(numGlobals: Int, numBindings: Int, numFunctions: Int) {
    // Declared types of global bindings. Indexed by the binding's global scope index.
    private val globalsDeclaredTypes: Array<StarlarkType?>

    // Types of bindings (regardless of scope). Indexed by binding's sequence number.
    private val bindingTypes: Array<StarlarkType?>

    // Types of functions. Indexed by function's sequence number.
    private val functionTypes: Array<Types.CallableType?>

    // Whether a function uses type syntax. Indexed by function's sequence number.
    private val functionsUsingTypeSyntax: BooleanArray

    val errors: MutableList<SyntaxError?> = ArrayList<SyntaxError?>()

    /** Constructs a [TypeTable] large enough to hold typeable entities in the given file.  */
    constructor(file: StarlarkFile) : this(file.getResolvedFunction()!!)

    /**
     * Constructs a [TypeTable] large enough to hold typeable entities in the given toplevel
     * function.
     */
    internal constructor(toplevel: Resolver.Function) : this(
        toplevel.getGlobals().size(),
        toplevel.getNumBindingsInFile(),  // toplevel's function sequence number is the max function sequence number in the file.
        toplevel.getFunctionId() + 1
    )

    init {
        this.globalsDeclaredTypes = arrayOfNulls<StarlarkType>(numGlobals)
        this.bindingTypes = arrayOfNulls<StarlarkType>(numBindings)
        this.functionTypes = arrayOfNulls<Types.CallableType>(numFunctions)
        this.functionsUsingTypeSyntax = BooleanArray(numFunctions)
    }

    /** Returns the list of errors recorded in the type table.  */
    fun errors(): ImmutableList<SyntaxError?> {
        return ImmutableList.copyOf<SyntaxError?>(errors)
    }

    /** Returns true if no errors were recorded in the type table.  */
    fun ok(): Boolean {
        return errors.isEmpty()
    }

    /**
     * Sets the declared (annotated) type of the given binding. May be called more than once. Null is
     * treated is untyped / Any.
     */
    fun setDeclaredType(binding: Resolver.Binding, type: StarlarkType?) {
        bindingTypes[binding.getBindingId()] = type
        if (binding.getScope() == Resolver.Scope.GLOBAL) {
            globalsDeclaredTypes[binding.getIndex()] = type
        }
    }

    /**
     * Sets the inferred type of the given binding. May be called more than once. Null is treated is
     * untyped / Any.
     */
    fun setInferredType(binding: Resolver.Binding, type: StarlarkType?) {
        bindingTypes[binding.getBindingId()] = type
    }

    /**
     * Sets the type of the given function. May be called more than once. Null is treated is untyped /
     * Any.
     */
    fun setType(function: Resolver.Function, type: Types.CallableType?) {
        functionTypes[function.getFunctionId()] = type
    }

    /** Returns the declared type of the global binding. Null indicates untyped / Any.  */
    fun getGlobalDeclaredType(binding: Resolver.Binding): StarlarkType? {
        Preconditions.checkArgument(binding.getScope() == Resolver.Scope.GLOBAL)
        return globalsDeclaredTypes[binding.getIndex()]
    }

    /**
     * Returns the type of the binding (whether declared or inferred). Null indicates untyped / Any.
     */
    fun getType(binding: Resolver.Binding): StarlarkType? {
        return bindingTypes[binding.getBindingId()]
    }

    /**
     * Returns the type of the function, or null for the program's toplevel. After type tagging is
     * complete, expected to be non-null for non-toplevel functions.
     */
    fun getType(function: Resolver.Function): Types.CallableType? {
        return functionTypes[function.getFunctionId()]
    }

    /**
     * After type tagging has been performed, returns true if the non-lambda function with this
     * sequence number is considered to use static typing syntax - in other words, type annotations or
     * `cast` expressions. Specifically:
     * 
     * 
     *  * For an ordinary function, returns true if the function's declaration or body (including
     * any nested lambdas, but *not* including any ordinary nested `def` functions)
     * uses type syntax.
     *  * For a file's toplevel function, returns true if any part of the file uses type syntax.
     *  * For a lambda, this bit is never set; callers should instead check [.usesTypeSyntax]
     * for the most proximate enclosing def or toplevel.
     * 
     */
    fun usesTypeSyntax(function: Resolver.Function): Boolean {
        return functionsUsingTypeSyntax[function.getFunctionId()]
    }

    /** Marks the function with the given sequence number as using type syntax.  */
    fun setUsesTypeSyntax(function: Resolver.Function) {
        functionsUsingTypeSyntax[function.getFunctionId()] = true
    }
}
