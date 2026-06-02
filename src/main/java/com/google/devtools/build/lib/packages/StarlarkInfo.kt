// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.StarlarkInfoNoSchema
import com.google.devtools.build.lib.packages.StructImpl


/** Superclass (provider instance) for providers defined in Starlark.  */
abstract class StarlarkInfo internal constructor() : StructImpl(), net.starlark.java.eval.HasBinary,
    net.starlark.java.eval.Compactable {
    // Relax visibility to public. getValue() is widely used to directly access fields from native
    // rule logic. Compared to Starlark.getattr(), it also avoids the need for the caller to pass a
    // Semantics.
    abstract override fun getValue(name: String?): Any?

    // Tighten return type.
    abstract override fun unsafeOptimizeMemoryLayout(): StarlarkInfo?

    companion object {
        /**
         * Threshold over which to use binary search for field lookup in lists/arrays.
         * 
         * 
         * Linear search is faster for small lists/arrays.
         */
        protected const val BINARY_SEARCH_THRESHOLD: Int = 16

        /**
         * Creates a schemaless provider instance with the given provider type and field values.
         * 
         * @param provider A `Provider` without a schema. `StarlarkProvider` with a schema is
         * not supported by this call.
         * @param values the field values
         */
        fun create(
            provider: com.google.devtools.build.lib.packages.Provider?,
            values: MutableMap<String?, Any?>?
        ): StarlarkInfo {
            return StarlarkInfoNoSchema.Companion.createSchemaless(provider, values)
        }
    }
}
