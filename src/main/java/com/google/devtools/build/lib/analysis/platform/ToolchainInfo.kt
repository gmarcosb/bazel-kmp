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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.ResolvedToolchainData
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo
import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.StructImpl
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainInfoApi

/**
 * A provider that supplies information about a specific language toolchain, including what platform
 * constraints are required for execution and for the target platform.
 * 
 * 
 * Unusually, ToolchainInfo exposes both its StarlarkCallable-annotated fields and a Map of
 * additional fields to Starlark code. Also, these are not disjoint.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class ToolchainInfo internal constructor(values: MutableMap<String?, Any?>) : StructImpl(), ToolchainInfoApi,
    ResolvedToolchainData {
    /** Provider for [ToolchainInfo] objects.  */
    private class Provider : BuiltinProvider<ToolchainInfo?>(STARLARK_NAME, ToolchainInfo::class.java),
        ToolchainInfoApi.Provider {
        override fun toolchainInfo(kwargs: net.starlark.java.eval.Dict<String?, Any?>): ToolchainInfo {
            return ToolchainInfo(kwargs)
        }
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    val values: com.google.common.collect.ImmutableSortedMap<String?, Any?>

    /** Constructs a ToolchainInfo. The `values` map itself is not retained.  */
    init {
        this.values = copyValues(values)
    }

    val provider: BuiltinProvider<ToolchainInfo?>
        get() = PROVIDER

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getValue(name: String?): Any? {
        return values.get(name)
    }

    val fieldNames: com.google.common.collect.ImmutableSortedSet<String?>
        get() = values.keys

    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "ToolchainInfo"

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<ToolchainInfo?> =
            com.google.devtools.build.lib.analysis.platform.ToolchainInfo.Provider()

        /**
         * Preprocesses a map of field values to convert the field names and field values to
         * Starlark-acceptable names and types.
         * 
         * 
         * Entries are ordered by key.
         */
        private fun copyValues(values: MutableMap<String?, Any?>): com.google.common.collect.ImmutableSortedMap<String?, Any?> {
            val builder: com.google.common.collect.ImmutableSortedMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableSortedMap.naturalOrder<String?, Any?>()
            for (e in values.entries) {
                builder.put(
                    com.google.devtools.build.lib.packages.Attribute.getStarlarkName(e.key),
                    net.starlark.java.eval.Starlark.fromJava(e.value, null)
                )
            }
            return builder.buildOrThrow()
        }
    }
}
