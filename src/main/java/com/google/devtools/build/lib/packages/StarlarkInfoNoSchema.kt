// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.StarlarkInfo
import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema
import com.google.devtools.build.lib.packages.StarlarkProvider
import java.util.HashMap

/**
 * A struct-like Info (provider instance) for providers defined in Starlark that don't have a
 * schema.
 */
open class StarlarkInfoNoSchema : StarlarkInfo {
    private val provider: com.google.devtools.build.lib.packages.Provider

    // For a n-element info, the table contains n key strings, sorted,
    // followed by the n corresponding legal Starlark values.
    private val table: Array<Any?>

    // TODO(adonovan): restrict type of provider to StarlarkProvider?
    // Do we ever need StarlarkInfos of BuiltinProviders? Such BuiltinProviders could
    // be  moved to Starlark using bzl builtins injection.
    // Alternatively: what about this implementation is specific to StarlarkProvider?
    // It's really just a "generic" or "dynamic" representation of a struct,
    // analogous to reflection versus generated message classes in the protobuf world.
    // The efficient table algorithms would be a nice addition to the Starlark
    // interpreter, to allow other clients to define their own fast structs
    // (or to define a standard one). See also comments at Info about upcoming clean-ups.
    private constructor(provider: com.google.devtools.build.lib.packages.Provider, table: Array<Any?>) {
        this.provider = provider
        this.table = table
    }

    internal constructor(provider: com.google.devtools.build.lib.packages.Provider, values: MutableMap<String?, Any?>) {
        this.provider = provider
        this.table = toTable(values)
    }

    override fun getProvider(): com.google.devtools.build.lib.packages.Provider {
        return provider
    }

    /**
     * Constructs a StarlarkInfo with calls forwarded from one of the StarlarkInfo ArgumentProcessor
     * implementations. Checks that each key is provided at most once. This class exists solely for
     * the StarlarkInfo ArgumentProcessors.
     */
    internal class StarlarkInfoFactory(provider: StarlarkProvider?, thread: net.starlark.java.eval.StarlarkThread?) :
        com.google.devtools.build.lib.packages.StarlarkProvider.StarlarkInfoFactory(provider, thread) {
        private val namedArgMap: MutableMap<String?, Any?>

        init {
            this.namedArgMap = HashMap<String?, Any?>()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun addNamedArg(name: String?, value: Any?) {
            // TODO(b/380824219): Evaluate whether we can know the number of named args here, and then
            // place the args into the table directly.
            val oldValue = namedArgMap.put(name, value)
            if (oldValue != null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "got multiple values for parameter %s in call to instantiate provider %s",
                    name, provider.getPrintableName()
                )
            }
        }

        public override fun createFromArgs(): StarlarkInfo {
            return StarlarkInfoNoSchema(provider, namedArgMap)
        }

        public override fun createFromMap(map: MutableMap<String?, Any?>): StarlarkInfo {
            return StarlarkInfoNoSchema(provider, map)
        }
    }

    override fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?> {
        // TODO(adonovan): opt: can we avoid allocating three objects?
        val keys = java.util.Arrays.asList<Any?>(*table)
            .subList(0, table.length / 2) as MutableList<*>? as MutableList<String?>
        return com.google.common.collect.ImmutableList.copyOf<String?>(keys)
    }

    override fun isImmutable(): Boolean {
        // If the provider is not yet exported, the hash code of the object is subject to change.
        if (!provider.isExported()) {
            return false
        }
        for (i in table.length / 2..<table.length) {
            if (!net.starlark.java.eval.Starlark.isImmutable(table[i])) {
                return false
            }
        }
        return true
    }

    override fun getValue(name: String?): Any? {
        val n: Int = table.length / 2
        var i: Int
        if (n <= StarlarkInfo.Companion.BINARY_SEARCH_THRESHOLD) {
            i = -1
            for (j in 0..<n) {
                if (table[j] == name) {
                    i = j
                    break
                }
            }
        } else {
            i = java.util.Arrays.binarySearch(table, 0, n, name)
        }
        if (i < 0) {
            return null
        }
        return table[n + i]
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun binaryOp(op: net.starlark.java.syntax.TokenKind?, that: Any?, thisLeft: Boolean): StarlarkInfo? {
        if (op == net.starlark.java.syntax.TokenKind.PLUS && that is StarlarkInfo) {
            val thatProvider: com.google.devtools.build.lib.packages.Provider = (that as StarlarkInfo).getProvider()
            if (provider != thatProvider) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Cannot use '+' operator on instances of different providers (%s and %s)",
                    provider.getPrintableName(), thatProvider.getPrintableName()
                )
            }
            com.google.common.base.Preconditions.checkArgument(that is StarlarkInfoNoSchema)
            return if (thisLeft)
                plus(this, that as StarlarkInfoNoSchema) //
            else
                plus(that as StarlarkInfoNoSchema, this)
        }
        return null
    }

    override fun unsafeOptimizeMemoryLayout(): StarlarkInfoNoSchema {
        for (i in table.length / 2..<table.length) {
            if (table[i] is net.starlark.java.eval.Compactable) {
                table[i] = compactable.unsafeOptimizeMemoryLayout()
            }
        }
        return this
    }

    companion object {
        /**
         * Creates a schemaless provider instance with the given provider type and field values.
         * 
         * @param provider A `Provider` without a schema. `StarlarkProvider` with a schema is
         * not supported by this call.
         * @param values the field values
         */
        fun createSchemaless(
            provider: com.google.devtools.build.lib.packages.Provider,
            values: MutableMap<String?, Any?>
        ): StarlarkInfo {
            com.google.common.base.Preconditions.checkArgument(
                provider !is StarlarkProvider || (provider as StarlarkProvider).getFields() == null
            )
            return StarlarkInfoNoSchema(provider, values)
        }

        // Converts a map to a table of sorted keys followed by corresponding values.
        private fun toTable(values: MutableMap<String?, Any?>): Array<Any?> {
            val n: Int = values.size()
            val table = arrayOfNulls<Any>(n + n)
            var i = 0
            // TODO(b/380824219): Once fastcall and thus createFromNamedArgs is removed, consider whether
            // we can wrap values.entrySet() in a SortedSet and avoid and remove sortPairs().
            // Maybe an overloaded constructor StarlarkInfoNoSchema(Provider, SortedMap<>, Location)
            // could also be useful in this context. Connection with b/380824219: StarlarkInfoFactory
            // assembles values into a TreeMap and calls StarlarkInfoNoSchema(Provider, Map<>, Location).
            for (e in values.entrySet()) {
                table[i] = e.getKey()
                table[n + i] = net.starlark.java.eval.Starlark.checkValid<Any?>(e.getValue())
                i++
            }
            // Sort keys, permuting values in parallel.
            if (n > 1) {
                sortPairs(table, 0, n - 1)
            }
            return table
        }

        fun newStarlarkInfoFactory(
            provider: StarlarkProvider?, thread: net.starlark.java.eval.StarlarkThread?
        ): com.google.devtools.build.lib.packages.StarlarkProvider.StarlarkInfoFactory {
            return com.google.devtools.build.lib.packages.StarlarkInfoNoSchema.StarlarkInfoFactory(provider, thread)
        }

        // Sorts non-empty slice a[lo:hi] (inclusive) in place.
        // Elements a[n:2n) are permuted the same way as a[0:n),
        // where n = a.length / 2. The lower half must be strings.
        // Precondition: 0 <= lo <= hi < n.
        fun sortPairs(a: Array<Any?>, lo: Int, hi: Int) {
            val pivot = a[lo + (hi - lo) / 2] as String

            var i = lo
            var j = hi
            while (i <= j) {
                while ((a[i] as String).compareTo(pivot) < 0) {
                    i++
                }
                while ((a[j] as String).compareTo(pivot) > 0) {
                    j--
                }
                if (i <= j) {
                    val n: Int = a.length shr 1
                    swap(a, i, j)
                    swap(a, i + n, j + n)
                    i++
                    j--
                }
            }
            if (lo < j) {
                sortPairs(a, lo, j)
            }
            if (i < hi) {
                sortPairs(a, i, hi)
            }
        }

        private fun swap(a: Array<Any?>, i: Int, j: Int) {
            val tmp = a[i]
            a[i] = a[j]
            a[j] = tmp
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun plus(x: StarlarkInfoNoSchema, y: StarlarkInfoNoSchema): StarlarkInfo {
            // ztable = merge(x.table, y.table)
            val xsize: Int = x.table.length / 2
            val ysize: Int = y.table.length / 2
            val zsize = xsize + ysize
            val ztable = arrayOfNulls<Any>(zsize + zsize)
            var xi = 0
            var yi = 0
            var zi = 0
            while (xi < xsize && yi < ysize) {
                val xk = x.table[xi] as String
                val yk = y.table[yi] as String
                val cmp = xk.compareTo(yk)
                if (cmp < 0) {
                    ztable[zi] = xk
                    ztable[zi + zsize] = x.table[xi + xsize]
                    xi++
                } else if (cmp > 0) {
                    ztable[zi] = yk
                    ztable[zi + zsize] = y.table[yi + ysize]
                    yi++
                } else {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "cannot add struct instances with common field '%s'",
                        xk
                    )
                }
                zi++
            }
            while (xi < xsize) {
                ztable[zi] = x.table[xi]
                ztable[zi + zsize] = x.table[xi + xsize]
                xi++
                zi++
            }
            while (yi < ysize) {
                ztable[zi] = y.table[yi]
                ztable[zi + zsize] = y.table[yi + ysize]
                yi++
                zi++
            }

            return StarlarkInfoNoSchema(x.provider, ztable)
        }
    }
}
