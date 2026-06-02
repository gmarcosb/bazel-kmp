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

/**
 * A context for obtaining more detailed information about Starlark types.
 * 
 * 
 * This is used to inject type information from the `eval/` package into the `syntax/` package, e.g. the method APIs of [StarlarkList].
 */
interface TypeContext {
    /** Returns the type of the given field of a `str` type, or null if no such field exists.  */
    fun getStrFieldType(name: String?): StarlarkType?

    /**
     * Returns the type of the given field of a `list[T]` type, or null if no such field exists.
     */
    fun getListFieldType(name: String?): StarlarkType?

    /**
     * Returns the type of the given field of a `dict[K, V]` type, or null if no such field
     * exists.
     */
    fun getDictFieldType(name: String?): StarlarkType?

    /**
     * Returns the type of the given field of a `set[T]` type, or null if no such field exists.
     */
    fun getSetFieldType(name: String?): StarlarkType?

    /**
     * Returns the value type of a [Resolver.Scope.PREDECLARED] symbol, or null if there is no
     * such symbol.
     */
    fun getPredeclaredSymbolType(name: String?): StarlarkType?

    /**
     * Returns the value type of a [Resolver.Scope.UNIVERSAL] symbol, or null if there is no
     * such symbol.
     */
    fun getUniversalSymbolType(name: String?): StarlarkType?
}
