// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSetMultimap
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * An immutable context, including variable bindings for variables introduced by [ ]s.
 */
@Immutable
@ThreadSafe
class QueryExpressionContext<T> protected constructor(protected val context: ImmutableMap<String?, MutableSet<T?>?>) {
    /**
     * Returns the value bound to the specified variable given by `name`, or `null` if
     * there is no such binding.
     */
    fun get(name: String?): MutableSet<T?>? {
        return context.get(name)
    }

    /**
     * Returns a [QueryExpressionContext] that has all the same bindings as the given `variableContext` and also the binding of `name` to `value`.
     */
    fun with(name: String?, value: MutableSet<T?>?): QueryExpressionContext<T?> {
        return QueryExpressionContext<T?>(withNewVariable(name, value))
    }

    protected fun withNewVariable(name: String?, value: MutableSet<T?>?): ImmutableMap<String?, MutableSet<T?>?> {
        val newContextBuilder = ImmutableMap.builder<String?, MutableSet<T?>?>()
        for (entry in context.entries) {
            if (entry.key != name) {
                // The binding of 'name' to 'value' should override any existing binding of name in
                // 'variableContext'. These are the semantics we want in order for nested let-expressions
                // to have the semantics we want.
                newContextBuilder.put(entry)
            }
        }
        newContextBuilder.put(name, value)
        return newContextBuilder.buildOrThrow()
    }

    /**
     * A globally defined map of extra dependency edges. If `//a -> //b` is an entry in this map, then
     * any dependency evaluation of the graph should behave as if `//a` depends on `//b`.
     */
    fun extraGlobalDeps(): ImmutableSetMultimap<SkyKey?, SkyKey?> {
        // Only subclasses of this class support extra global deps.
        return ImmutableSetMultimap.of<SkyKey?, SkyKey?>()
    }

    override fun toString(): String {
        return "QueryExpressionContext: " + context
    }

    companion object {
        /** Returns a [QueryExpressionContext] with no variables defined.  */
        fun <T> empty(): QueryExpressionContext<T?> {
            return QueryExpressionContext<T?>(ImmutableMap.of<String?, MutableSet<T?>?>())
        }
    }
}

