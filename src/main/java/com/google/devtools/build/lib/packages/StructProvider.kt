// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.StarlarkInfo
import com.google.devtools.build.lib.packages.StarlarkInfoWithMessage
import com.google.devtools.build.lib.packages.StructImpl
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi.StructProviderApi

/**
 * The provider for the built-in type `struct`.
 * 
 * 
 * Its singleton instance is [StructProvider.STRUCT].
 */
class StructProvider private constructor() : BuiltinProvider<StarlarkInfo?>("struct", StarlarkInfo::class.java),
    StructProviderApi {
    /** Implementation of `struct(**kwargs)` function exposed to Starlark.  */
    override fun createStruct(kwargs: net.starlark.java.eval.Dict<String?, Any?>?): StructImpl {
        return StarlarkInfo.Companion.create(this, kwargs)
    }

    /**
     * Creates a struct with the given field values and message format for unknown fields.
     * 
     * 
     * The custom message is useful for objects that have fields but aren't exactly used as
     * providers, such as the `native` object, and the struct fields of `ctx` like `ctx.attr`.
     */
    fun create(fields: MutableMap<String?, Any?>?, errorMessageFormatForUnknownField: String?): StarlarkInfo {
        return StarlarkInfoWithMessage.Companion.createWithCustomMessage(
            this, fields, errorMessageFormatForUnknownField
        )
    }

    companion object {
        /** Provider of "struct" instances.  */
        @kotlin.jvm.JvmField
        val STRUCT: StructProvider = StructProvider()
    }
}
